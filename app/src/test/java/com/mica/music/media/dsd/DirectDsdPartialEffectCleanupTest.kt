package com.mica.music.media.dsd

import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RuntimeIdentity
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdPartialEffectCleanupTest {
    private val facts = com.mica.music.media.dsf.DsfExtractorPacketFacts(
        sourceSampleRateHz = 5_644_800,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )

    @Test
    fun prefillPartialEffectThrowLeavesPendingStageAndBlocksRetirementAndSuccessor() {
        val harness = TestProtocolHarness.create()
        val target = PlaybackOccurrence("period-B", 2L)
        val successor = PlaybackOccurrence("period-C", 3L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)
        harness.releaseDirectSource()

        val original = IllegalStateException("prefill-partial-effect")
        var superseded = false
        val session = PartialWriteThrowingSession(facts, original) {
            if (superseded) return@PartialWriteThrowingSession
            superseded = true
            harness.stack.observeTimelinePeriod("C", successor.periodUid)
            harness.stack.observeApplicationMedia("C")
            assertNotNull(harness.stack.beginManualNavigation("C", "test-supersede"))
            harness.directAdapter.observeStream(successor, PlaybackFamily.DOP, "dop-target")
            harness.stack.observeCurrentPlayerOccurrence("C", successor)
        }
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { session },
            playbackAdapter = harness.directAdapter,
        )
        setPrivateField(renderer, "shadowOccurrence", target)
        val pump = invokeOpenPump(renderer, facts)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1L)

        try {
            invokePumpWithProtocol(renderer, pump)
        } catch (error: Throwable) {
            assertSame(original, error)
        }

        val supersedingMutation = harness.stack.snapshot().mutation
        assertEquals(successor, supersedingMutation?.targetOccurrence)
        val successorPermit = checkNotNull(
            harness.directAdapter.prepareDirectStage(
                successor,
                DirectStage.CREATE_RUNTIME,
                RuntimeIdentity("successor-runtime"),
            ),
        ) { "exact cleanup of B must unblock the superseding C destination" }
        assertEquals(
            CommitDisposition.RetryPendingSameMutation,
            harness.directAdapter.commitDirectStage(
                successorPermit,
                com.mica.music.media.usb.protocol.SideEffectReceipt.NotStarted(successorPermit.activationId),
            ),
        )

        harness.stack.protocol.beginRetiring()
        assertEquals(ProtocolLifecycle.Retired, harness.stack.snapshot().lifecycle)
        assertTrue(harness.stack.snapshot().inFlightActivations.isEmpty())
        assertEquals(1, session.writeCalls)
        assertEquals(1, session.partialBytes)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun armPartialEffectThrowConsumesArmPermitAndRetiresExactRuntime() {
        val harness = TestProtocolHarness.create()
        val target = PlaybackOccurrence("period-B", 2L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)
        harness.releaseDirectSource()

        val original = IllegalStateException("arm-partial-effect")
        val session = PartialWriteThrowingSession(
            facts = facts,
            original = original,
            failWrite = false,
            armFailure = original,
        ) {}
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { session },
            playbackAdapter = harness.directAdapter,
        )
        setPrivateField(renderer, "shadowOccurrence", target)
        val pump = invokeOpenPump(renderer, facts)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1L)
        invokePumpWithProtocol(renderer, pump)
        assertTrue(harness.directAdapter.acceptDirectStarted(target))

        try {
            invokeArmAndSourceAccept(renderer, pump)
        } catch (error: Throwable) {
            assertSame(original, error)
        }

        assertEquals(1, session.armCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(harness.stack.snapshot().inFlightActivations.isEmpty())
        assertTrue(harness.stack.snapshot().cleanupRequirements.isEmpty())
        harness.stack.protocol.beginRetiring()
        assertEquals(ProtocolLifecycle.Retired, harness.stack.snapshot().lifecycle)
    }

    @Test
    fun retainedPartialEffectThrowCleansExactRuntimeAndReleasesOldFamily() {
        val harness = TestProtocolHarness.create()
        val source = PlaybackOccurrence("period-A", 1L)
        val target = PlaybackOccurrence("period-B", 2L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)

        val original = IllegalStateException("retained-partial-effect")
        val session = PartialWriteThrowingSession(
            facts = facts,
            original = original,
            failWrite = false,
            retainedFailure = original,
        ) {}
        val pump = DirectDsdRendererPump(facts, session)
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { error("unused for retained handoff") },
            playbackAdapter = harness.directAdapter,
        )
        setPrivateField(renderer, "shadowOccurrence", target)
        setPrivateField(renderer, "shadowRuntimeOccurrence", source)
        setPrivateField(renderer, "shadowRuntimeIdentity", RuntimeIdentity("test-direct-runtime"))

        try {
            invokeRetainedTransition(renderer, pump, facts.copy(sourceBitOrder = DsdSourceBitOrder.MSB_FIRST))
        } catch (error: Throwable) {
            assertSame(original, error)
        }

        assertEquals(1, session.transitionCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(harness.stack.snapshot().inFlightActivations.isEmpty())
        assertTrue(harness.stack.snapshot().cleanupRequirements.isEmpty())
        assertEquals(com.mica.music.media.usb.protocol.FamilyOwnership.None, harness.stack.snapshot().familyOwnership)
    }

    @Test
    fun createRuntimePostOpenConstructionFailureConsumesPermitAndRetires() {
        val harness = TestProtocolHarness.create()
        val target = PlaybackOccurrence("period-B", 2L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)
        harness.releaseDirectSource()

        val returnedSession = PartialWriteThrowingSession(
            facts = facts.copy(sourceBitOrder = DsdSourceBitOrder.MSB_FIRST),
            original = IllegalStateException("unused-create-construction-failure"),
            failWrite = false,
        ) {}
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { returnedSession },
            playbackAdapter = harness.directAdapter,
        )
        setPrivateField(renderer, "shadowOccurrence", target)

        try {
            invokeOpenPump(renderer, facts)
        } catch (_: InvocationTargetException) {
            // The construction postcondition is the intentionally failing side effect.
        }

        assertEquals(1, returnedSession.closeCalls)
        assertTrue(harness.stack.snapshot().inFlightActivations.isEmpty())
        assertTrue(harness.stack.snapshot().cleanupRequirements.isEmpty())
        harness.stack.protocol.beginRetiring()
        assertEquals(ProtocolLifecycle.Retired, harness.stack.snapshot().lifecycle)
    }

    @Test
    fun retainedFailureCleanupRejectsWrongAndDuplicateOwnerIdentities() {
        val harness = TestProtocolHarness.create()
        val source = PlaybackOccurrence("period-A", 1L)
        val target = PlaybackOccurrence("period-B", 2L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)
        val runtime = RuntimeIdentity("test-direct-runtime")
        val permit = checkNotNull(
            harness.directAdapter.prepareRetainedDirectHandoff(source, target, runtime),
        )
        val wrongRuntime = harness.directAdapter.commitRetainedDirectHandoff(
            permit,
            com.mica.music.media.usb.protocol.SideEffectReceipt.TerminalFailure(
                permit.activationId,
                ResourceIdentity("wrong-runtime-resource"),
                "wrong-runtime",
                RuntimeIdentity("wrong-runtime"),
            ),
        )
        assertEquals(com.mica.music.media.usb.protocol.CommitDisposition.StaleNoEffect, wrongRuntime)
        assertTrue(harness.stack.snapshot().inFlightActivations.contains(permit.activationId))

        val wrongSource = permit.copy(sourceOccurrence = PlaybackOccurrence("period-wrong-source", 9L))
        assertEquals(
            com.mica.music.media.usb.protocol.CommitDisposition.StaleNoEffect,
            harness.directAdapter.commitRetainedDirectHandoff(
                wrongSource,
                com.mica.music.media.usb.protocol.SideEffectReceipt.TerminalFailure(
                    permit.activationId,
                    ResourceIdentity("wrong-source-resource"),
                    "wrong-source",
                    runtime,
                ),
            ),
        )
        assertTrue(harness.stack.snapshot().inFlightActivations.contains(permit.activationId))

        val wrongOccurrence = permit.copy(targetOccurrence = PlaybackOccurrence("period-C", 3L))
        assertEquals(
            com.mica.music.media.usb.protocol.CommitDisposition.StaleNoEffect,
            harness.directAdapter.commitRetainedDirectHandoff(
                wrongOccurrence,
                com.mica.music.media.usb.protocol.SideEffectReceipt.TerminalFailure(
                    permit.activationId,
                    ResourceIdentity("wrong-occurrence-resource"),
                    "wrong-occurrence",
                    runtime,
                ),
            ),
        )
        val resource = ResourceIdentity("${runtime}:retained-${target.windowSequenceNumber}")
        assertEquals(
            com.mica.music.media.usb.protocol.CommitDisposition.CurrentCleanupRequired(
                resource,
                com.mica.music.media.usb.protocol.CleanupContinuation.TERMINAL,
            ),
            harness.directAdapter.commitRetainedDirectHandoff(
                permit,
                com.mica.music.media.usb.protocol.SideEffectReceipt.TerminalFailure(
                    permit.activationId,
                    resource,
                    "terminal",
                    runtime,
                ),
            ),
        )

        assertTrue(harness.directAdapter.cleanupRequirements(permit.activationId).isNotEmpty())
        assertEquals(
            null,
            harness.directAdapter.completeCleanup(permit.activationId, ResourceIdentity("wrong-cleanup")),
        )
        val duplicateAfterExact = harness.directAdapter.completeCleanup(permit.activationId, resource)
        assertEquals(com.mica.music.media.usb.protocol.CommitDisposition.TerminalFailure, duplicateAfterExact)
        assertEquals(null, harness.directAdapter.completeCleanup(permit.activationId, resource))
        assertTrue(harness.stack.snapshot().cleanupRequirements.isEmpty())
    }

    @Test
    fun cleanupFailurePreservesTransportErrorAndLeavesAuthorityFailClosed() {
        val harness = TestProtocolHarness.create()
        val target = PlaybackOccurrence("period-B", 2L)
        harness.beginDestination("B", target, PlaybackFamily.DOP, "dop-target", playing = true)
        harness.releaseDirectSource()

        val original = IllegalStateException("prefill-primary-error")
        val cleanupFailure = IllegalStateException("runtime-close-error")
        val session = PartialWriteThrowingSession(
            facts = facts,
            original = original,
            closeFailure = cleanupFailure,
        ) {}
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { session },
            playbackAdapter = harness.directAdapter,
        )
        setPrivateField(renderer, "shadowOccurrence", target)
        val pump = invokeOpenPump(renderer, facts)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1L)

        try {
            invokePumpWithProtocol(renderer, pump)
            throw AssertionError("expected original transport failure")
        } catch (error: Throwable) {
            assertSame(original, error)
            assertTrue(error.suppressed.any { it === cleanupFailure })
        }
        assertTrue(harness.stack.snapshot().cleanupRequirements.isNotEmpty())
        harness.stack.protocol.beginRetiring()
        assertTrue(harness.stack.snapshot().lifecycle is ProtocolLifecycle.Retiring)
    }

    private fun invokeOpenPump(
        renderer: DirectDsdMedia3Renderer,
        facts: com.mica.music.media.dsf.DsfExtractorPacketFacts,
    ): DirectDsdRendererPump {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "openPumpIfNeeded",
            com.mica.music.media.dsf.DsfExtractorPacketFacts::class.java,
        )
        method.isAccessible = true
        return method.invoke(renderer, facts) as DirectDsdRendererPump
    }

    private fun invokePumpWithProtocol(
        renderer: DirectDsdMedia3Renderer,
        pump: DirectDsdRendererPump,
    ) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "pumpWithProtocol",
            DirectDsdRendererPump::class.java,
        )
        method.isAccessible = true
        try {
            method.invoke(renderer, pump)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun invokeArmAndSourceAccept(
        renderer: DirectDsdMedia3Renderer,
        pump: DirectDsdRendererPump,
    ) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "observeShadowArmAndSourceAccept",
            DirectDsdRendererPump::class.java,
        )
        method.isAccessible = true
        try {
            method.invoke(renderer, pump)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun invokeRetainedTransition(
        renderer: DirectDsdMedia3Renderer,
        pump: DirectDsdRendererPump,
        newFacts: com.mica.music.media.dsf.DsfExtractorPacketFacts,
    ) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "transitionRetainedSourceWithProtocol",
            DirectDsdRendererPump::class.java,
            com.mica.music.media.dsf.DsfExtractorPacketFacts::class.java,
        )
        method.isAccessible = true
        try {
            method.invoke(renderer, pump, newFacts)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun setPrivateField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).also {
            it.isAccessible = true
            it.set(instance, value)
        }
    }

    private class PartialWriteThrowingSession(
        override val facts: com.mica.music.media.dsf.DsfExtractorPacketFacts,
        private val original: Throwable,
        private val failWrite: Boolean = true,
        private val armFailure: Throwable? = null,
        private val retainedFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val beforeThrow: () -> Unit,
    ) : DirectDsdTransportSession {
        override val startupPrefillReady: Boolean = !failWrite
        override val playbackArmed: Boolean = false
        var writeCalls = 0
        var armCalls = 0
        var partialBytes = 0
        var closeCalls = 0
        var transitionCalls = 0

        override fun writeCanonical(
            bytes: ByteArray,
            offset: Int,
            byteCount: Int,
        ): DirectDsdTransportWriteResult {
            writeCalls++
            if (!failWrite) return DirectDsdTransportWriteResult(byteCount)
            partialBytes += minOf(1, byteCount)
            beforeThrow()
            throw original
        }

        override fun armPlayback() {
            armCalls++
            throw (armFailure ?: IllegalStateException("unused in PREFILL red test"))
        }

        override fun startPauseGapLiveness() = error("unused in PREFILL red test")

        override fun stopPauseGapLiveness() = error("unused in PREFILL red test")

        override fun quiescePauseGapForOutputRebuild(): Boolean = false

        override fun transitionRetainedSource(
            newFacts: com.mica.music.media.dsf.DsfExtractorPacketFacts,
        ): DirectDsdRetainedSourceTransitionResult {
            transitionCalls++
            retainedFailure?.let {
                beforeThrow()
                throw it
            }
            error("unused in partial-stage tests")
        }

        override fun prepareFreshTrackTransition(
            reason: DoPCarrierSessionReset,
        ): DirectDsdFreshTransitionPreparationResult = error("unused in PREFILL red test")

        override fun finishEndOfStream(): Boolean = true

        override fun close() {
            closeCalls++
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        fun reverse(value: Byte): Byte =
            (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()
    }
}
