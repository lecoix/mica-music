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
    private var dsdEncoder: DsdStreamEncoder? = null
    private val dsdIdleFillerRunning = AtomicBoolean(false)
    private var dsdIdleFillerThread: Thread? = null

    @Synchronized
    fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): String? {
        if (!usbManager.hasPermission(device)) {
            return "USB permission is required before exclusive playback."
        }
        if (sampleRate <= 0 || channels <= 0 || bitDepth !in setOf(8, 16, 24, 32)) {
            return "Unsupported PCM format: ${sampleRate}Hz/${channels}ch/${bitDepth}bit."
        }

        val requestedFormat = PcmFormat(sampleRate, channels, bitDepth)
        if (connection != null && this.device?.deviceId == device.deviceId && currentFormat == requestedFormat) {
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

        UsbDiagnostics.i(
            TAG,
            "opening interface=${resolvedTarget.usbInterface.id} alt=${resolvedTarget.alternateSetting} " +
                "endpoint=0x${resolvedTarget.endpoint.address.toString(16)} maxPacket=${resolvedTarget.endpoint.maxPacketSize} " +
                "feedback=${resolvedTarget.feedbackEndpointLabel} sampleRate=$sampleRate channels=$channels " +
                "bitDepth=$bitDepth format=${resolvedTarget.formatInfo}",
        )
        val openError = UsbExclusiveNative.open(
            openedConnection.fileDescriptor,
            resolvedTarget.usbInterface.id,
            resolvedTarget.alternateSetting,
            resolvedTarget.endpoint.address,
            resolvedTarget.endpoint.maxPacketSize,
            resolvedTarget.feedbackEndpoint?.address ?: 0,
            resolvedTarget.feedbackEndpoint?.maxPacketSize ?: 0,
            false,
        )
        if (openError != null) {
            openedConnection.close()
            return openError
        }

        val quirk = UsbDacQuirks.forDevice(appContext, device.vendorId, device.productId)
        val clockError = UsbStreamingTargetResolver.configureUsbAudioClock(
            connection = openedConnection,
            device = device,
            target = resolvedTarget,
            sampleRate = sampleRate,
            quirk = quirk,
        )
        if (clockError != null) {
            UsbExclusiveNative.close()
            openedConnection.close()
            return clockError
        }

        val inputBytesPerSample = bytesPerSampleForBitDepth(bitDepth)
        val usbBytesPerSample = resolvedTarget.usbBytesPerSample
        val usbBitResolution = resolvedTarget.usbBitResolution ?: (usbBytesPerSample * 8)
        val packetBytes = UsbStreamingTargetResolver.requiredIsoPacketBytes(
            sampleRate = sampleRate,
            packetsPerSecond = resolvedTarget.packetsPerSecond,
            channels = channels,
            bytesPerSample = usbBytesPerSample,
        )
        UsbExclusiveNative.setIsoPacketSize(packetBytes)
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
                { UsbExclusiveNative.feedbackFramesPerPacketQ16() }
            },
            volumeGainQ16 = null,
        ) { data, packetLengths, packetCount ->
            val error = UsbExclusiveNative.writeIsoPackets(data, packetLengths, packetCount)
            if (error != null) {
                throw UsbExclusiveTransportException(error)
            }
        }

        connection = openedConnection
        this.device = device
        target = resolvedTarget
        packetizer = newPacketizer
        currentFormat = requestedFormat
        UsbDiagnostics.i(
            TAG,
            "opened sampleRate=$sampleRate channels=$channels bitDepth=$bitDepth " +
                "usbBytesPerSample=$usbBytesPerSample usbBitResolution=$usbBitResolution " +
                "packetsPerSecond=${resolvedTarget.packetsPerSecond}",
        )
        return null
    }

    /**
     * Opens the reference DSD path. Native DSD is attempted first when requested, then falls back
     * to DoP using the same quirk gates and descriptor rules as sylvakru-usb.
     */
    @Synchronized
    fun openDsd(
        usbManager: UsbManager,
        device: UsbDevice,
        dsdSampleRate: Int,
        channels: Int,
        preference: DsdPreference = DsdPreference.NativeWithDopFallback,
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
            this.device?.deviceId == device.deviceId &&
            existing?.dsdSampleRate == dsdSampleRate &&
            existing.channels == channels &&
            (preference == DsdPreference.NativeWithDopFallback || existing.mode == DsdMode.DoP)
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
        var nativeFallbackReason: String? = null

        if (preference == DsdPreference.NativeWithDopFallback) {
            if (quirk.nativeDsdMaxDsd != null && multiple != null && multiple > quirk.nativeDsdMaxDsd) {
                nativeFallbackReason =
                    "DSD$multiple exceeds native DSD limit DSD${quirk.nativeDsdMaxDsd} (quirk)"
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
                    nativeFallbackReason =
                        "device declares no usable native DSD RAW_DATA/quirk alt for ${dsdSampleRate}Hz"
                }
            }
        }

        if (selectedTarget == null) {
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
            nativeFallbackReason?.let {
                UsbDiagnostics.w(TAG, "native DSD unavailable, falling back to DoP: $it")
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
        val openError = UsbExclusiveNative.open(
            openedConnection.fileDescriptor,
            target.usbInterface.id,
            target.alternateSetting,
            target.endpoint.address,
            target.endpoint.maxPacketSize,
            target.feedbackEndpoint?.address ?: 0,
            target.feedbackEndpoint?.maxPacketSize ?: 0,
            false,
        )
        if (openError != null) {
            openedConnection.close()
            return DsdOpenResult(error = openError)
        }

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
            UsbExclusiveNative.close()
            openedConnection.close()
            return DsdOpenResult(error = clockError)
        }

        val usbBytesPerSample = target.usbBytesPerSample
        val usbBitResolution = target.usbBitResolution ?: (usbBytesPerSample * 8)
        val packetBytes = UsbStreamingTargetResolver.requiredIsoPacketBytes(
            sampleRate = frameRate,
            packetsPerSecond = target.packetsPerSecond,
            channels = channels,
            bytesPerSample = usbBytesPerSample,
        )
        UsbExclusiveNative.setIsoPacketSize(packetBytes)
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
                { UsbExclusiveNative.feedbackFramesPerPacketQ16() }
            },
            volumeGainQ16 = null,
        ) { data, packetLengths, packetCount ->
            val error = UsbExclusiveNative.writeIsoPackets(data, packetLengths, packetCount)
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
        dsdEncoder = checkNotNull(encoder)
        UsbDiagnostics.i(TAG, "opened DSD session $dsdFormat")
        return DsdOpenResult(format = dsdFormat)
    }

    @Synchronized
    fun writePcm(data: ByteArray): String? {
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
    @Synchronized
    fun finishStream(): String? {
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
    @Synchronized
    fun resetForSeek() {
        if (currentDsdFormat == null) {
            packetizer?.reset()
        }
    }

    /** Writes MSB-first byte-interleaved DSD through the reference DoP/native encoder. */
    @Synchronized
    fun writeDsd(data: ByteArray, length: Int = data.size): String? {
        val encoder = dsdEncoder ?: return "USB exclusive DSD transport is not open."
        val activePacketizer = packetizer ?: return "USB exclusive DSD packetizer is not open."
        if (dsdIdleFillerRunning.get()) {
            stopDsdIdleFillerLocked()
        }
        return try {
            val encoded = encoder.encode(data, length)
            if (encoded.isNotEmpty()) {
                activePacketizer.write(encoded)
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
    fun prepareDsdSeek(): String? {
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
    fun pauseDsd() {
        startDsdIdleFillerLocked()
    }

    /** Stop the reference idle filler before real DSD samples resume. */
    @Synchronized
    fun resumeDsd() {
        stopDsdIdleFillerLocked()
    }

    /**
     * Reference EOF behavior: drain a partial DSD frame, send ~200 ms of 0x69, flush the Java
     * packetizer, then continue the idle filler until the next track reuses the session or it closes.
     */
    @Synchronized
    fun finishDsdStream(): String? {
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

    @Synchronized
    fun telemetry(): TransportTelemetry {
        val values = UsbExclusiveNative.transportTelemetry()
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
    fun dsdFormat(): DsdFormat? = currentDsdFormat

    @Synchronized
    fun device(): UsbDevice? = device

    @Synchronized
    override fun close() {
        stopDsdIdleFillerLocked()
        packetizer?.reset()
        packetizer = null
        target = null
        currentFormat = null
        currentDsdFormat = null
        dsdEncoder = null
        if (connection != null) {
            UsbExclusiveNative.close()
            connection?.close()
            connection = null
            UsbDiagnostics.i(TAG, "closed")
        }
        device = null
    }

    private fun startDsdIdleFillerLocked() {
        val encoder = dsdEncoder ?: return
        val activePacketizer = packetizer ?: return
        val format = currentDsdFormat ?: return
        if (dsdIdleFillerThread?.isAlive == true) return
        dsdIdleFillerRunning.set(true)
        val frames = maxOf(1, format.frameRate / 100)
        UsbDiagnostics.i(TAG, "DSD idle filler started at ${format.frameRate} frames/s")
        dsdIdleFillerThread = Thread({
            try {
                while (dsdIdleFillerRunning.get()) {
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
        if (thread != Thread.currentThread()) {
            runCatching { thread.join(800) }
        }
        dsdIdleFillerThread = null
    }

    private fun bytesPerSampleForBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 8 -> 1
        bitDepth <= 16 -> 2
        bitDepth <= 24 -> 3
        else -> 4
    }

    data class PcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
    )

    enum class DsdMode { DoP, Native }

    enum class DsdPreference { DopOnly, NativeWithDopFallback }

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

    private companion object {
        const val TAG = "UsbExclusiveAudioTransport"
    }
}
