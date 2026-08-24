package com.mica.music.media.usbhybrid

import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Media3 integer PCM16/PCM24/PCM32 adapter. USB slot widening is handled by the reference transport. */
@UnstableApi
class UsbHybridPcmAudioSink(
    private val owner: UsbHybridSessionOwner,
    private val realtime: UsbHybridRealtimePort,
    private val requestEpoch: UsbRequestEpoch,
    private val clockUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1_000L },
) : AudioSink {
    private var listener: AudioSink.Listener? = null
    private var requestedVolumeGainQ16: Int = 65_536
    private var configuredFormat: Format? = null
    private var session: UsbTransportSessionId? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var policyFailure: UsbFailure? = null
    private var playing = false
    private var endOfStream = false
    private var mediaAnchorUs: Long? = null
    private var realtimeAnchorUs = 0L
    private var pausePositionUs: Long? = null
    private var submittedEndUs: Long? = null
    private var pausedPrebuffer: PendingPcm? = null
    private var transportUnavailable = false
    private var pausedByPlayer = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int = if (
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
        UsbExactPcmPolicy.bitDepth(format.pcmEncoding) != null &&
        format.sampleRate > 0 &&
        format.channelCount > 0
    ) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val anchor = mediaAnchorUs ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val cap = submittedEndUs ?: anchor
        if (!playing) return min(pausePositionUs ?: anchor, cap)
        val elapsedUs = (nowUs() - realtimeAnchorUs).coerceAtLeast(0L)
        return min(anchor + elapsedUs, cap)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val configureStartedNs = SystemClock.elapsedRealtimeNanos()
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "configure-start epoch=${requestEpoch.value} format=${inputFormat.sampleRate}/${inputFormat.channelCount}/${inputFormat.pcmEncoding} playing=$playing",
        )
        val rendererAlreadyStarted = playing
        val bitDepth = UsbExactPcmPolicy.bitDepth(inputFormat.pcmEncoding)
            ?: throw AudioSink.ConfigurationException(
                "USB Exact PCM accepts only integer PCM16/PCM24/PCM32; encoding=${inputFormat.pcmEncoding}",
                inputFormat,
            )
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW ||
            inputFormat.sampleRate <= 0 || inputFormat.channelCount <= 0
        ) {
            throw AudioSink.ConfigurationException("Unsupported USB Exact PCM format: $inputFormat", inputFormat)
        }
        val facts = owner.requestOpen(
            requestEpoch,
            UsbStreamFormat.Pcm(inputFormat.sampleRate, inputFormat.channelCount, bitDepth),
        ).get(10L, TimeUnit.SECONDS)
        val sessionId = facts.sessionId
        if (facts.requestEpoch != requestEpoch.value ||
            facts.activeMode == null || facts.activeMode != facts.requestedMode ||
            sessionId == null || !facts.exclusive || !facts.transportExact
        ) {
            throw AudioSink.ConfigurationException(
                facts.failure?.message ?: "USB Exact PCM session did not become active.",
                inputFormat,
            )
        }
        session = UsbTransportSessionId(requestEpoch, sessionId)
        handlePcmLifecycleResult(
            realtime.beginPcmTimeline(checkNotNull(session)),
            "PCM_TIMELINE_INIT_FAILED",
        )
        applyVolumeToSession(checkNotNull(session))
        configuredFormat = inputFormat
        pausedPrebuffer = null
        policyFailure = null
        resetTimelineState()
        if (rendererAlreadyStarted) {
            playing = true
            realtimeAnchorUs = nowUs()
        }
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "configure-complete epoch=${requestEpoch.value} session=${session?.nativeId} " +
                "format=${inputFormat.sampleRate}/${inputFormat.channelCount}/$bitDepth " +
                "durMs=${(SystemClock.elapsedRealtimeNanos() - configureStartedNs) / 1_000_000.0}",
        )
    }

    override fun play() {
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "play epoch=${requestEpoch.value} session=${session?.nativeId} playingBefore=$playing pausedByPlayer=$pausedByPlayer",
        )
        if (playing) return
        if (pausedByPlayer) {
            session?.let { active ->
                handlePcmLifecycleResult(realtime.resumePcm(active), "PCM_RESUME_FAILED")
            }
        }
        mediaAnchorUs = pausePositionUs ?: mediaAnchorUs
        realtimeAnchorUs = nowUs()
        playing = true
        pausedByPlayer = false
    }

    override fun handleDiscontinuity() {
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "handle-discontinuity epoch=${requestEpoch.value} session=${session?.nativeId} " +
                "playing=$playing submittedEndUs=$submittedEndUs positionUs=${getCurrentPositionUs(false)}",
        )
        pausedPrebuffer = null
        session?.let { active ->
            handlePcmLifecycleResult(realtime.preparePcmSeek(active), "PCM_SEEK_FAILED")
            handlePcmLifecycleResult(realtime.beginPcmTimeline(active), "PCM_TIMELINE_RESET_FAILED")
        }
        mediaAnchorUs = null
        pausePositionUs = null
        submittedEndUs = null
        realtimeAnchorUs = nowUs()
        listener?.onPositionDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        policyFailure?.let { throwWrite(it) }
        if (transportUnavailable) return false
        if (!buffer.hasRemaining()) return true
        val format = configuredFormat ?: return false
        if (!playing || !owner.pcmSourceWriteAllowed(requestEpoch)) {
            if (pausedPrebuffer != null) return false
            val bytes = ByteArray(buffer.remaining())
            buffer.duplicate().get(bytes)
            pausedPrebuffer = PendingPcm(bytes, presentationTimeUs)
            buffer.position(buffer.limit())
            endOfStream = false
            return true
        }
        pausedPrebuffer?.let {
            if (!writePcmBytes(it.bytes, it.presentationTimeUs, format)) return false
            pausedPrebuffer = null
        }
        val bytesPerFrame = inputBytesPerFrame(format)
        if (buffer.remaining() % bytesPerFrame != 0) {
            throwWrite(UsbFailure("PCM_ALIGNMENT_FAILED", "USB PCM buffer is not frame aligned."))
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        if (!writePcmBytes(bytes, presentationTimeUs, format)) return false
        buffer.position(buffer.limit())
        endOfStream = false
        return true
    }

    override fun playToEndOfStream() {
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "play-to-end epoch=${requestEpoch.value} session=${session?.nativeId} playing=$playing " +
                "eos=$endOfStream submittedEndUs=$submittedEndUs positionUs=${getCurrentPositionUs(false)}",
        )
        policyFailure?.let { throwWrite(it) }
        if (endOfStream) return
        val format = configuredFormat ?: return
        if (!playing || !owner.pcmSourceWriteAllowed(requestEpoch)) return
        pausedPrebuffer?.let {
            if (!writePcmBytes(it.bytes, it.presentationTimeUs, format)) return
            pausedPrebuffer = null
        }
        val active = session ?: return
        when (val result = realtime.finishPcm(active)) {
            UsbRealtimeResult.Success,
            UsbRealtimeResult.Retired -> Unit
            is UsbRealtimeResult.Failed -> {
                val failure = UsbFailure("USB_WRITE_FAILED", result.message)
                listener?.onAudioSinkError(IllegalStateException(result.message))
                throwWrite(failure)
            }
        }
        endOfStream = true
    }

    override fun isEnded(): Boolean = endOfStream && !hasPendingData()

    override fun hasPendingData(): Boolean {
        if (pausedPrebuffer != null) return true
        val active = session ?: return false
        val usbPending = realtime.telemetry(active).pendingOutputUrbs > 0
        val timelinePending = submittedEndUs?.let { end ->
            val current = getCurrentPositionUs(false)
            current != AudioSink.CURRENT_POSITION_NOT_SET && end > current
        } ?: false
        return usbPending || timelinePending
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        policyFailure = UsbExactPcmPolicy.speedFailure(playbackParameters.speed)
        policyFailure?.let { listener?.onAudioSinkError(IllegalStateException(it.message)) }
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (skipSilenceEnabled) {
            policyFailure = UsbFailure("SKIP_SILENCE_REJECTED", "USB Exact PCM rejects skip-silence.")
            listener?.onAudioSinkError(IllegalStateException(policyFailure!!.message))
        }
        listener?.onSkipSilenceEnabledChanged(false)
    }

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun getAudioTrackBufferSizeUs(): Long = 0L
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) {
        requestedVolumeGainQ16 = (volume.coerceIn(0f, 1f) * 65_536f).toInt()
        session?.let(::applyVolumeToSession)
    }

    private fun applyVolumeToSession(active: UsbTransportSessionId) {
        when (val result = realtime.setVolume(active, requestedVolumeGainQ16)) {
            UsbRealtimeResult.Success -> policyFailure = null
            UsbRealtimeResult.Retired -> Unit
            is UsbRealtimeResult.Failed -> {
                policyFailure = UsbFailure("USB_VOLUME_FAILED", result.message)
                listener?.onAudioSinkError(IllegalStateException(result.message))
            }
        }
    }
    override fun pause() {
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "pause epoch=${requestEpoch.value} session=${session?.nativeId} playingBefore=$playing pausedByPlayer=$pausedByPlayer",
        )
        if (!playing) return
        val frozenPositionUs = getCurrentPositionUs(false)
        session?.let { active ->
            handlePcmLifecycleResult(realtime.pausePcm(active), "PCM_PAUSE_FAILED")
        }
        frozenPositionUs.takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET }?.let { frozen ->
            mediaAnchorUs = frozen
            pausePositionUs = frozen
            realtimeAnchorUs = nowUs()
        }
        playing = false
        pausedByPlayer = true
    }

    override fun flush() {
        DiagnosticLog.event(
            "UsbHybridPcmSink",
            "flush epoch=${requestEpoch.value} session=${session?.nativeId} playing=$playing " +
                "eos=$endOfStream submittedEndUs=$submittedEndUs positionUs=${getCurrentPositionUs(false)}",
        )
        val wasPlaying = playing
        pausedPrebuffer = null
        session?.let(realtime::resetPcmForSeek)
        session?.let { active ->
            handlePcmLifecycleResult(realtime.beginPcmTimeline(active), "PCM_TIMELINE_RESET_FAILED")
        }
        resetTimelineState()
        playing = wasPlaying
        if (wasPlaying) realtimeAnchorUs = nowUs()
    }

    override fun reset() {
        pausedPrebuffer = null
        configuredFormat = null
        session = null
        policyFailure = null
        transportUnavailable = false
        pausedByPlayer = false
        resetTimelineState()
    }

    override fun release() = reset()

    private fun writePcmBytes(bytes: ByteArray, presentationTimeUs: Long, format: Format): Boolean {
        if (!owner.pcmSourceWriteAllowed(requestEpoch)) {
            DiagnosticLog.event(
                "UsbHybridPcmSink",
                "source-write-denied epoch=${requestEpoch.value} session=${session?.nativeId} ptsUs=$presentationTimeUs bytes=${bytes.size}",
            )
            return false
        }
        if (!playing) {
            DiagnosticLog.event(
                "UsbHybridPcmSink",
                "write-while-paused epoch=${requestEpoch.value} session=${session?.nativeId} ptsUs=$presentationTimeUs bytes=${bytes.size}",
            )
        }
        val active = session ?: throwWrite(UsbFailure("SESSION_MISSING", "USB session is not active."))
        when (val result = realtime.writePcm(active, bytes)) {
            UsbRealtimeResult.Success -> Unit
            UsbRealtimeResult.Retired -> {
                if (owner.currentEpoch() != requestEpoch) return true
                val replacement = reopenCurrentPcmSession(format)
                session = replacement
                handlePcmLifecycleResult(
                    realtime.beginPcmTimeline(replacement),
                    "PCM_TIMELINE_INIT_FAILED",
                )
                when (val retry = realtime.writePcm(replacement, bytes)) {
                    UsbRealtimeResult.Success -> Unit
                    UsbRealtimeResult.Retired -> throwWrite(
                        UsbFailure("SESSION_RETIRED", "USB PCM session retired again after same-epoch reopen."),
                    )
                    is UsbRealtimeResult.Failed -> {
                        if (isUsbRealtimeTransportUnavailableError(retry.message)) {
                            transportUnavailable = true
                            return false
                        }
                        listener?.onAudioSinkError(IllegalStateException(retry.message))
                        throwWrite(UsbFailure("USB_WRITE_FAILED", retry.message))
                    }
                }
            }
            is UsbRealtimeResult.Failed -> {
                if (isUsbRealtimeTransportUnavailableError(result.message)) {
                    transportUnavailable = true
                    return false
                }
                listener?.onAudioSinkError(IllegalStateException(result.message))
                throwWrite(UsbFailure("USB_WRITE_FAILED", result.message))
            }
        }
        if (mediaAnchorUs == null) {
            DiagnosticLog.event(
                "UsbHybridPcmSink",
                "first-write epoch=${requestEpoch.value} session=${session?.nativeId} ptsUs=$presentationTimeUs bytes=${bytes.size} " +
                    "format=${format.sampleRate}/${format.channelCount}/${format.pcmEncoding}",
            )
            mediaAnchorUs = presentationTimeUs
            pausePositionUs = presentationTimeUs
            realtimeAnchorUs = nowUs()
            listener?.onPositionAdvancing(System.currentTimeMillis())
        }
        val bitDepth = UsbExactPcmPolicy.bitDepth(format.pcmEncoding) ?: return true
        val bytesPerFrame = format.channelCount * (bitDepth / 8)
        val frames = if (bytesPerFrame > 0) bytes.size / bytesPerFrame else 0
        val bufferEndUs = presentationTimeUs + frames * C.MICROS_PER_SECOND / format.sampleRate
        submittedEndUs = maxOf(submittedEndUs ?: bufferEndUs, bufferEndUs)
        return true
    }

    private fun handlePcmLifecycleResult(result: UsbRealtimeResult, code: String) {
        when (result) {
            UsbRealtimeResult.Success,
            UsbRealtimeResult.Retired -> Unit
            is UsbRealtimeResult.Failed -> {
                val failure = UsbFailure(code, result.message)
                listener?.onAudioSinkError(IllegalStateException(result.message))
                throwWrite(failure)
            }
        }
    }

    private fun reopenCurrentPcmSession(format: Format): UsbTransportSessionId {
        val bitDepth = UsbExactPcmPolicy.bitDepth(format.pcmEncoding)
            ?: throwWrite(UsbFailure("PCM_FORMAT_REJECTED", "USB PCM reopen requires PCM16, PCM24 or PCM32."))
        val facts = owner.requestOpen(
            requestEpoch,
            UsbStreamFormat.Pcm(format.sampleRate, format.channelCount, bitDepth),
        ).get(10L, TimeUnit.SECONDS)
        val nativeId = facts.sessionId
        if (facts.requestEpoch != requestEpoch.value ||
            facts.activeMode == null || facts.activeMode != facts.requestedMode ||
            nativeId == null || !facts.exclusive || !facts.transportExact
        ) {
            throwWrite(
                facts.failure ?: UsbFailure(
                    "SESSION_REOPEN_FAILED",
                    "USB PCM session did not become active after same-epoch retirement.",
                ),
            )
        }
        return UsbTransportSessionId(requestEpoch, nativeId)
    }

    private fun throwWrite(@Suppress("UNUSED_PARAMETER") failure: UsbFailure): Nothing {
        val format = configuredFormat ?: Format.Builder().build()
        throw AudioSink.WriteException(ERROR_USB_WRITE, format, false)
    }

    private fun resetTimelineState() {
        playing = false
        endOfStream = false
        mediaAnchorUs = null
        pausePositionUs = null
        submittedEndUs = null
        realtimeAnchorUs = 0L
    }

    private fun inputBytesPerFrame(format: Format): Int {
        val bitDepth = UsbExactPcmPolicy.bitDepth(format.pcmEncoding)
            ?: throwWrite(UsbFailure("PCM_FORMAT_REJECTED", "USB PCM format has no integer bit depth."))
        return format.channelCount * (bitDepth / 8)
    }

    private fun nowUs(): Long = clockUs()

    private data class PendingPcm(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
    )

    private companion object {
        const val ERROR_USB_WRITE = -10_201
    }
}
