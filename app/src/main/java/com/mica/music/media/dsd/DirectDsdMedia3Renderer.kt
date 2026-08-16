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
import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.DirectStagePermit
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.PlaybackIntent
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.protocol.SideEffectReceipt
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapter
import com.mica.music.media.usb.shadow.UsbExclusiveShadowMedia3Facts

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
    val navigationRequestId: Long? = null,
    val navigationFacts: ManualNavigationDestinationFacts? = null,
    val navigationPlaybackIdentity: ManualNavigationPlaybackIdentity? = null,
)

private data class PendingManualNavigationBoundary(
    val requestId: Long,
    val format: Format,
    val playbackIdentity: ManualNavigationPlaybackIdentity,
)

/**
 * Raw DSF renderer used only by the QA Direct-DSD prototype gate.
 * It consumes extractor packets before FFmpeg and owns no PCM sink or decoder.
 */
class DirectDsdMedia3Renderer @JvmOverloads internal constructor(
    private val sessionFactory: DirectDsdTransportSessionFactory,
    private val playbackAdapter: UsbExclusivePlaybackAdapter,
    private val milestone: (String) -> Unit = {},
    private val monotonicClock: DirectDsdMonotonicClock = DirectDsdSystemMonotonicClock,
    private val transitionCoordinator: DirectDsdTrackTransitionCoordinator = DirectDsdTrackTransitionCoordinator(),
    private val manualNavigationTransitionBridge: ManualNavigationTransitionBridge = ManualNavigationTransitionBridge(),
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val drainLoop = DirectDsdRenderDrainLoop(MAX_SOURCE_READS_PER_RENDER)
    private val timing = DirectDsdRenderTimingAccumulator(monotonicClock)
    private val positionResetState = DirectDsdPositionResetState()
    private val rendererGeneration = DirectDsdSeekDiscontinuityCoordinator.newRendererGeneration()
    private var nextSessionGeneration = 0L
    private var activeSessionGeneration: DirectDsdSessionGeneration? = null
    private var currentFormat: Format? = null
    private var shadowOccurrence: PlaybackOccurrence? = null
    private var shadowRuntimeOccurrence: PlaybackOccurrence? = null
    private var shadowRuntimeIdentity: RuntimeIdentity? = null
    private var shadowPrefillReported = false
    private var shadowArmReported = false
    private var shadowSourceAcceptReported = false
    private var directPrefillPermit: DirectStagePermit? = null
    private var directAuthorityAccepted = false
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
    private var pendingManualNavigationBoundary: PendingManualNavigationBoundary? = null
    private var navigationRetirementRequestedPaused = false

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
        if (!resumePendingManualNavigationBoundary()) return
        if (!refreshPendingNavigationBinding()) return
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
            val write = timing.measurePump { pumpWithProtocol(activePump) }
            if (write == null) {
                timing.onTermination(DirectDsdDrainTermination.PENDING_TAIL)
                return DirectDsdDrainStepResult(false, false, DirectDsdDrainAction.YIELD)
            }
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
                    val write = timing.measurePump { pumpWithProtocol(active) }
                    if (write == null) {
                        return DirectDsdDrainStepResult(true, false, DirectDsdDrainAction.YIELD)
                    }
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

    /**
     * Gates every real Direct transport write. Before SOURCE_ACCEPT the stage permit covers the
     * startup prefill; afterwards the committed protocol lease covers content writes.
     */
    private fun pumpWithProtocol(active: DirectDsdRendererPump): DirectDsdTransportWriteResult? {
        val adapter = playbackAdapter
        val runtime = shadowRuntimeIdentity ?: return null
        if (!directAuthorityAccepted) {
            if (directPrefillPermit == null) {
                directPrefillPermit = adapter.prepareDirectStage(
                    shadowOccurrence,
                    DirectStage.PREFILL,
                    runtime,
                ) ?: return null
            }
            val permit = checkNotNull(directPrefillPermit)
            val result = try {
                active.pump()
            } catch (error: Throwable) {
                failDirectStageAfterSideEffect(
                    adapter = adapter,
                    permit = permit,
                    active = active,
                    original = error,
                )
            }
            observeShadowPrefillIfReady(active)
            return result
        }
        val occurrence = shadowOccurrence ?: return null
        val lease = adapter.tryEnterWrite(occurrence, com.mica.music.media.usb.protocol.WriteKind.DOP_CONTENT)
            ?: return null
        return try {
            active.pump()
        } finally {
            lease.exit()
        }
    }

    /** Gates a retained DOP source reset with the protocol's exact old/new occurrence permit. */
    private fun transitionRetainedSourceWithProtocol(
        active: DirectDsdRendererPump,
        newFacts: DsfExtractorPacketFacts,
    ): Pair<Int, DirectDsdRetainedSourceTransitionResult> {
        val adapter = playbackAdapter
        val runtime = shadowRuntimeIdentity ?: error("retained Direct transition without runtime identity")
        val permit = adapter.prepareRetainedDirectHandoff(
            shadowRuntimeOccurrence,
            shadowOccurrence,
            runtime,
        ) ?: error("Direct protocol denied retained source handoff before carrier reset")
        val resource = ResourceIdentity("$runtime:retained-${permit.targetOccurrence.windowSequenceNumber}")
        val result = try {
            active.transitionRetainedSource(newFacts)
        } catch (error: Throwable) {
            failRetainedDirectHandoffAfterSideEffect(
                adapter = adapter,
                permit = permit,
                active = active,
                resource = resource,
                original = error,
            )
        }
        val disposition = adapter.commitRetainedDirectHandoff(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                resource,
                "direct-retained-source-reset",
                runtime,
            ),
        )
        check(disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused) {
            "Direct protocol rejected retained handoff receipt: $disposition"
        }
        // The next retained handoff must prove ownership from this committed source.
        shadowRuntimeOccurrence = shadowOccurrence
        return result
    }

    private fun refreshPendingNavigationBinding(): Boolean {
        val pending = pendingFreshDirectDestination ?: return true
        return protocolDestinationCurrent() || pending.navigationRequestId == null
    }

    private fun resumePendingManualNavigationBoundary(): Boolean {
        val pendingBoundary = pendingManualNavigationBoundary ?: return true
        if (!protocolDestinationCurrent()) return false
        pendingManualNavigationBoundary = null
        return true
    }

    private fun applyBoundManualNavigationDestination(
        newFormat: Format,
        newFacts: DsfExtractorPacketFacts,
        navigationEpoch: ManualNavigationTransitionEpoch,
        playbackIdentity: ManualNavigationPlaybackIdentity,
    ) {
        val active = pump
        val navigationPlaying = navigationEpoch.requestedPlaying
        if (active != null) {
            val navigationMode = DirectDsdTrackTransitionPolicy.decide(
                active.facts,
                newFacts,
                navigationPlaying,
            )
            if (navigationMode == DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN) {
                milestone(
                    "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=$navigationPlaying " +
                        "sourceRate=${active.facts.sourceSampleRateHz} newRate=${newFacts.sourceSampleRateHz} " +
                        "navigationRequest=${navigationEpoch.requestId}",
                )
                val (discardedCanonicalBytes, result) = transitionRetainedSourceWithProtocol(active, newFacts)
                check(result.feederPendingZero && result.sourceResetApplied)
                currentFormat = newFormat
                resetTrackSourceCounters()
                milestone(
                    "trackTransition=NEW_SOURCE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz} " +
                        "discardedRendererCanonical=$discardedCanonicalBytes navigationRequest=${navigationEpoch.requestId}",
                )
                if (!navigationPlaying) {
                    active.startPauseGapLiveness()
                    pauseGapActive = true
                    milestone("trackTransition=PAUSE_GAP_REESTABLISHED_AFTER_BOUNDARY")
                }
                completeNavigationProjection(navigationEpoch.requestId)
                milestone("trackTransition=NEW_SOURCE_ACCEPT_ALLOWED family=DOP retained=true")
                return
            }

            milestone(
                "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=$navigationPlaying " +
                    "sourceRate=${active.facts.sourceSampleRateHz} newRate=${newFacts.sourceSampleRateHz} " +
                    "navigationRequest=${navigationEpoch.requestId}",
            )
            active.prepareFreshTrackTransition(DoPCarrierSessionReset.RECONFIGURE)
            closePump("manual-navigation-fresh")
            milestone("trackTransition=OLD_DIRECT_RUNTIME_RELEASED navigationRequest=${navigationEpoch.requestId}")
        }
        bindPendingFreshDestination(
            newFormat,
            requiresStartedAuthority = !navigationPlaying,
            replacement = false,
            navigationEpoch = navigationEpoch,
            navigationPlaybackIdentity = playbackIdentity,
        )
        milestone(
            "trackTransition=MANUAL_NAVIGATION_DESTINATION_PENDING request=${navigationEpoch.requestId} " +
                "playing=$navigationPlaying family=DOP",
        )
    }

    private fun openPumpIfNeeded(facts: DsfExtractorPacketFacts): DirectDsdRendererPump {
        pump?.let { return it }
        val sessionGeneration = DirectDsdSessionGeneration(
            rendererGeneration = rendererGeneration,
            sessionGeneration = ++nextSessionGeneration,
        )
        val runtimeIdentity = RuntimeIdentity(
            "direct:$rendererGeneration:${sessionGeneration.sessionGeneration}",
        )
        val createPermit = playbackAdapter.prepareDirectStage(
            shadowOccurrence,
            DirectStage.CREATE_RUNTIME,
            runtimeIdentity,
        ) ?: error("Direct protocol denied CREATE_RUNTIME before sessionFactory.open")
        val session = try {
            sessionFactory.open(facts)
        } catch (error: Throwable) {
            try {
                playbackAdapter.commitDirectStage(
                    createPermit,
                    SideEffectReceipt.NotStarted(createPermit.activationId),
                )
            } catch (commitError: Throwable) {
                if (commitError !== error) error.addSuppressed(commitError)
            }
            throw error
        }
        val freshPump = try {
            DirectDsdRendererPump(facts, session)
        } catch (error: Throwable) {
            val sessionClosed = try {
                session.close()
                true
            } catch (cleanupError: Throwable) {
                if (cleanupError !== error) error.addSuppressed(cleanupError)
                false
            }
            failDirectStageAfterSideEffectWithoutPump(
                adapter = playbackAdapter,
                permit = createPermit,
                resource = ResourceIdentity("$runtimeIdentity:create"),
                cleanupCompleted = sessionClosed,
                original = error,
            )
        }
        val disposition = playbackAdapter.commitDirectStage(
            createPermit,
            SideEffectReceipt.Completed(
                createPermit.activationId,
                ResourceIdentity("$runtimeIdentity:create"),
                "direct-runtime-created",
                runtimeIdentity,
            ),
        )
        val resolved = disposition?.let {
            resolveDirectCommit(playbackAdapter, createPermit, it, freshPump)
        }
        check(resolved == null || resolved == DirectCommitOutcome.PROGRESSED) {
            "Direct protocol rejected CREATE_RUNTIME receipt: $resolved"
        }
        return freshPump.also {
            pump = freshPump
            activeSessionGeneration = sessionGeneration
            shadowRuntimeOccurrence = shadowOccurrence
            shadowRuntimeIdentity = runtimeIdentity
            shadowPrefillReported = false
            shadowArmReported = false
            shadowSourceAcceptReported = false
            directPrefillPermit = null
            directAuthorityAccepted = false
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

    private fun observeShadowPrefillIfReady(active: DirectDsdRendererPump): Boolean {
        if (shadowPrefillReported) return true
        if (!active.isStartupPrefillReady()) return false
        val runtime = shadowRuntimeIdentity ?: return false
        val adapter = playbackAdapter
        val permit = directPrefillPermit ?: adapter.prepareDirectStage(
            shadowOccurrence,
            DirectStage.PREFILL,
            runtime,
        ) ?: error("Direct protocol denied PREFILL completion")
        val disposition = adapter.commitDirectStage(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                ResourceIdentity("$runtime:prefill"),
                "direct-startup-prefill-ready",
                runtime,
            ),
        )
        val resolved = disposition?.let {
            resolveDirectCommit(adapter, permit, it, active)
        }
        when (resolved) {
            null,
            DirectCommitOutcome.PROGRESSED,
            -> {
                shadowPrefillReported = true
            }
            DirectCommitOutcome.RETRY -> {
                directPrefillPermit = null
                shadowPrefillReported = false
                return false
            }
            DirectCommitOutcome.REJECTED -> error("Direct protocol rejected PREFILL receipt: $disposition")
        }
        directPrefillPermit = null
        return true
    }

    private fun observeShadowArmAndSourceAccept(active: DirectDsdRendererPump): Boolean {
        val runtime = shadowRuntimeIdentity ?: return false
        if (shadowArmReported && shadowSourceAcceptReported) return true
        val adapter = playbackAdapter
        if (!shadowArmReported) {
            val armPermit = adapter.prepareDirectStage(
                shadowOccurrence,
                DirectStage.ARM,
                runtime,
            ) ?: error("Direct protocol denied ARM before transport arm")
            try {
                active.armPlayback()
                check(active.isPlaybackArmed())
            } catch (error: Throwable) {
                failDirectStageAfterSideEffect(
                    adapter = adapter,
                    permit = armPermit,
                    active = active,
                    original = error,
                )
            }
            val armDisposition = adapter.commitDirectStage(
                armPermit,
                SideEffectReceipt.Completed(
                    armPermit.activationId,
                    ResourceIdentity("$runtime:arm"),
                    "direct-runtime-armed",
                    runtime,
                ),
            )
            val resolvedArmDisposition = armDisposition?.let {
                resolveDirectCommit(adapter, armPermit, it, active)
            }
            when (resolvedArmDisposition) {
                null,
                DirectCommitOutcome.PROGRESSED,
                -> Unit
                DirectCommitOutcome.RETRY -> {
                    shadowArmReported = false
                    return false
                }
                DirectCommitOutcome.REJECTED -> error("Direct protocol rejected ARM receipt: $armDisposition")
            }
            shadowArmReported = true
        }

        val sourcePermit = adapter.prepareDirectStage(
            shadowOccurrence,
            DirectStage.SOURCE_ACCEPT,
            runtime,
        ) ?: error("Direct protocol denied SOURCE_ACCEPT after ARM")
        val sourceDisposition = adapter.commitDirectStage(
            sourcePermit,
            SideEffectReceipt.Completed(
                sourcePermit.activationId,
                ResourceIdentity("$runtime:source-accept"),
                "direct-source-accepted",
                runtime,
            ),
        )
        val resolvedSourceDisposition = sourceDisposition?.let {
            resolveDirectCommit(adapter, sourcePermit, it, active)
        }
        when (resolvedSourceDisposition) {
            null,
            DirectCommitOutcome.PROGRESSED,
            -> Unit
            DirectCommitOutcome.RETRY -> {
                shadowSourceAcceptReported = false
                return false
            }
            DirectCommitOutcome.REJECTED -> error("Direct protocol rejected SOURCE_ACCEPT receipt: $sourceDisposition")
        }
        directAuthorityAccepted = true
        shadowSourceAcceptReported = true
        return true
    }

    private enum class DirectCommitOutcome { PROGRESSED, RETRY, REJECTED }

    /**
     * Converts an uncertain post-permit Direct side effect into the existing typed terminal
     * receipt/cleanup path. The transport error remains the primary failure; protocol or
     * owner-scoped cleanup failures are suppressed onto it and cannot reopen authority.
     */
    private fun failDirectStageAfterSideEffect(
        adapter: UsbExclusivePlaybackAdapter,
        permit: DirectStagePermit,
        active: DirectDsdRendererPump,
        original: Throwable,
    ): Nothing {
        try {
            val disposition = checkNotNull(
                adapter.commitDirectStage(
                    permit,
                    SideEffectReceipt.TerminalFailure(
                        activationId = permit.activationId,
                        resourceIdentity = ResourceIdentity("${permit.runtimeIdentity}:${permit.stage.resourceSuffix()}"),
                        failure = "${original.javaClass.name}:${original.message ?: "no-message"}",
                        runtimeIdentity = permit.runtimeIdentity,
                    ),
                ),
            ) { "Direct ${permit.stage} failure receipt was not accepted" }
            resolveDirectCommit(adapter, permit, disposition, active)
        } catch (cleanupError: Throwable) {
            if (cleanupError !== original) original.addSuppressed(cleanupError)
        } finally {
            if (permit.stage == DirectStage.PREFILL && directPrefillPermit == permit) {
                directPrefillPermit = null
            }
        }
        throw original
    }

    /** Handles CREATE_RUNTIME post-open failures before a renderer pump exists to clean them. */
    private fun failDirectStageAfterSideEffectWithoutPump(
        adapter: UsbExclusivePlaybackAdapter,
        permit: DirectStagePermit,
        resource: ResourceIdentity,
        cleanupCompleted: Boolean,
        original: Throwable,
    ): Nothing {
        try {
            val disposition = checkNotNull(
                adapter.commitDirectStage(
                    permit,
                    SideEffectReceipt.TerminalFailure(
                        activationId = permit.activationId,
                        resourceIdentity = resource,
                        failure = "${original.javaClass.name}:${original.message ?: "no-message"}",
                        runtimeIdentity = permit.runtimeIdentity,
                    ),
                ),
            ) { "Direct CREATE_RUNTIME failure receipt was not accepted" }
            if (
                disposition is CommitDisposition.CurrentCleanupRequired ||
                disposition is CommitDisposition.StaleCleanupRequired ||
                disposition is CommitDisposition.RetiringCleanupRequired
            ) {
                check(cleanupCompleted) {
                    "Direct CREATE_RUNTIME cleanup failed before completeCleanup"
                }
                val requirements = adapter.cleanupRequirements(permit.activationId)
                check(requirements.map { it.resourceIdentity }.toSet() == setOf(resource)) {
                    "Direct CREATE_RUNTIME cleanup escaped exact resource ownership"
                }
                var continuation: CommitDisposition? = null
                requirements.asReversed().forEach { requirement ->
                    continuation = adapter.completeCleanup(permit.activationId, requirement.resourceIdentity)
                }
                check(
                    continuation == CommitDisposition.TerminalFailure ||
                        continuation == CommitDisposition.StaleNoEffect,
                ) { "unexpected Direct CREATE_RUNTIME cleanup continuation: $continuation" }
            } else {
                check(
                    disposition == CommitDisposition.TerminalFailure ||
                        disposition == CommitDisposition.StaleNoEffect,
                ) { "Direct CREATE_RUNTIME failure bypassed cleanup: $disposition" }
            }
        } catch (cleanupError: Throwable) {
            if (cleanupError !== original) original.addSuppressed(cleanupError)
        }
        throw original
    }

    /** Fails a retained runtime handoff closed after the carrier/source reset may be partial. */
    private fun failRetainedDirectHandoffAfterSideEffect(
        adapter: UsbExclusivePlaybackAdapter,
        permit: com.mica.music.media.usb.protocol.DirectRetainedHandoffPermit,
        active: DirectDsdRendererPump,
        resource: ResourceIdentity,
        original: Throwable,
    ): Nothing {
        try {
            val disposition = adapter.commitRetainedDirectHandoff(
                permit,
                SideEffectReceipt.TerminalFailure(
                    activationId = permit.activationId,
                    resourceIdentity = resource,
                    failure = "${original.javaClass.name}:${original.message ?: "no-message"}",
                    runtimeIdentity = permit.runtimeIdentity,
                ),
            )
            resolveRetainedDirectCommit(adapter, permit, disposition, active)
            adapter.observeDirectRuntimeReleased(
                permit.sourceOccurrence,
                permit.runtimeIdentity,
                "retained-handoff-failure",
            )
            check(adapter.snapshot().familyOwnership is FamilyOwnership.None) {
                "retained Direct failure did not release the exact old runtime family"
            }
        } catch (cleanupError: Throwable) {
            if (cleanupError !== original) original.addSuppressed(cleanupError)
        }
        throw original
    }

    /** Cleans every exact requirement before allowing the protocol continuation to advance. */
    private fun resolveDirectCommit(
        adapter: UsbExclusivePlaybackAdapter,
        permit: DirectStagePermit,
        disposition: CommitDisposition,
        active: DirectDsdRendererPump,
    ): DirectCommitOutcome {
        if (
            disposition !is CommitDisposition.CurrentCleanupRequired &&
            disposition !is CommitDisposition.StaleCleanupRequired &&
            disposition !is CommitDisposition.RetiringCleanupRequired
        ) {
            return when (disposition) {
                CommitDisposition.RetryPendingSameMutation ->
                    if (directStageStillCurrent(adapter, permit)) DirectCommitOutcome.RETRY
                    else DirectCommitOutcome.REJECTED
                is CommitDisposition.CurrentPlaying,
                is CommitDisposition.CurrentPaused,
                -> DirectCommitOutcome.PROGRESSED
                CommitDisposition.StaleNoEffect,
                CommitDisposition.TerminalFailure,
                -> DirectCommitOutcome.REJECTED
                else -> DirectCommitOutcome.REJECTED
            }
        }
        val requirements = adapter.cleanupRequirements(permit.activationId)
        check(requirements.isNotEmpty()) {
            "Direct cleanup disposition has no owner-scoped requirements: $disposition"
        }
        val exactResources = requirements.map { it.resourceIdentity }.toSet()
        check(active.cleanupExactResources(exactResources) == exactResources) {
            "Direct cleanup did not prove every exact resource identity"
        }
        var continuation: CommitDisposition? = null
        requirements.asReversed().forEach { requirement ->
            continuation = adapter.completeCleanup(permit.activationId, requirement.resourceIdentity)
        }
        return when (continuation) {
            CommitDisposition.RetryPendingSameMutation ->
                if (disposition is CommitDisposition.CurrentCleanupRequired && directStageStillCurrent(adapter, permit)) {
                    DirectCommitOutcome.RETRY
                } else {
                    DirectCommitOutcome.REJECTED
                }
            CommitDisposition.TerminalFailure,
            CommitDisposition.StaleNoEffect,
            -> DirectCommitOutcome.REJECTED
            null -> error("Direct cleanup continuation vanished after exact completion")
            else -> error("Unexpected Direct cleanup continuation: $continuation")
        }
    }

    /** Completes the one exact retained-handoff resource before observing family release. */
    private fun resolveRetainedDirectCommit(
        adapter: UsbExclusivePlaybackAdapter,
        permit: com.mica.music.media.usb.protocol.DirectRetainedHandoffPermit,
        disposition: CommitDisposition,
        active: DirectDsdRendererPump,
    ) {
        if (
            disposition is CommitDisposition.CurrentCleanupRequired ||
            disposition is CommitDisposition.StaleCleanupRequired ||
            disposition is CommitDisposition.RetiringCleanupRequired
        ) {
            val requirements = adapter.cleanupRequirements(permit.activationId)
            check(requirements.isNotEmpty()) {
                "retained Direct cleanup disposition has no owner-scoped requirements: $disposition"
            }
            val exactResources = requirements.map { it.resourceIdentity }.toSet()
            check(active.cleanupExactResources(exactResources) == exactResources) {
                "retained Direct cleanup did not prove every exact resource identity"
            }
            var continuation: CommitDisposition? = null
            requirements.asReversed().forEach { requirement ->
                continuation = adapter.completeCleanup(permit.activationId, requirement.resourceIdentity)
            }
            check(
                continuation == CommitDisposition.TerminalFailure ||
                    continuation == CommitDisposition.StaleNoEffect,
            ) { "unexpected retained Direct cleanup continuation: $continuation" }
        } else {
            check(
                disposition == CommitDisposition.TerminalFailure ||
                    disposition == CommitDisposition.StaleNoEffect,
            ) { "retained Direct failure receipt bypassed cleanup: $disposition" }
        }
        val snapshot = adapter.snapshot()
        check(permit.activationId !in snapshot.inFlightActivations) {
            "retained Direct failure left its activation in flight"
        }
        check(snapshot.cleanupRequirements.isEmpty()) { "retained Direct failure left cleanup pending" }
    }

    private fun directStageStillCurrent(
        adapter: UsbExclusivePlaybackAdapter,
        permit: DirectStagePermit,
    ): Boolean {
        val snapshot = adapter.snapshot()
        val mutation = snapshot.mutation ?: return false
        return snapshot.lifecycle is ProtocolLifecycle.Active &&
            snapshot.cleanupRequirements.isEmpty() &&
            permit.activationId in snapshot.inFlightActivations &&
            mutation.mutationId == permit.mutationId &&
            mutation.destinationBound &&
            mutation.targetFamily == PlaybackFamily.DOP &&
            mutation.targetOccurrence == permit.occurrence &&
            snapshot.applicationCurrent.occurrence == permit.occurrence
    }

    private fun DirectStage.resourceSuffix(): String = when (this) {
        DirectStage.CREATE_RUNTIME -> "create"
        DirectStage.PREFILL -> "prefill"
        DirectStage.ARM -> "arm"
        DirectStage.SOURCE_ACCEPT -> "source-accept"
    }

    private fun maybeArmAfterPlayingReset(active: DirectDsdRendererPump, sampleTimeUs: Long?): Boolean {
        val resetPositionUs = positionResetState.postResetArmPositionUsIfReady(
            startupReady = active.isStartupPrefillReady(),
            playbackArmed = active.isPlaybackArmed(),
        ) ?: return false
        if (!observeShadowPrefillIfReady(active)) return false
        if (!observeShadowArmAndSourceAccept(active)) return false
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
        if (!observeShadowPrefillIfReady(active)) return false
        if (!observeShadowArmAndSourceAccept(active)) return false
        pending.navigationRequestId?.let { requestId -> completeNavigationProjection(requestId) }
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
        val rawOccurrence = UsbExclusiveShadowMedia3Facts.occurrence(mediaPeriodId)
        shadowOccurrence = rawOccurrence
        playbackAdapter.observeStream(
            rawOccurrence,
            PlaybackFamily.DOP,
            UsbExclusiveShadowMedia3Facts.audio(newFormat, "direct-dop") +
                ";sourceRate=${newFacts.sourceSampleRateHz};bitOrder=${newFacts.sourceBitOrder}",
        )
        val active = pump
        val playing = state == Renderer.STATE_STARTED
        val playbackIdentity = manualNavigationTransitionBridge.observePlaybackStream(mediaPeriodId)
        val navigationEpoch = playbackIdentity?.let {
            manualNavigationTransitionBridge.bindDirectDestination(newFacts, it)
        }
        val navigationSnapshot = manualNavigationTransitionBridge.snapshot()
        if (navigationEpoch == null && navigationSnapshot != null) {
            pendingManualNavigationBoundary = PendingManualNavigationBoundary(
                requestId = navigationSnapshot.requestId,
                format = newFormat,
                playbackIdentity = playbackIdentity,
            )
            milestone(
                "trackTransition=MANUAL_NAVIGATION_WAIT_PLAYBACK_IDENTITY request=${navigationSnapshot.requestId} " +
                    "playing=${navigationSnapshot.requestedPlaying} family=DOP " +
                    "windowSequence=${playbackIdentity.windowSequenceNumber}",
            )
            return
        }
        if (navigationEpoch != null) {
            pendingManualNavigationBoundary = null
        }
        pendingFreshDirectDestination?.let { pending ->
            val pendingPump = pump
            if (pendingPump != null) {
                check(!pendingPump.isPlaybackArmed()) { "accepted Direct runtime still marked pending" }
                closePump("track-pending-destination-replaced")
                milestone("trackTransition=PENDING_RUNTIME_RETIRED epoch=${pending.epochId}")
            }
            val effectiveNavigationEpoch = navigationEpoch ?: navigationSnapshot
            bindPendingFreshDestination(
                newFormat,
                requiresStartedAuthority = !playing || effectiveNavigationEpoch?.requestedPlaying == false,
                replacement = true,
                navigationEpoch = effectiveNavigationEpoch,
                navigationPlaybackIdentity = playbackIdentity,
            )
            return
        }
        if (navigationEpoch != null) {
            applyBoundManualNavigationDestination(
                newFormat,
                newFacts,
                navigationEpoch,
                checkNotNull(playbackIdentity),
            )
            return
        }

        val transitionMode = DirectDsdTrackTransitionPolicy.decide(active?.facts, newFacts, playing)
        if (transitionMode == DirectDsdTrackTransitionMode.INITIAL) {
            val protocolSnapshot = playbackAdapter.snapshot()
            val pcmHandoff = protocolSnapshot.familyOwnership is FamilyOwnership.PcmOwned
            val protocolPaused = protocolSnapshot.adoptedIntent?.desired == PlaybackIntent.PAUSE
            val pcmHandoffWasPaused = pcmHandoff && protocolPaused
            if (
                pcmHandoffWasPaused ||
                (!playing && protocolPaused)
            ) {
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
            val (discardedCanonicalBytes, result) = transitionRetainedSourceWithProtocol(activePump, newFacts)
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
        bindPendingFreshDestination(newFormat, requiresStartedAuthority = false, replacement = false)
        milestone("trackTransition=NEW_RATE_FACTS_BOUND sourceRate=${newFacts.sourceSampleRateHz}")
    }

    private fun bindPendingFreshDestination(
        format: Format,
        requiresStartedAuthority: Boolean,
        replacement: Boolean,
    ) = bindPendingFreshDestination(
        format = format,
        requiresStartedAuthority = requiresStartedAuthority,
        replacement = replacement,
        navigationEpoch = null,
    )

    private fun bindPendingFreshDestination(
        format: Format,
        requiresStartedAuthority: Boolean,
        replacement: Boolean,
        navigationEpoch: ManualNavigationTransitionEpoch? = null,
        navigationPlaybackIdentity: ManualNavigationPlaybackIdentity? = null,
    ) {
        val previous = pendingFreshDirectDestination
        val facts = checkNotNull(DirectDsdMedia3FormatPolicy.factsOrNull(format))
        val next = PendingFreshDirectDestination(
            epochId = ++nextFreshTransitionEpochId,
            format = format,
            requiresStartedAuthority = requiresStartedAuthority,
            startedAuthorityObserved = false,
            navigationRequestId = navigationEpoch?.requestId,
            navigationFacts = navigationEpoch?.targetFacts,
            navigationPlaybackIdentity = navigationPlaybackIdentity,
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

    /** Compatibility projection only; protocol stage/ownership acceptance is authoritative. */
    private fun completeNavigationProjection(requestId: Long) {
        val completed = manualNavigationTransitionBridge.complete(
            requestId,
            DirectDsdTrackTransportFamily.DOP,
        ) == true
        if (!completed) {
            milestone("navigationTransition=compatibility-projection-stale request=$requestId")
        }
    }

    private fun protocolDestinationCurrent(): Boolean {
        val adapter = playbackAdapter
        val occurrence = shadowOccurrence ?: return false
        val snapshot = adapter.snapshot()
        val mutation = snapshot.mutation ?: return false
        return mutation.destinationBound &&
            mutation.targetFamily == PlaybackFamily.DOP &&
            mutation.targetOccurrence == occurrence &&
            snapshot.applicationCurrent.occurrence == occurrence
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
            check(playbackAdapter.acceptDirectStarted(shadowOccurrence)) {
                "Direct protocol rejected STARTED authority evidence"
            }
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
                if (!observeShadowPrefillIfReady(active)) return
                val armedFromPlayingReset = maybeArmAfterPlayingReset(active, sampleTimeUs = null)
                if (!armedFromPlayingReset) {
                    val armedFromTrackTransition = maybeArmAfterFreshTrackTransition(active, sampleTimeUs = null)
                    if (!armedFromTrackTransition) {
                        observeShadowArmAndSourceAccept(active)
                    }
                }
                if (active.isPlaybackArmed()) observeShadowArmAndSourceAccept(active)
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
            playbackAdapter.observeDirectStopped(shadowOccurrence)
            val navigationRetirement = manualNavigationTransitionBridge.observeDirectRetirementStop()
            if (navigationRetirement != null) {
                navigationRetirementRequestedPaused = !navigationRetirement.requestedPlaying
                milestone(
                    "renderer=stopped armed=${pump?.isPlaybackArmed() == true} navigationPending=true " +
                        "navigationRequest=${navigationRetirement.requestId} gapStarted=false " +
                        "retirementPaused=$navigationRetirementRequestedPaused " +
                        "samples=$sampleCount lastSampleTimeUs=$lastSampleMilestoneTimeUs",
                )
                return
            }
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
        playbackAdapter.observeDirectPositionReset(shadowOccurrence, sourcePositionUs)
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
        pendingFreshDirectDestination = null
        pendingManualNavigationBoundary = null
        currentFormat = null
        inputEosSeen = false
        ended = false
        pauseGapActive = false
        navigationRetirementRequestedPaused = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    override fun onReset() {
        closePump("reset")
        positionResetState.clear()
        pendingFreshDirectDestination = null
        pendingManualNavigationBoundary = null
        currentFormat = null
        inputEosSeen = false
        ended = false
        pauseGapActive = false
        navigationRetirementRequestedPaused = false
        sampleCount = 0L
        lastSampleMilestoneTimeUs = Long.MIN_VALUE
        renderInvocationCount = 0L
        drainBudgetExhaustionCount = 0L
        lastDrainMilestoneElapsedRealtimeUs = Long.MIN_VALUE
    }

    private fun closePump(reason: String) {
        val closingPump = pump
        val closingGeneration = activeSessionGeneration
        val closingShadowOccurrence = shadowRuntimeOccurrence
        val closingShadowRuntime = shadowRuntimeIdentity
        pump = null
        activeSessionGeneration = null
        closingGeneration?.let { generation ->
            DirectDsdTeardownQuiescenceCoordinator.unregister(generation)
            DirectDsdSeekDiscontinuityCoordinator.deactivateSession(generation)
        }
        try {
            closingPump?.close()
            if (closingPump != null && closingShadowRuntime != null) {
                playbackAdapter.observeDirectRuntimeReleased(
                    closingShadowOccurrence,
                    closingShadowRuntime,
                    reason,
                )
            }
        } finally {
            shadowRuntimeOccurrence = null
            shadowRuntimeIdentity = null
            shadowPrefillReported = false
            shadowArmReported = false
            shadowSourceAcceptReported = false
            directPrefillPermit = null
            directAuthorityAccepted = false
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
