package com.mica.music.media.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
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

private data class PendingFreshDirectDestination(
    val epochId: Long,
    val format: Format,
    val requiresStartedAuthority: Boolean,
    val startedAuthorityObserved: Boolean,
)

/**
 * Raw DSF renderer used only by the QA Direct-DSD prototype gate.
 * It consumes extractor packets before FFmpeg and owns no PCM sink or decoder.
 */
class DirectDsdMedia3Renderer(
    private val sessionFactory: DirectDsdTransportSessionFactory,
    private val milestone: (String) -> Unit = {},
    private val monotonicClock: DirectDsdMonotonicClock = DirectDsdSystemMonotonicClock,
    private val transitionCoordinator: DirectDsdTrackTransitionCoordinator? = null,
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
    private var nextFreshTransitionEpochId = 0L
    private var pendingFreshDirectDestination: PendingFreshDirectDestination? = null

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
        pendingFreshDirectDestination?.let { pending ->
            if (pending.requiresStartedAuthority && !pending.startedAuthorityObserved) return
        }
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
                val pending = pendingFreshDirectDestination
                if (pending == null) {
                    currentFormat = format
                } else {
                    val pendingFacts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(pending.format))
                    check(pendingFacts == facts) {
                        "Direct DSD format read does not match pending destination epoch"
                    }
                }
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
                    maybeArmAfterFreshTrackTransition(active, sampleTimeUs = inputBuffer.timeUs)
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
            pendingFreshDirectDestination?.let { pending ->
                val pendingFacts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(pending.format))
                check(pendingFacts == facts) { "fresh Direct runtime opened for stale destination epoch" }
                milestone(
                    "trackTransition=FRESH_DIRECT_RUNTIME_CREATED sourceRate=${facts.sourceSampleRateHz} " +
                        "sessionGeneration=${sessionGeneration.sessionGeneration} epoch=${pending.epochId}",
                )
            }
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

    private fun maybeArmAfterFreshTrackTransition(
        active: DirectDsdRendererPump,
        sampleTimeUs: Long?,
    ): Boolean {
        val pending = pendingFreshDirectDestination ?: return false
        if (
            state != Renderer.STATE_STARTED ||
            (pending.requiresStartedAuthority && !pending.startedAuthorityObserved) ||
            !active.isStartupPrefillReady() ||
            active.isPlaybackArmed()
        ) {
            return false
        }
        val pendingFacts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(pending.format))
        check(active.facts == pendingFacts) { "fresh Direct arm for stale destination epoch" }
        active.armPlayback()
        check(active.isPlaybackArmed())
        transitionCoordinator?.beforeDirectAccept(isPlaying = true)
        pendingFreshDirectDestination = null
        milestone(
            "trackTransition=FRESH_RUNTIME_ARMED sampleTimeUs=${sampleTimeUs ?: -1L} " +
                "armed=true epoch=${pending.epochId}",
        )
        milestone("trackTransition=QUALIFIED_STARTED_PREFILL_ARM_COMPLETE")
        milestone("trackTransition=NEW_SOURCE_ACCEPT_ALLOWED family=DOP retained=false")
        return true
    }

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val newFormat = formats.firstOrNull { DirectDsdMedia3FormatPolicy.factsOrNull(it) != null } ?: return
        val newFacts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(newFormat))
        val active = pump
        val playing = state == Renderer.STATE_STARTED
        pendingFreshDirectDestination?.let { pending ->
            val pendingPump = pump
            if (pendingPump != null) {
                check(!pendingPump.isPlaybackArmed()) { "accepted Direct runtime still marked pending" }
                closePump("track-pending-destination-replaced")
                milestone("trackTransition=PENDING_RUNTIME_RETIRED epoch=${pending.epochId}")
            }
            bindPendingFreshDestination(newFormat, requiresStartedAuthority = !playing, replacement = true)
            return
        }

        transitionCoordinator?.completePcmReleaseForDirectHandoff()
        val transitionMode = DirectDsdTrackTransitionPolicy.decide(active?.facts, newFacts, playing)
        if (transitionMode == DirectDsdTrackTransitionMode.INITIAL) {
            val familySnapshot = transitionCoordinator?.snapshot()
            val pcmHandoff = familySnapshot?.lastReleasedFamily == DirectDsdTrackTransportFamily.PCM
            val pcmHandoffWasPaused = pcmHandoff && familySnapshot?.lastReleasedWasPaused == true
            if (pcmHandoffWasPaused || (!playing && transitionCoordinator?.shouldDeferDirectUntilResume() == true)) {
                bindPendingFreshDestination(newFormat, requiresStartedAuthority = true, replacement = false)
                milestone("trackTransition=PENDING_DESTINATION_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
                milestone("trackTransition=FRESH_DIRECT_DEFERRED family=PCM_TO_DOP")
                milestone(
                    "trackTransition=DEFERRED_PAUSED_FRESH_RUNTIME oldRate=-1 " +
                        "newRate=${newFacts.sourceSampleRateHz} acceptAllowed=false family=PCM_TO_DOP",
                )
                return
            }
            if (pcmHandoff) {
                bindPendingFreshDestination(newFormat, requiresStartedAuthority = false, replacement = false)
                milestone("trackTransition=NEW_DSD_SOURCE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
                return
            }
            transitionCoordinator?.beforeDirectAccept(playing)
            currentFormat = newFormat
            milestone("trackTransition=NEW_SOURCE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
            milestone("trackTransition=NEW_SOURCE_ACCEPT_ALLOWED family=DOP")
            return
        }

        val activePump = checkNotNull(active)
        if (transitionMode == DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN) {
            val wasPausedGap = pauseGapActive
            if (wasPausedGap) {
                activePump.stopPauseGapLiveness()
                pauseGapActive = false
                milestone("trackTransition=PAUSE_GAP_STOPPED")
            }
            milestone(
                "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=$playing " +
                    "sourceRate=${activePump.facts.sourceSampleRateHz} newRate=${newFacts.sourceSampleRateHz} " +
                    "startPositionUs=$startPositionUs offsetUs=$offsetUs",
            )
            val (discardedCanonicalBytes, result) = activePump.transitionRetainedSource(newFacts)
            check(result.feederPendingZero && result.sourceResetApplied)
            currentFormat = newFormat
            resetTrackSourceCounters()
            milestone(
                "trackTransition=NEW_SOURCE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz} " +
                    "discardedRendererCanonical=$discardedCanonicalBytes",
            )
            if (wasPausedGap) {
                activePump.startPauseGapLiveness()
                pauseGapActive = true
                milestone("trackTransition=PAUSE_GAP_REESTABLISHED_AFTER_BOUNDARY")
            } else {
                milestone("trackTransition=NEW_SOURCE_ACCEPT_ALLOWED family=DOP retained=true")
            }
            return
        }

        if (transitionMode == DirectDsdTrackTransitionMode.DEFERRED_PAUSED_FRESH_RUNTIME) {
            if (pauseGapActive) {
                activePump.stopPauseGapLiveness()
                pauseGapActive = false
                milestone("trackTransition=PAUSE_GAP_STOPPED")
            }
            milestone(
                "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=false " +
                    "sourceRate=${activePump.facts.sourceSampleRateHz} newRate=${newFacts.sourceSampleRateHz}",
            )
            activePump.prepareFreshTrackTransition(DoPCarrierSessionReset.RECONFIGURE)
            closePump("track-reconfigure-paused-deferred")
            milestone("trackTransition=OLD_DIRECT_RUNTIME_RELEASED")
            transitionCoordinator?.onDirectReleased(wasPaused = true)
            bindPendingFreshDestination(newFormat, requiresStartedAuthority = true, replacement = false)
            milestone("trackTransition=PENDING_DESTINATION_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
            milestone("trackTransition=FRESH_DIRECT_DEFERRED")
            milestone(
                "trackTransition=DEFERRED_PAUSED_FRESH_RUNTIME oldRate=${activePump.facts.sourceSampleRateHz} " +
                    "newRate=${newFacts.sourceSampleRateHz} acceptAllowed=false",
            )
            return
        }

        milestone(
            "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=true " +
                "sourceRate=${activePump.facts.sourceSampleRateHz} newRate=${newFacts.sourceSampleRateHz}",
        )
        activePump.prepareFreshTrackTransition(DoPCarrierSessionReset.RECONFIGURE)
        closePump("track-reconfigure")
        milestone("trackTransition=OLD_DIRECT_RUNTIME_RELEASED")
        transitionCoordinator?.onDirectReleased(wasPaused = false)
        bindPendingFreshDestination(newFormat, requiresStartedAuthority = false, replacement = false)
        milestone("trackTransition=NEW_RATE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
    }

    private fun bindPendingFreshDestination(
        format: Format,
        requiresStartedAuthority: Boolean,
        replacement: Boolean,
    ) {
        val previous = pendingFreshDirectDestination
        val facts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(format))
        val next = PendingFreshDirectDestination(
            epochId = ++nextFreshTransitionEpochId,
            format = format,
            requiresStartedAuthority = requiresStartedAuthority,
            startedAuthorityObserved = false,
        )
        currentFormat = format
        pendingFreshDirectDestination = next
        resetTrackSourceCounters()
        milestone(
            "trackTransition=PENDING_DESTINATION_${if (replacement) "REPLACED" else "BOUND"} " +
                "epoch=${next.epochId} previousEpoch=${previous?.epochId ?: -1L} " +
                "sourceRate=${facts.sourceSampleRateHz} requiresStarted=$requiresStartedAuthority",
        )
    }

    private fun resetTrackSourceCounters() {
        inputEosSeen = false
        ended = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun isReady(): Boolean {
        pendingFreshDirectDestination?.let { pending ->
            if (pending.requiresStartedAuthority && !pending.startedAuthorityObserved) return true
        }
        return !ended && (pump?.isStartupPrefillReady() == true) &&
            ((pump?.snapshot()?.pendingCanonicalBytes ?: 0) > 0 || isSourceReady() || inputEosSeen)
    }

    override fun isEnded(): Boolean = ended

    override fun onStarted() {
        try {
            transitionCoordinator?.onDirectPlayState(paused = false)
            pendingFreshDirectDestination?.let { pending ->
                if (pending.requiresStartedAuthority && !pending.startedAuthorityObserved) {
                    val pendingFacts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(pending.format))
                    check(currentFormat === pending.format) {
                        "deferred Direct DSD destination format changed before resume"
                    }
                    val currentFacts = authoritativeCurrentFacts()
                    check(currentFacts == pendingFacts) {
                        "deferred Direct DSD destination facts changed before resume"
                    }
                    pendingFreshDirectDestination = pending.copy(startedAuthorityObserved = true)
                    milestone("trackTransition=RENDERER_STARTED_AUTHORITY_OBSERVED epoch=${pending.epochId}")
                    milestone(
                        "trackTransition=DESTINATION_CURRENTNESS_REVALIDATED sourceRate=${pendingFacts.sourceSampleRateHz}",
                    )
                    return
                }
            }
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
                    val armedFromTrackTransition = maybeArmAfterFreshTrackTransition(active, sampleTimeUs = null)
                    if (!armedFromTrackTransition) {
                        active.armPlayback()
                        check(active.isPlaybackArmed())
                    }
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
            transitionCoordinator?.onDirectPlayState(paused = true)
            pendingFreshDirectDestination?.let { pending ->
                val deferredPump = pump
                if (deferredPump != null) {
                    check(!deferredPump.isPlaybackArmed()) { "accepted Direct runtime still marked pending" }
                    closePump("track-deferred-repaused")
                }
                pendingFreshDirectDestination = pending.copy(
                    requiresStartedAuthority = true,
                    startedAuthorityObserved = false,
                )
                milestone(
                    "trackTransition=DEFERRED_PAUSED_FRESH_RUNTIME reasserted=true " +
                        "acceptAllowed=false epoch=${pending.epochId}",
                )
                return
            }
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
        val wasPausedGap = pauseGapActive
        closePump("disabled")
        transitionCoordinator?.onDirectReleased(wasPausedGap)
        positionResetState.clear()
        pendingFreshDirectDestination = null
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
        val wasPausedGap = pauseGapActive
        closePump("reset")
        transitionCoordinator?.onDirectReleased(wasPausedGap)
        positionResetState.clear()
        pendingFreshDirectDestination = null
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
