package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.mica.music.media.usb.AndroidUsbAudioControlIo
import com.mica.music.media.usb.AndroidUsbAudioDiscovery
import com.mica.music.media.usb.AndroidUsbRuntimeFactsProvider
import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.StandardUacDescriptorParser
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReadResult
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReader
import com.mica.music.media.usb.UsbAudioDescriptorParseResult
import com.mica.music.media.usb.UsbAudioDeviceIdentity
import com.mica.music.media.usb.UsbAudioEndpointShape
import com.mica.music.media.usb.UsbAudioRuntimeHandle
import com.mica.music.media.usb.UsbAudioStreamingProfile
import com.mica.music.media.usb.UsbClockPlan
import com.mica.music.media.usb.UsbGenericPcmSelection
import com.mica.music.media.usb.UsbGenericPcmSelectionResult
import com.mica.music.media.usb.UsbOutputCleanupLease
import com.mica.music.media.usb.UsbOutputLifecycleRuntime
import com.mica.music.media.usb.UsbOutputPhase
import com.mica.music.media.usb.UsbOutputRequest
import com.mica.music.media.usb.UsbOutputRequestLease
import com.mica.music.media.usb.UsbOutputRequestToken
import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbOutputSession
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbPermissionState
import com.mica.music.media.usb.UsbPotentialAudioDevice
import com.mica.music.media.usb.UsbPotentialAudioDiscoveryResult
import com.mica.music.media.usb.UsbRuntimeFactsResult
import com.mica.music.media.usb.UsbRuntimeHealth
import com.mica.music.media.usb.UsbRuntimeStreamingProfileValidator
import com.mica.music.media.usb.UsbStableIdentityPolicy
import com.mica.music.media.usb.UsbStreamingProfileValidation
import com.mica.music.media.usb.UsbTransportConfig
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.round

internal fun shouldConsumeUsbSource(
    requestedPlaying: Boolean,
    volume: Float,
    bufferedFrames: Long,
    minimumBufferedFrames: Long,
): Boolean = requestedPlaying && volume == 1f && bufferedFrames >= minimumBufferedFrames

internal fun usbStartPrefillFrames(sampleRate: Int): Long =
    ((sampleRate.toLong() * USB_START_PREFILL_MILLIS) + 999L) / 1_000L

private const val USB_START_PREFILL_MILLIS = 20L

/**
 * THROWAWAY PROTOTYPE: Media3 1.9 AudioOutputProvider backed by the proven SK02 USBFS queue.
 *
 * This is deliberately narrower than a USB audio engine: one VID/PID, stereo PCM16 plus float
 * buffers that are provably exact signed PCM32, and no AudioTrack fallback while selected.
 */
@UnstableApi
/** SK02-only production provider. Availability is build-time; user intent remains default-off. */
class UsbSk02AudioOutputProvider(context: Context) : AudioOutputProvider {
    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.channelCount != CHANNEL_COUNT ||
            format.sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE ||
            formatConfig.enableOffload ||
            formatConfig.enableTunneling
        ) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        val support = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            // Advertise direct acceptance so FFmpeg does not pre-emptively decode high-resolution
            // sources to PCM16. Exact S32/2^31 floats are losslessly packed for SK02 alt 3.
            C.ENCODING_PCM_FLOAT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            // The scoped FFmpeg prototype asks for S32 only for 24/32-bit sources. Sending the
            // decoder's S32 samples through SK02 alt 3 avoids any float-to-integer quantization.
            C.ENCODING_PCM_32BIT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            else -> AudioOutputProvider.FORMAT_UNSUPPORTED
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(support)
            .build()
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        val support = getFormatSupport(formatConfig)
        if (support.supportLevel == AudioOutputProvider.FORMAT_UNSUPPORTED) {
            throw AudioOutputProvider.ConfigurationException(
                "SK02 prototype does not support ${formatConfig.format}",
            )
        }
        val format = formatConfig.format
        val inputBytesPerFrame = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 4
            C.ENCODING_PCM_FLOAT -> 8
            C.ENCODING_PCM_32BIT -> 8
            else -> throw AudioOutputProvider.ConfigurationException("Unsupported PCM encoding")
        }
        val preferred = formatConfig.preferredBufferSize.takeIf { it > 0 }
            ?: format.sampleRate * inputBytesPerFrame
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(preferred)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
            .setIsOffload(false)
            .setIsTunneling(false)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        return try {
            UsbOutputRuntime.installGenerationPublisher(UsbSk02NativePrototype::publishGeneration)
            UsbSk02Media3SessionOwner.open(appContext, config)
        } catch (error: Exception) {
            throw AudioOutputProvider.InitializationException(error)
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners -= listener
    }

    override fun release() = Unit

    private companion object {
        const val CHANNEL_COUNT = 2
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 384_000
    }
}

@UnstableApi
private object UsbSk02Media3SessionOwner {
    private data class SelectedTarget(
        val candidate: UsbPotentialAudioDevice,
        val identity: UsbAudioDeviceIdentity,
    )

    fun open(
        context: Context,
        config: AudioOutputProvider.OutputConfig,
    ): UsbSk02AudioOutput {
        val sourceFormat = config.toUsbPcmFormat()
        val manager = context.getSystemService(UsbManager::class.java)
        val target = selectTarget(manager)
        val candidate = target.candidate
        val identity = target.identity
        return UsbOutputRuntime.owner.replace(
            request = UsbOutputRequest(
                device = identity,
                sourceFormat = sourceFormat,
            ),
        ) { lease ->
            UsbSk02AudioOutput.open(
                context = context,
                config = config,
                sourceFormat = sourceFormat,
                candidate = candidate,
                expectedIdentity = identity,
                lease = lease,
            )
        }
    }

    private fun selectTarget(manager: UsbManager): SelectedTarget {
        val existingFacts = UsbOutputRuntime.owner.facts
        val reconnectExpected = if (
            UsbOutputLifecycleRuntime.hasInterruptedUsbIntent() &&
            existingFacts.phase == UsbOutputPhase.REQUESTED &&
            existingFacts.permission == UsbPermissionState.GRANTED
        ) {
            existingFacts.request?.device?.let { expected ->
                existingFacts.runtimeHandle?.let { runtimeHandle -> expected to runtimeHandle }
            }
        } else {
            null
        }

        if (reconnectExpected != null) {
            val (expectedIdentity, runtimeHandle) = reconnectExpected
            val candidate = UsbPotentialAudioDevice(
                runtimeHandle = runtimeHandle,
                vendorId = expectedIdentity.vendorId,
                productId = expectedIdentity.productId,
                permission = UsbPermissionState.GRANTED,
            )
            val observedIdentity = preflightIdentity(manager, candidate)
            val conflicts = UsbStableIdentityPolicy.conflicts(expectedIdentity, observedIdentity)
            check(conflicts.isEmpty()) {
                "Stable reconnect identity changed before production open: $conflicts"
            }
            DiagnosticLog.event(
                "UsbExclusivePrototype",
                "target selection=stable-reconnect runtimeDeviceId=${runtimeHandle.runtimeDeviceId}",
            )
            return SelectedTarget(candidate, observedIdentity)
        }

        val candidate = when (val discovery = AndroidUsbAudioDiscovery.discover(manager)) {
            UsbPotentialAudioDiscoveryResult.NoPotentialDevice ->
                error("No potential USB Audio device is attached")
            is UsbPotentialAudioDiscoveryResult.PermissionNeeded ->
                error("USB Audio permission is required for ${discovery.candidates.size} potential device(s)")
            is UsbPotentialAudioDiscoveryResult.Ambiguous ->
                error("Multiple potential USB Audio devices are attached; explicit DAC selection is required")
            is UsbPotentialAudioDiscoveryResult.OnePermittedCandidate -> discovery.candidate
        }
        return SelectedTarget(candidate, preflightIdentity(manager, candidate))
    }

    private fun preflightIdentity(
        manager: UsbManager,
        candidate: UsbPotentialAudioDevice,
    ): UsbAudioDeviceIdentity {
        val device = AndroidUsbAudioDiscovery.resolve(manager, candidate)
            ?: error("Discovered USB Audio device disappeared before identity preflight")
        check(manager.hasPermission(device)) { "USB Audio permission disappeared before identity preflight" }
        val connection = manager.openDevice(device)
            ?: error("Unable to open discovered USB Audio device for identity preflight")
        return try {
            when (val result = AndroidUsbRuntimeFactsProvider.acquire(device, connection)) {
                is UsbRuntimeFactsResult.Ready -> result.facts.identity
                is UsbRuntimeFactsResult.Rejected -> error(
                    "USB runtime facts rejected during identity preflight: " +
                        "${result.rejection.code}: ${result.rejection.detail}",
                )
            }
        } finally {
            connection.close()
        }
    }

    fun restart(output: UsbSk02AudioOutput) = UsbOutputRuntime.owner.restart(output)

    fun release(output: UsbSk02AudioOutput) {
        UsbOutputRuntime.owner.release(output)
    }

    private fun AudioOutputProvider.OutputConfig.toUsbPcmFormat(): UsbPcmFormat =
        UsbPcmFormat(
            sampleRateHz = sampleRate,
            channelCount = 2,
            encoding = when (encoding) {
                C.ENCODING_PCM_16BIT -> UsbPcmEncoding.PCM_16
                C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> UsbPcmEncoding.PCM_32
                else -> error("Unsupported SK02 input encoding $encoding")
            },
        )
}

@UnstableApi
private class UsbSk02AudioOutput private constructor(
    private val connection: UsbDeviceConnection,
    private val audioControl: UsbInterface,
    private val streamingAlt0: UsbInterface,
    private val streamingTarget: UsbInterface,
    private val sampleRate: Int,
    private val inputEncoding: Int,
    private val transportConfig: UsbTransportConfig,
    private val clockSourceId: Int,
    private val originalClockHz: Int?,
    private val runtimeHandle: UsbAudioRuntimeHandle,
    private val negotiatedFormat: UsbPcmFormat,
    initialLease: UsbOutputRequestLease,
) : AudioOutput, UsbOutputSession {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var generation = initialLease.token
    private var nativeHandle = 0L
    private var requestedPlaying = false
    private var volume = 1f
    private var released = false
    private var positionNotified = false
    private var lastUnderrunBytes = 0L
    private var resumeSequence = 0L
    private var resumeRequestedAtNs = 0L
    private var firstWriteLoggedForResume = true
    private var lastAppliedConsuming = false
    private var nextHealthSampleAtMs = 0L
    private var floatScratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    override val activeFacts: PlaybackOutputFacts
        get() = PlaybackOutputFacts(
            runtimeHandle = runtimeHandle,
            negotiatedFormat = negotiatedFormat,
            attached = true,
            permission = UsbPermissionState.GRANTED,
            claimed = true,
            exclusive = true,
            signalExact = true,
        )

    init {
        nativeHandle = initialLease.io { createNative(generation) }
    }

    override fun play() {
        UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            requestedPlaying = true
            resumeSequence++
            resumeRequestedAtNs = SystemClock.elapsedRealtimeNanos()
            firstWriteLoggedForResume = false
            DiagnosticLog.event(
                "UsbResumeTiming",
                "resume=$resumeSequence event=playRequested bufferedFrames=" +
                    lease.io { UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) },
            )
            applyPlayingState(lease)
            if (!positionNotified) {
                lease.ensureCurrent()
                positionNotified = true
                listeners.forEach {
                    lease.ensureCurrent()
                    it.onPositionAdvancing(System.currentTimeMillis())
                }
            }
        }
    }

    override fun pause() {
        UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            requestedPlaying = false
            DiagnosticLog.event(
                "UsbResumeTiming",
                "resume=$resumeSequence event=pause bufferedFrames=" +
                    lease.io { UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) },
            )
            applyPlayingState(lease)
        }
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean = UsbOutputRuntime.owner.withActiveSession(this) { lease ->
        checkNativeError(lease)
        if (!buffer.hasRemaining()) return@withActiveSession true
        val writtenInputBytes = when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> writePcm16(buffer, lease)
            C.ENCODING_PCM_FLOAT -> writeExactFloatAsPcm32(buffer, lease)
            C.ENCODING_PCM_32BIT -> writePcm32(buffer, lease)
            else -> throw AudioOutput.WriteException(ERROR_UNSUPPORTED_ENCODING, false)
        }
        lease.ensureCurrent()
        buffer.position(buffer.position() + writtenInputBytes)
        if (writtenInputBytes > 0 && !firstWriteLoggedForResume) {
            firstWriteLoggedForResume = true
            DiagnosticLog.event(
                "UsbResumeTiming",
                "resume=$resumeSequence event=firstWrite elapsedUs=${resumeElapsedUs()} " +
                    "acceptedInputBytes=$writtenInputBytes bufferedFrames=" +
                    lease.io { UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) },
            )
        }
        if (writtenInputBytes > 0) applyPlayingState(lease)
        reportUnderrunIfChanged(lease)
        checkNativeError(lease)
        maybePublishRuntimeHealth(lease)
        !buffer.hasRemaining()
    } ?: throw AudioOutput.WriteException(ERROR_RELEASED, true)

    override fun flush() {
        UsbSk02Media3SessionOwner.restart(this)
        UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            positionNotified = false
            lastUnderrunBytes = 0
            applyPlayingState(lease)
        }
    }

    override fun stop() = Unit

    override fun release() {
        UsbSk02Media3SessionOwner.release(this)
    }

    override fun setVolume(volume: Float) {
        UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            this.volume = volume
            // Never apply digital gain. Non-unity volume stops source consumption.
            applyPlayingState(lease)
            if (volume != 1f) {
                DiagnosticLog.event("UsbExclusivePrototype", "nonUnityVolume=$volume action=mute")
            }
        }
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET
    override fun getSampleRate(): Int = sampleRate
    override fun getBufferSizeInFrames(): Long = sampleRate.toLong() * 2L
    override fun getPositionUs(): Long = UsbOutputRuntime.owner.withActiveSession(this) { lease ->
        lease.io { UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle) } *
            C.MICROS_PER_SECOND / sampleRate
    } ?: 0L

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled(): Boolean = UsbOutputRuntime.owner.withActiveSession(this) { lease ->
        nativeHandle == 0L ||
            lease.io { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) } != 0
    } ?: true

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    override fun restart(lease: UsbOutputRequestLease) {
        if (nativeHandle != 0L) {
            lease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) }
            nativeHandle = 0L
        }
        generation = lease.token
        nativeHandle = lease.io { createNative(lease.token) }
        nextHealthSampleAtMs = 0L
    }

    override fun release(lease: UsbOutputCleanupLease, reason: String) {
        if (released) return
        released = true
        requestedPlaying = false
        if (nativeHandle != 0L) {
            lease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) }
            nativeHandle = 0L
        }
        val alt0Restored = runCatching {
            lease.io { connection.setInterface(streamingAlt0) }
        }.getOrDefault(false)
        val clockRestoreBytes = originalClockHz?.let { original ->
            lease.io {
                setClockFrequency(connection, audioControl.id, clockSourceId, original)
            }
        }
        val restoredClockHz = originalClockHz?.let {
            lease.io { readClockCurrentHz(connection, audioControl.id, clockSourceId) }
        }
        val streamingReleased = runCatching {
            lease.io { connection.releaseInterface(streamingAlt0) }
        }
            .getOrDefault(false)
        val controlReleased = runCatching {
            lease.io { connection.releaseInterface(audioControl) }
        }
            .getOrDefault(false)
        val reconnectErrno = lease.io {
            UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor)
        }
        lease.io { connection.close() }
        DiagnosticLog.event(
            "UsbExclusivePrototype",
            "closed reason=$reason alt0=$alt0Restored clockBytes=$clockRestoreBytes " +
                "clockHz=$restoredClockHz streamingReleased=$streamingReleased " +
                "controlReleased=$controlReleased reconnectErrno=$reconnectErrno",
        )
        lease.ensureSerialized()
        listeners.forEach(AudioOutput.Listener::onReleased)
        listeners.clear()
    }

    private fun createNative(token: UsbOutputRequestToken): Long {
        val handle = UsbSk02NativePrototype.createMedia3Stream(
            connection.fileDescriptor,
            transportConfig,
            token.value,
        )
        check(handle != 0L) { "Unable to create native SK02 Media3 stream" }
        return handle
    }

    private fun writePcm16(buffer: ByteBuffer, lease: UsbOutputRequestLease): Int {
        val length = buffer.remaining() - buffer.remaining() % PCM16_INPUT_FRAME_BYTES
        if (length == 0) return 0
        return lease.io {
            UsbSk02NativePrototype.writeMedia3Stream(
                nativeHandle,
                buffer,
                buffer.position(),
                length,
            )
        }
    }

    private fun writePcm32(buffer: ByteBuffer, lease: UsbOutputRequestLease): Int {
        val length = buffer.remaining() - buffer.remaining() % PCM32_INPUT_FRAME_BYTES
        if (length == 0) return 0
        return lease.io {
            UsbSk02NativePrototype.writeMedia3Stream(
                nativeHandle,
                buffer,
                buffer.position(),
                length,
            )
        }
    }

    private fun writeExactFloatAsPcm32(
        buffer: ByteBuffer,
        lease: UsbOutputRequestLease,
    ): Int {
        val availableFrames = buffer.remaining() / FLOAT_INPUT_FRAME_BYTES
        if (availableFrames == 0) return 0
        val frames = minOf(availableFrames, FLOAT_CONVERSION_FRAMES)
        val outputBytes = frames * PCM32_OUTPUT_FRAME_BYTES
        if (floatScratch.capacity() < outputBytes) {
            floatScratch = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        } else {
            floatScratch.clear()
        }
        try {
            ExactPcm32Packing.pack(buffer, frames, floatScratch)
        } catch (error: IllegalArgumentException) {
            DiagnosticLog.event(
                "UsbExclusivePrototype",
                "exactPcm32Rejected ${error.message ?: "unknown"}",
            )
            throw AudioOutput.WriteException(ERROR_NON_EXACT_PCM24, false)
        }
        floatScratch.flip()
        val acceptedOutput = lease.io {
            UsbSk02NativePrototype.writeMedia3Stream(
                nativeHandle,
                floatScratch,
                0,
                outputBytes,
            )
        }
        val acceptedFrames = acceptedOutput / PCM32_OUTPUT_FRAME_BYTES
        return acceptedFrames * FLOAT_INPUT_FRAME_BYTES
    }

    private fun applyPlayingState(lease: UsbOutputRequestLease) {
        if (nativeHandle != 0L) {
            val bufferedFrames = lease.io {
                UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
            }
            val consuming = shouldConsumeUsbSource(
                requestedPlaying = requestedPlaying,
                volume = volume,
                bufferedFrames = bufferedFrames,
                minimumBufferedFrames = usbStartPrefillFrames(sampleRate),
            )
            if (consuming != lastAppliedConsuming) {
                DiagnosticLog.event(
                    "UsbResumeTiming",
                    "resume=$resumeSequence event=consumeState consuming=$consuming " +
                        "elapsedUs=${resumeElapsedUs()} bufferedFrames=$bufferedFrames " +
                        "minimumFrames=${usbStartPrefillFrames(sampleRate)}",
                )
                lastAppliedConsuming = consuming
            }
            lease.io {
                UsbSk02NativePrototype.setMedia3StreamPlaying(
                    nativeHandle,
                    consuming,
                )
            }
        }
    }

    private fun resumeElapsedUs(): Long = if (resumeRequestedAtNs == 0L) {
        -1L
    } else {
        (SystemClock.elapsedRealtimeNanos() - resumeRequestedAtNs) / 1_000L
    }

    private fun reportUnderrunIfChanged(lease: UsbOutputRequestLease) {
        val current = lease.io { UsbSk02NativePrototype.getMedia3UnderrunBytes(nativeHandle) }
        if (current > lastUnderrunBytes) {
            lease.ensureCurrent()
            lastUnderrunBytes = current
            listeners.forEach {
                lease.ensureCurrent()
                it.onUnderrun()
            }
            DiagnosticLog.event(
                "UsbExclusivePrototype",
                "underrunBytes=$current resume=$resumeSequence elapsedUs=${resumeElapsedUs()} " +
                    "bufferedFrames=${lease.io {
                        UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
                    }}",
            )
        }
    }

    private fun checkNativeError(lease: UsbOutputRequestLease) {
        val error = if (nativeHandle == 0L) ERROR_RELEASED else {
            lease.io { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) }
        }
        if (error != 0) throw AudioOutput.WriteException(error, true)
    }

    private fun maybePublishRuntimeHealth(lease: UsbOutputRequestLease) {
        val sampledAtMs = SystemClock.elapsedRealtime()
        if (sampledAtMs < nextHealthSampleAtMs || nativeHandle == 0L) return
        val completedFrames = lease.io {
            UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle)
        }
        val bufferedFrames = lease.io {
            UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
        }
        val bufferCapacityFrames = lease.io {
            UsbSk02NativePrototype.getMedia3BufferCapacityFrames(nativeHandle)
        }
        val minimumBufferedFrames = lease.io {
            UsbSk02NativePrototype.getMedia3MinimumBufferedFrames(nativeHandle)
        }
        val acceptedPcmBytes = lease.io {
            UsbSk02NativePrototype.getMedia3AcceptedPcmBytes(nativeHandle)
        }
        val previousSuccessfulWriteGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3PreviousSuccessfulWriteGapUs(nativeHandle)
        }
        val maximumSuccessfulWriteGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumSuccessfulWriteGapUs(nativeHandle)
        }
        val previousDataCompletionGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3PreviousDataCompletionGapUs(nativeHandle)
        }
        val maximumDataCompletionGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumDataCompletionGapUs(nativeHandle)
        }
        val previousFeedbackCompletionGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3PreviousFeedbackCompletionGapUs(nativeHandle)
        }
        val maximumFeedbackCompletionGapUs = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumFeedbackCompletionGapUs(nativeHandle)
        }
        val totalPollTimeouts = lease.io {
            UsbSk02NativePrototype.getMedia3TotalPollTimeouts(nativeHandle)
        }
        val maximumConsecutivePollTimeouts = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumConsecutivePollTimeouts(nativeHandle)
        }
        val invalidFeedbackPacketCount = lease.io {
            UsbSk02NativePrototype.getMedia3InvalidFeedbackPacketCount(nativeHandle)
        }
        val dataPacketErrorCount = lease.io {
            UsbSk02NativePrototype.getMedia3DataPacketErrorCount(nativeHandle)
        }
        val currentFeedbackQ16 = lease.io {
            UsbSk02NativePrototype.getMedia3CurrentFeedbackQ16(nativeHandle)
        }
        val minimumFeedbackQ16 = lease.io {
            UsbSk02NativePrototype.getMedia3MinimumFeedbackQ16(nativeHandle)
        }
        val maximumFeedbackQ16 = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumFeedbackQ16(nativeHandle)
        }
        val maximumFeedbackStepQ16 = lease.io {
            UsbSk02NativePrototype.getMedia3MaximumFeedbackStepQ16(nativeHandle)
        }
        val trustedFeedbackQ16 = lease.io {
            UsbSk02NativePrototype.getMedia3TrustedFeedbackQ16(nativeHandle)
        }
        val feedbackFilterInterventionCount = lease.io {
            UsbSk02NativePrototype.getMedia3FeedbackFilterInterventionCount(nativeHandle)
        }
        val diagnosticMetrics = lease.io {
            UsbSk02NativePrototype.getMedia3DiagnosticMetrics(nativeHandle)
        }
        fun diagnostic(index: Int): Long = diagnosticMetrics.getOrElse(index) { 0L }
        val scheduledPacketCount = diagnostic(0)
        val scheduledFrameCount = diagnostic(1)
        val outOfNominalRequestCount = diagnostic(2)
        val maximumConsecutiveOutOfNominalRequests = diagnostic(3)
        val minimumFramesPerPacket = diagnostic(4)
        val maximumFramesPerPacket = diagnostic(5)
        val maximumPacketFrameStep = diagnostic(6)
        val scheduleDeviationFrames = diagnostic(7)
        val observedPcmFrames = diagnostic(8)
        val zeroPcmFrameCount = diagnostic(9)
        val maximumConsecutiveZeroPcmFrames = diagnostic(10)
        val repeatedPcmFrameCount = diagnostic(11)
        val maximumConsecutiveRepeatedPcmFrames = diagnostic(12)
        val duplicatePcmRequestCount = diagnostic(13)
        val maximumConsecutiveDuplicatePcmRequests = diagnostic(14)
        val maximumAdjacentSampleDelta = diagnostic(15)
        val maximumRequestBoundarySampleDelta = diagnostic(16)
        val underrunBytes = lease.io {
            UsbSk02NativePrototype.getMedia3UnderrunBytes(nativeHandle)
        }
        val errorCode = lease.io {
            UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
        }
        lease.ensureCurrent()
        val published = UsbOutputRuntime.owner.publishRuntimeHealth(
            session = this,
            lease = lease,
            health = UsbRuntimeHealth(
                sampledAtElapsedRealtimeMs = sampledAtMs,
                completedFrames = completedFrames,
                bufferedFrames = bufferedFrames,
                underrunBytes = underrunBytes,
                transportErrorCode = errorCode,
                playbackRequested = requestedPlaying,
                sourceConsumptionActive = lastAppliedConsuming,
                bufferCapacityFrames = bufferCapacityFrames,
                minimumBufferedFrames = minimumBufferedFrames,
                acceptedPcmBytes = acceptedPcmBytes,
                previousSuccessfulWriteGapUs = previousSuccessfulWriteGapUs,
                maximumSuccessfulWriteGapUs = maximumSuccessfulWriteGapUs,
                previousDataCompletionGapUs = previousDataCompletionGapUs,
                maximumDataCompletionGapUs = maximumDataCompletionGapUs,
                previousFeedbackCompletionGapUs = previousFeedbackCompletionGapUs,
                maximumFeedbackCompletionGapUs = maximumFeedbackCompletionGapUs,
                totalPollTimeouts = totalPollTimeouts,
                maximumConsecutivePollTimeouts = maximumConsecutivePollTimeouts,
                invalidFeedbackPacketCount = invalidFeedbackPacketCount,
                dataPacketErrorCount = dataPacketErrorCount,
                currentFeedbackQ16 = currentFeedbackQ16,
                minimumFeedbackQ16 = minimumFeedbackQ16,
                maximumFeedbackQ16 = maximumFeedbackQ16,
                maximumFeedbackStepQ16 = maximumFeedbackStepQ16,
                trustedFeedbackQ16 = trustedFeedbackQ16,
                feedbackFilterInterventionCount = feedbackFilterInterventionCount,
                scheduledPacketCount = scheduledPacketCount,
                scheduledFrameCount = scheduledFrameCount,
                outOfNominalRequestCount = outOfNominalRequestCount,
                maximumConsecutiveOutOfNominalRequests =
                    maximumConsecutiveOutOfNominalRequests,
                minimumFramesPerPacket = minimumFramesPerPacket,
                maximumFramesPerPacket = maximumFramesPerPacket,
                maximumPacketFrameStep = maximumPacketFrameStep,
                scheduleDeviationFrames = scheduleDeviationFrames,
                observedPcmFrames = observedPcmFrames,
                zeroPcmFrameCount = zeroPcmFrameCount,
                maximumConsecutiveZeroPcmFrames = maximumConsecutiveZeroPcmFrames,
                repeatedPcmFrameCount = repeatedPcmFrameCount,
                maximumConsecutiveRepeatedPcmFrames = maximumConsecutiveRepeatedPcmFrames,
                duplicatePcmRequestCount = duplicatePcmRequestCount,
                maximumConsecutiveDuplicatePcmRequests =
                    maximumConsecutiveDuplicatePcmRequests,
                maximumAdjacentSampleDelta = maximumAdjacentSampleDelta,
                maximumRequestBoundarySampleDelta = maximumRequestBoundarySampleDelta,
            ),
        )
        if (published) {
            DiagnosticLog.event(
                "UsbPcmQueueHealth",
                "generation=${lease.token.value} sampledAtMs=$sampledAtMs " +
                    "levelFrames=$bufferedFrames capacityFrames=$bufferCapacityFrames " +
                    "minimumFrames=$minimumBufferedFrames acceptedPcmBytes=$acceptedPcmBytes " +
                    "completedFrames=$completedFrames " +
                    "previousWriteGapUs=$previousSuccessfulWriteGapUs " +
                    "maximumWriteGapUs=$maximumSuccessfulWriteGapUs " +
                    "previousDataCompletionGapUs=$previousDataCompletionGapUs " +
                    "maximumDataCompletionGapUs=$maximumDataCompletionGapUs " +
                    "previousFeedbackCompletionGapUs=$previousFeedbackCompletionGapUs " +
                    "maximumFeedbackCompletionGapUs=$maximumFeedbackCompletionGapUs " +
                    "totalPollTimeouts=$totalPollTimeouts " +
                    "maximumConsecutivePollTimeouts=$maximumConsecutivePollTimeouts " +
                    "invalidFeedbackPacketCount=$invalidFeedbackPacketCount " +
                    "dataPacketErrorCount=$dataPacketErrorCount " +
                    "feedbackQ16=$currentFeedbackQ16 " +
                    "minimumFeedbackQ16=$minimumFeedbackQ16 " +
                    "maximumFeedbackQ16=$maximumFeedbackQ16 " +
                    "maximumFeedbackStepQ16=$maximumFeedbackStepQ16 " +
                    "trustedFeedbackQ16=$trustedFeedbackQ16 " +
                    "feedbackFilterInterventionCount=$feedbackFilterInterventionCount " +
                    "scheduledPackets=$scheduledPacketCount scheduledFrames=$scheduledFrameCount " +
                    "outOfNominalRequests=$outOfNominalRequestCount " +
                    "maxConsecutiveOutOfNominalRequests=" +
                    "$maximumConsecutiveOutOfNominalRequests " +
                    "packetFramesMin=$minimumFramesPerPacket " +
                    "packetFramesMax=$maximumFramesPerPacket " +
                    "packetFrameMaxStep=$maximumPacketFrameStep " +
                    "scheduleDeviationFrames=$scheduleDeviationFrames " +
                    "observedPcmFrames=$observedPcmFrames zeroPcmFrames=$zeroPcmFrameCount " +
                    "maxZeroPcmRun=$maximumConsecutiveZeroPcmFrames " +
                    "repeatedPcmFrames=$repeatedPcmFrameCount " +
                    "maxRepeatedPcmRun=$maximumConsecutiveRepeatedPcmFrames " +
                    "duplicatePcmRequests=$duplicatePcmRequestCount " +
                    "maxDuplicatePcmRequests=$maximumConsecutiveDuplicatePcmRequests " +
                    "maxAdjacentSampleDelta=$maximumAdjacentSampleDelta " +
                    "maxRequestBoundarySampleDelta=$maximumRequestBoundarySampleDelta " +
                    "underrunBytes=$underrunBytes transportErrorCode=$errorCode " +
                    "playbackRequested=$requestedPlaying consuming=$lastAppliedConsuming",
            )
            nextHealthSampleAtMs = sampledAtMs + HEALTH_SAMPLE_INTERVAL_MS
        }
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 1_000
        private const val USB_CLASS_INTERFACE_IN = 0xa1
        private const val USB_CLASS_INTERFACE_OUT = 0x21
        private const val UAC2_REQUEST_CUR = 0x01
        private const val UAC2_SAMPLING_FREQUENCY_CONTROL = 0x0100
        private const val CHANNEL_COUNT = 2
        private const val PCM16_INPUT_FRAME_BYTES = 4
        private const val PCM32_INPUT_FRAME_BYTES = 8
        private const val FLOAT_INPUT_FRAME_BYTES = 8
        private const val PCM32_OUTPUT_FRAME_BYTES = 8
        private const val FLOAT_CONVERSION_FRAMES = 4_096
        private const val ERROR_RELEASED = 10_001
        private const val ERROR_UNSUPPORTED_ENCODING = 10_002
        private const val ERROR_NON_EXACT_PCM24 = 10_003
        private const val HEALTH_SAMPLE_INTERVAL_MS = 1_000L

        fun open(
            context: Context,
            config: AudioOutputProvider.OutputConfig,
            sourceFormat: UsbPcmFormat,
            candidate: UsbPotentialAudioDevice,
            expectedIdentity: UsbAudioDeviceIdentity,
            lease: UsbOutputRequestLease,
        ): UsbSk02AudioOutput {
            val manager = context.getSystemService(UsbManager::class.java)
            val target = AndroidUsbAudioDiscovery.resolve(manager, candidate)
                ?: error("Discovered USB Audio device disappeared before production open")
            lease.ensureCurrent()
            check(manager.hasPermission(target)) { "USB Audio permission disappeared before production open" }
            val connection = lease.io { manager.openDevice(target) }
                ?: error("Unable to open discovered USB Audio device")
            val interfaces = (0 until target.interfaceCount).map(target::getInterface)
            var audioControl: UsbInterface? = null
            var streamingAlt0: UsbInterface? = null
            var controlClaimed = false
            var streamingClaimed = false
            var altSelected = false
            var selectedClockSourceId: Int? = null
            var originalClockHz: Int? = null
            try {
                val runtime = when (val result = AndroidUsbRuntimeFactsProvider.acquire(target, connection)) {
                    is UsbRuntimeFactsResult.Ready -> result.facts
                    is UsbRuntimeFactsResult.Rejected -> error(
                        "USB runtime facts rejected: ${result.rejection.code}: ${result.rejection.detail}",
                    )
                }
                check(runtime.runtimeHandle == candidate.runtimeHandle) {
                    "USB runtime handle changed between discovery and production open"
                }
                check(runtime.identity == expectedIdentity) {
                    "USB stable identity changed between identity preflight and production open"
                }
                val parsed = when (val result = StandardUacDescriptorParser.parse(runtime.descriptorSet)) {
                    is UsbAudioDescriptorParseResult.Parsed -> result.facts
                    is UsbAudioDescriptorParseResult.Rejected -> error(
                        "USB descriptors rejected: ${result.rejection}",
                    )
                }
                val selectedAudioControl = interfaces.firstOrNull {
                    it.id == parsed.audioFunction.controlInterfaceNumber && it.alternateSetting == 0
                } ?: error(
                    "Parsed AudioControl interface ${parsed.audioFunction.controlInterfaceNumber} is missing at runtime",
                )
                audioControl = selectedAudioControl
                controlClaimed = lease.io { connection.claimInterface(selectedAudioControl, true) }
                check(controlClaimed) { "Unable to force-claim parsed AudioControl interface" }

                val controlIo = AndroidUsbAudioControlIo(
                    connection = connection,
                    executeIo = { block -> lease.io(block) },
                )
                val clockEvidence = when (val result = Uac2RuntimeClockEvidenceReader.read(parsed, controlIo)) {
                    is Uac2RuntimeClockEvidenceReadResult.Ready -> result.evidence
                    is Uac2RuntimeClockEvidenceReadResult.Rejected -> error(
                        "USB runtime clock evidence rejected: ${result.rejection}",
                    )
                }
                val selection = when (
                    val result = UsbGenericPcmSelection.select(
                        source = sourceFormat,
                        identity = runtime.identity,
                        facts = parsed,
                        uac2ClockEvidence = clockEvidence,
                    )
                ) {
                    is UsbGenericPcmSelectionResult.Ready -> result
                    is UsbGenericPcmSelectionResult.Rejected -> error(
                        "Generic USB exact selection rejected: ${result.rejection}",
                    )
                }
                val decision = selection.decision
                val profile = decision.streamingProfile
                val claimPlan = checkNotNull(profile.claimPlan) { "Generic USB candidate has no claim plan" }
                check(claimPlan.controlInterfaceNumber == selectedAudioControl.id) {
                    "Generic claim plan control interface does not match parsed AudioControl"
                }
                check(claimPlan.streamingInterfaceNumber == profile.interfaceNumber &&
                    claimPlan.alternateSetting == profile.alternateSetting
                ) { "Generic claim plan does not match selected streaming profile" }
                check(config.sampleRate == decision.deviceFormat.sampleRateHz)
                check(profile.channelCount == decision.deviceFormat.channelCount)

                val selectedStreamingAlt0 = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == 0
                } ?: error("Parsed AudioStreaming alt 0 is missing")
                streamingAlt0 = selectedStreamingAlt0
                val streamingTarget = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber &&
                        it.alternateSetting == claimPlan.alternateSetting
                } ?: error("Parsed AudioStreaming alt ${claimPlan.alternateSetting} is missing")
                validateStreamingEndpoints(streamingTarget, profile)
                streamingClaimed = lease.io { connection.claimInterface(selectedStreamingAlt0, true) }
                check(streamingClaimed) { "Unable to force-claim parsed AudioStreaming interface" }

                val clockPlan = profile.clockPlan as? UsbClockPlan.Uac2Entity
                    ?: error("Contained SK02 generic path requires a proven UAC2 ClockSource plan")
                selectedClockSourceId = clockPlan.sourceEntityId
                originalClockHz = lease.io {
                    readClockCurrentHz(connection, selectedAudioControl.id, clockPlan.sourceEntityId)
                }
                check(
                    lease.io {
                        setClockFrequency(
                            connection,
                            selectedAudioControl.id,
                            clockPlan.sourceEntityId,
                            config.sampleRate,
                        )
                    } == 4,
                ) { "Unable to set parsed USB clock to ${config.sampleRate} Hz" }
                check(
                    lease.io {
                        readClockCurrentHz(connection, selectedAudioControl.id, clockPlan.sourceEntityId)
                    } == config.sampleRate,
                ) { "Parsed USB clock did not settle at ${config.sampleRate} Hz" }
                altSelected = lease.io { connection.setInterface(streamingTarget) }
                check(altSelected) { "Unable to select parsed USB alt ${profile.alternateSetting}" }

                return UsbSk02AudioOutput(
                    connection = connection,
                    audioControl = selectedAudioControl,
                    streamingAlt0 = selectedStreamingAlt0,
                    streamingTarget = streamingTarget,
                    sampleRate = config.sampleRate,
                    inputEncoding = config.encoding,
                    transportConfig = selection.transportConfig,
                    clockSourceId = clockPlan.sourceEntityId,
                    originalClockHz = originalClockHz,
                    runtimeHandle = runtime.runtimeHandle,
                    negotiatedFormat = decision.deviceFormat,
                    initialLease = lease,
                ).also {
                    DiagnosticLog.event(
                        "UsbExclusivePrototype",
                        "opened selection=generic-descriptor sr=${config.sampleRate} " +
                            "inputEncoding=${config.encoding} alt=${profile.alternateSetting} " +
                            "bus=${runtime.descriptorSet.busSpeed} bcdDevice=${runtime.identity.bcdDevice} " +
                            "generation=${lease.token.value}",
                    )
                }
            } catch (error: Exception) {
                val cleanup = lease.cleanupLease()
                val cleanupStreamingAlt0 = streamingAlt0
                if (altSelected && cleanupStreamingAlt0 != null) {
                    runCatching { cleanup.io { connection.setInterface(cleanupStreamingAlt0) } }
                }
                val cleanupAudioControl = audioControl
                val cleanupClockSourceId = selectedClockSourceId
                if (originalClockHz != null && cleanupAudioControl != null && cleanupClockSourceId != null) {
                    val original = checkNotNull(originalClockHz)
                    runCatching {
                        cleanup.io {
                            setClockFrequency(
                                connection,
                                cleanupAudioControl.id,
                                cleanupClockSourceId,
                                original,
                            )
                        }
                    }
                }
                if (streamingClaimed && cleanupStreamingAlt0 != null) {
                    runCatching { cleanup.io { connection.releaseInterface(cleanupStreamingAlt0) } }
                }
                if (controlClaimed && cleanupAudioControl != null) {
                    runCatching { cleanup.io { connection.releaseInterface(cleanupAudioControl) } }
                }
                cleanup.io { connection.close() }
                throw error
            }
        }

        private fun validateStreamingEndpoints(
            streamingInterface: UsbInterface,
            profile: UsbAudioStreamingProfile,
        ) {
            val endpoints = (0 until streamingInterface.endpointCount)
                .map(streamingInterface::getEndpoint)
                .map {
                    UsbAudioEndpointShape(
                        address = it.address,
                        transferType = it.type,
                        maxPacketBytes = it.maxPacketSize,
                        interval = it.interval,
                    )
                }
            when (val result = UsbRuntimeStreamingProfileValidator.validate(profile, endpoints)) {
                UsbStreamingProfileValidation.Valid -> Unit
                is UsbStreamingProfileValidation.Rejected -> error(
                    "Parsed runtime topology rejected: ${result.reason}",
                )
            }
        }

        private fun readClockCurrentHz(
            connection: UsbDeviceConnection,
            audioControlInterface: Int,
            clockSourceId: Int,
        ): Int? {
            val bytes = ByteArray(4)
            val transferred = connection.controlTransfer(
                USB_CLASS_INTERFACE_IN,
                UAC2_REQUEST_CUR,
                UAC2_SAMPLING_FREQUENCY_CONTROL,
                (clockSourceId shl 8) or audioControlInterface,
                bytes,
                bytes.size,
                CONTROL_TIMEOUT_MS,
            )
            return if (transferred == 4) {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                null
            }
        }

        private fun setClockFrequency(
            connection: UsbDeviceConnection,
            audioControlInterface: Int,
            clockSourceId: Int,
            sampleRate: Int,
        ): Int {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sampleRate)
                .array()
            return connection.controlTransfer(
                USB_CLASS_INTERFACE_OUT,
                UAC2_REQUEST_CUR,
                UAC2_SAMPLING_FREQUENCY_CONTROL,
                (clockSourceId shl 8) or audioControlInterface,
                bytes,
                bytes.size,
                CONTROL_TIMEOUT_MS,
            )
        }

    }
}

/** Fail-closed conversion used only for exact signed PCM32 carried in Media3 float buffers. */
internal object ExactPcm32Packing {
    private const val CHANNEL_COUNT = 2
    private const val SCALE = 2_147_483_648.0
    private const val MIN = -2_147_483_648.0
    private const val MAX = 2_147_483_647.0

    fun pack(inputBuffer: ByteBuffer, frames: Int, output: ByteBuffer) {
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        repeat(frames * CHANNEL_COUNT) { sampleIndex ->
            val sample = input.float
            require(sample.isFinite()) { "sampleIndex=$sampleIndex value=$sample nonFinite=true" }
            val scaled = sample.toDouble() * SCALE
            val rounded = round(scaled)
            require(scaled == rounded && rounded in MIN..MAX) {
                "sampleIndex=$sampleIndex value=$sample scaled=$scaled rounded=$rounded " +
                    "residual=${scaled - rounded}"
            }
            val value = rounded.toLong().toInt()
            output.put((value and 0xff).toByte())
            output.put(((value ushr 8) and 0xff).toByte())
            output.put(((value ushr 16) and 0xff).toByte())
            output.put(((value ushr 24) and 0xff).toByte())
        }
    }
}
