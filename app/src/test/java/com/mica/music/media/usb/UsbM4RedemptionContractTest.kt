package com.mica.music.media.usb

import com.mica.music.media.usb.protocol.ActiveWriteLease
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.protocol.SideEffectReceipt
import com.mica.music.media.usb.protocol.WriteKind
import com.mica.music.media.usb.shadow.UsbExclusiveShadowAdapter
import com.mica.music.media.usb.shadow.UsbExclusiveShadowAdapterKind
import com.mica.music.media.usb.shadow.UsbExclusiveShadowCoordinator
import com.mica.music.media.usb.shadow.UsbExclusiveShadowStack
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbM4RedemptionContractTest {
    @Test
    fun staleGenerationCannotInvokeUsbOpenCallback() {
        val owner = UsbOutputSessionOwner()
        repeat(4) { owner.invalidate() }
        val context = UsbP2RedemptionContext(owner, request())
        val reservedTarget = context.prepareProtocolBinding()
        assertTrue(reservedTarget is OutputTarget.UsbBound)
        owner.invalidate()
        var openEffects = 0

        assertThrows(IllegalStateException::class.java) {
            context.consumeCurrent { _, _ ->
                openEffects++
                TrackingSession(UsbAudioRuntimeHandle(99))
            }
        }

        assertEquals(0, openEffects)
    }

    @Test
    fun generationFivePermitCannotTouchDelegateAfterP2RotatesToSix() {
        val harness = harness()
        val permit = preparePcm(harness)
        val oldTarget = permit.outputTarget
        harness.owner.restart(harness.session)
        var effects = 0

        assertNotEquals(oldTarget, harness.context.currentBinding().target)
        assertThrows(IllegalStateException::class.java) {
            harness.context.ensurePermitTarget(permit.outputTarget)
            effects++
        }
        assertEquals(0, effects)
    }

    @Test
    fun generationFiveWriteLeaseCannotTouchDelegateAfterP2RotatesToSix() {
        val harness = harness()
        val lease = configurePcm(harness)
        val oldTarget = lease.identity.outputTarget
        harness.owner.restart(harness.session)
        var effects = 0

        assertThrows(IllegalStateException::class.java) {
            harness.context.withProtocolWrite(oldTarget, lease, WriteKind.PCM_DATA) {
                effects++
            }
        }
        lease.exit()
        assertEquals(0, effects)
    }

    @Test
    fun exactPcmPermitP2SessionAndWriteLeaseReachDelegateOnce() {
        val harness = harness()
        val lease = configurePcm(harness)
        var effects = 0

        harness.context.withProtocolWrite(
            harness.context.currentBinding().target,
            lease,
            WriteKind.PCM_DATA,
        ) {
            harness.context.requireProtocolWrite(
                harness.context.currentBinding().target,
                WriteKind.PCM_DATA,
            )
            effects++
        }
        lease.exit()

        assertEquals(1, effects)
        assertTrue(harness.owner.facts.phase == UsbOutputPhase.ACTIVE)
        assertTrue(harness.stack.snapshot().familyOwnership is FamilyOwnership.PcmOwned)
    }

    @Test
    fun directContentScopeRejectsGapKindBeforeAnyEffect() {
        val harness = harness()
        val lease = configureDirect(harness)
        var effects = 0

        harness.context.withProtocolWrite(
            harness.context.currentBinding().target,
            lease,
            WriteKind.DOP_CONTENT,
        ) {
            assertThrows(IllegalStateException::class.java) {
                harness.context.requireProtocolWrite(
                    harness.context.currentBinding().target,
                    WriteKind.DOP_GAP,
                )
            }
            effects++
        }
        lease.exit()

        assertEquals(1, effects)
        assertTrue(harness.stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    @Test
    fun directGapScopeRejectsContentKindAfterExactPauseTransition() {
        val harness = harness()
        val contentLease = configureDirect(harness)
        contentLease.exit()
        harness.coordinator.publishSemanticIntent(false)
        val gapLease = requireNotNull(
            harness.directAdapter.tryEnterWrite(harness.occurrence, WriteKind.DOP_GAP),
        )
        var effects = 0

        harness.context.withProtocolWrite(
            harness.context.currentBinding().target,
            gapLease,
            WriteKind.DOP_GAP,
        ) {
            assertThrows(IllegalStateException::class.java) {
                harness.context.requireProtocolWrite(
                    harness.context.currentBinding().target,
                    WriteKind.DOP_CONTENT,
                )
            }
            effects++
        }
        gapLease.exit()

        assertEquals(1, effects)
        assertTrue(harness.stack.snapshot().familyOwnership is FamilyOwnership.DopOwned)
    }

    @Test
    fun detachPublishesNewGenerationDrainsOldWriterCleansSessionThenAllowsSuccessor() {
        val published = Collections.synchronizedList(mutableListOf<Long>())
        val harness = harness(published)
        val lease = configurePcm(harness)
        val entered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>(null)
        var effects = 0

        val writer = thread(name = "m4-pcm-writer") {
            try {
                harness.owner.withActiveSession(harness.session) { p2Lease ->
                    harness.context.withProtocolWrite(
                        harness.context.currentBinding().target,
                        lease,
                        WriteKind.PCM_DATA,
                    ) {
                        entered.countDown()
                        check(releaseWriter.await(5, TimeUnit.SECONDS))
                        try {
                            harness.context.requireProtocolWrite(
                                harness.context.currentBinding().target,
                                WriteKind.PCM_DATA,
                            )
                            p2Lease.io { effects++ }
                        } catch (error: Throwable) {
                            writerFailure.set(error)
                        }
                    }
                }
            } finally {
                lease.exit()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val detach = thread(name = "m4-usb-detach") {
            assertEquals(
                UsbDeviceDetachDisposition.RELEASED_CURRENT,
                harness.owner.deviceDetached(harness.session.runtimeHandle),
            )
        }
        while (!published.contains(6L)) Thread.yield()
        releaseWriter.countDown()
        writer.join(5_000)
        detach.join(5_000)

        assertFalse(writer.isAlive)
        assertFalse(detach.isAlive)
        assertNotNull(writerFailure.get())
        assertEquals(0, effects)
        assertEquals(1, harness.session.releaseCount)

        val successor = UsbP2RedemptionContext(harness.owner, request())
        val successorTarget = successor.prepareProtocolBinding()
        assertTrue(successorTarget is OutputTarget.UsbBound)
        assertTrue((successorTarget as OutputTarget.UsbBound).generation.value > 6L)
        successor.consumeCurrent { _, _ -> TrackingSession(UsbAudioRuntimeHandle(88)) }
        assertEquals(UsbOutputPhase.ACTIVE, harness.owner.facts.phase)
    }

    @Test
    fun lateDetachOfOldRuntimeCannotInvalidateSuccessorBinding() {
        val harness = harness()
        val oldRuntime = harness.session.runtimeHandle
        val successor = UsbP2RedemptionContext(harness.owner, request())
        successor.prepareProtocolBinding()
        successor.consumeCurrent { _, _ -> TrackingSession(UsbAudioRuntimeHandle(88)) }

        assertEquals(
            UsbDeviceDetachDisposition.STALE_RUNTIME,
            harness.owner.deviceDetached(oldRuntime),
        )
        assertEquals(UsbAudioRuntimeHandle(88), harness.owner.facts.runtimeHandle)
        assertTrue(successor.currentBinding().isProtocolCurrent())
    }

    @Test
    fun invalidatedBindingCanCleanupButCannotSubmitContent() {
        val harness = harness()
        val lease = configurePcm(harness)
        harness.owner.invalidate()
        var effects = 0

        assertThrows(IllegalStateException::class.java) {
            harness.context.withProtocolWrite(
                harness.context.currentBinding().target,
                lease,
                WriteKind.PCM_DATA,
            ) {
                effects++
            }
        }
        lease.exit()
        harness.owner.release(harness.session, "m4-invalidation-cleanup")

        assertEquals(0, effects)
        assertEquals(1, harness.session.releaseCount)
    }

    @Test
    fun staleActiveSessionStillAllowsCleanupLeaseButCannotSubmitContent() {
        val harness = harness()
        harness.owner.invalidate()
        var cleanupEffects = 0
        var contentEffects = 0

        assertEquals(
            true,
            harness.owner.withActiveSessionCleanup(harness.session) { cleanup ->
                cleanup.io {
                    cleanupEffects++
                    true
                }
            },
        )
        assertEquals(
            null,
            harness.owner.withActiveSession(harness.session) { lease ->
                lease.io { contentEffects++ }
            },
        )

        harness.owner.release(harness.session, "m4-stale-cleanup")
        assertEquals(1, cleanupEffects)
        assertEquals(0, contentEffects)
    }

    private fun preparePcm(harness: Harness): com.mica.music.media.usb.protocol.PcmConfigurePermit {
        seedDestination(harness, PlaybackFamily.PCM, "pcm-facts")
        return requireNotNull(
            harness.pcmAdapter.preparePcmConfigure(harness.occurrence, "sink-format"),
        )
    }

    private fun configurePcm(harness: Harness): ActiveWriteLease {
        val permit = preparePcm(harness)
        val disposition = harness.pcmAdapter.commitPcmConfigure(
            permit,
            harness.occurrence,
            ResourceIdentity("pcm-runtime"),
            "delegate-configured",
        )
        assertTrue(disposition is CommitDisposition.CurrentPlaying)
        return requireNotNull(
            harness.pcmAdapter.tryEnterWrite(harness.occurrence, WriteKind.PCM_DATA),
        )
    }

    private fun configureDirect(harness: Harness): ActiveWriteLease {
        seedDestination(harness, PlaybackFamily.DOP, "dop-facts")
        val runtime = RuntimeIdentity("direct-runtime")
        listOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL).forEach { stage ->
            val permit = requireNotNull(
                harness.directAdapter.prepareDirectStage(harness.occurrence, stage, runtime),
            )
            assertEquals(null, harness.directAdapter.commitDirectStage(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("$stage"),
                    "stage-complete",
                    runtime,
                ),
            ))
        }
        harness.directAdapter.observeDirectStarted(harness.occurrence)
        listOf(DirectStage.ARM, DirectStage.SOURCE_ACCEPT).forEach { stage ->
            val permit = requireNotNull(
                harness.directAdapter.prepareDirectStage(harness.occurrence, stage, runtime),
            )
            val disposition = harness.directAdapter.commitDirectStage(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("$stage"),
                    "stage-complete",
                    runtime,
                ),
            )
            if (stage == DirectStage.SOURCE_ACCEPT) {
                assertTrue(disposition is CommitDisposition.CurrentPlaying)
            } else {
                assertEquals(null, disposition)
            }
        }
        return requireNotNull(
            harness.directAdapter.tryEnterWrite(harness.occurrence, WriteKind.DOP_CONTENT),
        )
    }

    private fun seedDestination(harness: Harness, family: PlaybackFamily, facts: String) {
        harness.stack.observeTimelinePeriod("A", harness.occurrence.periodUid)
        harness.stack.observeApplicationMedia("A")
        harness.stack.observeManualNavigation("A", "m4-seed")
        harness.stack.observeCurrentPlayerOccurrence("A", harness.occurrence)
        val adapter = if (family == PlaybackFamily.PCM) harness.pcmAdapter else harness.directAdapter
        adapter.observeStream(harness.occurrence, family, facts)
        assertEquals(family, harness.stack.snapshot().mutation?.targetFamily)
    }

    private fun harness(
        published: MutableList<Long> = Collections.synchronizedList(mutableListOf()),
    ): Harness {
        val owner = UsbOutputSessionOwner(onGenerationPublished = { published += it })
        repeat(4) { owner.invalidate() }
        val context = UsbP2RedemptionContext(owner, request())
        val target = requireNotNull(context.prepareProtocolBinding()) as OutputTarget.UsbBound
        val session = TrackingSession(UsbAudioRuntimeHandle(77))
        context.consumeCurrent { _, _ -> session }
        val coordinator = UsbExclusiveShadowCoordinator { }
        coordinator.publishSemanticIntent(true)
        val stack = coordinator.createStack(target)
        val pcmAdapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.PLATFORM_PCM)
        val directAdapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.DIRECT_DOP)
        return Harness(owner, context, session, coordinator, stack, pcmAdapter, directAdapter, target)
    }

    private fun request(): UsbOutputRequest = UsbOutputRequest(
        device = UsbAudioDeviceIdentity(0x1234, 0x5678, "m4-device"),
    )

    private data class Harness(
        val owner: UsbOutputSessionOwner,
        val context: UsbP2RedemptionContext,
        val session: TrackingSession,
        val coordinator: UsbExclusiveShadowCoordinator,
        val stack: UsbExclusiveShadowStack,
        val pcmAdapter: UsbExclusiveShadowAdapter,
        val directAdapter: UsbExclusiveShadowAdapter,
        val target: OutputTarget.UsbBound,
        val occurrence: PlaybackOccurrence = PlaybackOccurrence("m4-period", 1L),
    )

    private class TrackingSession(
        val runtimeHandle: UsbAudioRuntimeHandle,
    ) : UsbOutputSession {
        var releaseCount: Int = 0
            private set

        override val activeFacts: PlaybackOutputFacts = PlaybackOutputFacts(
            runtimeHandle = runtimeHandle,
            attached = true,
            permission = UsbPermissionState.GRANTED,
            claimed = true,
            exclusive = true,
            signalExact = true,
        )

        override fun restart(lease: UsbOutputRequestLease) {
            lease.ensureCurrent()
        }

        override fun release(lease: UsbOutputCleanupLease, reason: String) {
            lease.ensureSerialized()
            releaseCount++
        }
    }
}
