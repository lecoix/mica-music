package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R4: runs Mica's EQ + spectrum tap on the float (hi-res) PCM path.
 *
 * Media3's [androidx.media3.exoplayer.audio.DefaultAudioSink] only feeds its custom
 * `AudioProcessorChain` on the int16 output path; the float output path
 * (`enableFloatOutput && isEncodingHighResolutionPcm`) is delivered without any custom processor.
 * Wrapping the float sink lets us apply EQ in place and tap the spectrum on the decoder's float PCM
 * before forwarding, without touching frame counts, so the inner sink's media clock stays correct.
 *
 * Speed/pitch is intentionally NOT done here: it changes frame counts and must be accounted for by
 * the sink that owns the AudioTrack. The inner sink handles speed/pitch via AudioTrack playback
 * parameters ([androidx.media3.exoplayer.audio.DefaultAudioSink.Builder.setEnableAudioTrackPlaybackParams]).
 *
 * Audio quality: purely additive. When EQ is disabled and spectrum is inactive the input buffer is
 * forwarded untouched (bit-exact). EQ/limiter only alter the signal when the user enables them.
 */
@UnstableApi
internal class MicaFloatDspAudioSink(
    sink: AudioSink,
    private val tap: FloatPcmDspTap,
) : ForwardingAudioSink(sink) {

    private var androidEncoding = android.media.AudioFormat.ENCODING_INVALID
    private var sampleRate = 0
    private var channelCount = 0
    private var linearPcm = false

    // Buffer state machine: while a source buffer is not yet fully consumed by the inner sink we
    // must keep forwarding the SAME buffer object chosen on the first attempt. Active EQ/spectrum
    // state is sampled only for new source buffers; retries keep their original forwarding mode.
    private var inFlightSource: ByteBuffer? = null
    private var inFlightForwarded: ByteBuffer? = null
    private var inFlightMode: InFlightMode = InFlightMode.NONE
    private var processedBuffer: ByteBuffer = ByteBuffer.allocate(0)
    private var scratchBytes = ByteArray(0)
    private var scratchDirect: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    // Lightweight cadence probe (float path has no SpectrumAudioProcessor to report through).
    private var probeWindowStartMs = 0L
    private var probeHandleCalls = 0
    private var probeProcessCalls = 0
    private var probePassthroughCalls = 0
    private var probeLastHandleMs = 0L
    private var probeMaxGapMs = 0L

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        androidEncoding = media3EncodingToAndroid(inputFormat.pcmEncoding)
        sampleRate = inputFormat.sampleRate
        // We process before the inner sink's channel mapping, so use the input channel count.
        channelCount = inputFormat.channelCount
        linearPcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            androidEncoding != android.media.AudioFormat.ENCODING_INVALID
        clearInFlight()
        if (linearPcm) {
            tap.configure(sampleRate, channelCount)
        }
        DiagnosticLog.event(
            "FloatDspSink",
            "configure sr=$sampleRate ch=$channelCount enc=$androidEncoding linearPcm=$linearPcm",
        )
        resetProbe()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val active = tap.isActive()

        inFlightSource?.let {
            return handleInFlightBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (!linearPcm || !active) {
            return startPassthroughBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        return startProcessedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun startPassthroughBuffer(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // Bit-exact passthrough when there is nothing to do.
        recordHandle(processed = false)
        inFlightSource = source
        inFlightForwarded = source
        inFlightMode = InFlightMode.PASSTHROUGH
        val accepted = super.handleBuffer(source, presentationTimeUs, encodedAccessUnitCount)
        if (accepted) {
            clearInFlight()
        }
        return accepted
    }

    private fun startProcessedBuffer(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        recordHandle(processed = true)
        processedBuffer = process(source)
        inFlightSource = source
        inFlightForwarded = processedBuffer
        inFlightMode = InFlightMode.PROCESSED
        val accepted = super.handleBuffer(processedBuffer, presentationTimeUs, encodedAccessUnitCount)
        if (accepted) {
            source.position(source.limit())
            clearInFlight()
        }
        return accepted
    }

    private fun handleInFlightBuffer(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val mode = inFlightMode
        val originalSource = inFlightSource ?: source
        val forwarded = inFlightForwarded ?: source
        val accepted = super.handleBuffer(forwarded, presentationTimeUs, encodedAccessUnitCount)
        if (accepted) {
            if (mode == InFlightMode.PROCESSED) {
                originalSource.position(originalSource.limit())
            }
            clearInFlight()
        }
        return accepted
    }

    private fun recordHandle(processed: Boolean) {
        val nowMs = System.currentTimeMillis()
        if (probeWindowStartMs == 0L) probeWindowStartMs = nowMs
        probeHandleCalls++
        if (processed) probeProcessCalls++ else probePassthroughCalls++
        if (probeLastHandleMs != 0L) {
            probeMaxGapMs = maxOf(probeMaxGapMs, nowMs - probeLastHandleMs)
        }
        probeLastHandleMs = nowMs
        if (nowMs - probeWindowStartMs >= 1_000L) {
            DiagnosticLog.event(
                "FloatDspSink",
                "handleCalls=$probeHandleCalls processed=$probeProcessCalls " +
                    "passthrough=$probePassthroughCalls maxGapMs=$probeMaxGapMs " +
                    "eqOn=${tap.isActive()} sr=$sampleRate ch=$channelCount enc=$androidEncoding",
            )
            resetProbe()
        }
    }

    private fun resetProbe() {
        probeWindowStartMs = 0L
        probeHandleCalls = 0
        probeProcessCalls = 0
        probePassthroughCalls = 0
        probeLastHandleMs = 0L
        probeMaxGapMs = 0L
    }

    override fun flush() {
        clearInFlight()
        super.flush()
    }

    override fun reset() {
        clearInFlight()
        androidEncoding = android.media.AudioFormat.ENCODING_INVALID
        linearPcm = false
        super.reset()
    }

    private fun process(source: ByteBuffer): ByteBuffer {
        val length = source.remaining()
        if (scratchBytes.size < length) {
            scratchBytes = ByteArray(length)
        }
        // Read via a duplicate so the source position stays intact until the inner sink accepts;
        // a rejected buffer must be re-presentable unchanged (handleBuffer contract).
        source.duplicate().get(scratchBytes, 0, length)
        tap.process(scratchBytes, 0, length, androidEncoding, sampleRate, channelCount)
        if (scratchDirect.capacity() < length) {
            scratchDirect = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder())
        } else {
            scratchDirect.clear()
        }
        scratchDirect.put(scratchBytes, 0, length)
        scratchDirect.flip()
        return scratchDirect
    }

    private fun clearInFlight() {
        inFlightSource = null
        inFlightForwarded = null
        inFlightMode = InFlightMode.NONE
    }

    private fun media3EncodingToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        C.ENCODING_PCM_32BIT -> android.media.AudioFormat.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        else -> android.media.AudioFormat.ENCODING_INVALID
    }

    private enum class InFlightMode {
        NONE,
        PASSTHROUGH,
        PROCESSED,
    }

    /**
     * DSP applied to interleaved PCM in place, plus read-only taps. Injected so the sink's buffer
     * state machine can be unit-tested without the EQ/spectrum singletons.
     */
    interface FloatPcmDspTap {
        /** Prepares stateful DSP (e.g. EQ biquads) for the given output format. */
        fun configure(sampleRate: Int, channelCount: Int)

        /** Whether any processing is required right now (EQ enabled or spectrum active). */
        fun isActive(): Boolean

        /** Applies EQ in place on [bytes] and feeds the spectrum analyzer. */
        fun process(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            androidEncoding: Int,
            sampleRate: Int,
            channelCount: Int,
        )
    }
}
