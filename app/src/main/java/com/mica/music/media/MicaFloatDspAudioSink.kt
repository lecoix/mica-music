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
import java.util.ArrayDeque

/**
 * R4: runs Mica's EQ + spectrum tap on the float (hi-res) PCM path.
 *
 * Media3's [androidx.media3.exoplayer.audio.DefaultAudioSink] only feeds its custom
 * `AudioProcessorChain` on the int16 output path; the float output path
 * (`enableFloatOutput && isEncodingHighResolutionPcm`) is delivered without any custom processor.
 * Wrapping the float sink lets us apply EQ in place and tap the spectrum on the decoder's float PCM
 * before forwarding, without touching frame counts, so the inner sink's media clock stays correct.
 *
 * Spectrum/EQ taps run when ExoPlayer delivers a buffer; inner [AudioSink.handleBuffer] writes are
 * queued separately so AudioTrack backpressure (inner reject) does not block the decoder from
 * advancing or starve the analysis queue.
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

    private val pendingWrites = ArrayDeque<PendingWrite>()
    private val recycledWriteBuffers = ArrayDeque<ByteBuffer>()
    private var scratchBytes = ByteArray(0)
    private var scratchDirect: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    // Lightweight cadence probe (float path has no SpectrumAudioProcessor to report through).
    private var probeWindowStartMs = 0L
    private var probeHandleCalls = 0
    private var probeProcessCalls = 0
    private var probePassthroughCalls = 0
    private var probeLastHandleMs = 0L
    private var probeMaxGapMs = 0L
    private var consecutiveInnerRejects = 0

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        androidEncoding = media3EncodingToAndroid(inputFormat.pcmEncoding)
        sampleRate = inputFormat.sampleRate
        // We process before the inner sink's channel mapping, so use the input channel count.
        channelCount = inputFormat.channelCount
        linearPcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            androidEncoding != android.media.AudioFormat.ENCODING_INVALID
        clearPendingWrites()
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
        if (buffer.hasRemaining()) {
            if (!linearPcm || !tap.isActive()) {
                val reason = when {
                    !linearPcm -> "nonLinearPcm"
                    else -> "tapInactive"
                }
                if (reason == "tapInactive") {
                    SpectrumPcmPipelineDiagnostics.onFloatDspPassthroughWhileAnalysisExpected(reason)
                }
                enqueuePassthrough(buffer, presentationTimeUs, encodedAccessUnitCount, reason)
            } else {
                enqueueProcessed(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            buffer.position(buffer.limit())
        }
        drainPendingWrites()
        return pendingWrites.isEmpty()
    }

    private fun enqueuePassthrough(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
        passthroughReason: String?,
    ) {
        recordHandle(
            processed = false,
            bufferBytes = source.remaining(),
            presentationTimeUs = presentationTimeUs,
            passthroughReason = passthroughReason,
            inFlightRetry = false,
        )
        pendingWrites.addLast(
            PendingWrite(
                buffer = copyBuffer(source),
                presentationTimeUs = presentationTimeUs,
                encodedAccessUnitCount = encodedAccessUnitCount,
                mode = WriteMode.PASSTHROUGH,
            ),
        )
    }

    private fun enqueueProcessed(
        source: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ) {
        recordHandle(
            processed = true,
            bufferBytes = source.remaining(),
            presentationTimeUs = presentationTimeUs,
            passthroughReason = null,
            inFlightRetry = false,
        )
        val processed = process(source)
        pendingWrites.addLast(
            PendingWrite(
                buffer = copyBuffer(processed),
                presentationTimeUs = presentationTimeUs,
                encodedAccessUnitCount = encodedAccessUnitCount,
                mode = WriteMode.PROCESSED,
            ),
        )
    }

    private fun drainPendingWrites() {
        while (pendingWrites.isNotEmpty()) {
            val pending = pendingWrites.first()
            val positionBeforeWrite = pending.buffer.position()
            val accepted = super.handleBuffer(
                pending.buffer,
                pending.presentationTimeUs,
                pending.encodedAccessUnitCount,
            )
            if (!accepted) {
                if (pending.buffer.position() == positionBeforeWrite) {
                    consecutiveInnerRejects++
                    SpectrumPcmPipelineDiagnostics.onFloatDspInnerReject(
                        streak = consecutiveInnerRejects,
                        mode = pending.mode.name.lowercase(),
                        bufferBytes = pending.buffer.remaining(),
                        presentationTimeUs = pending.presentationTimeUs,
                    )
                } else {
                    // Partial consumption is normal AudioSink backpressure, not a rejection.
                    consecutiveInnerRejects = 0
                }
                return
            }
            consecutiveInnerRejects = 0
            val completed = pendingWrites.removeFirst()
            recycleWriteBuffer(completed.buffer)
        }
    }

    private fun recordHandle(
        processed: Boolean,
        bufferBytes: Int,
        presentationTimeUs: Long,
        passthroughReason: String?,
        inFlightRetry: Boolean,
    ) {
        val nowMs = System.currentTimeMillis()
        if (probeWindowStartMs == 0L) probeWindowStartMs = nowMs
        probeHandleCalls++
        if (processed) probeProcessCalls++ else probePassthroughCalls++
        if (probeLastHandleMs != 0L) {
            val gapMs = nowMs - probeLastHandleMs
            probeMaxGapMs = maxOf(probeMaxGapMs, gapMs)
            SpectrumPcmPipelineDiagnostics.onFloatDspUpstreamGap(
                gapMs = gapMs,
                bufferBytes = bufferBytes,
                presentationTimeUs = presentationTimeUs,
                processed = processed,
                passthroughReason = passthroughReason,
                inFlightRetry = inFlightRetry,
                consecutiveInnerRejects = consecutiveInnerRejects,
            )
        }
        consecutiveInnerRejects = 0
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
        SpectrumPcmPipelineDiagnostics.onFloatDspFlush()
        clearPendingWrites()
        consecutiveInnerRejects = 0
        super.flush()
    }

    override fun reset() {
        clearPendingWrites()
        androidEncoding = android.media.AudioFormat.ENCODING_INVALID
        linearPcm = false
        super.reset()
    }

    private fun process(source: ByteBuffer): ByteBuffer {
        val length = source.remaining()
        if (scratchBytes.size < length) {
            scratchBytes = ByteArray(length)
        }
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

    private fun copyBuffer(source: ByteBuffer): ByteBuffer {
        val required = source.remaining()
        val copy = acquireWriteBuffer(required, source.order())
        copy.put(source.duplicate())
        copy.flip()
        return copy
    }

    private fun acquireWriteBuffer(required: Int, order: ByteOrder): ByteBuffer {
        var match: ByteBuffer? = null
        val candidates = recycledWriteBuffers.size
        repeat(candidates) {
            val candidate = recycledWriteBuffers.removeFirst()
            if (match == null && candidate.capacity() >= required) {
                match = candidate
            } else {
                recycledWriteBuffers.addLast(candidate)
            }
        }
        return (match ?: ByteBuffer.allocateDirect(required))
            .order(order)
            .apply { clear() }
    }

    private fun recycleWriteBuffer(buffer: ByteBuffer) {
        if (recycledWriteBuffers.size >= MAX_RECYCLED_WRITE_BUFFERS) return
        buffer.clear()
        recycledWriteBuffers.addLast(buffer)
    }

    private fun clearPendingWrites() {
        while (pendingWrites.isNotEmpty()) {
            recycleWriteBuffer(pendingWrites.removeFirst().buffer)
        }
    }

    private fun media3EncodingToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        C.ENCODING_PCM_32BIT -> android.media.AudioFormat.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        else -> android.media.AudioFormat.ENCODING_INVALID
    }

    private data class PendingWrite(
        val buffer: ByteBuffer,
        val presentationTimeUs: Long,
        val encodedAccessUnitCount: Int,
        val mode: WriteMode,
    )

    private companion object {
        const val MAX_RECYCLED_WRITE_BUFFERS = 8
    }

    private enum class WriteMode {
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
