package com.afalphy.sylvakru

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin shared PCM/DSD session extracted from sylvakru-usb's UsbExclusiveAudioEngine.
 *
 * Decoding and playback policy deliberately do not live here. The caller supplies already-decoded
 * little-endian integer PCM or reference-format interleaved DSD bytes. This class keeps the
 * reference project's single usbfs owner, endpoint selection, sample-rate programming,
 * packetization, DSD silence continuity and feedback path together as one session.
 */
class UsbExclusiveAudioTransport(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var target: UsbStreamingTarget? = null
    private var packetizer: UsbPcmIsoPacketizer? = null
    private var currentFormat: PcmFormat? = null
    private var currentDsdFormat: DsdFormat? = null
    private var currentUsbBitResolution: Int? = null
    private var dsdEncoder: DsdStreamEncoder? = null
    private var dsdPayloadWriteObserved = false
    @Volatile private var requestEpoch: Long = 0L
    @Volatile private var nativeSessionId: Long = 0L
    private val dsdIdleFillerRunning = AtomicBoolean(false)
    private var dsdIdleFillerThread: Thread? = null

    @Synchronized
    fun open(
        epoch: Long,
        usbManager: UsbManager,
        device: UsbDevice,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): String? {
        if (!usbManager.hasPermission(device)) {
            return "USB permission is required before exclusive playback."
        }
        if (sampleRate <= 0 || channels <= 0 || bitDepth !in setOf(16, 32)) {
            return "Unsupported PCM format: ${sampleRate}Hz/${channels}ch/${bitDepth}bit."
        }

        val requestedFormat = PcmFormat(sampleRate, channels, bitDepth)
        if (
            connection != null && requestEpoch == epoch &&
            this.device?.deviceId == device.deviceId && currentFormat == requestedFormat
        ) {
            UsbDiagnostics.i(TAG, "reusing exclusive USB PCM session $requestedFormat")
            return null
        }
        close()

        val openedConnection = usbManager.openDevice(device)
            ?: return "Failed to open USB device for exclusive playback."
        val resolvedTarget = UsbStreamingTargetResolver.resolvePcmTarget(
            device = device,
            rawDescriptors = openedConnection.rawDescriptors,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
        )
        if (resolvedTarget == null) {
            openedConnection.close()
            return "No isochronous USB Audio OUT endpoint was found."
        }
        val inputBytesPerSample = bytesPerSampleForBitDepth(bitDepth)
        val usbBytesPerSample = resolvedTarget.usbBytesPerSample
        val usbBitResolution = resolvedTarget.usbBitResolution ?: (usbBytesPerSample * 8)
        if (!UsbExactPcmTargetPolicy.accepts(bitDepth, usbBytesPerSample, usbBitResolution)) {
            openedConnection.close()
            return "USB Exact PCM requires an exact ${bitDepth}-bit/${inputBytesPerSample}-byte alt; " +
                "resolved ${usbBitResolution}-bit/${usbBytesPerSample}-byte."
        }

        UsbDiagnostics.i(
            TAG,
            "opening interface=${resolvedTarget.usbInterface.id} alt=${resolvedTarget.alternateSetting} " +
                "endpoint=0x${resolvedTarget.endpoint.address.toString(16)} maxPacket=${resolvedTarget.endpoint.maxPacketSize} " +
                "feedback=${resolvedTarget.feedbackEndpointLabel} sampleRate=$sampleRate channels=$channels " +
                "bitDepth=$bitDepth format=${resolvedTarget.formatInfo}",
        )
        val openedNative = UsbExclusiveNative.open(
            epoch,
            openedConnection.fileDescriptor,
            resolvedTarget.usbInterface.id,
            resolvedTarget.alternateSetting,
            resolvedTarget.endpoint.address,
            resolvedTarget.endpoint.maxPacketSize,
            resolvedTarget.feedbackEndpoint?.address ?: 0,
            resolvedTarget.feedbackEndpoint?.maxPacketSize ?: 0,
            false,
        )
        if (openedNative.error != null || openedNative.sessionId == null) {
            openedConnection.close()
            return openedNative.error ?: "Native USB open returned no session id."
        }
        val sessionId = openedNative.sessionId

        val quirk = UsbDacQuirks.forDevice(appContext, device.vendorId, device.productId)
        if (!UsbExclusiveNative.isCurrent(epoch, sessionId)) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return "USB request became stale before clock configuration."
        }
        val clockError = UsbStreamingTargetResolver.configureUsbAudioClock(
            connection = openedConnection,
            device = device,
            target = resolvedTarget,
            sampleRate = sampleRate,
            quirk = quirk,
        )
        if (clockError != null) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return clockError
        }
        if (!UsbExclusiveNative.isCurrent(epoch, sessionId)) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return "USB request became stale after clock configuration."
        }

        val packetBytes = UsbStreamingTargetResolver.requiredIsoPacketBytes(
            sampleRate = sampleRate,
            packetsPerSecond = resolvedTarget.packetsPerSecond,
            channels = channels,
            bytesPerSample = usbBytesPerSample,
        )
        UsbExclusiveNative.setIsoPacketSize(epoch, sessionId, packetBytes)?.let {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return it
        }
        val newPacketizer = UsbPcmIsoPacketizer(
            sampleRate = sampleRate,
            packetsPerSecond = resolvedTarget.packetsPerSecond,
            channels = channels,
            inputBytesPerSample = inputBytesPerSample,
            inputBitDepth = bitDepth,
            usbBytesPerSample = usbBytesPerSample,
            usbBitResolution = usbBitResolution,
            feedbackOutputPacketDivisor = 1,
            feedbackFramesPerPacketQ16 = resolvedTarget.feedbackEndpoint?.let {
                { UsbExclusiveNative.feedbackFramesPerPacketQ16(epoch, sessionId) }
            },
            volumeGainQ16 = null,
        ) { data, packetLengths, packetCount ->
            val error = UsbExclusiveNative.writeIsoPackets(
                epoch,
                sessionId,
                data,
                packetLengths,
                packetCount,
            )
            if (error != null) {
                throw UsbExclusiveTransportException(error)
            }
        }

        connection = openedConnection
        this.device = device
        target = resolvedTarget
        packetizer = newPacketizer
        currentFormat = requestedFormat
        currentUsbBitResolution = usbBitResolution
        requestEpoch = epoch
        nativeSessionId = sessionId
        UsbDiagnostics.i(
            TAG,
            "opened sampleRate=$sampleRate channels=$channels bitDepth=$bitDepth " +
                "usbBytesPerSample=$usbBytesPerSample usbBitResolution=$usbBitResolution " +
                "packetsPerSecond=${resolvedTarget.packetsPerSecond}",
        )
        return null
    }

    /**
     * Opens one explicit DSD path. DoP and Native never fall back to each other.
     */
    @Synchronized
    fun openDsd(
        epoch: Long,
        usbManager: UsbManager,
        device: UsbDevice,
        dsdSampleRate: Int,
        channels: Int,
        preference: DsdPreference,
    ): DsdOpenResult {
        if (!usbManager.hasPermission(device)) {
            return DsdOpenResult(error = "USB permission is required before exclusive playback.")
        }
        if (dsdSampleRate <= 0 || channels <= 0) {
            return DsdOpenResult(error = "Unsupported DSD format: ${dsdSampleRate}Hz/${channels}ch.")
        }

        val existing = currentDsdFormat
        if (
            connection != null &&
            requestEpoch == epoch &&
            this.device?.deviceId == device.deviceId &&
            existing?.dsdSampleRate == dsdSampleRate &&
            existing.channels == channels &&
            ((preference == DsdPreference.NativeOnly && existing.mode == DsdMode.Native) ||
                (preference == DsdPreference.DopOnly && existing.mode == DsdMode.DoP))
        ) {
            stopDsdIdleFillerLocked()
            UsbDiagnostics.i(TAG, "reusing exclusive USB DSD session $existing")
            return DsdOpenResult(format = existing)
        }

        close()
        val openedConnection = usbManager.openDevice(device)
            ?: return DsdOpenResult(error = "Failed to open USB device for exclusive DSD playback.")
        val descriptors = openedConnection.rawDescriptors
        val quirk = UsbDacQuirks.forDevice(appContext, device.vendorId, device.productId)
        val multiple = if (dsdSampleRate % 44_100 == 0) dsdSampleRate / 44_100 else null

        var selectedTarget: UsbStreamingTarget? = null
        var selectedMode = DsdMode.DoP
        var nativeFormat: String? = null
        var frameRate = dsdSampleRate / 16
        var inputBytesPerSample = 3
        var inputBitDepth = 24
        var encoder: DsdStreamEncoder? = null

        if (preference == DsdPreference.NativeOnly) {
            if (quirk.nativeDsdMaxDsd != null && multiple != null && multiple > quirk.nativeDsdMaxDsd) {
                openedConnection.close()
                return DsdOpenResult(
                    error = "DSD$multiple exceeds native DSD limit DSD${quirk.nativeDsdMaxDsd} (quirk)",
                )
            } else {
                val native = UsbStreamingTargetResolver.resolveNativeDsdTarget(
                    device = device,
                    rawDescriptors = descriptors,
                    dsdSampleRate = dsdSampleRate,
                    channels = channels,
                    quirk = quirk,
                )
                if (native != null) {
                    selectedTarget = native.target
                    selectedMode = DsdMode.Native
                    nativeFormat = native.nativeFormat
                    frameRate = native.frameRate
                    inputBytesPerSample = native.bytesPerSample
                    inputBitDepth = native.bytesPerSample * 8
                    encoder = NativeDsdPacketizer(
                        channels = channels,
                        bytesPerSample = native.bytesPerSample,
                        bigEndian = native.nativeFormat == "u32be",
                    )
                } else {
                    openedConnection.close()
                    return DsdOpenResult(
                        error = "Native DSD framing is unproven or no exact built-in profile fits ${dsdSampleRate}Hz.",
                    )
                }
            }
        }

        if (preference == DsdPreference.DopOnly) {
            val dopGateError = when {
                quirk.dopSupported == false ->
                    "Device is marked as not supporting DoP (quirk${quirk.label?.let { ": $it" } ?: ""})."
                quirk.dopMaxDsd != null && multiple != null && multiple > quirk.dopMaxDsd ->
                    "DSD$multiple exceeds this device's DoP limit (DSD${quirk.dopMaxDsd}, quirk)."
                else -> null
            }
            if (dopGateError != null) {
                openedConnection.close()
                return DsdOpenResult(error = dopGateError)
            }
            selectedTarget = UsbStreamingTargetResolver.resolveDopTarget(
                device = device,
                rawDescriptors = descriptors,
                dopFrameRate = frameRate,
                channels = channels,
            )
            if (selectedTarget == null) {
                openedConnection.close()
                return DsdOpenResult(
                    error = "DoP requires a fitting 24/32-bit USB Audio OUT alternate setting at ${frameRate}Hz.",
                )
            }
            selectedMode = DsdMode.DoP
            nativeFormat = null
            inputBytesPerSample = 3
            inputBitDepth = 24
            encoder = DopPacketizer(channels)
        }

        val target = checkNotNull(selectedTarget)
        UsbDiagnostics.i(
            TAG,
            "opening DSD mode=$selectedMode interface=${target.usbInterface.id} alt=${target.alternateSetting} " +
                "endpoint=0x${target.endpoint.address.toString(16)} maxPacket=${target.endpoint.maxPacketSize} " +
                "feedback=${target.feedbackEndpointLabel} dsdSampleRate=$dsdSampleRate frameRate=$frameRate " +
                "nativeFormat=${nativeFormat ?: "n/a"} format=${target.formatInfo}",
        )
        val openedNative = UsbExclusiveNative.open(
            epoch,
            openedConnection.fileDescriptor,
            target.usbInterface.id,
            target.alternateSetting,
            target.endpoint.address,
            target.endpoint.maxPacketSize,
            target.feedbackEndpoint?.address ?: 0,
            target.feedbackEndpoint?.maxPacketSize ?: 0,
            false,
        )
        if (openedNative.error != null || openedNative.sessionId == null) {
            openedConnection.close()
            return DsdOpenResult(error = openedNative.error ?: "Native USB open returned no session id.")
        }
        val sessionId = openedNative.sessionId

        // Reference rule: UAC clock is the DSD container frame rate (DoP DSD/16; native
        // DSD/8/subslotBytes), never the raw DSD byte rate.
        val clockError = UsbStreamingTargetResolver.configureUsbAudioClock(
            connection = openedConnection,
            device = device,
            target = target,
            sampleRate = frameRate,
            quirk = quirk,
        )
        if (clockError != null) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return DsdOpenResult(error = clockError)
        }
        if (!UsbExclusiveNative.isCurrent(epoch, sessionId)) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return DsdOpenResult(error = "USB request became stale after clock configuration.")
        }

        val usbBytesPerSample = target.usbBytesPerSample
        val usbBitResolution = target.usbBitResolution ?: (usbBytesPerSample * 8)
        val packetBytes = UsbStreamingTargetResolver.requiredIsoPacketBytes(
            sampleRate = frameRate,
            packetsPerSecond = target.packetsPerSecond,
            channels = channels,
            bytesPerSample = usbBytesPerSample,
        )
        UsbExclusiveNative.setIsoPacketSize(epoch, sessionId, packetBytes)?.let {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return DsdOpenResult(error = it)
        }
        val dsdPacketizer = UsbPcmIsoPacketizer(
            sampleRate = frameRate,
            packetsPerSecond = target.packetsPerSecond,
            channels = channels,
            inputBytesPerSample = inputBytesPerSample,
            inputBitDepth = inputBitDepth,
            usbBytesPerSample = usbBytesPerSample,
            usbBitResolution = usbBitResolution,
            feedbackOutputPacketDivisor = 1,
            feedbackFramesPerPacketQ16 = target.feedbackEndpoint?.let {
                { UsbExclusiveNative.feedbackFramesPerPacketQ16(epoch, sessionId) }
            },
            volumeGainQ16 = null,
        ) { data, packetLengths, packetCount ->
            val error = UsbExclusiveNative.writeIsoPackets(
                epoch,
                sessionId,
                data,
                packetLengths,
                packetCount,
            )
            if (error != null) {
                throw UsbExclusiveTransportException(error)
            }
        }

        val dsdFormat = DsdFormat(
            dsdSampleRate = dsdSampleRate,
            channels = channels,
            mode = selectedMode,
            frameRate = frameRate,
            nativeFormat = nativeFormat,
        )
        connection = openedConnection
        this.device = device
        this.target = target
        packetizer = dsdPacketizer
        currentFormat = null
        currentDsdFormat = dsdFormat
        currentUsbBitResolution = usbBitResolution
        dsdEncoder = checkNotNull(encoder)
        dsdPayloadWriteObserved = false
        requestEpoch = epoch
        nativeSessionId = sessionId
        UsbDiagnostics.i(TAG, "opened DSD session $dsdFormat")
        return DsdOpenResult(format = dsdFormat)
    }

    fun writePcm(epoch: Long, sessionId: Long, data: ByteArray): String? {
        if (requestEpoch != epoch || nativeSessionId != sessionId) return STALE_SESSION
        if (currentDsdFormat != null) {
            return "USB exclusive transport is currently in DSD mode."
        }
        val activePacketizer = packetizer ?: return "USB exclusive PCM transport is not open."
        return try {
            activePacketizer.write(data)
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive write failed."
        }
    }

    /** Flushes the packetizer's final short packet without discarding already queued USB URBs. */
    fun finishStream(epoch: Long, sessionId: Long): String? {
        if (requestEpoch != epoch || nativeSessionId != sessionId) return STALE_SESSION
        if (currentDsdFormat != null) {
            return "USB exclusive transport is currently in DSD mode."
        }
        val activePacketizer = packetizer ?: return null
        return try {
            activePacketizer.flush()
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive final flush failed."
        }
    }

    /**
     * Seek semantics from the reference project: reset packet cadence but do NOT cancel in-flight
     * URBs. Cancelling the native queue creates an ISO gap (PCM pop, DSD loses lock).
     */
    fun resetForSeek(epoch: Long, sessionId: Long) {
        if (requestEpoch != epoch || nativeSessionId != sessionId) return
        if (currentDsdFormat == null) {
            packetizer?.reset()
        }
    }

    /** Writes MSB-first byte-interleaved DSD through the reference DoP/native encoder. */
    @Synchronized
    fun writeDsd(epoch: Long, sessionId: Long, data: ByteArray, length: Int = data.size): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        val encoder = dsdEncoder ?: return "USB exclusive DSD transport is not open."
        val activePacketizer = packetizer ?: return "USB exclusive DSD packetizer is not open."
        if (dsdIdleFillerRunning.get()) {
            stopDsdIdleFillerLocked()
        }
        return try {
            val encoded = encoder.encode(data, length)
            if (encoded.isNotEmpty()) {
                activePacketizer.write(encoded)
                if (!dsdPayloadWriteObserved) {
                    dsdPayloadWriteObserved = true
                    UsbDiagnostics.i(
                        TAG,
                        "DSD payload first-write mode=${currentDsdFormat?.mode} " +
                            "rawBytes=$length encodedBytes=${encoded.size}",
                    )
                }
            }
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive DSD write failed."
        }
    }

    /**
     * Reference seek rule for DSD: finish only the encoder's partial frame with 0x69; preserve DoP
     * marker phase and native ISO queue. Never reset/cancel the in-flight USB stream.
     */
    @Synchronized
    fun prepareDsdSeek(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        val encoder = dsdEncoder ?: return null
        val activePacketizer = packetizer ?: return null
        stopDsdIdleFillerLocked()
        return try {
            val tail = encoder.drain()
            if (tail.isNotEmpty()) {
                activePacketizer.write(tail)
            }
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive DSD seek preparation failed."
        }
    }

    /** While Media3 is paused, keep valid 0x69 DSD frames flowing so the DAC stays locked. */
    @Synchronized
    fun pauseDsd(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        startDsdIdleFillerLocked()
        return null
    }

    /** Stop the reference idle filler before real DSD samples resume. */
    @Synchronized
    fun resumeDsd(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        stopDsdIdleFillerLocked()
        return null
    }

    /**
     * Reference EOF behavior: drain a partial DSD frame, send ~200 ms of 0x69, flush the Java
     * packetizer, then continue the idle filler until the next track reuses the session or it closes.
     */
    @Synchronized
    fun finishDsdStream(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        val encoder = dsdEncoder ?: return null
        val activePacketizer = packetizer ?: return null
        val format = currentDsdFormat ?: return null
        stopDsdIdleFillerLocked()
        return try {
            val tail = encoder.drain()
            if (tail.isNotEmpty()) activePacketizer.write(tail)
            val silenceFramesPerWrite = maxOf(1, format.frameRate / 100)
            activePacketizer.write(encoder.encodeSilence(silenceFramesPerWrite * 20))
            activePacketizer.flush()
            startDsdIdleFillerLocked()
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive DSD final flush failed."
        }
    }

    fun telemetry(epoch: Long, sessionId: Long): TransportTelemetry {
        if (requestEpoch != epoch || nativeSessionId != sessionId) {
            return TransportTelemetry(-1L, -1L, -1L, -1L)
        }
        val values = UsbExclusiveNative.transportTelemetry(requestEpoch, nativeSessionId)
        return TransportTelemetry(
            pendingIsoPackets = values.getOrElse(0) { 0L },
            totalIsoPackets = values.getOrElse(1) { 0L },
            pendingOutputUrbs = values.getOrElse(2) { 0L },
            isoErrorCount = values.getOrElse(3) { 0L },
        )
    }

    @Synchronized
    fun isOpen(): Boolean = connection != null

    @Synchronized
    fun format(): PcmFormat? = currentFormat

    @Synchronized
    fun dsdFormat(epoch: Long, sessionId: Long): DsdFormat? =
        currentDsdFormat?.takeIf { matches(epoch, sessionId) }

    @Synchronized
    fun device(): UsbDevice? = device

    @Synchronized
    fun sessionToken(): Pair<Long, Long>? =
        if (requestEpoch > 0L && nativeSessionId > 0L) requestEpoch to nativeSessionId else null

    @Synchronized
    fun usbBitResolution(): Int? = currentUsbBitResolution

    @Synchronized
    override fun close() {
        stopDsdIdleFillerLocked()
        packetizer?.reset()
        packetizer = null
        target = null
        currentFormat = null
        currentDsdFormat = null
        currentUsbBitResolution = null
        dsdEncoder = null
        dsdPayloadWriteObserved = false
        if (connection != null) {
            UsbExclusiveNative.close(requestEpoch, nativeSessionId)
            connection?.close()
            connection = null
            UsbDiagnostics.i(TAG, "closed")
        }
        device = null
        requestEpoch = 0L
        nativeSessionId = 0L
    }

    private fun startDsdIdleFillerLocked() {
        val encoder = dsdEncoder ?: return
        val activePacketizer = packetizer ?: return
        val format = currentDsdFormat ?: return
        val fillerEpoch = requestEpoch
        val fillerSessionId = nativeSessionId
        if (dsdIdleFillerThread?.isAlive == true) return
        dsdIdleFillerRunning.set(true)
        val frames = maxOf(1, format.frameRate / 100)
        UsbDiagnostics.i(TAG, "DSD idle filler started at ${format.frameRate} frames/s")
        dsdIdleFillerThread = Thread({
            try {
                while (
                    dsdIdleFillerRunning.get() &&
                    matches(fillerEpoch, fillerSessionId) &&
                    UsbExclusiveNative.isCurrent(fillerEpoch, fillerSessionId)
                ) {
                    activePacketizer.write(encoder.encodeSilence(frames))
                }
            } catch (error: Throwable) {
                UsbDiagnostics.w(TAG, "DSD idle filler exit: ${error.message}")
            }
        }, "MicaUsbDsdIdleFill").also { it.start() }
    }

    private fun stopDsdIdleFillerLocked() {
        dsdIdleFillerRunning.set(false)
        val thread = dsdIdleFillerThread ?: return
        val startedNs = System.nanoTime()
        if (thread != Thread.currentThread()) {
            runCatching { thread.join() }
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        UsbDiagnostics.i(
            TAG,
            "DSD idle filler stop/join complete elapsedMs=$elapsedMs alive=${thread.isAlive}",
        )
        dsdIdleFillerThread = null
    }

    private fun bytesPerSampleForBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 8 -> 1
        bitDepth <= 16 -> 2
        bitDepth <= 24 -> 3
        else -> 4
    }

    private fun matches(epoch: Long, sessionId: Long): Boolean =
        requestEpoch == epoch && nativeSessionId == sessionId

    data class PcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
    )

    enum class DsdMode { DoP, Native }

    enum class DsdPreference { DopOnly, NativeOnly }

    data class DsdFormat(
        val dsdSampleRate: Int,
        val channels: Int,
        val mode: DsdMode,
        val frameRate: Int,
        val nativeFormat: String? = null,
    )

    data class DsdOpenResult(
        val format: DsdFormat? = null,
        val error: String? = null,
    )

    data class TransportTelemetry(
        val pendingIsoPackets: Long,
        val totalIsoPackets: Long,
        val pendingOutputUrbs: Long,
        val isoErrorCount: Long,
    )

    private class UsbExclusiveTransportException(message: String) : IllegalStateException(message)

    companion object {
        const val STALE_SESSION_ERROR = "USB exclusive epoch/session is stale."

        private const val STALE_SESSION = STALE_SESSION_ERROR
        const val TAG = "UsbExclusiveAudioTransport"
    }
}
