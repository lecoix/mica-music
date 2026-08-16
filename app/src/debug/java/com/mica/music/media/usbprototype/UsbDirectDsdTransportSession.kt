package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.mica.music.media.dsd.DirectDsdTransportSession
import com.mica.music.media.dsd.DirectDsdTransportSessionFactory
import com.mica.music.media.dsd.DirectDsdWriteAuthority
import com.mica.music.media.dsd.DirectDsdTransportWriteResult
import com.mica.music.media.dsd.DirectDsdFreshTransitionPreparationResult
import com.mica.music.media.dsd.DirectDsdRetainedSourceTransitionResult
import com.mica.music.media.dsd.DirectDsdMonotonicClock
import com.mica.music.media.dsd.DirectDsdSystemMonotonicClock
import com.mica.music.media.dsd.DoPDiscontinuity
import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DoPCarrierSessionReset
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import com.mica.music.media.usb.AndroidUsbAudioControlIo
import com.mica.music.media.usb.AndroidUsbRuntimeFactsProvider
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.ExactCarrierFeeder
import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.StandardUacDescriptorParser
import com.mica.music.media.usb.Uac2ClockRateController
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReadResult
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReader
import com.mica.music.media.usb.UsbAudioControlIo
import com.mica.music.media.usb.UsbAudioDescriptorParseResult
import com.mica.music.media.usb.UsbAudioEndpointShape
import com.mica.music.media.usb.UsbAudioStreamingProfile
import com.mica.music.media.usb.UsbClockPlan
import com.mica.music.media.usb.UsbControlDirection
import com.mica.music.media.usb.UsbControlIoResult
import com.mica.music.media.usb.UsbControlRecipient
import com.mica.music.media.usb.UsbControlRequest
import com.mica.music.media.usb.UsbDoPCarrierBridge
import com.mica.music.media.usb.UsbDoPCarrierBridgeResult
import com.mica.music.media.usb.UsbGenericPcmSelection
import com.mica.music.media.usb.UsbGenericPcmSelectionResult
import com.mica.music.media.usb.UsbOutputCleanupLease
import com.mica.music.media.usb.UsbOutputRequestLease
import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbOutputSession
import com.mica.music.media.usb.UsbOutputRedemptionBinding
import com.mica.music.media.usb.UsbP2RedemptionContext
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbPermissionState
import com.mica.music.media.usb.UsbRateControlResult
import com.mica.music.media.usb.UsbRuntimeFactsResult
import com.mica.music.media.usb.UsbRuntimeStreamingProfileValidator
import com.mica.music.media.usb.UsbStreamingProfileValidation
import com.mica.music.media.usb.protocol.WriteKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class UsbDirectDsdTransportSessionFactory(
    context: Context,
    private val milestone: (String) -> Unit,
    private val monotonicClock: DirectDsdMonotonicClock = DirectDsdSystemMonotonicClock,
    private val redemptionContext: UsbP2RedemptionContext,
) : DirectDsdTransportSessionFactory {
    private val appContext = context.applicationContext

    override fun open(facts: DsfExtractorPacketFacts): DirectDsdTransportSession =
        error("Direct USB transport requires the explicit M4 write authority")

    override fun open(
        facts: DsfExtractorPacketFacts,
        writeAuthority: DirectDsdWriteAuthority,
    ): DirectDsdTransportSession {
        require(facts.channelCount == 2)
        require(facts.sourceSampleRateHz == 2_822_400 || facts.sourceSampleRateHz == 5_644_800)
        require(facts.sourceSampleRateHz % 16 == 0)
        val manager = appContext.getSystemService(UsbManager::class.java)
        val identity = Sk02UsbContract.identity
        val reservedBinding = redemptionContext.requireCurrentBinding()
        check(reservedBinding.request.device == identity) {
            "Direct DSD target does not match the reserved P2 device identity"
        }
        check(reservedBinding.request.signalPolicy == com.mica.music.media.usb.UsbSignalPolicy.EXACT_ONLY)
        val device = manager.deviceList.values
            .filter { it.vendorId == identity.vendorId && it.productId == identity.productId }
            .singleOrNull() ?: error("Direct DSD requires exactly one SK02")
        check(manager.hasPermission(device)) { "Direct DSD requires existing SK02 USB permission" }

        UsbOutputRuntime.installGenerationPublisher(UsbSk02NativePrototype::publishGeneration)
        val carrierFormat = UsbPcmFormat(
            facts.sourceSampleRateHz / 16,
            facts.channelCount,
            UsbPcmEncoding.PCM_24_PACKED,
        )
        return redemptionContext.consumeCurrent { binding, lease ->
            check(binding === reservedBinding)
            binding.ensureRequestLease(lease)
            lease.io { UsbSk02NativePrototype.publishGeneration(lease.token.value) }
            UsbDirectDsdTransportSession.open(
                context = appContext,
                device = device,
                facts = facts,
                carrierFormat = carrierFormat,
                lease = lease,
                milestone = milestone,
                monotonicClock = monotonicClock,
                redemptionContext = redemptionContext,
                redemptionBinding = binding,
                writeAuthority = writeAuthority,
            )
        }
    }
}

private class UsbDirectDsdTransportSession private constructor(
    facts: DsfExtractorPacketFacts,
    private val carrierFormat: UsbPcmFormat,
    private val runtimeHandle: com.mica.music.media.usb.UsbAudioRuntimeHandle,
    private val connection: android.hardware.usb.UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val streamingAlt0: UsbInterface,
    private val clockController: Uac2ClockRateController,
    private val clockSourceId: Int,
    private val originalClockHz: Int,
    private val nativeHandle: Long,
    private val requiredPrefillFrames: Long,
    private val bufferPolicy: UsbDirectDsdBufferPolicy,
    private val sinkIoAuthority: UsbDirectDsdSinkIoAuthority,
    private val carrierSession: DoPCarrierSession,
    private val feeder: ExactCarrierFeeder,
    private val milestone: (String) -> Unit,
    private val writeTiming: DirectDsdWriteTimingRecorder,
    private val redemptionContext: UsbP2RedemptionContext,
    private val redemptionBinding: UsbOutputRedemptionBinding,
    private val writeAuthority: DirectDsdWriteAuthority,
) : DirectDsdTransportSession, UsbOutputSession {
    override var facts: DsfExtractorPacketFacts = facts
        private set
    @Volatile
    override var startupPrefillReady: Boolean = false
        private set
    @Volatile
    override var playbackArmed: Boolean = false
        private set
    @Volatile
    private var closed = false
    @Volatile
    private var exactCleanupComplete = false
    private val pauseLiveness = UsbDirectDsdPauseLivenessController()
    @Volatile
    private var gapLivenessEverStarted = false
    private var gapRefillActive = false
    private var lastProgressMilestoneMs = 0L
    private var lastWriteTimingMilestoneMs = 0L
    private var lastGapMilestoneMs = 0L
    private var cleanupLeaseScope: UsbOutputCleanupLease? = null

    private interface DirectTransportIoLease {
        fun ensureUsable()
        fun <T> io(block: () -> T): T
    }

    override val activeFacts: PlaybackOutputFacts = PlaybackOutputFacts(
        runtimeHandle = runtimeHandle,
        negotiatedFormat = carrierFormat,
        attached = true,
        permission = UsbPermissionState.GRANTED,
        claimed = true,
        exclusive = true,
        signalExact = true,
    )

    override fun writeCanonical(bytes: ByteArray, offset: Int, byteCount: Int): DirectDsdTransportWriteResult {
        check(!closed)
        val consumed = writeAuthority.withWrite(WriteKind.DOP_CONTENT) {
            writeTiming.recordWriteCanonical(byteCount)
            val guardStartNs = writeTiming.nowNs()
            pauseLiveness.withContentWriter {
                UsbOutputRuntime.owner.withActiveSession(this) { lease ->
                    redemptionBinding.ensureActiveSession(this, lease)
                    lease.ensureCurrent()
                    check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0) { "Native exact transport failed" }
                    writeTiming.recordOwnershipGuardElapsed(writeTiming.nowNs() - guardStartNs)
                    val result = writeTiming.measureFeeder {
                        feeder.writeContentBytes(bytes, offset, byteCount)
                    }
                    writeTiming.recordCarrierBytesEmitted(result.carrierBytesCapturedFromSession)
                    writeTiming.measurePostFeed {
                        check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                            "ExactCarrierFeeder failed ${result.error}"
                        }
                        maybeMarkStartupPrefillReady(lease)
                        publishProgressIfDue()
                    }
                    result.canonicalBytesConsumed
                } ?: error("Direct DSD USB session became stale")
            }
        }
        publishWriteTimingIfDue()
        return DirectDsdTransportWriteResult(consumed)
    }

    override fun armPlayback() {
        check(!closed) { "arm after close" }
        check(startupPrefillReady) { "Direct DSD arm before startup prefill" }
        check(!playbackArmed) { "Direct DSD playback already armed" }
        armPlaybackWithOwner()
    }

    override fun startPauseGapLiveness() {
        check(!closed) { "pause-gap start after close" }
        check(playbackArmed) { "pause-gap start before Direct DSD arm" }
        val before = currentCarrierSnapshot("gap-start")
        gapLivenessEverStarted = true
        gapRefillActive = false
        milestone(
            "directDsd=gap-start buffered=${before.bufferedFrames} high=${bufferPolicy.highWatermarkFrames} " +
                "low=${bufferPolicy.lowWatermarkFrames} completed=${before.completedFrames} " +
                "contentPacked=${before.contentPacked} idlePacked=${before.idlePacked}",
        )
        pauseLiveness.startGap(::runGapLivenessStep)
    }

    override fun stopPauseGapLiveness() {
        check(!closed) { "pause-gap stop after close" }
        check(playbackArmed) { "pause-gap stop before Direct DSD arm" }
        pauseLiveness.stopGapAndJoin()
        gapRefillActive = false
        val after = currentCarrierSnapshot("gap-stop")
        check(after.errorCode == 0) { "Native exact transport failed during pause gap" }
        milestone(
            "directDsd=gap-stop joined=true buffered=${after.bufferedFrames} high=${bufferPolicy.highWatermarkFrames} " +
                "completed=${after.completedFrames} contentPacked=${after.contentPacked} idlePacked=${after.idlePacked}",
        )
    }

    override fun quiescePauseGapForOutputRebuild(): Boolean {
        if (closed || !playbackArmed) return false
        return when (pauseLiveness.snapshot().phase) {
            UsbDirectDsdWriterPhase.CONTENT,
            UsbDirectDsdWriterPhase.CLOSED,
            -> false
            UsbDirectDsdWriterPhase.GAP,
            UsbDirectDsdWriterPhase.FAILED,
            -> {
                stopPauseGapLiveness()
                true
            }
        }
    }

    override fun transitionRetainedSource(
        newFacts: DsfExtractorPacketFacts,
    ): DirectDsdRetainedSourceTransitionResult {
        check(!closed) { "retained source transition after close" }
        check(newFacts.sourceSampleRateHz == facts.sourceSampleRateHz) {
            "retained Direct DSD transition changed source rate"
        }
        check(newFacts.channelCount == facts.channelCount) {
            "retained Direct DSD transition changed channel geometry"
        }
        return pauseLiveness.withContentWriter {
            withTransportIo { io ->
                io.ensureUsable()
                var guard = 0
                while (true) {
                    val snapshot = feeder.snapshot()
                    snapshot.contractError?.let { error("feeder contract failed at track boundary: $it") }
                    if (snapshot.stagedCarrierBytes.isEmpty() &&
                        snapshot.upstreamPendingPackedCarrierBytes == 0
                    ) {
                        break
                    }
                    check(guard++ < 1024) { "retained track boundary feeder drain exceeded work bound" }
                    check(io.io { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) } == 0) {
                        "Native exact transport failed during retained track boundary drain"
                    }
                    val result = feeder.pump()
                    check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                        "ExactCarrierFeeder failed retained track boundary drain ${result.error}"
                    }
                    check(result.sinkBytesAccepted > 0 || result.carrierBytesFlushedFromSession > 0) {
                        "retained track boundary drain made no progress"
                    }
                }
                val drained = feeder.snapshot()
                val accountingBeforeReset = carrierSession.accounting()
                check(drained.stagedCarrierBytes.isEmpty())
                check(drained.upstreamPendingPackedCarrierBytes == 0)
                check(accountingBeforeReset.pendingPackedCarrierBytes == 0)
                milestone(
                    "trackTransition=OLD_FEEDER_DRAINED staged=0 upstreamPending=0 " +
                        "pendingPacked=${accountingBeforeReset.pendingPackedCarrierBytes}",
                )
                val reset = feeder.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)
                check(reset.applied && reset.reset != null) {
                    "retained source reset blocked reason=${reset.blockedReason} error=${reset.error}"
                }
                val accountingAfterReset = carrierSession.accounting()
                check(accountingAfterReset.pendingPackedCarrierBytes == 0)
                check(accountingAfterReset.pendingPartialCanonicalFrameBytes == 0)
                check(!accountingAfterReset.hasPendingCanonicalHalfFrame)
                facts = newFacts
                milestone(
                    "trackTransition=SOURCE_RESET_APPLIED sourceRate=${newFacts.sourceSampleRateHz} " +
                        "marker=${accountingAfterReset.nextMarker} pendingPacked=0 pendingPartial=0 pendingHalf=false",
                )
                DirectDsdRetainedSourceTransitionResult(
                    feederPendingZero = true,
                    sourceResetApplied = true,
                )
            } ?: error("Direct DSD USB session became stale during retained track transition")
        }
    }

    override fun prepareFreshTrackTransition(
        reason: DoPCarrierSessionReset,
    ): DirectDsdFreshTransitionPreparationResult {
        check(!closed) { "fresh track transition after close" }
        return pauseLiveness.withContentWriter {
            withTransportIo { io ->
                io.ensureUsable()
                var guard = 0
                while (true) {
                    val snapshot = feeder.snapshot()
                    snapshot.contractError?.let { error("feeder contract failed at fresh track boundary: $it") }
                    if (snapshot.stagedCarrierBytes.isEmpty() &&
                        snapshot.upstreamPendingPackedCarrierBytes == 0
                    ) {
                        break
                    }
                    check(guard++ < 1024) { "fresh track boundary feeder drain exceeded work bound" }
                    check(io.io { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) } == 0) {
                        "Native exact transport failed during fresh track boundary drain"
                    }
                    val result = feeder.pump()
                    check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                        "ExactCarrierFeeder failed fresh track boundary drain ${result.error}"
                    }
                    check(result.sinkBytesAccepted > 0 || result.carrierBytesFlushedFromSession > 0) {
                        "fresh track boundary drain made no progress"
                    }
                }
                val drained = feeder.snapshot()
                val beforeReset = carrierSession.accounting()
                check(drained.stagedCarrierBytes.isEmpty())
                check(drained.upstreamPendingPackedCarrierBytes == 0)
                check(beforeReset.pendingPackedCarrierBytes == 0)
                milestone(
                    "trackTransition=OLD_FEEDER_P5_PENDING_ZERO staged=0 upstreamPending=0 " +
                        "pendingPacked=${beforeReset.pendingPackedCarrierBytes}",
                )
                val reset = feeder.resetCarrier(reason)
                check(reset.applied && reset.reset != null) {
                    "fresh carrier reset blocked reason=${reset.blockedReason} error=${reset.error}"
                }
                val afterReset = carrierSession.accounting()
                check(afterReset.pendingPackedCarrierBytes == 0)
                check(afterReset.pendingPartialCanonicalFrameBytes == 0)
                check(!afterReset.hasPendingCanonicalHalfFrame)
                milestone(
                    "trackTransition=CARRIER_RECONFIGURE_RESET_APPLIED reason=$reason " +
                        "marker=${afterReset.nextMarker} pendingPacked=0 pendingPartial=0 pendingHalf=false",
                )
                DirectDsdFreshTransitionPreparationResult(
                    feederPendingZero = true,
                    carrierResetApplied = true,
                )
            } ?: error("Direct DSD USB session became stale during fresh track transition")
        }
    }

    override fun <T> withCleanup(block: () -> T): T =
        UsbOutputRuntime.owner.withActiveSessionCleanup(this) { lease ->
            sinkIoAuthority.withCleanupLease(lease) {
                check(cleanupLeaseScope == null) { "Direct DSD cleanup authority was re-entered" }
                cleanupLeaseScope = lease
                try {
                    block()
                } finally {
                    cleanupLeaseScope = null
                }
            }
        } ?: error("Direct DSD USB session is no longer available for exact cleanup")

    private fun <T> withTransportIo(block: (DirectTransportIoLease) -> T): T {
        val cleanup = cleanupLeaseScope
        if (cleanup != null) {
            cleanup.ensureSerialized()
            return block(
                object : DirectTransportIoLease {
                    override fun ensureUsable() = cleanup.ensureSerialized()
                    override fun <R> io(block: () -> R): R = cleanup.io(block)
                },
            )
        }
        return UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            lease.ensureCurrent()
            block(
                object : DirectTransportIoLease {
                    override fun ensureUsable() = lease.ensureCurrent()
                    override fun <R> io(block: () -> R): R = lease.io(block)
                },
            )
        } ?: error("Direct DSD USB session became stale during transport transition")
    }

    override fun finishEndOfStream(): Boolean {
        check(!closed)
        return pauseLiveness.withContentWriter {
            UsbOutputRuntime.owner.withActiveSession(this) { lease ->
                lease.ensureCurrent()
                var guard = 0
                while (guard++ < 1024) {
                    val snapshot = feeder.snapshot()
                    if (snapshot.contractError != null) error("feeder contract failed at EOS")
                    if (snapshot.stagedCarrierBytes.isEmpty() && snapshot.upstreamPendingPackedCarrierBytes == 0) break
                    val result = feeder.pump()
                    if (result.status == ExactCarrierFeedStatus.FAILED) error("feeder failed draining EOS")
                    if (result.sinkBytesAccepted == 0 && result.carrierBytesFlushedFromSession == 0) return@withActiveSession false
                }
                val feederSnapshot = feeder.snapshot()
                val accounting = carrierSession.accounting()
                val clean = feederSnapshot.stagedCarrierBytes.isEmpty() &&
                    feederSnapshot.upstreamPendingPackedCarrierBytes == 0 &&
                    feederSnapshot.contractError == null &&
                    accounting.pendingPackedCarrierBytes == 0 &&
                    accounting.pendingPartialCanonicalFrameBytes == 0 &&
                    !accounting.hasPendingCanonicalHalfFrame
                milestone("directDsd=eos clean=$clean canonical=${accounting.canonicalBytesConsumed} contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked}")
                clean
            } ?: false
        }
    }

    override fun restart(lease: UsbOutputRequestLease) {
        lease.ensureCurrent()
        error("Direct DSD prototype restart is intentionally unsupported")
    }

    override fun release(lease: UsbOutputCleanupLease, reason: String) {
        lease.ensureSerialized()
        if (closed) return
        closed = true
        pauseLiveness.markReleasedWithoutJoin()
        val drainFailure = runCatching { drainCommittedCarrierUnderCleanup(lease) }.exceptionOrNull()
        if (drainFailure != null) {
            val failedDrainSnapshot = feeder.snapshot()
            milestone(
                "directDsd=close-drain authority=cleanup status=FAIL staged=${failedDrainSnapshot.stagedCarrierBytes.size} " +
                    "upstreamPending=${failedDrainSnapshot.upstreamPendingPackedCarrierBytes} " +
                    "error=${failedDrainSnapshot.contractError} failure=${drainFailure.message}",
            )
        }
        val authorityCloseFailure = runCatching { sinkIoAuthority.close() }.exceptionOrNull()
        val pauseSnapshot = pauseLiveness.snapshot()
        val accounting = carrierSession.accounting()
        val feederSnapshot = feeder.snapshot()
        val finalCompleted = runCatching { UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle) }.getOrDefault(-1L)
        val finalError = runCatching { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) }.getOrDefault(-1)
        val underrun = runCatching { UsbSk02NativePrototype.getMedia3UnderrunBytes(nativeHandle) }.getOrDefault(-1L)
        val invalidFeedback = runCatching { UsbSk02NativePrototype.getMedia3InvalidFeedbackPacketCount(nativeHandle) }.getOrDefault(-1L)
        val dataErrors = runCatching { UsbSk02NativePrototype.getMedia3DataPacketErrorCount(nativeHandle) }.getOrDefault(-1L)
        val pcmMetrics = runCatching { UsbSk02NativePrototype.getMedia3DiagnosticMetrics(nativeHandle) }
            .getOrDefault(longArrayOf())
            .let { metrics -> (8..16).map { metrics.getOrElse(it) { 0L } } }

        val nativeDestroyed = runCatching { lease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) } }.isSuccess
        val altRestored = runCatching { lease.io { connection.setInterface(streamingAlt0) } }.getOrDefault(false)
        val clockRestored = runCatching {
            lease.io { clockController.setAndVerify(clockSourceId, originalClockHz) }
        }.getOrNull() == UsbRateControlResult.Applied(originalClockHz)
        val streamingReleased = runCatching { lease.io { connection.releaseInterface(streamingAlt0) } }.getOrDefault(false)
        val controlReleased = runCatching { lease.io { connection.releaseInterface(controlInterface) } }.getOrDefault(false)
        val reconnectErrno = runCatching {
            lease.io { UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor) }
        }.getOrNull()
        val controlDriver = runCatching {
            lease.io { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, controlInterface.id) }
        }.getOrNull()
        val streamingDriver = runCatching {
            lease.io { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, streamingAlt0.id) }
        }.getOrNull()
        val driversBound = controlDriver?.contains("driver=snd-usb-audio") == true &&
            streamingDriver?.contains("driver=snd-usb-audio") == true
        val cleanupGreen = nativeDestroyed && altRestored && clockRestored && streamingReleased &&
            controlReleased && reconnectErrno == 0 && driversBound
        val idleAccountingGreen = if (gapLivenessEverStarted) {
            accounting.idleRuntimeFramesPacked > 0L
        } else {
            accounting.idleRuntimeFramesPacked == 0L
        }
        val transportGreen = playbackArmed && finalError == 0 && underrun == 0L &&
            invalidFeedback == 0L && dataErrors == 0L && pcmMetrics.all { it == 0L } &&
            accounting.canonicalBytesConsumed > 0L && accounting.contentRuntimeFramesPacked > 0L &&
            idleAccountingGreen && accounting.pendingPackedCarrierBytes == 0 &&
            accounting.pendingPartialCanonicalFrameBytes == 0 && !accounting.hasPendingCanonicalHalfFrame &&
            feederSnapshot.stagedCarrierBytes.isEmpty() &&
            feederSnapshot.upstreamPendingPackedCarrierBytes == 0 && feederSnapshot.contractError == null &&
            drainFailure == null && authorityCloseFailure == null &&
            pauseSnapshot.workerFailure == null && !pauseSnapshot.workerAlive && cleanupGreen
        lease.io { connection.close() }
        exactCleanupComplete = cleanupGreen &&
            drainFailure == null &&
            authorityCloseFailure == null &&
            pauseSnapshot.workerFailure == null &&
            !pauseSnapshot.workerAlive &&
            feederSnapshot.stagedCarrierBytes.isEmpty() &&
            feederSnapshot.upstreamPendingPackedCarrierBytes == 0 &&
            feederSnapshot.contractError == null
        milestone(
            "directDsd=close reason=$reason completed=$finalCompleted error=$finalError underrun=$underrun " +
                "invalidFeedback=$invalidFeedback dataErrors=$dataErrors pcmContent=${pcmMetrics.joinToString(",")} " +
                "canonical=${accounting.canonicalBytesConsumed} contentPacked=${accounting.contentRuntimeFramesPacked} " +
                "idlePacked=${accounting.idleRuntimeFramesPacked} pendingPacked=${accounting.pendingPackedCarrierBytes} " +
                "pendingPartial=${accounting.pendingPartialCanonicalFrameBytes} pendingHalf=${accounting.hasPendingCanonicalHalfFrame} " +
                "feederStaged=${feederSnapshot.stagedCarrierBytes.size} feederPending=${feederSnapshot.upstreamPendingPackedCarrierBytes} " +
                "feederError=${feederSnapshot.contractError} nativeDestroyed=$nativeDestroyed altRestored=$altRestored " +
                "clockRestored=$clockRestored streamingReleased=$streamingReleased controlReleased=$controlReleased " +
                "reconnectErrno=$reconnectErrno driversBound=$driversBound gapEver=$gapLivenessEverStarted " +
                "gapPhase=${pauseSnapshot.phase} gapWorkerAlive=${pauseSnapshot.workerAlive} " +
                "gapFailure=${pauseSnapshot.workerFailure} closeDrainFailure=${drainFailure?.message} " +
                "sinkAuthorityFailure=${authorityCloseFailure?.message} cleanupGreen=$cleanupGreen transportGreen=$transportGreen",
        )
        milestone("directDsd=result status=${if (transportGreen) "PASS" else "FAIL"} reason=$reason")
    }

    override fun isExactCleanupComplete(): Boolean = exactCleanupComplete

    override fun close() {
        if (closed) return
        val pauseFailure = runCatching { pauseLiveness.stopGapAndJoin() }.exceptionOrNull()
        val livenessCloseFailure = runCatching { pauseLiveness.closeAndJoin() }.exceptionOrNull()
        UsbOutputRuntime.owner.release(this, "renderer-close")
        pauseFailure?.let { throw it }
        livenessCloseFailure?.let { throw it }
    }

    /** Drains only carrier output already accepted by P5/feeder before owner-driven release. */
    private fun drainCommittedCarrierUnderCleanup(lease: UsbOutputCleanupLease) {
        lease.ensureSerialized()
        sinkIoAuthority.withCleanupLease(lease) {
            val deadlineMs = SystemClock.elapsedRealtime() + CLOSE_DRAIN_TIMEOUT_MS
            while (true) {
                val snapshot = feeder.snapshot()
                snapshot.contractError?.let { error("feeder contract failed before close: $it") }
                if (snapshot.stagedCarrierBytes.isEmpty() && snapshot.upstreamPendingPackedCarrierBytes == 0) break
                check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0) {
                    "Native exact transport failed while draining close tail"
                }
                val result = feeder.pump()
                check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                    "ExactCarrierFeeder failed draining close tail ${result.error}"
                }
                if (result.sinkBytesAccepted == 0 && result.carrierBytesFlushedFromSession == 0) {
                    check(SystemClock.elapsedRealtime() < deadlineMs) {
                        "Timed out draining committed Direct DSD carrier tail"
                    }
                    Thread.sleep(1L)
                }
            }
        }
        val snapshot = feeder.snapshot()
        milestone(
            "directDsd=close-drain authority=cleanup status=PASS staged=${snapshot.stagedCarrierBytes.size} " +
                "upstreamPending=${snapshot.upstreamPendingPackedCarrierBytes} error=${snapshot.contractError}",
        )
        check(snapshot.stagedCarrierBytes.isEmpty()) { "Direct DSD committed feeder staging remained after cleanup drain" }
        check(snapshot.upstreamPendingPackedCarrierBytes == 0) { "Direct DSD P5 packed output remained after cleanup drain" }
        check(snapshot.contractError == null) { "Direct DSD feeder failed during cleanup drain" }
    }

    private fun maybeMarkStartupPrefillReady(lease: UsbOutputRequestLease) {
        if (startupPrefillReady) return
        lease.ensureCurrent()
        check(!playbackArmed) { "Direct DSD armed before startup readiness" }
        check(!lease.io { UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle) }) {
            "Native exact session armed during startup prefill"
        }
        val buffered = lease.io { UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) }
        if (buffered < requiredPrefillFrames) return
        val accounting = carrierSession.accounting()
        check(accounting.canonicalBytesConsumed > 0)
        check(accounting.contentRuntimeFramesPacked > 0)
        check(accounting.idleRuntimeFramesPacked == 0L)
        startupPrefillReady = true
        milestone(
            "directDsd=prefill buffered=$buffered required=$requiredPrefillFrames canonical=${accounting.canonicalBytesConsumed} " +
                "contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked} armed=false",
        )
    }

    private fun armPlaybackWithOwner() {
        val armed = UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            redemptionBinding.ensureActiveSession(this, lease)
            lease.ensureCurrent()
            check(lease.io { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) } == 0) {
                "Native exact transport failed before arm"
            }
            val buffered = lease.io { UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) }
            check(buffered >= requiredPrefillFrames) {
                "Direct DSD startup prefill regressed buffered=$buffered required=$requiredPrefillFrames"
            }
            check(!playbackArmed) { "Direct DSD playback already armed" }
            check(!lease.io { UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle) }) {
                "Native exact session armed outside renderer STARTED lifecycle"
            }
            val arm = lease.io { UsbSk02NativePrototype.armExactCarrierSession(nativeHandle) }
            check(arm == UsbExactCarrierArmResult.ARMED) { "Native exact arm failed result=$arm" }
            check(lease.io { UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle) })
            playbackArmed = true
            milestone("directDsd=arm result=$arm armed=true")
            true
        } ?: error("Direct DSD USB session became stale before arm")
        check(armed)
    }

    private data class CarrierSnapshot(
        val bufferedFrames: Long,
        val completedFrames: Long,
        val errorCode: Int,
        val contentPacked: Long,
        val idlePacked: Long,
    )

    private fun currentCarrierSnapshot(label: String): CarrierSnapshot =
        pauseLiveness.withContentWriter {
            UsbOutputRuntime.owner.withActiveSession(this) { lease ->
                lease.ensureCurrent()
                val error = UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
                check(error == 0) { "Native exact transport failed at $label error=$error" }
                val accounting = carrierSession.accounting()
                CarrierSnapshot(
                    bufferedFrames = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle),
                    completedFrames = UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle),
                    errorCode = error,
                    contentPacked = accounting.contentRuntimeFramesPacked,
                    idlePacked = accounting.idleRuntimeFramesPacked,
                )
            } ?: error("Direct DSD USB session became stale at $label")
        }

    private fun runGapLivenessStep(): Long {
        check(!closed) { "GAP worker after close" }
        return writeAuthority.withWrite(WriteKind.DOP_GAP) {
            UsbOutputRuntime.owner.withActiveSession(this) { lease ->
                redemptionBinding.ensureActiveSession(this, lease)
                lease.ensureCurrent()
                val error = UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
                check(error == 0) { "Native exact transport failed during GAP error=$error" }
                val beforeBuffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
                check(beforeBuffered >= 0L)
                if (!gapRefillActive && bufferPolicy.shouldBeginRefill(beforeBuffered)) {
                    gapRefillActive = true
                }

                if (gapRefillActive) {
                    val requestFrames = bufferPolicy.refillRequestFrames(beforeBuffered, GAP_WRITE_REQUEST_FRAMES)
                    if (requestFrames > 0) {
                        val result = feeder.writeGapFrames(requestFrames)
                        check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                            "ExactCarrierFeeder GAP failed ${result.error}"
                        }
                        check(result.blockedReason == null) {
                            "Direct DSD GAP blocked by accepted source state ${result.blockedReason}"
                        }
                    }
                    val afterBuffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
                    if (afterBuffered >= bufferPolicy.highWatermarkFrames) {
                        gapRefillActive = false
                    }
                }

                publishGapProgressIfDue()
                if (gapRefillActive) 0L else GAP_POLL_INTERVAL_MS
            } ?: error("Direct DSD USB session became stale during GAP")
        }
    }

    private fun publishGapProgressIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGapMilestoneMs < 250L) return
        lastGapMilestoneMs = now
        val accounting = carrierSession.accounting()
        milestone(
            "directDsd=gap-progress elapsedMs=$now completed=${UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle)} " +
                "buffered=${UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)} " +
                "error=${UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)} " +
                "contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked} " +
                "refillActive=$gapRefillActive",
        )
    }
    private fun publishProgressIfDue() {
        if (!playbackArmed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressMilestoneMs < 250L) return
        lastProgressMilestoneMs = now
        val accounting = carrierSession.accounting()
        milestone(
            "directDsd=progress elapsedMs=$now completed=${UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle)} " +
                "buffered=${UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)} " +
                "error=${UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)} canonical=${accounting.canonicalBytesConsumed} " +
                "contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked}",
        )
    }

    private fun publishWriteTimingIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWriteTimingMilestoneMs < 250L) return
        lastWriteTimingMilestoneMs = now
        val t = writeTiming.snapshotAndReset()
        milestone(
            "directDsd=write-timing elapsedMs=$now windowNs=${t.windowNs} writes=${t.writeCanonicalCalls} " +
                "sourceBytes=${t.sourceBytes} carrierBytes=${t.carrierBytesEmitted} " +
                "guardTotalNs=${t.ownershipGuardTotalNs} guardMaxNs=${t.ownershipGuardMaxNs} " +
                "feederTotalNs=${t.feederTotalNs} feederMaxNs=${t.feederMaxNs} " +
                "postTotalNs=${t.postFeedTotalNs} postMaxNs=${t.postFeedMaxNs} " +
                "sinkTotalNs=${t.sinkTotalNs} sinkMaxNs=${t.sinkMaxNs} sinkCalls=${t.sinkCalls} " +
                "sinkOffered=${t.sinkOfferedBytes} sinkAccepted=${t.sinkAcceptedBytes} " +
                "sinkPartial=${t.sinkPartialAccepts} sinkZero=${t.sinkZeroAccepts} " +
                "directBufferTotalNs=${t.directBufferTotalNs} directBufferMaxNs=${t.directBufferMaxNs} " +
                "nativeWriteTotalNs=${t.nativeWriteTotalNs} nativeWriteMaxNs=${t.nativeWriteMaxNs} " +
                "nativeWriteCalls=${t.nativeWriteCalls} bufferedJniTotalNs=${t.bufferedFramesJniTotalNs} " +
                "bufferedJniMaxNs=${t.bufferedFramesJniMaxNs} streamWriteJniTotalNs=${t.streamWriteJniTotalNs} " +
                "streamWriteJniMaxNs=${t.streamWriteJniMaxNs}",
        )
    }

    companion object {
        private const val GAP_WRITE_REQUEST_FRAMES = 4_096
        private const val GAP_POLL_INTERVAL_MS = 5L
        private const val CLOSE_DRAIN_TIMEOUT_MS = 250L

        fun open(
            context: Context,
            device: UsbDevice,
            facts: DsfExtractorPacketFacts,
            carrierFormat: UsbPcmFormat,
            lease: UsbOutputRequestLease,
            milestone: (String) -> Unit,
            monotonicClock: DirectDsdMonotonicClock,
            redemptionContext: UsbP2RedemptionContext,
            redemptionBinding: UsbOutputRedemptionBinding,
            writeAuthority: DirectDsdWriteAuthority,
        ): UsbDirectDsdTransportSession {
            val manager = context.getSystemService(UsbManager::class.java)
            redemptionBinding.ensureRequestLease(lease)
            lease.ensureCurrent()
            check(manager.hasPermission(device))
            val connection = lease.io { manager.openDevice(device) } ?: error("unable to open Direct DSD target")
            var controlInterface: UsbInterface? = null
            var streamingAlt0: UsbInterface? = null
            var controlClaimed = false
            var streamingClaimed = false
            var altSelected = false
            var nativeHandle = 0L
            var clockController: Uac2ClockRateController? = null
            var clockSourceId: Int? = null
            var originalClockHz: Int? = null
            try {
                val runtime = when (val r = lease.io {
                    AndroidUsbRuntimeFactsProvider.acquire(device, connection)
                }) {
                    is UsbRuntimeFactsResult.Ready -> r.facts
                    is UsbRuntimeFactsResult.Rejected -> error("runtime facts rejected ${r.rejection.code}:${r.rejection.detail}")
                }
                val parsed = when (val r = StandardUacDescriptorParser.parse(runtime.descriptorSet)) {
                    is UsbAudioDescriptorParseResult.Parsed -> r.facts
                    is UsbAudioDescriptorParseResult.Rejected -> error("descriptor rejected ${r.rejection}")
                }
                val interfaces = (0 until device.interfaceCount).map(device::getInterface)
                val selectedControl = interfaces.firstOrNull {
                    it.id == parsed.audioFunction.controlInterfaceNumber && it.alternateSetting == 0
                } ?: error("AudioControl alt0 missing")
                controlInterface = selectedControl
                controlClaimed = lease.io { connection.claimInterface(selectedControl, true) }
                check(controlClaimed)
                val controlIo = AndroidUsbAudioControlIo(
                    connection = connection,
                    executeIo = { block -> lease.io(block) },
                )
                val clockEvidence = when (val r = Uac2RuntimeClockEvidenceReader.read(parsed, controlIo)) {
                    is Uac2RuntimeClockEvidenceReadResult.Ready -> r.evidence
                    is Uac2RuntimeClockEvidenceReadResult.Rejected -> error("clock evidence rejected ${r.rejection}")
                }
                val selection = when (
                    val r = UsbGenericPcmSelection.select(carrierFormat, runtime.identity, parsed, clockEvidence)
                ) {
                    is UsbGenericPcmSelectionResult.Ready -> r
                    is UsbGenericPcmSelectionResult.Rejected -> error("generic exact carrier rejected ${r.rejection}")
                }
                val decision = selection.decision
                check(decision.signalExact && decision.requestedFormat == carrierFormat && decision.deviceFormat == carrierFormat)
                val profile = decision.streamingProfile
                check(profile.subslotBytes == 3 && profile.bitResolution == 24 && profile.channelCount == facts.channelCount)
                val claimPlan = checkNotNull(profile.claimPlan)
                val alt0 = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == 0
                } ?: error("streaming alt0 missing")
                val streamingTarget = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == claimPlan.alternateSetting
                } ?: error("streaming target missing")
                validateStreamingEndpoints(streamingTarget, profile)
                streamingAlt0 = alt0
                streamingClaimed = lease.io { connection.claimInterface(alt0, true) }
                check(streamingClaimed)
                val selectedClock = profile.clockPlan as? UsbClockPlan.Uac2Entity ?: error("UAC2 clock proof required")
                clockSourceId = selectedClock.sourceEntityId
                val rateController = Uac2ClockRateController(controlIo, selectedControl.id)
                clockController = rateController
                originalClockHz = readClockCurrentHz(controlIo, selectedControl.id, selectedClock.sourceEntityId)
                    ?: error("original clock read failed")
                when (val applied = lease.io { rateController.setAndVerify(selectedClock.sourceEntityId, carrierFormat.sampleRateHz) }) {
                    is UsbRateControlResult.Applied -> check(applied.sampleRateHz == carrierFormat.sampleRateHz)
                    is UsbRateControlResult.Rejected -> error("carrier clock rejected ${applied.rejection}")
                }
                altSelected = lease.io { connection.setInterface(streamingTarget) }
                check(altSelected)
                val bridge = UsbDoPCarrierBridge.planDoP(
                    decision = decision,
                    transport = selection.transportConfig,
                    source = DsdCarrierSourceFacts(facts.sourceSampleRateHz.toLong(), facts.channelCount),
                )
                val plannerResult = bridge as? UsbDoPCarrierBridgeResult.PlannerResult
                    ?: error("bridge rejected ${(bridge as UsbDoPCarrierBridgeResult.Rejected).rejection}")
                val ready = plannerResult.result as? DoPCarrierPlanningResult.Ready
                    ?: error("DoP planner rejected ${(plannerResult.result as DoPCarrierPlanningResult.Rejected).rejection}")
                val plan = ready.plan
                check(plan.packing == DoPCarrierPacking.PACKED_24_LE)
                check(plan.runtimeFrameRateHz == carrierFormat.sampleRateHz.toLong())
                check(plan.dsdBitRateHz == facts.sourceSampleRateHz.toLong())
                check(plan.channelCount == facts.channelCount && plan.bytesPerRuntimeFrame == 6)
                milestone(
                    "directDsd=selection sourceRate=${facts.sourceSampleRateHz} carrierRate=${plan.runtimeFrameRateHz} " +
                        "packing=${plan.packing} channels=${plan.channelCount} interface=${profile.interfaceNumber} " +
                        "alt=${profile.alternateSetting} dataEndpoint=0x${selection.transportConfig.dataEndpointAddress.toString(16)}",
                )

                nativeHandle = lease.io {
                    UsbSk02NativePrototype.createMedia3Stream(
                        connection.fileDescriptor,
                        selection.transportConfig,
                        lease.token.value,
                        UsbNativePayloadPolicy.EXACT_FRAMES_ONLY,
                    )
                }
                check(nativeHandle != 0L)
                check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
                val requiredBytes = UsbSk02NativePrototype.getExactCarrierStartupPrefillBytes(nativeHandle)
                val requiredFrames = UsbSk02NativePrototype.getExactCarrierStartupPrefillFrames(nativeHandle)
                val capacityFrames = UsbSk02NativePrototype.getMedia3BufferCapacityFrames(nativeHandle)
                check(requiredBytes > 0 && requiredFrames > 0 && capacityFrames >= requiredFrames)
                check(requiredBytes == requiredFrames * plan.bytesPerRuntimeFrame)
                val bufferPolicy = UsbDirectDsdBufferPolicy.create(
                    carrierRateHz = plan.runtimeFrameRateHz,
                    requiredPrefillFrames = requiredFrames,
                    capacityFrames = capacityFrames,
                )
                // This is a dormant-session precondition probe: the native contract must return
                // RETRY_INSUFFICIENT_PREFILL and must not arm. Keep it under the CREATE request
                // lease; the only successful ARM remains renderer-gated by the ARM permit below.
                val underArm = lease.io { UsbSk02NativePrototype.armExactCarrierSession(nativeHandle) }
                check(underArm == UsbExactCarrierArmResult.RETRY_INSUFFICIENT_PREFILL)
                milestone(
                    "directDsd=native dormant=true requiredBytes=$requiredBytes requiredFrames=$requiredFrames " +
                        "capacityFrames=$capacityFrames highWatermark=${bufferPolicy.highWatermarkFrames} " +
                        "lowWatermark=${bufferPolicy.lowWatermarkFrames} underArm=$underArm",
                )

                val carrierSession = DoPCarrierSession(plan)
                val handleForSink = nativeHandle
                val sinkIoAuthority = UsbDirectDsdSinkIoAuthority(lease) {
                    writeAuthority.requireNativeIoAllowed()
                }
                val writeTiming = DirectDsdWriteTimingRecorder(monotonicClock)
                val sink = UsbDoPIdleNativeSink(plan.bytesPerRuntimeFrame, timing = writeTiming) { buffer, length ->
                    sinkIoAuthority.io {
                        val bufferedFrames = writeTiming.measureBufferedFramesJni {
                            UsbSk02NativePrototype.getMedia3BufferedFrames(handleForSink)
                        }
                        check(bufferedFrames >= 0L) { "Native exact buffered-frame query failed" }
                        val allowedBytes = bufferPolicy.allowedSinkBytes(
                            bufferedFrames = bufferedFrames,
                            requestedBytes = length,
                            bytesPerRuntimeFrame = plan.bytesPerRuntimeFrame,
                        )
                        if (allowedBytes == 0) 0 else writeTiming.measureStreamWriteJni {
                            UsbSk02NativePrototype.writeMedia3Stream(handleForSink, buffer, 0, allowedBytes)
                        }
                    }
                }
                val feeder = ExactCarrierFeeder(carrierSession, sink, stagingFrameCapacity = 4_096)
                return UsbDirectDsdTransportSession(
                    facts = facts,
                    carrierFormat = carrierFormat,
                    runtimeHandle = runtime.runtimeHandle,
                    connection = connection,
                    controlInterface = selectedControl,
                    streamingAlt0 = alt0,
                    clockController = rateController,
                    clockSourceId = selectedClock.sourceEntityId,
                    originalClockHz = checkNotNull(originalClockHz),
                    nativeHandle = nativeHandle,
                    requiredPrefillFrames = requiredFrames,
                    bufferPolicy = bufferPolicy,
                    sinkIoAuthority = sinkIoAuthority,
                    carrierSession = carrierSession,
                    feeder = feeder,
                    milestone = milestone,
                    writeTiming = writeTiming,
                    redemptionContext = redemptionContext,
                    redemptionBinding = redemptionBinding,
                    writeAuthority = writeAuthority,
                )
            } catch (error: Throwable) {
                val cleanupLease = lease.cleanupLease()
                if (nativeHandle != 0L) runCatching { cleanupLease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) } }
                if (altSelected && streamingAlt0 != null) runCatching { cleanupLease.io { connection.setInterface(checkNotNull(streamingAlt0)) } }
                if (clockController != null && clockSourceId != null && originalClockHz != null) {
                    runCatching { cleanupLease.io { checkNotNull(clockController).setAndVerify(checkNotNull(clockSourceId), checkNotNull(originalClockHz)) } }
                }
                if (streamingClaimed && streamingAlt0 != null) runCatching { cleanupLease.io { connection.releaseInterface(checkNotNull(streamingAlt0)) } }
                if (controlClaimed && controlInterface != null) runCatching { cleanupLease.io { connection.releaseInterface(checkNotNull(controlInterface)) } }
                runCatching { cleanupLease.io { UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor) } }
                runCatching { cleanupLease.io { connection.close() } }
                throw error
            }
        }

        private fun validateStreamingEndpoints(streamingInterface: UsbInterface, profile: UsbAudioStreamingProfile) {
            val endpoints = (0 until streamingInterface.endpointCount).map(streamingInterface::getEndpoint).map {
                UsbAudioEndpointShape(it.address, it.type, it.maxPacketSize, it.interval)
            }
            when (val result = UsbRuntimeStreamingProfileValidator.validate(profile, endpoints)) {
                UsbStreamingProfileValidation.Valid -> Unit
                is UsbStreamingProfileValidation.Rejected -> error("runtime topology rejected ${result.reason}")
            }
        }

        private fun readClockCurrentHz(io: UsbAudioControlIo, audioControlInterface: Int, clockSourceId: Int): Int? {
            val result = io.execute(
                UsbControlRequest(
                    direction = UsbControlDirection.IN,
                    recipient = UsbControlRecipient.INTERFACE,
                    request = 0x01,
                    value = 0x01 shl 8,
                    index = (clockSourceId shl 8) or audioControlInterface,
                    readLength = 4,
                ),
            )
            val success = result as? UsbControlIoResult.Success ?: return null
            if (success.transferredBytes != 4 || success.data.size != 4) return null
            return ByteBuffer.wrap(success.data).order(ByteOrder.LITTLE_ENDIAN).int.takeIf { it > 0 }
        }
    }
}
