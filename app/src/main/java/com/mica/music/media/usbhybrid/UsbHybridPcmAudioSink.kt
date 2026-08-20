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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Media3 PCM16/PCM32 adapter. It never converts float or packed PCM24. */
@UnstableApi
class UsbHybridPcmAudioSink(
    private val owner: UsbHybridSessionOwner,
    private val realtime: UsbHybridRealtimePort,
    private val requestEpoch: UsbRequestEpoch,
) : AudioSink {
    private var listener: AudioSink.Listener? = null
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
        return min(anchor + nowUs() - realtimeAnchorUs, cap)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val bitDepth = UsbExactPcmPolicy.bitDepth(inputFormat.pcmEncoding)
            ?: throw AudioSink.ConfigurationException(
                "USB Exact PCM accepts only integer PCM16/PCM32; encoding=${inputFormat.pcmEncoding}",
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
        configuredFormat = inputFormat
        pausedPrebuffer = null
        policyFailure = null
        resetTimelineState()
    }

    override fun play() {
        if (playing) return
        pausePositionUs?.let { mediaAnchorUs = it }
        realtimeAnchorUs = nowUs()
        playing = true
    }

    override fun handleDiscontinuity() {
        pausedPrebuffer = null
        session?.let(realtime::resetPcmForSeek)
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
        if (!buffer.hasRemaining()) return true
        val format = configuredFormat ?: return false
        if (!playing) {
            if (pausedPrebuffer != null) return false
            val bytes = ByteArray(buffer.remaining())
            buffer.duplicate().get(bytes)
            pausedPrebuffer = PendingPcm(bytes, presentationTimeUs)
            buffer.position(buffer.limit())
            endOfStream = false
            return true
        }
        pausedPrebuffer?.let {
            writePcmBytes(it.bytes, it.presentationTimeUs, format)
            pausedPrebuffer = null
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        writePcmBytes(bytes, presentationTimeUs, format)
        buffer.position(buffer.limit())
        endOfStream = false
        return true
    }

    override fun playToEndOfStream() {
        policyFailure?.let { throwWrite(it) }
        if (endOfStream) return
        val format = configuredFormat ?: return
        if (!playing && pausedPrebuffer != null) return
        pausedPrebuffer?.let {
            writePcmBytes(it.bytes, it.presentationTimeUs, format)
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
        if (volume != 1f) {
            policyFailure = UsbFailure(
                "SOFTWARE_VOLUME_REJECTED",
                "USB Exact PCM requires hardware volume and software volume 1.0.",
            )
            listener?.onAudioSinkError(IllegalStateException(policyFailure!!.message))
        }
    }

    override fun pause() {
        if (!playing) return
        getCurrentPositionUs(false).takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET }
            ?.let { pausePositionUs = it }
        playing = false
    }

    override fun flush() {
        pausedPrebuffer = null
        session?.let(realtime::resetPcmForSeek)
        resetTimelineState()
    }

    override fun reset() {
        pausedPrebuffer = null
        configuredFormat = null
        session = null
        policyFailure = null
        resetTimelineState()
    }

    override fun release() = reset()

    private fun writePcmBytes(bytes: ByteArray, presentationTimeUs: Long, format: Format) {
        val active = session ?: throwWrite(UsbFailure("SESSION_MISSING", "USB session is not active."))
        when (val result = realtime.writePcm(active, bytes)) {
            UsbRealtimeResult.Success -> Unit
            UsbRealtimeResult.Retired -> return
            is UsbRealtimeResult.Failed -> {
                listener?.onAudioSinkError(IllegalStateException(result.message))
                throwWrite(UsbFailure("USB_WRITE_FAILED", result.message))
            }
        }
        if (mediaAnchorUs == null) {
            mediaAnchorUs = presentationTimeUs
            pausePositionUs = presentationTimeUs
            realtimeAnchorUs = nowUs()
            listener?.onPositionAdvancing(System.currentTimeMillis())
        }
        val bitDepth = UsbExactPcmPolicy.bitDepth(format.pcmEncoding) ?: return
        val bytesPerFrame = format.channelCount * (bitDepth / 8)
        val frames = if (bytesPerFrame > 0) bytes.size / bytesPerFrame else 0
        submittedEndUs = presentationTimeUs + frames * C.MICROS_PER_SECOND / format.sampleRate
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

    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    private data class PendingPcm(val bytes: ByteArray, val presentationTimeUs: Long)

    private companion object {
        const val ERROR_USB_WRITE = -10_201
    }
}
