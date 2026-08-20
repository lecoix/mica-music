package com.mica.music.media.usbhybrid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.media3.common.C
import com.afalphy.sylvakru.UsbExclusiveAudioTransport
import com.afalphy.sylvakru.UsbExclusiveNative
import java.util.concurrent.ConcurrentHashMap

/** Android boundary for the owner. It never selects an arbitrary first UAC device. */
class AndroidUsbHybridControlEffects(
    context: Context,
    private val permissionResultSink: (UsbPermissionResult) -> Unit,
    private val topologyEventSink: (UsbTopologyEvent) -> Unit,
) : UsbHybridControlEffects, UsbHybridRealtimePort, AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val pendingRequests = ConcurrentHashMap<Long, UsbPermissionRequest>()
    private val transport = UsbExclusiveAudioTransport(appContext)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val epoch = intent.getLongExtra(EXTRA_EPOCH, Long.MIN_VALUE)
            val request = pendingRequests.remove(epoch) ?: return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val observed = device?.let(AndroidUsbIdentityProbe::candidate)
            permissionResultSink(
                UsbPermissionResult(
                    epoch = request.epoch,
                    mode = request.mode,
                    identity = observed?.identity ?: request.identity,
                    runtimeHandle = observed?.runtimeHandle ?: request.runtimeHandle,
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        observed != null,
                ),
            )
        }
    }

    private val permissionAction = "${appContext.packageName}.USB_HYBRID_PERMISSION"
    private val topologyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> topologyEventSink(UsbTopologyEvent.Attached)
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
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
            appContext.registerReceiver(permissionReceiver, filter)
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
        try {
            UsbExclusiveNative.publishActiveEpoch(epoch.value)
        } catch (error: UnsatisfiedLinkError) {
            if (System.getProperty("java.vm.name") == "Dalvik") throw error
            // Host/Robolectric tests cannot load an arm64 Android .so. They test owner ordering via
            // injected effects; the production ART path remains fail-fast.
        }
    }

    override fun requestPermission(request: UsbPermissionRequest) {
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
        val intent = Intent(permissionAction)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_EPOCH, request.epoch.value)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            request.epoch.value.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    override fun open(request: UsbOpenRequest): UsbOpenResult {
        val candidate = findCandidate(request.runtimeHandle)
            ?: return failure("TARGET_MISSING", "The selected SK02 is no longer attached.")
        if (candidate.second != request.identity) {
            return failure("TARGET_CHANGED", "The selected SK02 identity changed before open.")
        }
        if (!Sk02ExperimentalNativeEvidence.matches(
                request.identity,
                safeDeviceString { candidate.first.manufacturerName },
                safeDeviceString { candidate.first.productName },
            )
        ) {
            return failure(
                "SK02_IDENTITY_UNPROVEN",
                "The attached 262a:0001 device cannot be proven to be the scoped Fosi Audio SK02 revision.",
            )
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
        if (format.bitDepth !in setOf(16, 32)) {
            return failure("PCM_FORMAT_REJECTED", "USB Exact PCM accepts only PCM16 or PCM32.")
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
            sourceEncoding = if (format.bitDepth == 16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_32BIT,
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
        if (nativeRequested && !Sk02ExperimentalNativeEvidence.matches(
                request.identity,
                safeDeviceString { device.manufacturerName },
                safeDeviceString { device.productName },
            )
        ) {
            return failure(
                "NATIVE_PROFILE_MISMATCH",
                "Experimental Native is limited to the exact SK02 bcdDevice 0x0004 product scope.",
            )
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

    override fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray): String? =
        transport.writePcm(sessionId.epoch.value, sessionId.nativeId, data)

    override fun finishPcm(sessionId: UsbTransportSessionId): String? =
        transport.finishStream(sessionId.epoch.value, sessionId.nativeId)

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

    override fun writeDsd(sessionId: UsbTransportSessionId, data: ByteArray): String? =
        transport.writeDsd(sessionId.epoch.value, sessionId.nativeId, data)

    override fun prepareDsdSeek(sessionId: UsbTransportSessionId): String? =
        transport.prepareDsdSeek(sessionId.epoch.value, sessionId.nativeId)

    override fun pauseDsd(sessionId: UsbTransportSessionId): String? =
        transport.pauseDsd(sessionId.epoch.value, sessionId.nativeId)

    override fun resumeDsd(sessionId: UsbTransportSessionId): String? =
        transport.resumeDsd(sessionId.epoch.value, sessionId.nativeId)

    override fun finishDsd(sessionId: UsbTransportSessionId): String? =
        transport.finishDsdStream(sessionId.epoch.value, sessionId.nativeId)

    fun discoverSk02(): Sk02Selection = Sk02TargetSelector.select(
        usbManager.deviceList.values.map(AndroidUsbIdentityProbe::candidate),
    )

    override fun close() {
        pendingRequests.clear()
        transport.close()
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(topologyReceiver) }
    }

    private fun findCandidate(handle: UsbRuntimeHandle): Pair<UsbDevice, UsbStableIdentity>? {
        val device = usbManager.deviceList.values.singleOrNull {
            it.deviceId == handle.deviceId && it.deviceName == handle.deviceName &&
                it.vendorId == Sk02TargetSelector.VENDOR_ID &&
                it.productId == Sk02TargetSelector.PRODUCT_ID
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

    private companion object {
        const val EXTRA_EPOCH = "usb_hybrid_epoch"
    }
}
