package com.mica.music.media.dsd

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdTeardownQuiescenceStateTest {
    @Test
    fun activeGapQuiescesBeforeOwnerInvalidation() {
        val state = DirectDsdTeardownQuiescenceState()
        val session = DirectDsdSessionGeneration(rendererGeneration = 7, sessionGeneration = 2)
        val effects = mutableListOf<String>()
        assertTrue(
            state.register(session) {
                effects += "gap-stop-joined"
                true
            },
        )

        val outcome = state.quiesceBeforeOwnerInvalidation(
            onQuiesced = { effects += "quiesced-$it" },
            invalidateOwner = { effects += "owner-invalidate" },
        )

        assertEquals(DirectDsdTeardownQuiesceOutcome.QUIESCED, outcome)
        assertEquals(
            listOf(
                "gap-stop-joined",
                "quiesced-QUIESCED",
                "owner-invalidate",
            ),
            effects,
        )
    }

    @Test
    fun contentOrNeverStartedGapIsNoOpAndInvalidationStillProceeds() {
        val state = DirectDsdTeardownQuiescenceState()
        val effects = mutableListOf<String>()
        assertTrue(
            state.register(DirectDsdSessionGeneration(3, 1)) {
                effects += "checked-content"
                false
            },
        )

        val outcome = state.quiesceBeforeOwnerInvalidation(
            onQuiesced = { effects += "quiesced-$it" },
            invalidateOwner = { effects += "owner-invalidate" },
        )

        assertEquals(DirectDsdTeardownQuiesceOutcome.NO_ACTIVE_GAP, outcome)
        assertEquals(
            listOf("checked-content", "quiesced-NO_ACTIVE_GAP", "owner-invalidate"),
            effects,
        )
    }

    @Test
    fun staleRegistrationAndUnregisterCannotTouchNewerSession() {
        val state = DirectDsdTeardownQuiescenceState()
        val oldSession = DirectDsdSessionGeneration(4, 1)
        val newerSession = DirectDsdSessionGeneration(4, 2)
        var oldCalls = 0
        var newerCalls = 0

        assertTrue(state.register(oldSession) { oldCalls++; true })
        assertTrue(state.register(newerSession) { newerCalls++; true })
        assertFalse(state.unregister(oldSession))
        assertFalse(state.register(oldSession) { oldCalls++; true })
        assertEquals(newerSession, state.activeSessionForTest())

        assertEquals(DirectDsdTeardownQuiesceOutcome.QUIESCED, state.quiesceActive())
        assertEquals(0, oldCalls)
        assertEquals(1, newerCalls)
        assertTrue(state.unregister(newerSession))
        assertNull(state.activeSessionForTest())
    }

    @Test
    fun olderRendererCannotReplaceNewerRendererRegistration() {
        val state = DirectDsdTeardownQuiescenceState()
        val newer = DirectDsdSessionGeneration(10, 1)
        val older = DirectDsdSessionGeneration(9, 99)
        var newerCalls = 0

        assertTrue(state.register(newer) { newerCalls++; false })
        assertFalse(state.register(older) { error("older renderer must never quiesce") })
        assertEquals(DirectDsdTeardownQuiesceOutcome.NO_ACTIVE_GAP, state.quiesceActive())
        assertEquals(1, newerCalls)
    }

    @Test
    fun quiesceFailureFailsClosedBeforeOwnerInvalidation() {
        val state = DirectDsdTeardownQuiescenceState()
        var invalidated = false
        assertTrue(
            state.register(DirectDsdSessionGeneration(5, 1)) {
                error("stale active GAP")
            },
        )

        val failure = runCatching {
            state.quiesceBeforeOwnerInvalidation(invalidateOwner = { invalidated = true })
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(invalidated)
    }

    @Test
    fun newerRegistrationCannotSlipBetweenGapJoinAndOwnerInvalidation() {
        val state = DirectDsdTeardownQuiescenceState()
        val oldSession = DirectDsdSessionGeneration(20, 1)
        val newerSession = DirectDsdSessionGeneration(21, 1)
        val quiesceEntered = CountDownLatch(1)
        val finishQuiesce = CountDownLatch(1)
        val registerFinished = CountDownLatch(1)
        val effects = Collections.synchronizedList(mutableListOf<String>())
        assertTrue(
            state.register(oldSession) {
                effects += "gap-stop-enter"
                quiesceEntered.countDown()
                assertTrue(finishQuiesce.await(1, TimeUnit.SECONDS))
                effects += "gap-stop-joined"
                true
            },
        )

        val rebuild = Thread {
            state.quiesceBeforeOwnerInvalidation(
                invalidateOwner = { effects += "owner-invalidate" },
            )
        }
        rebuild.start()
        assertTrue(quiesceEntered.await(1, TimeUnit.SECONDS))

        val register = Thread {
            assertTrue(state.register(newerSession) { false })
            effects += "new-register"
            registerFinished.countDown()
        }
        register.start()
        assertFalse(registerFinished.await(50, TimeUnit.MILLISECONDS))

        finishQuiesce.countDown()
        rebuild.join(1_000)
        register.join(1_000)
        assertFalse(rebuild.isAlive)
        assertFalse(register.isAlive)
        assertEquals(
            listOf("gap-stop-enter", "gap-stop-joined", "owner-invalidate", "new-register"),
            effects.toList(),
        )
        assertEquals(newerSession, state.activeSessionForTest())
    }

    @Test
    fun noRegisteredDirectSessionStillAllowsOwnerInvalidation() {
        val state = DirectDsdTeardownQuiescenceState()
        var invalidated = false

        val outcome = state.quiesceBeforeOwnerInvalidation(invalidateOwner = { invalidated = true })

        assertEquals(DirectDsdTeardownQuiesceOutcome.NO_ACTIVE_SESSION, outcome)
        assertTrue(invalidated)
    }
}
