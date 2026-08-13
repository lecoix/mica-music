package com.mica.music.media.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import com.mica.music.media.dsf.DsfFormat

object DirectDsdMedia3FormatPolicy {
    private val prototypeRates = setOf(2_822_400, 5_644_800)

    fun factsOrNull(format: Format): DsfExtractorPacketFacts? {
        if (format.sampleMimeType != DsfFormat.MIME_DSF) return null
        if (format.containerMimeType != DsfFormat.MIME_CONTAINER_DSF) return null
        val facts = format.customData as? DsfExtractorPacketFacts ?: return null
        if (facts.channelCount != 2 || format.channelCount != facts.channelCount) return null
        if (facts.sourceSampleRateHz !in prototypeRates) return null
        if (facts.sourceSampleRateHz % 8 != 0 || format.sampleRate != facts.sourceSampleRateHz / 8) return null
        return facts
    }
}

internal enum class DirectDsdDrainAction {
    CONTINUE,
    YIELD,
    TERMINAL,
}

internal data class DirectDsdDrainStepResult(
    val sourceReadPerformed: Boolean,
    val packetRead: Boolean,
    val action: DirectDsdDrainAction,
)

internal data class DirectDsdDrainResult(
    val sourceReadCount: Int,
    val packetReadCount: Int,
    val budgetExhausted: Boolean,
)

/** Bounds one Media3 render opportunity without coupling the limit to USB transport policy. */
internal class DirectDsdRenderDrainLoop(
    private val maxSourceReads: Int,
) {
    init {
        require(maxSourceReads > 0)
    }

    fun drain(step: () -> DirectDsdDrainStepResult): DirectDsdDrainResult {
        var sourceReads = 0
        var packets = 0
        while (sourceReads < maxSourceReads) {
            val result = step()
            if (result.sourceReadPerformed) sourceReads++
            if (result.packetRead) packets++
            when (result.action) {
                DirectDsdDrainAction.YIELD,
                DirectDsdDrainAction.TERMINAL,
                -> return DirectDsdDrainResult(sourceReads, packets, budgetExhausted = false)
                DirectDsdDrainAction.CONTINUE -> check(result.sourceReadPerformed) {
                    "Direct DSD drain CONTINUE must account for one source read"
                }
            }
        }
        return DirectDsdDrainResult(sourceReads, packets, budgetExhausted = true)
    }
}

/**
 * Raw DSF renderer used only by the QA Direct-DSD prototype gate.
 * It consumes extractor packets before FFmpeg and owns no PCM sink or decoder.
 */
class DirectDsdMedia3Renderer(
    private val sessionFactory: DirectDsdTransportSessionFactory,
    private val milestone: (String) -> Unit = {},
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val drainLoop = DirectDsdRenderDrainLoop(MAX_SOURCE_READS_PER_RENDER)
    private var currentFormat: Format? = null
    private var pump: DirectDsdRendererPump? = null
    private var inputEosSeen = false
    private var ended = false
    private var resumeBlockedAfterStop = false
    private var sampleCount = 0L
    private var lastSampleMilestoneTimeUs = Long.MIN_VALUE
    private var renderInvocationCount = 0L
    private var drainBudgetExhaustionCount = 0L
    private var lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE

    override fun getName(): String = NAME

    override fun supportsFormat(format: Format): Int =
        if (DirectDsdMedia3FormatPolicy.factsOrNull(format) != null) {
            C.FORMAT_HANDLED
        } else if (format.sampleMimeType == DsfFormat.MIME_DSF) {
            C.FORMAT_UNSUPPORTED_SUBTYPE
        } else {
            C.FORMAT_UNSUPPORTED_TYPE
        }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (ended || resumeBlockedAfterStop) return
        try {
            renderInvocationCount++
            val drain = drainLoop.drain { renderDrainStep(positionUs) }
            if (drain.budgetExhausted) drainBudgetExhaustionCount++
            if (
                renderInvocationCount == 1L ||
                elapsedRealtimeUs - lastDrainMilestoneElapsedRealtimeUs >= DRAIN_MILESTONE_INTERVAL_US
            ) {
                lastDrainMilestoneElapsedRealtimeUs = elapsedRealtimeUs
                milestone(
                    "renderer=drain callbacks=$renderInvocationCount " +
                        "packets=$sampleCount budgetExhausted=$drainBudgetExhaustionCount " +
                        "lastReads=${drain.sourceReadCount} lastPackets=${drain.packetReadCount} " +
                        "pending=${pump?.snapshot()?.pendingCanonicalBytes ?: 0}",
                )
            }
        } catch (error: ExoPlaybackException) {
            throw error
        } catch (error: Throwable) {
            throw createRendererException(
                error,
                currentFormat,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        }
    }

    private fun renderDrainStep(positionUs: Long): DirectDsdDrainStepResult {
        val activePump = pump
        if (activePump != null && !activePump.canAcceptPacket()) {
            activePump.pump()
            if (!activePump.canAcceptPacket()) {
                return DirectDsdDrainStepResult(
                    sourceReadPerformed = false,
                    packetRead = false,
                    action = DirectDsdDrainAction.YIELD,
                )
            }
        }
        if (inputEosSeen) {
            if (activePump == null || activePump.signalEndOfStream()) {
                ended = true
                milestone("renderer=eos positionUs=$positionUs")
                return DirectDsdDrainStepResult(false, false, DirectDsdDrainAction.TERMINAL)
            }
            return DirectDsdDrainStepResult(false, false, DirectDsdDrainAction.YIELD)
        }

        inputBuffer.clear()
        val holder = formatHolder
        return when (readSource(holder, inputBuffer, 0)) {
            C.RESULT_NOTHING_READ -> DirectDsdDrainStepResult(
                sourceReadPerformed = true,
                packetRead = false,
                action = DirectDsdDrainAction.YIELD,
            )
            C.RESULT_FORMAT_READ -> {
                val format = checkNotNull(holder.format)
                val facts = DirectDsdMedia3FormatPolicy.factsOrNull(format)
                    ?: error("Direct DSD renderer received non-authoritative DSF format")
                currentFormat = format
                if (pump == null) {
                    val session = sessionFactory.open(facts)
                    pump = DirectDsdRendererPump(facts, session)
                    milestone(
                        "renderer=claimed sourceRate=${facts.sourceSampleRateHz} " +
                            "channels=${facts.channelCount} bitOrder=${facts.sourceBitOrder}",
                    )
                }
                DirectDsdDrainStepResult(true, false, DirectDsdDrainAction.CONTINUE)
            }
            C.RESULT_BUFFER_READ -> {
                if (inputBuffer.isEndOfStream) {
                    inputEosSeen = true
                    milestone("renderer=input-eos positionUs=$positionUs")
                    DirectDsdDrainStepResult(true, false, DirectDsdDrainAction.CONTINUE)
                } else {
                    val active = pump ?: error("DSD sample arrived before format")
                    inputBuffer.flip()
                    val data = checkNotNull(inputBuffer.data)
                    val packet = ByteArray(data.remaining())
                    data.get(packet)
                    active.offerExtractorPacket(packet, inputBuffer.timeUs)
                    sampleCount++
                    if (
                        sampleCount == 1L ||
                        inputBuffer.timeUs - lastSampleMilestoneTimeUs >= SAMPLE_MILESTONE_INTERVAL_US
                    ) {
                        lastSampleMilestoneTimeUs = inputBuffer.timeUs
                        milestone(
                            "renderer=sample count=$sampleCount timeUs=${inputBuffer.timeUs} " +
                                "packetBytes=${packet.size}",
                        )
                    }
                    active.pump()
                    DirectDsdDrainStepResult(
                        sourceReadPerformed = true,
                        packetRead = true,
                        action = if (active.canAcceptPacket()) {
                            DirectDsdDrainAction.CONTINUE
                        } else {
                            DirectDsdDrainAction.YIELD
                        },
                    )
                }
            }
            else -> DirectDsdDrainStepResult(
                sourceReadPerformed = true,
                packetRead = false,
                action = DirectDsdDrainAction.YIELD,
            )
        }
    }

    override fun isReady(): Boolean =
        !ended && !resumeBlockedAfterStop && (pump?.isStartupPrefillReady() == true) &&
            ((pump?.snapshot()?.pendingCanonicalBytes ?: 0) > 0 || isSourceReady() || inputEosSeen)

    override fun isEnded(): Boolean = ended

    override fun onStarted() {
        try {
            check(!resumeBlockedAfterStop) { "Direct DSD resume requires renderer rebuild" }
            val active = checkNotNull(pump) { "Direct DSD renderer started before transport prepare" }
            check(active.isStartupPrefillReady()) { "Direct DSD renderer started before startup prefill" }
            active.armPlayback()
            check(active.isPlaybackArmed())
            milestone("renderer=started armed=true")
        } catch (error: Throwable) {
            throw createRendererException(
                error,
                currentFormat,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        }
    }

    override fun onStopped() {
        val active = pump
        val wasArmed = active?.isPlaybackArmed() == true
        if (wasArmed) {
            closePump("stopped-after-arm")
            resumeBlockedAfterStop = true
        }
        milestone("renderer=stopped armed=$wasArmed resumeBlocked=$resumeBlockedAfterStop")
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isPlaying: Boolean) {
        closePump("position-reset:$positionUs")
        inputEosSeen = false
        ended = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun onDisabled() {
        closePump("disabled")
        currentFormat = null
        inputEosSeen = false
        ended = false
        resumeBlockedAfterStop = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun onReset() {
        closePump("reset")
        currentFormat = null
        inputEosSeen = false
        ended = false
        resumeBlockedAfterStop = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    private fun closePump(reason: String) {
        pump?.close()
        pump = null
        milestone("renderer=close reason=$reason")
    }

    companion object {
        const val NAME = "MicaDirectDsdDoP"
        // Four immediate source reads per callback exceed DSD128 demand even near 50 callbacks/s.
        internal const val MAX_SOURCE_READS_PER_RENDER = 4
        private const val SAMPLE_MILESTONE_INTERVAL_US = 250_000L
        private const val DRAIN_MILESTONE_INTERVAL_US = 250_000L
    }
}
