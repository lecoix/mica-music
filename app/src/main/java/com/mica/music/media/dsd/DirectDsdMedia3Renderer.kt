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

/**
 * Converts the worst supported raw-DSD packet demand into a bounded per-render read budget.
 * The callback floor is an explicit qualification assumption rather than an accidental Media3 cadence dependency.
 */
internal object DirectDsdRenderDrainCapacityPolicy {
    private const val QUALIFICATION_CALLBACKS_PER_SECOND_FLOOR = 35
    private const val CAPACITY_MARGIN_NUMERATOR = 6
    private const val CAPACITY_MARGIN_DENOMINATOR = 5
    private const val EXTRACTOR_PACKET_BYTES = 8192
    private const val BITS_PER_BYTE = 8
    private const val HARD_MAX_SOURCE_READS_PER_CALLBACK = 8

    fun sourceReadsPerCallback(sourceSampleRateHz: Int, channelCount: Int): Int {
        require(sourceSampleRateHz > 0)
        require(channelCount > 0)
        val demandNumerator = Math.multiplyExact(sourceSampleRateHz.toLong(), channelCount.toLong())
        val demandDenominator = BITS_PER_BYTE.toLong() * EXTRACTOR_PACKET_BYTES
        val budgetNumerator = Math.multiplyExact(demandNumerator, CAPACITY_MARGIN_NUMERATOR.toLong())
        val budgetDenominator =
            demandDenominator * QUALIFICATION_CALLBACKS_PER_SECOND_FLOOR * CAPACITY_MARGIN_DENOMINATOR
        val reads = ((budgetNumerator + budgetDenominator - 1L) / budgetDenominator).toInt()
        check(reads in 1..HARD_MAX_SOURCE_READS_PER_CALLBACK) {
            "Direct DSD demand exceeds bounded render drain capacity: reads=$reads"
        }
        return reads
    }

    fun packetCapacityPerSecond(
        sourceSampleRateHz: Int,
        channelCount: Int,
        callbacksPerSecond: Int,
    ): Int = sourceReadsPerCallback(sourceSampleRateHz, channelCount) * callbacksPerSecond
}

/** Tracks only renderer-local discontinuity state; transport/session ownership stays elsewhere. */
internal class DirectDsdPositionResetState {
    private var freshPumpPositionUs: Long? = null
    private var playingArmPositionUs: Long? = null

    fun onPositionReset(positionUs: Long, hadPump: Boolean, isPlaying: Boolean) {
        freshPumpPositionUs = positionUs.takeIf { hadPump }
        playingArmPositionUs = positionUs.takeIf { hadPump && isPlaying }
    }

    fun consumeFreshPumpPositionUs(): Long? = freshPumpPositionUs.also { freshPumpPositionUs = null }

    fun postResetArmPositionUsIfReady(startupReady: Boolean, playbackArmed: Boolean): Long? =
        playingArmPositionUs?.takeIf { startupReady && !playbackArmed }

    fun markPostResetArmed(positionUs: Long) {
        check(playingArmPositionUs == positionUs) { "unexpected Direct DSD post-reset arm" }
        playingArmPositionUs = null
    }

    fun clear() {
        freshPumpPositionUs = null
        playingArmPositionUs = null
    }
}

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
    private val monotonicClock: DirectDsdMonotonicClock = DirectDsdSystemMonotonicClock,
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val drainLoop = DirectDsdRenderDrainLoop(MAX_SOURCE_READS_PER_RENDER)
    private val timing = DirectDsdRenderTimingAccumulator(monotonicClock)
    private val positionResetState = DirectDsdPositionResetState()
    private val rendererGeneration = DirectDsdSeekDiscontinuityCoordinator.newRendererGeneration()
    private var nextSessionGeneration = 0L
    private var activeSessionGeneration: DirectDsdSessionGeneration? = null
    private var currentFormat: Format? = null
    private var pump: DirectDsdRendererPump? = null
    private var inputEosSeen = false
    private var ended = false
    private var pauseGapActive = false
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
        if (ended || pauseGapActive) return
        try {
            renderInvocationCount++
            val callbackStartNs = timing.onCallbackStart()
            val drain = drainLoop.drain { renderDrainStep(positionUs) }
            timing.onDrainComplete(callbackStartNs, drain)
            if (drain.budgetExhausted) drainBudgetExhaustionCount++
            if (
                renderInvocationCount == 1L ||
                elapsedRealtimeUs - lastDrainMilestoneElapsedRealtimeUs >= DRAIN_MILESTONE_INTERVAL_US
            ) {
                lastDrainMilestoneElapsedRealtimeUs = elapsedRealtimeUs
                val t = timing.snapshotAndReset()
                milestone(
                    "renderer=drain callbacks=$renderInvocationCount packets=$sampleCount " +
                        "budgetExhausted=$drainBudgetExhaustionCount readBudget=$MAX_SOURCE_READS_PER_RENDER " +
                        "lastReads=${drain.sourceReadCount} lastPackets=${drain.packetReadCount} " +
                        "pending=${pump?.snapshot()?.pendingCanonicalBytes ?: 0} " +
                        "timingWallNs=${t.wallNs} timingCallbacks=${t.callbacks} " +
                        "interArrivalMinNs=${t.interArrivalMinNs} interArrivalMaxNs=${t.interArrivalMaxNs} " +
                        "timingReads=${t.sourceReads} timingPackets=${t.packets} timingBudget=${t.budgetExhausted} " +
                        "termPending=${t.pendingTailYields} termNothing=${t.nothingReadYields} " +
                        "termTerminal=${t.terminalStops} termError=${t.errors} " +
                        "busyTotalNs=${t.drainBusyTotalNs} busyMaxNs=${t.drainBusyMaxNs} " +
                        "readTotalNs=${t.readSourceTotalNs} readMaxNs=${t.readSourceMaxNs} " +
                        "packetTotalNs=${t.packetStageTotalNs} packetMaxNs=${t.packetStageMaxNs} " +
                        "pumpTotalNs=${t.pumpTotalNs} pumpMaxNs=${t.pumpMaxNs}",
                )
            }
        } catch (error: ExoPlaybackException) {
            timing.onTermination(DirectDsdDrainTermination.ERROR)
            throw error
        } catch (error: Throwable) {
            timing.onTermination(DirectDsdDrainTermination.ERROR)
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
            timing.measurePump { activePump.pump() }
            maybeArmAfterPlayingReset(activePump, sampleTimeUs = null)
            if (!activePump.canAcceptPacket()) {
                timing.onTermination(DirectDsdDrainTermination.PENDING_TAIL)
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
                timing.onTermination(DirectDsdDrainTermination.TERMINAL)
                milestone("renderer=eos positionUs=$positionUs")
                return DirectDsdDrainStepResult(false, false, DirectDsdDrainAction.TERMINAL)
            }
            timing.onTermination(DirectDsdDrainTermination.PENDING_TAIL)
            return DirectDsdDrainStepResult(false, false, DirectDsdDrainAction.YIELD)
        }

        inputBuffer.clear()
        val holder = formatHolder
        return when (timing.measureReadSource { readSource(holder, inputBuffer, 0) }) {
            C.RESULT_NOTHING_READ -> {
                timing.onTermination(DirectDsdDrainTermination.NOTHING_READ)
                DirectDsdDrainStepResult(
                    sourceReadPerformed = true,
                    packetRead = false,
                    action = DirectDsdDrainAction.YIELD,
                )
            }
            C.RESULT_FORMAT_READ -> {
                val format = checkNotNull(holder.format)
                val facts = DirectDsdMedia3FormatPolicy.factsOrNull(format)
                    ?: error("Direct DSD renderer received non-authoritative DSF format")
                currentFormat = format
                openPumpIfNeeded(facts)
                DirectDsdDrainStepResult(true, false, DirectDsdDrainAction.CONTINUE)
            }
            C.RESULT_BUFFER_READ -> {
                if (inputBuffer.isEndOfStream) {
                    inputEosSeen = true
                    milestone("renderer=input-eos positionUs=$positionUs")
                    DirectDsdDrainStepResult(true, false, DirectDsdDrainAction.CONTINUE)
                } else {
                    val active = pump ?: openPumpIfNeeded(authoritativeCurrentFacts())
                    val packet = timing.measurePacketStage {
                        inputBuffer.flip()
                        val data = checkNotNull(inputBuffer.data)
                        ByteArray(data.remaining()).also { bytes ->
                            data.get(bytes)
                            active.offerExtractorPacket(bytes, inputBuffer.timeUs)
                        }
                    }
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
                    timing.measurePump { active.pump() }
                    maybeArmAfterPlayingReset(active, sampleTimeUs = inputBuffer.timeUs)
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

    private fun authoritativeCurrentFacts(): DsfExtractorPacketFacts {
        val format = checkNotNull(currentFormat) { "DSD sample arrived without retained authoritative format" }
        return DirectDsdMedia3FormatPolicy.factsOrNull(format)
            ?: error("Retained Direct DSD format is no longer authoritative")
    }

    private fun openPumpIfNeeded(facts: DsfExtractorPacketFacts): DirectDsdRendererPump {
        pump?.let { return it }
        val session = sessionFactory.open(facts)
        val freshPump = DirectDsdRendererPump(facts, session)
        val sessionGeneration = DirectDsdSessionGeneration(
            rendererGeneration = rendererGeneration,
            sessionGeneration = ++nextSessionGeneration,
        )
        return freshPump.also {
            pump = freshPump
            activeSessionGeneration = sessionGeneration
            DirectDsdSeekDiscontinuityCoordinator.activateSession(sessionGeneration)
            check(
                DirectDsdTeardownQuiescenceCoordinator.register(sessionGeneration) {
                    freshPump.quiescePauseGapForOutputRebuild()
                },
            ) { "stale Direct DSD teardown registration $sessionGeneration" }
            milestone(
                "renderer=claimed sourceRate=${facts.sourceSampleRateHz} " +
                    "channels=${facts.channelCount} bitOrder=${facts.sourceBitOrder} " +
                    "rendererGeneration=$rendererGeneration sessionGeneration=${sessionGeneration.sessionGeneration}",
            )
            positionResetState.consumeFreshPumpPositionUs()?.let { resetPositionUs ->
                milestone("renderer=post-reset-open positionUs=$resetPositionUs")
            }
        }
    }

    private fun maybeArmAfterPlayingReset(active: DirectDsdRendererPump, sampleTimeUs: Long?): Boolean {
        val resetPositionUs = positionResetState.postResetArmPositionUsIfReady(
            startupReady = active.isStartupPrefillReady(),
            playbackArmed = active.isPlaybackArmed(),
        ) ?: return false
        active.armPlayback()
        check(active.isPlaybackArmed())
        positionResetState.markPostResetArmed(resetPositionUs)
        milestone(
            "renderer=post-reset-arm positionUs=$resetPositionUs " +
                "sampleTimeUs=${sampleTimeUs ?: -1L} armed=true",
        )
        return true
    }

    override fun isReady(): Boolean =
        !ended && (pump?.isStartupPrefillReady() == true) &&
            ((pump?.snapshot()?.pendingCanonicalBytes ?: 0) > 0 || isSourceReady() || inputEosSeen)

    override fun isEnded(): Boolean = ended

    override fun onStarted() {
        try {
            val active = checkNotNull(pump) { "Direct DSD renderer started before transport prepare" }
            if (active.isPlaybackArmed()) {
                if (pauseGapActive) {
                    active.stopPauseGapLiveness()
                    check(active.isPlaybackArmed())
                    pauseGapActive = false
                    milestone(
                        "renderer=started armed=true resumed=true samples=$sampleCount " +
                            "lastSampleTimeUs=$lastSampleMilestoneTimeUs",
                    )
                } else {
                    milestone(
                        "renderer=started armed=true resumed=false alreadyArmed=true samples=$sampleCount " +
                            "lastSampleTimeUs=$lastSampleMilestoneTimeUs",
                    )
                }
            } else {
                check(active.isStartupPrefillReady()) { "Direct DSD renderer started before startup prefill" }
                val armedFromPlayingReset = maybeArmAfterPlayingReset(active, sampleTimeUs = null)
                if (!armedFromPlayingReset) {
                    active.armPlayback()
                    check(active.isPlaybackArmed())
                }
                milestone("renderer=started armed=true resumed=false postReset=$armedFromPlayingReset")
            }
        } catch (error: Throwable) {
            throw createRendererException(
                error,
                currentFormat,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        }
    }

    override fun onStopped() {
        try {
            val active = pump
            val wasArmed = active?.isPlaybackArmed() == true
            val sessionGeneration = activeSessionGeneration
            val seekDispatch = sessionGeneration?.let(DirectDsdSeekDiscontinuityCoordinator::observeStopped)
            val seekPending = seekDispatch != null
            val gapStarted = wasArmed && !ended && !seekPending
            if (gapStarted) {
                pauseGapActive = true
                checkNotNull(active).startPauseGapLiveness()
            }
            milestone(
                "renderer=stopped armed=$wasArmed seekPending=$seekPending gapStarted=$gapStarted " +
                    "seekRequest=${seekDispatch?.requestId ?: -1L} samples=$sampleCount " +
                    "lastSampleTimeUs=$lastSampleMilestoneTimeUs",
            )
        } catch (error: Throwable) {
            throw createRendererException(
                error,
                currentFormat,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isPlaying: Boolean) {
        val oldPump = pump
        val hadPump = oldPump != null
        val oldPendingBytes = oldPump?.snapshot()?.pendingCanonicalBytes ?: 0
        val oldArmed = oldPump?.isPlaybackArmed() == true
        val streamOffsetUs = getStreamOffsetUs()
        val sourcePositionUs = positionUs - streamOffsetUs
        val seekDecision = activeSessionGeneration?.let { sessionGeneration ->
            DirectDsdSeekDiscontinuityCoordinator.consumePositionReset(
                session = sessionGeneration,
                sourcePositionUs = sourcePositionUs,
                isPlaying = isPlaying,
            )
        } ?: DirectDsdSeekResetDecision(DirectDsdSeekResetMatch.NONE)
        val matchedPlayingSeek = seekDecision.match == DirectDsdSeekResetMatch.MATCHED
        milestone(
            "renderer=position-reset positionUs=$positionUs joining=$joining isPlaying=$isPlaying " +
                "hadPump=$hadPump oldPending=$oldPendingBytes oldArmed=$oldArmed " +
                "streamOffsetUs=$streamOffsetUs sourcePositionUs=$sourcePositionUs " +
                "seekMatch=${seekDecision.match} seekRequest=${seekDecision.requestId ?: -1L} " +
                "seekTargetUs=${seekDecision.targetSourcePositionUs ?: -1L}",
        )
        positionResetState.onPositionReset(
            positionUs,
            hadPump = hadPump,
            isPlaying = isPlaying && matchedPlayingSeek,
        )
        closePump("position-reset:$positionUs")
        inputEosSeen = false
        ended = false
        pauseGapActive = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun onDisabled() {
        closePump("disabled")
        positionResetState.clear()
        currentFormat = null
        inputEosSeen = false
        ended = false
        pauseGapActive = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun onReset() {
        closePump("reset")
        positionResetState.clear()
        currentFormat = null
        inputEosSeen = false
        ended = false
        pauseGapActive = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    private fun closePump(reason: String) {
        val closingPump = pump
        val closingGeneration = activeSessionGeneration
        pump = null
        activeSessionGeneration = null
        closingGeneration?.let { generation ->
            DirectDsdTeardownQuiescenceCoordinator.unregister(generation)
            DirectDsdSeekDiscontinuityCoordinator.deactivateSession(generation)
        }
        try {
            closingPump?.close()
        } finally {
            milestone("renderer=close reason=$reason")
        }
    }

    companion object {
        const val NAME = "MicaDirectDsdDoP"
        // Worst supported prototype demand is DSD128 stereo. The policy derives a bounded budget
        // with explicit margin at the 35 callbacks/s qualification floor instead of assuming ~50 Hz.
        internal val MAX_SOURCE_READS_PER_RENDER = DirectDsdRenderDrainCapacityPolicy.sourceReadsPerCallback(
            sourceSampleRateHz = 5_644_800,
            channelCount = 2,
        )
        private const val SAMPLE_MILESTONE_INTERVAL_US = 250_000L
        private const val DRAIN_MILESTONE_INTERVAL_US = 250_000L
    }
}
