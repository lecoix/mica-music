/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
package com.afalphy.sylvakru

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val standardHardwareVolume = UsbStandardHardwareVolumeController(appContext)
    private val ibassoHardwareVolume = UsbIbassoHardwareVolumeController(appContext) { event ->
        synchronized(this) {
            val isDsd = currentDsdFormat != null
            val left = ibassoActualEventGainQ16(event.leftRaw, isDsd, dsdGainCompensationDb)
            val right = ibassoActualEventGainQ16(event.rightRaw, isDsd, dsdGainCompensationDb)
            val actual = if (left.gainQ16 <= right.gainQ16) left else right
            hardwareVolumeActive = true
            hardwareVolumeProtocol = IbassoHidVolumeProtocol.id
            hardwareVolumeRaw = actual.raw
            hardwareVolumeGainQ16 = actual.gainQ16
            hardwareVolumeReadbackVerified = true
            volumeControlEnabled = false
            setPcmVolumeGain(UNITY_GAIN_Q16, smooth = true)
            updateSessionDiagnostics(
                "hardwareVolumeEvent",
                mapOf(
                    "protocol" to IbassoHidVolumeProtocol.id,
                    "gainQ16" to actual.gainQ16,
                    "leftRaw" to event.leftRaw,
                    "rightRaw" to event.rightRaw,
                    "isDsd" to isDsd,
                    "replayGainMilliDb" to requestedReplayGainMilliDb,
                    "dsdGainCompensationDb" to dsdGainCompensationDb,
                ),
            )
        }
    }

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var target: UsbStreamingTarget? = null
    private var packetizer: UsbPcmIsoPacketizer? = null
    private var currentFormat: PcmFormat? = null
    private var currentDsdFormat: DsdFormat? = null
    private var currentUsbBitResolution: Int? = null
    private var currentSignature: UsbStreamSignature? = null
    private var dsdEncoder: DsdStreamEncoder? = null
    private var dsdPayloadWriteObserved = false
    private var feedbackIgnoredCount = 0L
    private var feedbackActualQ16: Int? = null
    private var feedbackNominalQ16: Int? = null
    private var feedbackEndpointLabel: String? = null
    private val diagnosticsLock = Any()
    private var sessionSequence = 0L
    private var sessionStartedAtMs = 0L
    private val sessionSubmittedBytes = AtomicLong()
    @Volatile private var latestSessionDiagnostics: Map<String, Any?> = emptyMap()
    private var lastTelemetryBufferMs: Long? = null
    private var minimumBufferLevelMs: Long? = null
    private var zeroBufferUnderruns = 0L
    private var lastTelemetryUnderrunCount = 0L
    private var lastUnderrunAtMs: Long? = null
    @Volatile private var requestEpoch: Long = 0L
    @Volatile private var nativeSessionId: Long = 0L
    @Volatile private var pcmSourceTimelineGeneration: Long = 0L
    private val dsdIdleFillerRunning = AtomicBoolean(false)
    private var dsdIdleFillerThread: Thread? = null
    private val pcmIdleFillerRunning = AtomicBoolean(false)
    private var pcmIdleFillerThread: Thread? = null
    private var deferredCloseGeneration = 0L
    private var deferredCloseThread: Thread? = null
    private val volumeSessionGeneration = AtomicLong()
    @Volatile private var pcmVolumeGainQ16 = UNITY_GAIN_Q16
    @Volatile private var volumeControlEnabled = false
    @Volatile private var volumeMode = "raw"
    @Volatile private var requestedVolumeGainQ16 = UNITY_GAIN_Q16
    @Volatile private var requestedReplayGainMilliDb = 0
    @Volatile private var dsdGainCompensationDb = 0
    @Volatile private var volumeSmoothHandoff = true
    @Volatile private var hardwareVolumeActive = false
    @Volatile private var hardwareVolumeProtocol: String? = null
    @Volatile private var hardwareVolumeRaw: Int? = null
    @Volatile private var hardwareVolumeGainQ16: Int? = null
    @Volatile private var hardwareVolumeReadbackVerified = false
    @Volatile private var hardwareVolumeWriteOnly = false
    @Volatile private var hardwareVolumeFrozen = false
    @Volatile private var hardwareVolumeSyncPending = false
    private var hardwareVolumeControl: HardwareVolumeControl? = null
    private var volumeRampGeneration = 0L
    private val volumeCommandLock = Any()
    private val volumeCommandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MicaUsbVolume").apply { isDaemon = true }
    }
    private var volumeCommandRunning = false
    private var runningVolumeRequest: UsbVolumeRequest? = null
    private var pendingVolumeRequest: UsbVolumeRequest? = null
    private var pendingVolumeRequestUpdatedAtMs: Long? = null
    private data class PendingPreservedPcmVerification(
        val volumeGeneration: Long,
        val deviceId: Int,
        val target: UsbVolumeTarget,
    )
    @Volatile private var pendingPreservedPcmVerification: PendingPreservedPcmVerification? = null

    @Synchronized
    fun open(
        epoch: Long,
        usbManager: UsbManager,
        device: UsbDevice,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): String? {
        cancelDeferredCloseLocked()
        if (!usbManager.hasPermission(device)) {
            return "USB permission is required before exclusive playback."
        }
        if (sampleRate <= 0 || channels <= 0 || bitDepth !in setOf(16, 24, 32)) {
            return "Unsupported PCM format: ${sampleRate}Hz/${channels}ch/${bitDepth}bit."
        }

        val requestedFormat = PcmFormat(sampleRate, channels, bitDepth)
        val quirk = UsbDacQuirks.forDevice(appContext, device.vendorId, device.productId)
        val nextSignature = UsbStreamSignature(
            deviceId = device.deviceId,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            dsdKind = null,
            nativeFormat = null,
        )
        val transitionAction = usbStreamTransitionAction(
            current = currentSignature,
            next = nextSignature,
            replaceActive = connection != null && requestEpoch == epoch,
        )
        val silencePlan = usbTransitionSilencePlan(
            transitionAction,
            preRollMs = quirk.clockPreRollMs ?: USB_TRANSITION_PREROLL_MS,
        )
        beginSessionDiagnostics(
            reused = transitionAction == UsbStreamTransitionAction.REUSE,
            device = device,
            sourceFormat = "pcm",
            dsdMode = null,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
        )
        UsbDiagnostics.i(TAG, "PCM transition action=$transitionAction current=$currentSignature next=$nextSignature")
        if (transitionAction == UsbStreamTransitionAction.REUSE) {
            invalidatePendingVolumeRequestsLocked()
            updateSessionDiagnostics("transitionStage", "reuse")
            UsbDiagnostics.i(TAG, "reusing exclusive USB PCM session $requestedFormat")
            return null
        }
        if (transitionAction == UsbStreamTransitionAction.SILENT_RECONFIGURE) {
            try {
                prepareSilentReconfigureLocked(silencePlan)
            } catch (error: Throwable) {
                return error.message ?: "USB PCM silent reconfigure failed."
            }
        }
        closeTransportLocked(preserveTrustedHardwareTarget = true)

        val openedConnection = usbManager.openDevice(device)
            ?: return "Failed to open USB device for exclusive playback."
        val resolvedTarget = UsbStreamingTargetResolver.resolvePcmTarget(
            connection = openedConnection,
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
        recordOutputSelection(resolvedTarget, sampleRate, channels, bitDepth, requireRawData = false)

        UsbDiagnostics.i(
            TAG,
            "opening interface=${resolvedTarget.usbInterface.id} alt=${resolvedTarget.alternateSetting} " +
                "endpoint=0x${resolvedTarget.endpoint.address.toString(16)} maxPacket=${resolvedTarget.endpoint.maxPacketSize} " +
                "feedback=${resolvedTarget.feedbackEndpointLabel} sampleRate=$sampleRate channels=$channels " +
                "bitDepth=$bitDepth format=${resolvedTarget.formatInfo}",
        )
        val activationPlan = UsbStreamingTargetResolver.streamingActivationPlan(
            isUac2 = resolvedTarget.isUac2,
            resetAltQuirk = quirk.streamingResetAlt,
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
            activationPlan.deferTargetAltUntilConfigured,
            activationPlan.resetAltBeforeConfigured,
        )
        if (openedNative.error != null || openedNative.sessionId == null) {
            openedConnection.close()
            return openedNative.error ?: "Native USB open returned no session id."
        }
        val sessionId = openedNative.sessionId

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
        UsbExclusiveNative.configureOutputStream(
            epoch = epoch,
            sessionId = sessionId,
            sampleRate = sampleRate,
            packetsPerSecond = resolvedTarget.packetsPerSecond,
            bytesPerFrame = channels * usbBytesPerSample,
            targetBufferMs = ASYNC_FRAME_FIFO_MS,
        )?.let {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return it
        }
        val initialSourceTimeline = UsbExclusiveNative.beginSourceTimeline(epoch, sessionId)
        if (initialSourceTimeline <= 0L) {
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return "USB PCM source timeline initialization failed."
        }
        pcmSourceTimelineGeneration = initialSourceTimeline
        val newPacketizer = UsbPcmIsoPacketizer(
            sampleRate = sampleRate,
            packetsPerSecond = resolvedTarget.packetsPerSecond,
            channels = channels,
            inputBytesPerSample = inputBytesPerSample,
            inputBitDepth = bitDepth,
            usbBytesPerSample = usbBytesPerSample,
            usbBitResolution = usbBitResolution,
            packetsPerTransfer = ASYNC_PACKETS_PER_TRANSFER,
            feedbackOutputPacketDivisor = feedbackOutputPacketDivisor(resolvedTarget),
            feedbackFramesPerPacketQ16 = resolvedTarget.feedbackEndpoint?.let {
                { UsbExclusiveNative.feedbackFramesPerPacketQ16(epoch, sessionId) }
            },
            reportFeedback = { actualQ16, nominalQ16, ignored ->
                recordFeedbackDiagnostics(resolvedTarget, actualQ16, nominalQ16, ignored)
            },
            volumeGainQ16 = { pcmVolumeGainQ16 },
            writeFrames = { data ->
                val timeline = pcmSourceTimelineGeneration
                if (timeline <= 0L) {
                    throw UsbExclusiveTransportException("USB PCM source timeline is unavailable.")
                }
                val error = UsbExclusiveNative.writeSourceFrames(
                    epoch,
                    sessionId,
                    timeline,
                    data,
                    data.size,
                )
                if (error != null) throw UsbExclusiveTransportException(error)
                sessionSubmittedBytes.addAndGet(data.size.toLong())
            },
            writeSyntheticFrames = { data ->
                val error = UsbExclusiveNative.writeFrames(epoch, sessionId, data, data.size)
                if (error != null) throw UsbExclusiveTransportException(error)
                sessionSubmittedBytes.addAndGet(data.size.toLong())
            },
        )

        val configuredPreRollMs = silencePlan.newPreRollMs
        val asyncStartupPrimeMs = asyncStartupPrimeMs(resolvedTarget.packetsPerSecond)
        val startupPreRollMs = maxOf(configuredPreRollMs, asyncStartupPrimeMs)
        val primedPreRollMs = asyncStartupPrimeMs
        if (primedPreRollMs > 0) {
            try {
                newPacketizer.writeUsbSilence(usbSilenceFrames(sampleRate, primedPreRollMs))
                newPacketizer.flush()
            } catch (error: Throwable) {
                UsbExclusiveNative.close(epoch, sessionId)
                openedConnection.close()
                return error.message ?: "USB PCM priming failed."
            }
        }
        UsbExclusiveNative.activateConfiguredAlt(
            epoch,
            sessionId,
            resolvedTarget.alternateSetting,
        )?.let { altError ->
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return altError
        }
        if (activationPlan.deferTargetAltUntilConfigured) {
            UsbDiagnostics.i(
                TAG,
                "UAC2 PCM activation complete interface=${resolvedTarget.usbInterface.id} " +
                    "alt=${resolvedTarget.alternateSetting} primedMs=$primedPreRollMs " +
                    "resetAlt=${activationPlan.resetAltBeforeConfigured} " +
                    "sequence=${if (activationPlan.resetAltBeforeConfigured) "alt0->clock->prime->targetAlt" else "clock->prime->targetAlt"}",
            )
        }

        connection = openedConnection
        this.device = device
        target = resolvedTarget
        packetizer = newPacketizer
        currentFormat = requestedFormat
        currentSignature = nextSignature
        currentUsbBitResolution = usbBitResolution
        requestEpoch = epoch
        nativeSessionId = sessionId
        if (volumeMode != "raw") {
            applyVolumeControlLocked()?.let { volumeError ->
                close()
                return volumeError
            }
        }
        val remainingPreRollMs = startupPreRollMs - primedPreRollMs
        if (remainingPreRollMs > 0) {
            try {
                newPacketizer.writeUsbSilence(usbSilenceFrames(sampleRate, remainingPreRollMs))
                updateSessionDiagnostics("transitionStage", "new-silence-preroll")
            } catch (error: Throwable) {
                UsbExclusiveNative.close(epoch, sessionId)
                openedConnection.close()
                return error.message ?: "USB PCM pre-roll failed."
            }
        }
        startPcmIdleFillerLocked()
        schedulePreservedPcmVerificationAfterPreRoll()

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
        cancelDeferredCloseLocked()
        if (!usbManager.hasPermission(device)) {
            return DsdOpenResult(error = "USB permission is required before exclusive playback.")
        }
        if (dsdSampleRate <= 0 || channels <= 0) {
            return DsdOpenResult(error = "Unsupported DSD format: ${dsdSampleRate}Hz/${channels}ch.")
        }

        val quirk = UsbDacQuirks.forDevice(appContext, device.vendorId, device.productId)
        val multiple = if (dsdSampleRate % 44_100 == 0) dsdSampleRate / 44_100 else null
        val existing = currentDsdFormat
        val requestedKind = if (preference == DsdPreference.NativeOnly) "native" else "dop"
        val nextSignature = UsbStreamSignature(
            deviceId = device.deviceId,
            sampleRate = dsdSampleRate,
            channels = channels,
            bitDepth = null,
            dsdKind = requestedKind,
            nativeFormat = if (preference == DsdPreference.NativeOnly) quirk.nativeDsdFormat else null,
        )
        val transitionAction = usbStreamTransitionAction(
            current = currentSignature,
            next = nextSignature,
            replaceActive = connection != null && requestEpoch == epoch,
        )
        val silencePlan = usbTransitionSilencePlan(
            transitionAction,
            preRollMs = quirk.clockPreRollMs ?: USB_TRANSITION_PREROLL_MS,
        )
        beginSessionDiagnostics(
            reused = transitionAction == UsbStreamTransitionAction.REUSE,
            device = device,
            sourceFormat = "dsd",
            dsdMode = requestedKind,
            sampleRate = dsdSampleRate,
            channels = channels,
            bitDepth = null,
        )
        UsbDiagnostics.i(TAG, "DSD transition action=$transitionAction current=$currentSignature next=$nextSignature")
        if (transitionAction == UsbStreamTransitionAction.REUSE && existing != null) {
            invalidatePendingVolumeRequestsLocked()
            updateSessionDiagnostics("transitionStage", "reuse")
            stopDsdIdleFillerLocked()
            UsbDiagnostics.i(TAG, "reusing exclusive USB DSD session $existing")
            return DsdOpenResult(format = existing)
        }
        if (transitionAction == UsbStreamTransitionAction.SILENT_RECONFIGURE) {
            try {
                prepareSilentReconfigureLocked(silencePlan)
            } catch (error: Throwable) {
                return DsdOpenResult(error = error.message ?: "USB DSD silent reconfigure failed.")
            }
        }
        closeTransportLocked(preserveTrustedHardwareTarget = true)

        val openedConnection = usbManager.openDevice(device)
            ?: return DsdOpenResult(error = "Failed to open USB device for exclusive DSD playback.")
        val descriptors = openedConnection.rawDescriptors

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
                    connection = openedConnection,
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
                connection = openedConnection,
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
        recordOutputSelection(
            target,
            frameRate,
            channels,
            if (selectedMode == DsdMode.Native) inputBitDepth else null,
            requireRawData = selectedMode == DsdMode.Native,
        )
        UsbDiagnostics.i(
            TAG,
            "opening DSD mode=$selectedMode interface=${target.usbInterface.id} alt=${target.alternateSetting} " +
                "endpoint=0x${target.endpoint.address.toString(16)} maxPacket=${target.endpoint.maxPacketSize} " +
                "feedback=${target.feedbackEndpointLabel} dsdSampleRate=$dsdSampleRate frameRate=$frameRate " +
                "nativeFormat=${nativeFormat ?: "n/a"} format=${target.formatInfo}",
        )
        val activationPlan = UsbStreamingTargetResolver.streamingActivationPlan(
            isUac2 = target.isUac2,
            resetAltQuirk = quirk.streamingResetAlt,
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
            activationPlan.deferTargetAltUntilConfigured,
            activationPlan.resetAltBeforeConfigured,
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
        UsbExclusiveNative.configureOutputStream(
            epoch = epoch,
            sessionId = sessionId,
            sampleRate = frameRate,
            packetsPerSecond = target.packetsPerSecond,
            bytesPerFrame = channels * usbBytesPerSample,
            targetBufferMs = ASYNC_FRAME_FIFO_MS,
        )?.let {
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
            packetsPerTransfer = ASYNC_PACKETS_PER_TRANSFER,
            feedbackOutputPacketDivisor = feedbackOutputPacketDivisor(target),
            feedbackFramesPerPacketQ16 = target.feedbackEndpoint?.let {
                { UsbExclusiveNative.feedbackFramesPerPacketQ16(epoch, sessionId) }
            },
            reportFeedback = { actualQ16, nominalQ16, ignored ->
                recordFeedbackDiagnostics(target, actualQ16, nominalQ16, ignored)
            },
            volumeGainQ16 = { pcmVolumeGainQ16 },
            writeFrames = { data ->
                val error = UsbExclusiveNative.writeFrames(epoch, sessionId, data, data.size)
                if (error != null) throw UsbExclusiveTransportException(error)
                sessionSubmittedBytes.addAndGet(data.size.toLong())
            },
        )

        val configuredPreRollMs = silencePlan.newPreRollMs
        val asyncStartupPrimeMs = asyncStartupPrimeMs(target.packetsPerSecond)
        val startupPreRollMs = maxOf(configuredPreRollMs, asyncStartupPrimeMs)
        val primedPreRollMs = asyncStartupPrimeMs
        if (primedPreRollMs > 0) {
            try {
                val primeFrames = maxOf(
                    1,
                    ((frameRate.toLong() * primedPreRollMs + 999L) / 1_000L).toInt(),
                )
                dsdPacketizer.write(checkNotNull(encoder).encodeSilence(primeFrames))
                dsdPacketizer.flush()
            } catch (error: UsbExclusiveTransportException) {
                UsbExclusiveNative.close(epoch, sessionId)
                openedConnection.close()
                return DsdOpenResult(error = error.message ?: "USB exclusive DSD priming failed.")
            }
        }
        UsbExclusiveNative.activateConfiguredAlt(epoch, sessionId, target.alternateSetting)?.let { altError ->
            UsbExclusiveNative.close(epoch, sessionId)
            openedConnection.close()
            return DsdOpenResult(error = altError)
        }
        if (activationPlan.deferTargetAltUntilConfigured) {
            UsbDiagnostics.i(
                TAG,
                "UAC2 DSD activation complete mode=$selectedMode interface=${target.usbInterface.id} " +
                    "alt=${target.alternateSetting} frameRate=$frameRate primedMs=$primedPreRollMs " +
                    "resetAlt=${activationPlan.resetAltBeforeConfigured} " +
                    "sequence=${if (activationPlan.resetAltBeforeConfigured) "alt0->prime->clock->targetAlt" else "prime->clock->targetAlt"}",
            )
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
        currentSignature = nextSignature.copy(
            dsdKind = if (dsdFormat.mode == DsdMode.Native) "native" else "dop",
            nativeFormat = dsdFormat.nativeFormat,
        )
        currentUsbBitResolution = usbBitResolution
        dsdEncoder = checkNotNull(encoder)
        dsdPayloadWriteObserved = false
        requestEpoch = epoch
        nativeSessionId = sessionId
        if (volumeMode != "raw") {
            applyVolumeControlLocked()?.let { volumeError ->
                close()
                return DsdOpenResult(error = volumeError)
            }
        }

        // Reference transition rule: a newly configured DSD stream gets valid 0x69 silence
        // before real payload. Session reuse above intentionally skips this pre-roll.
        val remainingPreRollMs = startupPreRollMs - primedPreRollMs
        if (remainingPreRollMs > 0) {
            try {
                val preRollFrames = maxOf(
                    1,
                    ((frameRate.toLong() * remainingPreRollMs + 999L) / 1_000L).toInt(),
                )
                dsdPacketizer.write(dsdEncoder!!.encodeSilence(preRollFrames))
                dsdPacketizer.flush()
                UsbDiagnostics.i(
                    TAG,
                    "DSD new-session silence pre-roll ${startupPreRollMs}ms " +
                        "primedMs=$primedPreRollMs remainingFrames=$preRollFrames",
                )
            } catch (error: UsbExclusiveTransportException) {
                val message = error.message ?: "USB exclusive DSD pre-roll failed."
                close()
                return DsdOpenResult(error = message)
            }
        }
        UsbDiagnostics.i(TAG, "opened DSD session $dsdFormat")
        return DsdOpenResult(format = dsdFormat)
    }

    @Synchronized
    fun writePcm(epoch: Long, sessionId: Long, data: ByteArray): String? {
        if (requestEpoch != epoch || nativeSessionId != sessionId) return STALE_SESSION
        if (currentDsdFormat != null) {
            return "USB exclusive transport is currently in DSD mode."
        }
        val activePacketizer = packetizer ?: return "USB exclusive PCM transport is not open."
        if (pcmIdleFillerRunning.get()) {
            stopPcmIdleFillerLocked()
        }
        return try {
            activePacketizer.write(data)
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive write failed."
        }
    }

    @Synchronized
    fun beginPcmTimeline(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        if (currentDsdFormat != null) return "USB exclusive transport is currently in DSD mode."
        val timeline = UsbExclusiveNative.beginSourceTimeline(epoch, sessionId)
        if (timeline <= 0L) return "USB PCM source timeline reset failed."
        pcmSourceTimelineGeneration = timeline
        return null
    }

    fun consumedPcmSourceFrames(epoch: Long, sessionId: Long): Long {
        if (!matches(epoch, sessionId) || currentDsdFormat != null) return -1L
        val timeline = pcmSourceTimelineGeneration
        if (timeline <= 0L) return -1L
        return UsbExclusiveNative.consumedSourceFrames(epoch, sessionId, timeline)
    }

    /** Flushes the packetizer's final short packet without discarding already queued USB URBs. */
    @Synchronized
    fun finishStream(epoch: Long, sessionId: Long): String? {
        if (requestEpoch != epoch || nativeSessionId != sessionId) return STALE_SESSION
        if (currentDsdFormat != null) {
            return "USB exclusive transport is currently in DSD mode."
        }
        val activePacketizer = packetizer ?: return null
        return try {
            activePacketizer.flush()
            scheduleDeferredCloseLocked()
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

    /** Reference PCM pause behavior: fade the current sample to zero, then leave the USB queue alone. */
    @Synchronized
    fun pausePcm(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        if (currentDsdFormat != null) return "USB exclusive transport is currently in DSD mode."
        val activePacketizer = packetizer ?: return null
        return try {
            activePacketizer.writeTransitionTail(
                USB_TRANSITION_FADE_MS,
                USB_TRANSITION_OLD_SILENCE_MS,
            )
            startPcmIdleFillerLocked()
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive PCM pause transition failed."
        }
    }

    /** Reference PCM resume behavior: fade the first resumed samples in over 16 ms. */
    @Synchronized
    fun resumePcm(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        if (currentDsdFormat != null) return "USB exclusive transport is currently in DSD mode."
        stopPcmIdleFillerLocked()
        packetizer?.beginFadeIn(USB_PAUSE_RESUME_FADE_MS)
        return null
    }

    /** Reference PCM seek splice: old position fades out, cadence resets, new position fades in. */
    @Synchronized
    fun preparePcmSeek(epoch: Long, sessionId: Long): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        if (currentDsdFormat != null) return "USB exclusive transport is currently in DSD mode."
        val activePacketizer = packetizer ?: return null
        return try {
            activePacketizer.writeTransitionTail(USB_PAUSE_RESUME_FADE_MS, 0)
            activePacketizer.reset()
            activePacketizer.beginFadeIn(USB_PAUSE_RESUME_FADE_MS)
            null
        } catch (error: UsbExclusiveTransportException) {
            error.message ?: "USB exclusive PCM seek transition failed."
        }
    }

    /** Writes MSB-first byte-interleaved DSD through the reference DoP/native encoder. */
    @Synchronized
    fun writeDsd(epoch: Long, sessionId: Long, data: ByteArray, length: Int = data.size): String? {
        if (!matches(epoch, sessionId)) return STALE_SESSION
        dsdPayloadVolumeSafetyError(
            volumeMode = volumeMode,
            hardwareVolumeActive = hardwareVolumeActive,
            readbackVerified = hardwareVolumeReadbackVerified,
            writeOnly = hardwareVolumeWriteOnly,
        )?.let { return it }
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
            scheduleDeferredCloseLocked()
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
        val telemetry = TransportTelemetry(
            pendingIsoPackets = values.getOrElse(0) { 0L },
            totalIsoPackets = values.getOrElse(1) { 0L },
            pendingOutputUrbs = values.getOrElse(2) { 0L },
            isoErrorCount = values.getOrElse(3) { 0L },
        )
        val packetsPerSecond = target?.packetsPerSecond ?: 0
        if (packetsPerSecond > 0) {
            val nowMs = System.nanoTime() / 1_000_000L
            val bufferLevelMs = (telemetry.pendingIsoPackets.coerceAtLeast(0L) * 1000L) / packetsPerSecond
            if (lastTelemetryBufferMs != null && lastTelemetryBufferMs!! > 0L && bufferLevelMs == 0L) {
                zeroBufferUnderruns += 1
            }
            lastTelemetryBufferMs = bufferLevelMs
            if (bufferLevelMs > 0L) {
                minimumBufferLevelMs = minimumBufferLevelMs?.let { minOf(it, bufferLevelMs) } ?: bufferLevelMs
            }
            val underrunCount = telemetry.isoErrorCount.coerceAtLeast(0L) + zeroBufferUnderruns
            if (underrunCount > lastTelemetryUnderrunCount) lastUnderrunAtMs = nowMs
            lastTelemetryUnderrunCount = underrunCount
            val elapsedMs = (nowMs - sessionStartedAtMs).coerceAtLeast(0L)
            val submittedBytes = sessionSubmittedBytes.get()
            updateSessionDiagnostics(
                "transport",
                mapOf(
                    "submittedBytes" to submittedBytes,
                    "averageBytesPerSecond" to if (elapsedMs > 0) submittedBytes * 1000L / elapsedMs else 0L,
                    "bufferLevelMs" to bufferLevelMs,
                    "minimumBufferLevelMs" to minimumBufferLevelMs,
                    "transportStrategy" to ASYNC_TRANSPORT_STRATEGY,
                    "poolSlots" to ASYNC_OUTPUT_SLOTS,
                    "packetsPerTransfer" to transportPacketsPerTransfer(packetsPerSecond),
                    "pendingUrbs" to telemetry.pendingOutputUrbs,
                    "isoPacketCount" to telemetry.totalIsoPackets,
                    "underrunCount" to underrunCount,
                    "lastUnderrunAtMs" to lastUnderrunAtMs,
                ),
            )
        }
        return telemetry
    }

    fun setVolume(
        gainQ16: Int,
        replayGainMilliDb: Int,
        mode: String,
        dsdCompensationDb: Int,
        smoothHandoff: Boolean,
    ): String? {
        val request = UsbVolumeRequest(
            gainQ16 = gainQ16.coerceIn(0, UNITY_GAIN_Q16),
            replayGainMilliDb = replayGainMilliDb,
            mode = mode.lowercase(Locale.ROOT)
                .takeIf { it == "auto" || it == "dac" || it == "digital" || it == "raw" }
                ?: "auto",
            dsdCompensationDb = dsdCompensationDb.coerceIn(-12, 6),
            smoothHandoff = smoothHandoff,
            sessionGeneration = volumeSessionGeneration.get(),
        )
        val isDsd = synchronized(this) { currentDsdFormat != null }
        val start = synchronized(volumeCommandLock) {
            if (volumeCommandRunning) {
                val running = checkNotNull(runningVolumeRequest)
                pendingVolumeRequest = coalescedUsbVolumeRequest(running, pendingVolumeRequest, request, isDsd)
                pendingVolumeRequestUpdatedAtMs = android.os.SystemClock.elapsedRealtime()
                UsbDiagnostics.i(TAG, "USB volume request coalesced into the pending target.")
                false
            } else {
                volumeCommandRunning = true
                runningVolumeRequest = request
                true
            }
        }
        if (start) volumeCommandExecutor.execute { drainVolumeRequests(request) }
        return null
    }

    private fun drainVolumeRequests(first: UsbVolumeRequest) {
        var request: UsbVolumeRequest? = first
        var lastCompletedAtMs: Long? = null
        var lastCompletedProtocol: String? = null
        while (true) {
            if (lastCompletedAtMs != null) {
                var next: UsbVolumeRequest? = null
                while (next == null) {
                    var delayMs = 0L
                    var hasPending = true
                    synchronized(volumeCommandLock) {
                        delayMs = usbVolumePendingDelayMs(
                            lastCompletedProtocol,
                            lastCompletedAtMs,
                            pendingVolumeRequestUpdatedAtMs,
                            android.os.SystemClock.elapsedRealtime(),
                        )
                        if (delayMs == 0L) {
                            next = pendingVolumeRequest
                            pendingVolumeRequest = null
                            pendingVolumeRequestUpdatedAtMs = null
                            if (next == null) {
                                runningVolumeRequest = null
                                volumeCommandRunning = false
                                hasPending = false
                            } else {
                                runningVolumeRequest = next
                            }
                        }
                    }
                    if (!hasPending) return
                    if (next == null) android.os.SystemClock.sleep(delayMs)
                }
                request = next
            }
            val current = checkNotNull(request)
            val requestProtocol = synchronized(this) {
                val activeDevice = device
                val quirk = activeDevice?.let {
                    UsbDacQuirks.forDevice(appContext, it.vendorId, it.productId)
                }
                if (quirk == null) null else usbVolumeProtocolForRequest(
                    current.mode,
                    quirk.hardwareVolumeProtocol,
                    quirk.hardwareVolumeEnabled != false,
                    hardwareVolumeSupportedForStream(
                        usbVolumeProtocolSelection(quirk.hardwareVolumeProtocol),
                        currentDsdFormat != null,
                        quirk.hardwareVolumeDsdSupported,
                    ),
                )
            }
            try {
                applyVolumeRequest(current)
            } catch (error: Exception) {
                UsbDiagnostics.w(TAG, "USB volume transaction failed: ${error.message}")
                synchronized(this) {
                    updateSessionDiagnostics("volumeError", error.message ?: error.javaClass.simpleName)
                }
            }
            lastCompletedProtocol = requestProtocol
            lastCompletedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun applyVolumeRequest(request: UsbVolumeRequest) {
        if (request.sessionGeneration != volumeSessionGeneration.get()) return
        synchronized(this) {
            if (request.sessionGeneration != volumeSessionGeneration.get()) return
            requestedVolumeGainQ16 = request.gainQ16
            requestedReplayGainMilliDb = request.replayGainMilliDb
            volumeMode = request.mode
            dsdGainCompensationDb = request.dsdCompensationDb
            volumeSmoothHandoff = request.smoothHandoff
            applyVolumeControlLocked()?.let { error ->
                updateSessionDiagnostics("volumeError", error)
            }
        }
    }

    private fun invalidatePendingVolumeRequestsLocked() {
        volumeSessionGeneration.incrementAndGet()
        pendingPreservedPcmVerification = null
        synchronized(volumeCommandLock) {
            pendingVolumeRequest = null
            pendingVolumeRequestUpdatedAtMs = null
        }
    }
    @Synchronized
    fun isVolumeControlEngaged(): Boolean = isUsbVolumeControlEngaged(
        active = connection != null,
        hardwareVolumeActive = hardwareVolumeActive,
        hardwareVolumeSyncPending = hardwareVolumeSyncPending,
        digitalVolumeActive = volumeControlEnabled,
        bitDepth = currentFormat?.bitDepth,
    )
    @Synchronized
    fun volumeDiagnostics(): Map<String, Any?> = mapOf(
        "mode" to volumeMode,
        "hardwareVolumeActive" to hardwareVolumeActive,
        "hardwareVolumeProtocol" to hardwareVolumeProtocol,
        "hardwareVolumeRaw" to hardwareVolumeRaw,
        "hardwareVolumeGainQ16" to hardwareVolumeGainQ16,
        "hardwareVolumeReadbackVerified" to hardwareVolumeReadbackVerified,
        "hardwareVolumeWriteOnly" to hardwareVolumeWriteOnly,
        "hardwareVolumeSyncPending" to hardwareVolumeSyncPending,
        "hardwareVolumeFrozen" to hardwareVolumeFrozen,
        "digitalVolumeActive" to volumeControlEnabled,
        "pcmVolumeGainQ16" to pcmVolumeGainQ16,
        "requestedVolumeGainQ16" to requestedVolumeGainQ16,
        "requestedReplayGainMilliDb" to requestedReplayGainMilliDb,
        "dsdGainCompensationDb" to dsdGainCompensationDb,
        "smoothHandoff" to volumeSmoothHandoff,
    )

    private fun applyVolumeControlLocked(forceSmoothPcmHandoff: Boolean = false): String? {
        val activeConnection = connection ?: return null
        val activeDevice = device ?: return null
        val activeTarget = target ?: return null
        val isDsd = currentDsdFormat != null
        val quirk = UsbDacQuirks.forDevice(appContext, activeDevice.vendorId, activeDevice.productId)
        val wasHardwareActive = hardwareVolumeActive
        val wasHardwareFrozen = hardwareVolumeFrozen
        if (!hardwareVolumeFrozen) {
            hardwareVolumeActive = false
            hardwareVolumeProtocol = null
            hardwareVolumeRaw = null
            hardwareVolumeGainQ16 = null
            hardwareVolumeReadbackVerified = false
            hardwareVolumeWriteOnly = false
            hardwareVolumeSyncPending = false
        }

        val effectiveGainQ16 = effectiveVolumeGainQ16(
            requestedVolumeGainQ16,
            requestedReplayGainMilliDb,
        )
        val effectiveHardwareGainQ16 = effectiveHardwareVolumeGainQ16(
            requestedVolumeGainQ16,
            requestedReplayGainMilliDb,
            dsdGainCompensationDb,
            isDsd,
        )
        var fallbackReason: String? = null

        when (volumeMode) {
            "raw" -> {
                if (!hardwareVolumeFrozen) {
                    hardwareVolumeControl = null
                    volumeControlEnabled = false
                    setPcmVolumeGain(UNITY_GAIN_Q16, smooth = false)
                }
            }
            "digital" -> {
                if (!hardwareVolumeFrozen) {
                    hardwareVolumeControl = null
                    if (isDsd) {
                        fallbackReason = "DSD digital volume is not supported; select raw or verified DAC hardware volume."
                        volumeControlEnabled = false
                    } else {
                        volumeControlEnabled = true
                        setPcmVolumeGain(effectiveGainQ16, volumeSmoothHandoff)
                    }
                }
            }
            "auto", "dac" -> {
                val protocolSelection = usbVolumeProtocolSelection(quirk.hardwareVolumeProtocol)
                if (!hardwareVolumeSupportedForStream(protocolSelection, isDsd, quirk.hardwareVolumeDsdSupported)) {
                    fallbackReason = "DSD hardware volume is not supported by protocol capability or quirk."
                } else when (protocolSelection) {
is VendorUsbVolumeProtocol -> {
                        val volumeTarget = protocolSelection.protocol.appGainToRaw(
                            requestedVolumeGainQ16,
                            requestedReplayGainMilliDb,
                            if (isDsd) dsdGainCompensationDb else 0,
                        )
                        val activeRaw = if (isDsd) volumeTarget.dsdRaw else volumeTarget.baseRaw
                        val generation = volumeSessionGeneration.get()
                        val result = ibassoHardwareVolume.apply(
                            device = activeDevice,
                            target = volumeTarget,
                            activeRaw = activeRaw,
                            isDsd = isDsd,
                            dsdCompensationDb = dsdGainCompensationDb,
                            smoothHandoff = volumeSmoothHandoff,
                            wasFrozen = wasHardwareFrozen,
                            hasPendingRequest = { synchronized(volumeCommandLock) { pendingVolumeRequest != null } },
                        ) { generation == volumeSessionGeneration.get() }
                        fallbackReason = result.error
                        val actual = result.actual
                        hardwareVolumeActive = result.active
                        hardwareVolumeReadbackVerified = result.readbackVerified
                        hardwareVolumeWriteOnly = result.writeOnly
                        hardwareVolumeFrozen = result.frozen
                        hardwareVolumeSyncPending = result.syncPending
                        hardwareVolumeProtocol = if (result.active) protocolSelection.protocol.id else null
                        hardwareVolumeRaw = actual?.raw
                        hardwareVolumeGainQ16 = actual?.gainQ16
                        if (result.frozen && !isDsd && actual != null) {
                            result.trustedTarget?.let { trusted ->
                                pendingPreservedPcmVerification = PendingPreservedPcmVerification(
                                    volumeGeneration = generation,
                                    deviceId = activeDevice.deviceId,
                                    target = trusted,
                                )
                                updateSessionDiagnostics("transitionStage", "hardware-volume-frozen")
                            }
                            val compensation = frozenPcmCompensationGainQ16(
                                trustedHardwareGainQ16 = actual.gainQ16,
                                requestedTotalGainQ16 = effectiveGainQ16,
                            )
                            volumeControlEnabled = compensation < UNITY_GAIN_Q16
                            setPcmVolumeGain(minOf(pcmVolumeGainQ16, compensation), smooth = false)
                        }
                    }
                    is UnsupportedUsbVolumeProtocol -> {
                        fallbackReason = "Unsupported hardware volume protocol: ${protocolSelection.id}."
                    }
                    StandardUsbVolumeProtocol -> {
                        val existingControl = hardwareVolumeControl
                        val control = existingControl ?: standardHardwareVolume.resolve(
                            activeConnection,
                            activeDevice,
                            activeTarget,
                            quirk,
                        )?.also { hardwareVolumeControl = it }
                        if (control == null) {
                            fallbackReason = "No unique writable playback Feature Unit passed probing."
                        } else {
                            val shouldReadInitial = shouldReadInitialHardwareVolume(
                                isNewConnection = existingControl == null,
                                readable = true,
                            )
                            val initialValues = if (shouldReadInitial) {
                                standardHardwareVolume.readValues(activeConnection, activeDevice, control)
                            } else {
                                null
                            }
                            val initialActual = initialValues?.let {
                                actualHardwareVolume(it, control.range.muteQ8_8)
                            }
                            val handoff = hardwareVolumeHandoffTarget(
                                volumeSmoothHandoff,
                                initialActual?.gainQ16,
                                effectiveHardwareGainQ16,
                            )
                            val actual = if (handoff.source == HardwareVolumeHandoffSource.DEVICE) {
                                initialActual
                            } else {
                                val generation = volumeSessionGeneration.get()
                                standardHardwareVolume.write(
                                    activeConnection,
                                    activeDevice,
                                    control,
                                    handoff.gainQ16,
                                ) { generation == volumeSessionGeneration.get() }
                                    .also { result -> fallbackReason = result.error }
                                    .actual
                            }
                            if (fallbackReason == null && actual != null) {
                                hardwareVolumeActive = true
                                hardwareVolumeReadbackVerified = true
                                hardwareVolumeProtocol = control.features.first().protocol
                                hardwareVolumeRaw = actual.raw
                                hardwareVolumeGainQ16 = actual.gainQ16
                            } else if (fallbackReason == null) {
                                fallbackReason = "Hardware volume readback is unavailable."
                            }
                        }
                    }
                }

                if (!hardwareVolumeFrozen) {
                    volumeControlEnabled = shouldUsePcmDigitalVolumeFallback(
                        isDsd = isDsd,
                        volumeMode = volumeMode,
                        hardwareVolumeActive = hardwareVolumeActive,
                        readbackVerified = hardwareVolumeReadbackVerified,
                        writeOnly = hardwareVolumeWriteOnly,
                    )
                    val targetPcmGain = if (volumeControlEnabled) effectiveGainQ16 else UNITY_GAIN_Q16
                    val immediateFallback = !isDsd && wasHardwareActive && !hardwareVolumeActive && volumeMode != "raw"
                    setPcmVolumeGain(
                        targetPcmGain,
                        if (immediateFallback) {
                            false
                        } else {
                            forceSmoothPcmHandoff || shouldSmoothPcmVolumeHandoff(
                                volumeSmoothHandoff,
                                isDsd,
                                wasHardwareActive,
                                hardwareVolumeActive,
                            )
                        },
                    )
                }
                if (isDsd) {
                    unsafeDsdVolumeReason(
                        isDsd = true,
                        hardwareVolumeActive = hardwareVolumeActive,
                        readbackVerified = hardwareVolumeReadbackVerified,
                        writeOnly = hardwareVolumeWriteOnly,
                    )?.let { fallbackReason = fallbackReason ?: it }
                }
            }
        }

        val diagnostics = volumeDiagnostics() + mapOf(
            "fallbackReason" to fallbackReason,
            "features" to hardwareVolumeControl?.features?.map { it.description() },
            "range" to hardwareVolumeControl?.range?.let {
                mapOf(
                    "minQ8_8Db" to it.minQ8_8,
                    "maxQ8_8Db" to it.maxQ8_8,
                    "stepQ8_8Db" to it.stepQ8_8,
                    "muteQ8_8Db" to it.muteQ8_8,
                )
            },
        )
        updateSessionDiagnostics("hardwareVolume", diagnostics)
        if (fallbackReason != null) {
            UsbDiagnostics.w(TAG, "hardware volume fallback: $fallbackReason mode=$volumeMode")
        }
        return if (isDsd && volumeMode != "raw") fallbackReason else null
    }

    private fun setPcmVolumeGain(targetGainQ16: Int, smooth: Boolean) {
        val startGainQ16 = pcmVolumeGainQ16
        val generation = ++volumeRampGeneration
        if (!smooth || startGainQ16 == targetGainQ16) {
            pcmVolumeGainQ16 = targetGainQ16
            return
        }
        val steps = pcmVolumeRampSteps(startGainQ16, targetGainQ16)
        repeat(steps) { index ->
            mainHandler.postDelayed({
                if (generation == volumeRampGeneration) {
                    val step = index + 1
                    pcmVolumeGainQ16 = startGainQ16 +
                        ((targetGainQ16 - startGainQ16) * step / steps)
                }
            }, (index + 1) * USB_VOLUME_RAMP_STEP_MS)
        }
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
        closeTransportLocked(preserveTrustedHardwareTarget = false)
    }

    private fun closeTransportLocked(preserveTrustedHardwareTarget: Boolean) {
        cancelDeferredCloseLocked()
        invalidatePendingVolumeRequestsLocked()
        volumeRampGeneration += 1
        ibassoHardwareVolume.closeControl(
            resetReaderHealth = true,
            clearTrustedTarget = !preserveTrustedHardwareTarget,
        )
        stopDsdIdleFillerLocked()
        stopPcmIdleFillerLocked()
        packetizer?.reset()
        packetizer = null
        target = null
        currentFormat = null
        currentDsdFormat = null
        currentSignature = null
        currentUsbBitResolution = null
        dsdEncoder = null
        dsdPayloadWriteObserved = false
        hardwareVolumeControl = null
        hardwareVolumeActive = false
        hardwareVolumeProtocol = null
        hardwareVolumeRaw = null
        hardwareVolumeGainQ16 = null
        hardwareVolumeReadbackVerified = false
        hardwareVolumeWriteOnly = false
        hardwareVolumeFrozen = false
        hardwareVolumeSyncPending = false
        volumeControlEnabled = false
        pcmVolumeGainQ16 = UNITY_GAIN_Q16
        lastTelemetryBufferMs = null
        minimumBufferLevelMs = null
        zeroBufferUnderruns = 0L
        lastTelemetryUnderrunCount = 0L
        lastUnderrunAtMs = null
        updateSessionDiagnostics(
            "transportInactive",
            mapOf(
                "active" to false,
                "bufferLevelMs" to 0L,
                "minimumBufferLevelMs" to null,
                "transportStrategy" to ASYNC_TRANSPORT_STRATEGY,
                "poolSlots" to ASYNC_OUTPUT_SLOTS,
                "packetsPerTransfer" to ASYNC_PACKETS_PER_TRANSFER,
                "isoPacketCount" to 0L,
                "pendingUrbs" to 0L,
                "underrunCount" to 0L,
                "lastUnderrunAtMs" to null,
            ),
        )
        if (connection != null) {
            UsbExclusiveNative.close(requestEpoch, nativeSessionId)
            connection?.close()
            connection = null
            UsbDiagnostics.i(TAG, "closed")
        }
        device = null
        requestEpoch = 0L
        nativeSessionId = 0L
        pcmSourceTimelineGeneration = 0L
    }

    private fun scheduleDeferredCloseLocked() {
        cancelDeferredCloseLocked()
        val generation = ++deferredCloseGeneration
        deferredCloseThread = Thread({
            try {
                Thread.sleep(DEFERRED_CLOSE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            synchronized(this@UsbExclusiveAudioTransport) {
                if (generation != deferredCloseGeneration || connection == null) return@synchronized
                UsbDiagnostics.i(TAG, "deferred exclusive USB close after ${DEFERRED_CLOSE_MS}ms idle window")
                close()
            }
        }, "MicaUsbDeferredClose").also { it.start() }
    }

    private fun cancelDeferredCloseLocked() {
        deferredCloseGeneration += 1
        val thread = deferredCloseThread
        deferredCloseThread = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
        }
    }


    private fun schedulePreservedPcmVerificationAfterPreRoll() {
        val pending = pendingPreservedPcmVerification ?: return
        volumeCommandExecutor.execute {
            val readbackRaw = synchronized(this) {
                if (
                    pendingPreservedPcmVerification != pending ||
                    pending.volumeGeneration != volumeSessionGeneration.get() ||
                    pending.deviceId != device?.deviceId ||
                    currentDsdFormat != null ||
                    connection == null
                ) return@execute
                ibassoHardwareVolume.verifyPreservedTarget(pending.deviceId, pending.target)
            }
            synchronized(this) {
                val activeDevice = device
                val generationMatches =
                    pendingPreservedPcmVerification == pending &&
                        pending.volumeGeneration == volumeSessionGeneration.get() &&
                        pending.deviceId == activeDevice?.deviceId &&
                        currentDsdFormat == null && connection != null
                when (preservedVolumeVerificationAction(
                    generationMatches = generationMatches,
                    isDsd = currentDsdFormat != null,
                    readbackRaw = readbackRaw,
                    trustedRaw = pending.target.baseRaw,
                )) {
                    PreservedVolumeVerificationAction.IGNORE -> Unit
                    PreservedVolumeVerificationAction.KEEP_FROZEN -> {
                        pendingPreservedPcmVerification = null
                        hardwareVolumeSyncPending = false
                        hardwareVolumeFrozen = true
                        updateSessionDiagnostics("transitionStage", "hardware-volume-kept-frozen")
                        UsbDiagnostics.w(TAG, "Preserved PCM hardware volume readback was not confirmed; kept it frozen.")
                    }
                    PreservedVolumeVerificationAction.ACCEPT -> {
                        if (activeDevice != null && ibassoHardwareVolume.acceptPreservedTarget(activeDevice, pending.target, checkNotNull(readbackRaw))) {
                            pendingPreservedPcmVerification = null
                            hardwareVolumeSyncPending = false
                            hardwareVolumeFrozen = false
                            hardwareVolumeActive = true
                            hardwareVolumeReadbackVerified = true
                            hardwareVolumeProtocol = IbassoHidVolumeProtocol.id
                            val actual = ibassoActualEventGainQ16(pending.target.baseRaw, false, 0)
                            hardwareVolumeRaw = actual.raw
                            hardwareVolumeGainQ16 = actual.gainQ16
                            updateSessionDiagnostics("transitionStage", "hardware-volume-verified")
                            applyVolumeControlLocked(forceSmoothPcmHandoff = true)
                        }
                    }
                }
            }
        }
    }    private fun prepareSilentReconfigureLocked(plan: UsbTransitionSilencePlan) {
        if (connection == null || requestEpoch <= 0L || nativeSessionId <= 0L) return
        stopDsdIdleFillerLocked()
        stopPcmIdleFillerLocked()
        val dsdFormat = currentDsdFormat
        if (dsdFormat != null) {
            val encoder = dsdEncoder
            val activePacketizer = packetizer
            if (encoder != null && activePacketizer != null) {
                val tail = encoder.drain()
                if (tail.isNotEmpty()) activePacketizer.write(tail)
                val durationMs = plan.oldFadeMs + plan.oldSilenceMs
                if (durationMs > 0) {
                    activePacketizer.write(
                        encoder.encodeSilence(usbSilenceFrames(dsdFormat.frameRate, durationMs)),
                    )
                }
                activePacketizer.flush()
            }
        } else {
            packetizer?.writeTransitionTail(plan.oldFadeMs, plan.oldSilenceMs)
        }
        completeFinalOutputTransferLocked()
        awaitOldOutputDrainLocked()
    }

    private fun completeFinalOutputTransferLocked() {
        val startedNs = System.nanoTime()
        while (true) {
            val paddingFrames = UsbExclusiveNative.reserveOutputTailPaddingFrames(
                requestEpoch,
                nativeSessionId,
            )
            when {
                paddingFrames == -1 -> {
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                    if (elapsedMs >= USB_TRANSITION_TAIL_RESERVE_TIMEOUT_MS) {
                        throw UsbExclusiveTransportException(
                            "USB output tail did not reach its final partial transfer in " +
                                "$USB_TRANSITION_TAIL_RESERVE_TIMEOUT_MS ms.",
                        )
                    }
                    Thread.sleep(5L)
                }
                paddingFrames == -2 -> throw UsbExclusiveTransportException(
                    "USB output tail reservation became stale or failed.",
                )
                paddingFrames == 0 -> return
                else -> {
                    val activePacketizer = packetizer
                        ?: throw UsbExclusiveTransportException("USB output packetizer is unavailable.")
                    val encoder = dsdEncoder
                    if (currentDsdFormat != null) {
                        if (encoder == null) {
                            throw UsbExclusiveTransportException("DSD idle encoder is unavailable.")
                        }
                        activePacketizer.write(encoder.encodeSilence(paddingFrames))
                    } else {
                        activePacketizer.writeUsbSilence(paddingFrames)
                    }
                    UsbExclusiveNative.commitOutputTailPadding(requestEpoch, nativeSessionId)?.let {
                        throw UsbExclusiveTransportException(it)
                    }
                    UsbDiagnostics.i(
                        TAG,
                        "USB transition tail completed paddingFrames=$paddingFrames " +
                            "mode=${if (currentDsdFormat == null) "PCM" else currentDsdFormat?.mode}",
                    )
                    return
                }
            }
        }
    }

    private fun awaitOldOutputDrainLocked() {
        val startedNs = System.nanoTime()
        while (true) {
            val pendingPackets = telemetry(requestEpoch, nativeSessionId).pendingIsoPackets
            if (pendingPackets <= 0L) return
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            if (elapsedMs >= USB_TRANSITION_DRAIN_TIMEOUT_MS) {
                UsbDiagnostics.w(
                    TAG,
                    "USB transition output drain timed out pendingPackets=$pendingPackets elapsedMs=$elapsedMs",
                )
                return
            }
            Thread.sleep(10L)
        }
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
            runCatching { thread.join(500L) }
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        UsbDiagnostics.i(
            TAG,
            "DSD idle filler stop/join complete elapsedMs=$elapsedMs alive=${thread.isAlive}",
        )
        dsdIdleFillerThread = null
    }

    private fun startPcmIdleFillerLocked() {
        val activePacketizer = packetizer ?: return
        val format = currentFormat ?: return
        val fillerEpoch = requestEpoch
        val fillerSessionId = nativeSessionId
        if (pcmIdleFillerThread?.isAlive == true) return
        pcmIdleFillerRunning.set(true)
        val frames = usbSilenceFrames(format.sampleRate, ASYNC_TRANSFER_QUANTUM_MS)
        UsbDiagnostics.i(TAG, "PCM idle filler started at ${format.sampleRate} frames/s")
        pcmIdleFillerThread = Thread({
            try {
                while (
                    pcmIdleFillerRunning.get() &&
                    matches(fillerEpoch, fillerSessionId) &&
                    UsbExclusiveNative.isCurrent(fillerEpoch, fillerSessionId)
                ) {
                    activePacketizer.writeUsbSilence(frames)
                }
            } catch (error: Throwable) {
                UsbDiagnostics.w(TAG, "PCM idle filler exit: ${error.message}")
            }
        }, "MicaUsbPcmIdleFill").also { it.start() }
    }

    private fun stopPcmIdleFillerLocked() {
        pcmIdleFillerRunning.set(false)
        val thread = pcmIdleFillerThread ?: return
        val startedNs = System.nanoTime()
        if (thread != Thread.currentThread()) {
            runCatching { thread.join(500L) }
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        UsbDiagnostics.i(
            TAG,
            "PCM idle filler stop/join complete elapsedMs=$elapsedMs alive=${thread.isAlive}",
        )
        pcmIdleFillerThread = null
    }

    private fun feedbackOutputPacketDivisor(target: UsbStreamingTarget): Int {
        val outputIntervalMicroframes =
            UsbStreamingTargetResolver.isoIntervalMicroframes(target.endpoint.interval)
        target.feedbackEndpoint?.let { feedbackEndpoint ->
            val feedbackIntervalMicroframes =
                UsbStreamingTargetResolver.isoIntervalMicroframes(feedbackEndpoint.interval)
            UsbDiagnostics.i(
                TAG,
                "USB feedback intervals outputMicroframes=$outputIntervalMicroframes, " +
                    "feedbackMicroframes=$feedbackIntervalMicroframes",
            )
        }
        UsbDiagnostics.i(
            TAG,
            "USB feedback scaling outputIntervalMicroframes=$outputIntervalMicroframes, " +
                "feedbackDivisor=1, feedback=${target.feedbackEndpointLabel}",
        )
        return 1
    }

    private fun transportPacketsPerTransfer(@Suppress("UNUSED_PARAMETER") packetsPerSecond: Int): Int =
        ASYNC_PACKETS_PER_TRANSFER

    private fun asyncStartupPrimeMs(@Suppress("UNUSED_PARAMETER") packetsPerSecond: Int): Int =
        ASYNC_FRAME_FIFO_MS

    private fun beginSessionDiagnostics(
        reused: Boolean,
        device: UsbDevice,
        sourceFormat: String?,
        dsdMode: String?,
        sampleRate: Int?,
        channels: Int,
        bitDepth: Int?,
    ) {
        val input = mapOf(
            "sourceFormat" to sourceFormat,
            "mode" to dsdMode,
            "sampleRate" to sampleRate,
            "channels" to channels,
            "bitDepth" to bitDepth,
            "streaming" to false,
            "transportStrategy" to ASYNC_TRANSPORT_STRATEGY,
            "poolSlots" to ASYNC_OUTPUT_SLOTS,
            "packetsPerTransfer" to ASYNC_PACKETS_PER_TRANSFER,
            "deviceId" to device.deviceId,
        )
        synchronized(diagnosticsLock) {
            if (!reused || latestSessionDiagnostics.isEmpty()) {
                sessionSequence += 1
                sessionStartedAtMs = System.nanoTime() / 1_000_000L
                sessionSubmittedBytes.set(0L)
                feedbackIgnoredCount = 0L
                feedbackActualQ16 = null
                feedbackNominalQ16 = null
                feedbackEndpointLabel = null
                lastTelemetryBufferMs = null
                minimumBufferLevelMs = null
                zeroBufferUnderruns = 0L
                lastTelemetryUnderrunCount = 0L
                lastUnderrunAtMs = null
                latestSessionDiagnostics = mapOf(
                    "id" to "usb-${System.currentTimeMillis()}-$sessionSequence",
                    "startedAtMs" to System.currentTimeMillis(),
                    "reused" to false,
                    "input" to input,
                    "outputSelections" to emptyList<Map<String, Any?>>(),
                )
            } else {
                latestSessionDiagnostics = latestSessionDiagnostics + mapOf(
                    "reused" to true,
                    "input" to input,
                    "updatedAtMs" to System.currentTimeMillis(),
                )
            }
        }
    }

    private fun addOutputSelectionDiagnostics(selection: Map<String, Any?>) {
        synchronized(diagnosticsLock) {
            if (latestSessionDiagnostics.isEmpty()) return
            val attempts = (latestSessionDiagnostics["outputSelections"] as? List<*>)
                ?.filterIsInstance<Map<String, Any?>>()
                ?.toMutableList()
                ?: mutableListOf()
            attempts += selection
            latestSessionDiagnostics = latestSessionDiagnostics + mapOf(
                "outputSelections" to attempts,
                "updatedAtMs" to System.currentTimeMillis(),
            )
        }
    }

    private fun recordOutputSelection(
        target: UsbStreamingTarget,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int?,
        requireRawData: Boolean,
    ) {
        val required = UsbStreamingTargetResolver.requiredIsoPacketBytes(
            sampleRate,
            target.packetsPerSecond,
            channels,
            target.usbBytesPerSample,
        )
        addOutputSelectionDiagnostics(
            mapOf(
                "sampleRate" to sampleRate,
                "channels" to channels,
                "bitDepth" to bitDepth,
                "requireRawData" to requireRawData,
                "selected" to mapOf(
                    "interface" to target.usbInterface.id,
                    "alt" to target.alternateSetting,
                    "maxPacketSize" to target.endpoint.maxPacketSize,
                    "packetsPerSecond" to target.packetsPerSecond,
                    "usbBytes" to target.usbBytesPerSample,
                    "usbBitDepth" to target.usbBitResolution,
                    "raw" to target.isRawData,
                    "requiredPacketBytes" to required,
                    "fits" to (target.endpoint.maxPacketSize >= required),
                    "feedback" to target.feedbackEndpointLabel,
                ),
            ),
        )
    }

    private fun updateSessionDiagnostics(section: String, value: Any?) {
        synchronized(diagnosticsLock) {
            if (latestSessionDiagnostics.isEmpty()) return
            latestSessionDiagnostics = latestSessionDiagnostics + mapOf(
                section to value,
                "updatedAtMs" to System.currentTimeMillis(),
            )
        }
    }

    fun sessionDiagnosticsSnapshot(): Map<String, Any?> = synchronized(diagnosticsLock) {
        latestSessionDiagnostics
    }

    private fun recordFeedbackDiagnostics(
        target: UsbStreamingTarget,
        actualQ16: Int,
        nominalQ16: Int,
        ignored: Boolean,
    ) {
        if (ignored) feedbackIgnoredCount += 1
        feedbackActualQ16 = actualQ16
        feedbackNominalQ16 = nominalQ16
        feedbackEndpointLabel = target.feedbackEndpointLabel
        val actualFrames = actualQ16.toDouble() / 65536.0
        val nominalFrames = nominalQ16.toDouble() / 65536.0
        updateSessionDiagnostics(
            "feedback",
            mapOf(
                "endpoint" to target.feedbackEndpointLabel,
                "actualFrames" to actualFrames,
                "nominalFrames" to nominalFrames,
                "deviationRatio" to if (nominalFrames > 0) actualFrames / nominalFrames else null,
                "ignoredCount" to feedbackIgnoredCount,
            ),
        )
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
        fun collectHardwareVolumeDiagnostics(
            context: Context,
            usbManager: UsbManager,
            device: UsbDevice,
        ): Map<String, Any?> {
            if (!usbManager.hasPermission(device)) {
                return mapOf("available" to false, "error" to "USB permission not granted.")
            }
            val connection = runCatching { usbManager.openDevice(device) }.getOrNull()
                ?: return mapOf("available" to false, "error" to "openDevice failed.")
            return try {
                UsbStandardHardwareVolumeController(context.applicationContext).collectDiagnostics(
                    connection,
                    device,
                    connection.rawDescriptors,
                )
            } finally {
                runCatching { connection.close() }
            }
        }
        const val STALE_SESSION_ERROR = "USB exclusive epoch/session is stale."

        private const val STALE_SESSION = STALE_SESSION_ERROR
        private const val UNITY_GAIN_Q16 = 65_536
        private const val ASYNC_TRANSPORT_STRATEGY = "libusb-frame-fifo-200ms-15x16-v4"
        private const val ASYNC_OUTPUT_SLOTS = 16
        private const val ASYNC_ACTIVE_OUTPUT_TRANSFERS = 15
        private const val ASYNC_PACKETS_PER_TRANSFER = 16
        private const val ASYNC_TRANSFER_QUANTUM_MS = 2
        private const val ASYNC_FRAME_FIFO_MS = 200
        private const val DEFERRED_CLOSE_MS = 4_000L
        const val TAG = "UsbExclusiveAudioTransport"
    }
}
