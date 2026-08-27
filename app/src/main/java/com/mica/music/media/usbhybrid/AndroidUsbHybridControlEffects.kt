package com.mica.music.media.usbhybrid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import com.afalphy.sylvakru.UsbExclusiveAudioTransport
import com.afalphy.sylvakru.UsbExclusiveNative
import com.mica.music.data.preferences.UsbHybridPreferences
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android boundary for the owner. It never selects an arbitrary first UAC device. */
class AndroidUsbHybridControlEffects(
    context: Context,
    private val permissionResultSink: (UsbPermissionResult) -> Unit,
    private val topologyEventSink: (UsbTopologyEvent) -> Unit,
    private val outputPermissionResultSink: (UsbOutputPermissionResult) -> Unit = {},
) : UsbHybridControlEffects, UsbHybridRealtimePort, AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val pendingRequests = ConcurrentHashMap<Long, UsbPermissionRequest>()
    private val pendingOutputPermissionRequests = ConcurrentHashMap<Long, UsbOutputPermissionRequest>()
    private val transport = UsbExclusiveAudioTransport(appContext)
    @Volatile
    private var publishedEpoch: Long = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val requestId = intent.getLongExtra(EXTRA_PERMISSION_REQUEST_ID, Long.MIN_VALUE)
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val observed = device?.let(AndroidUsbIdentityProbe::candidate)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && observed != null
            when (intent.getStringExtra(EXTRA_PERMISSION_DOMAIN)) {
                PERMISSION_DOMAIN_OWNER -> {
                    val request = pendingRequests.remove(requestId) ?: return
                    permissionResultSink(
                        UsbPermissionResult(
                            epoch = request.epoch,
                            mode = request.mode,
                            identity = observed?.identity ?: request.identity,
                            runtimeHandle = observed?.runtimeHandle ?: request.runtimeHandle,
                            granted = granted,
                        ),
                    )
                }
                PERMISSION_DOMAIN_OUTPUT -> {
                    val request = pendingOutputPermissionRequests.remove(requestId) ?: return
                    outputPermissionResultSink(
                        UsbOutputPermissionResult(
                            operationId = request.operationId,
                            mode = request.mode,
                            identity = observed?.identity ?: request.identity,
                            runtimeHandle = observed?.runtimeHandle ?: request.runtimeHandle,
                            granted = granted,
                        ),
                    )
                }
            }
        }
    }

    private val permissionAction = "${appContext.packageName}.USB_HYBRID_PERMISSION.${UUID.randomUUID()}"
    private val topologyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbDeviceExtra(intent)?.let { device ->
                    runCatching { AndroidUsbIdentityProbe.candidate(device) }.getOrNull()?.let { candidate ->
                        topologyEventSink(
                            UsbTopologyEvent.Attached(candidate.runtimeHandle, candidate.hasAudioOutput),
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> usbDeviceExtra(intent)?.let { device ->
                    topologyEventSink(
                        UsbTopologyEvent.Detached(UsbRuntimeHandle(device.deviceId, device.deviceName)),
                    )
                }
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(
                topologyReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                topologyReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
            )
        }
    }

    override fun publishActiveEpoch(epoch: UsbRequestEpoch) {
        publishedEpoch = epoch.value
        if (System.getProperty("java.vm.name") != "Dalvik") return
        // Production ART remains fail-fast. Host/Robolectric cannot load an arm64 Android .so and
        // exercises owner ordering through injected effects instead.
        UsbExclusiveNative.publishActiveEpoch(epoch.value)
    }

    override fun requestPermission(request: UsbPermissionRequest) {
        // The output owner has already superseded the previous attempt. Keep only the request
        // whose instance-scoped broadcast is still allowed to complete.
        pendingRequests.clear()
        val candidate = findCandidate(request.runtimeHandle)
        if (candidate == null || candidate.second != request.identity) {
            permissionResultSink(
                UsbPermissionResult(
                    request.epoch,
                    request.mode,
                    candidate?.second ?: request.identity,
                    candidate?.first?.let { UsbRuntimeHandle(it.deviceId, it.deviceName) }
                        ?: request.runtimeHandle,
                    granted = false,
                ),
            )
            return
        }
        val device = candidate.first
        if (usbManager.hasPermission(device)) {
            permissionResultSink(
                UsbPermissionResult(
                    request.epoch,
                    request.mode,
                    request.identity,
                    request.runtimeHandle,
                    granted = true,
                ),
            )
            return
        }

        pendingRequests[request.epoch.value] = request
        val intent = permissionIntent(
            requestId = request.epoch.value,
            domain = PERMISSION_DOMAIN_OWNER,
        )
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            permissionRequestCode(request.epoch.value, PERMISSION_DOMAIN_OWNER),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    fun requestOutputPermission(request: UsbOutputPermissionRequest) {
        pendingOutputPermissionRequests.clear()
        val candidate = findCandidate(request.runtimeHandle)
        if (candidate == null || candidate.second != request.identity) {
            outputPermissionResultSink(
                UsbOutputPermissionResult(
                    operationId = request.operationId,
                    mode = request.mode,
                    identity = candidate?.second ?: request.identity,
                    runtimeHandle = candidate?.first?.let { UsbRuntimeHandle(it.deviceId, it.deviceName) }
                        ?: request.runtimeHandle,
                    granted = false,
                ),
            )
            return
        }
        val device = candidate.first
        if (usbManager.hasPermission(device)) {
            outputPermissionResultSink(
                UsbOutputPermissionResult(
                    operationId = request.operationId,
                    mode = request.mode,
                    identity = request.identity,
                    runtimeHandle = request.runtimeHandle,
                    granted = true,
                ),
            )
            return
        }

        pendingOutputPermissionRequests[request.operationId.value] = request
        val intent = permissionIntent(
            requestId = request.operationId.value,
            domain = PERMISSION_DOMAIN_OUTPUT,
        )
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            permissionRequestCode(request.operationId.value, PERMISSION_DOMAIN_OUTPUT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    override fun open(request: UsbOpenRequest): UsbOpenResult {
        val candidate = findCandidate(request.runtimeHandle)
            ?: return failure("TARGET_MISSING", "The selected USB audio device is no longer attached.")
        if (candidate.second != request.identity) {
            return failure("TARGET_CHANGED", "The selected USB audio device identity changed before open.")
        }
        return when (val format = request.format) {
            is UsbStreamFormat.Pcm -> openPcm(request, candidate.first, format)
            is UsbStreamFormat.Dsd -> openDsd(request, candidate.first, format)
        }
    }

    private fun openPcm(
        request: UsbOpenRequest,
        device: UsbDevice,
        format: UsbStreamFormat.Pcm,
    ): UsbOpenResult {
        if (format.bitDepth !in setOf(16, 24, 32)) {
            return failure("PCM_FORMAT_REJECTED", "USB Exact PCM accepts only PCM16, PCM24 or PCM32.")
        }
        val error = transport.open(
            request.epoch.value,
            usbManager,
            device,
            format.sampleRate,
            format.channels,
            format.bitDepth,
        )
        if (error != null) return failure("OPEN_FAILED", error)
        val token = transport.sessionToken()
            ?: return failure("OPEN_FAILED", "Native transport returned no session token.")
        if (token.first != request.epoch.value) {
            transport.close()
            return failure("STALE_OPEN", "Native transport opened for a stale request epoch.")
        }
        return UsbOpenResult(
            sessionId = UsbTransportSessionId(request.epoch, token.second),
            claimed = true,
            transportExact = true,
            signalExact = transport.usbBitResolution()?.let { it >= format.bitDepth } == true,
            sourceEncoding = when (format.bitDepth) {
                16 -> C.ENCODING_PCM_16BIT
                24 -> C.ENCODING_PCM_24BIT
                else -> C.ENCODING_PCM_32BIT
            },
            usbBitResolution = transport.usbBitResolution(),
            sampleRate = format.sampleRate,
            channels = format.channels,
            streamFormat = "PCM${format.bitDepth}",
        )
    }

    private fun openDsd(
        request: UsbOpenRequest,
        device: UsbDevice,
        format: UsbStreamFormat.Dsd,
    ): UsbOpenResult {
        val nativeRequested = request.mode == UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL
        if ((nativeRequested && !format.native) ||
            (!nativeRequested && (request.mode != UsbExclusiveMode.USB_DOP || format.native))
        ) {
            return failure("DSD_MODE_MISMATCH", "The raw DSD request does not match the explicit USB DSD mode.")
        }
        val result = transport.openDsd(
            epoch = request.epoch.value,
            usbManager = usbManager,
            device = device,
            dsdSampleRate = format.sampleRate,
            channels = format.channels,
            preference = if (nativeRequested) {
                UsbExclusiveAudioTransport.DsdPreference.NativeOnly
            } else {
                UsbExclusiveAudioTransport.DsdPreference.DopOnly
            },
        )
        result.error?.let { return failure(if (nativeRequested) "NATIVE_OPEN_FAILED" else "DOP_OPEN_FAILED", it) }
        val token = transport.sessionToken()
            ?: return failure("DOP_OPEN_FAILED", "Native transport returned no DoP session token.")
        val actual = result.format ?: return failure("DOP_OPEN_FAILED", "Transport returned no DoP format.")
        return UsbOpenResult(
            sessionId = UsbTransportSessionId(request.epoch, token.second),
            claimed = true,
            transportExact = true,
            signalExact = !nativeRequested && actual.mode == UsbExclusiveAudioTransport.DsdMode.DoP,
            usbBitResolution = transport.usbBitResolution(),
            sampleRate = format.sampleRate,
            channels = format.channels,
            streamFormat = if (nativeRequested) {
                "Native DSD${format.sampleRate / 44_100} ${actual.nativeFormat} (experimental)"
            } else {
                "DoP DSD${format.sampleRate / 44_100} carrier=${actual.frameRate}Hz"
            },
        )
    }

    override fun close(sessionId: UsbTransportSessionId) {
        val token = transport.sessionToken() ?: return
        if (token.first == sessionId.epoch.value && token.second == sessionId.nativeId) {
            transport.close()
        }
    }


    override fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray): UsbRealtimeResult =
        realtimeResult(sessionId, transport.writePcm(sessionId.epoch.value, sessionId.nativeId, data))

    override fun beginPcmTimeline(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(
            sessionId,
            transport.beginPcmTimeline(sessionId.epoch.value, sessionId.nativeId),
        )

    override fun consumedPcmSourceFrames(sessionId: UsbTransportSessionId): Long =
        transport.consumedPcmSourceFrames(sessionId.epoch.value, sessionId.nativeId)

    override fun finishPcm(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.finishStream(sessionId.epoch.value, sessionId.nativeId))

    override fun setVolume(sessionId: UsbTransportSessionId, gainQ16: Int): UsbRealtimeResult {
        val token = transport.sessionToken()
        if (token == null || token.first != sessionId.epoch.value || token.second != sessionId.nativeId) {
            return UsbRealtimeResult.Retired
        }
        val mode = UsbHybridPreferences.volumeControlMode(appContext).name.lowercase(Locale.ROOT)
        val error = transport.setVolume(
            gainQ16 = gainQ16.coerceIn(0, 65_536),
            replayGainMilliDb = 0,
            mode = mode,
            dsdCompensationDb = UsbHybridPreferences.dsdGainCompensationDb(appContext),
            smoothHandoff = UsbHybridPreferences.volumeSmoothHandoff(appContext),
        )
        return realtimeResult(sessionId, error)
    }
    override fun pausePcm(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.pausePcm(sessionId.epoch.value, sessionId.nativeId))

    override fun resumePcm(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.resumePcm(sessionId.epoch.value, sessionId.nativeId))

    override fun preparePcmSeek(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.preparePcmSeek(sessionId.epoch.value, sessionId.nativeId))

    override fun resetPcmForSeek(sessionId: UsbTransportSessionId) {
        transport.resetForSeek(sessionId.epoch.value, sessionId.nativeId)
    }

    override fun telemetry(sessionId: UsbTransportSessionId): UsbRealtimeTelemetry {
        val value = transport.telemetry(sessionId.epoch.value, sessionId.nativeId)
        return UsbRealtimeTelemetry(
            value.pendingIsoPackets,
            value.totalIsoPackets,
            value.pendingOutputUrbs,
            value.isoErrorCount,
        )
    }

    override fun sessionDiagnostics(sessionId: UsbTransportSessionId): Map<String, Any?> {
        val token = transport.sessionToken() ?: return emptyMap()
        return if (token.first == sessionId.epoch.value && token.second == sessionId.nativeId) {
            transport.sessionDiagnosticsSnapshot()
        } else {
            emptyMap()
        }
    }

    override fun writeDsd(sessionId: UsbTransportSessionId, data: ByteArray): UsbRealtimeResult =
        realtimeResult(sessionId, transport.writeDsd(sessionId.epoch.value, sessionId.nativeId, data))

    override fun prepareDsdSeek(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.prepareDsdSeek(sessionId.epoch.value, sessionId.nativeId))

    override fun pauseDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.pauseDsd(sessionId.epoch.value, sessionId.nativeId))

    override fun resumeDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.resumeDsd(sessionId.epoch.value, sessionId.nativeId))

    override fun finishDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult =
        realtimeResult(sessionId, transport.finishDsdStream(sessionId.epoch.value, sessionId.nativeId))

    private fun realtimeResult(sessionId: UsbTransportSessionId, error: String?): UsbRealtimeResult =
        classifyUsbRealtimeResult(error, sessionId.epoch.value, publishedEpoch)

    fun discoverUsbAudioDevice(): UsbAudioSelection = UsbAudioTargetSelector.select(
        usbManager.deviceList.values.map(AndroidUsbIdentityProbe::candidate),
    )

    /** Re-validates permission against the currently enumerated runtime handle. */
    fun hasPermission(candidate: UsbDeviceCandidate): Boolean {
        val current = findCandidate(candidate.runtimeHandle) ?: return false
        return current.second == candidate.identity && usbManager.hasPermission(current.first)
    }

    override fun close() {
        pendingRequests.clear()
        pendingOutputPermissionRequests.clear()
        transport.close()
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(topologyReceiver) }
    }

    private fun findCandidate(handle: UsbRuntimeHandle): Pair<UsbDevice, UsbStableIdentity>? {
        val device = usbManager.deviceList.values.singleOrNull {
            it.deviceId == handle.deviceId && it.deviceName == handle.deviceName
        } ?: return null
        return device to AndroidUsbIdentityProbe.candidate(device).identity
    }

    private fun failure(code: String, message: String) = UsbOpenResult(
        failure = UsbFailure(code, message),
    )

    private fun usbDeviceExtra(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun safeDeviceString(value: () -> String?): String? = runCatching(value).getOrNull()

    private fun permissionIntent(requestId: Long, domain: String): Intent =
        Intent(permissionAction)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_PERMISSION_REQUEST_ID, requestId)
            .putExtra(EXTRA_PERMISSION_DOMAIN, domain)

    private fun permissionRequestCode(requestId: Long, domain: String): Int =
        31 * requestId.hashCode() + domain.hashCode()

    private companion object {
        const val EXTRA_PERMISSION_REQUEST_ID = "usb_hybrid_permission_request_id"
        const val EXTRA_PERMISSION_DOMAIN = "usb_hybrid_permission_domain"
        const val PERMISSION_DOMAIN_OWNER = "owner_epoch"
        const val PERMISSION_DOMAIN_OUTPUT = "output_operation"
    }
}
