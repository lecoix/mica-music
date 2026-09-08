package com.mica.music.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafProviderDiscoveryBackoffTest {

    @Test
    fun s4FrozenSlowSuccessThresholdAndCadenceAreTenSecondsAndFifteenMinutes() {
        assertEquals(10_000L, SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS)
        assertEquals(15L * 60L * 1_000L, SafProviderDiscoveryBackoff.SLOW_SUCCESS_CADENCE_MS)

        val backoff = SafProviderDiscoveryBackoff()
        val fast = backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 100L,
            wallTimeMs = SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS - 1L,
        )
        assertTrue(!fast.slowSuccess)
        assertTrue(backoff.permit("scope", 100L) is SafProviderDiscoveryPermit.Allowed)

        val slow = backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 200L,
            wallTimeMs = SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS,
        )
        assertTrue(slow.slowSuccess)
        assertEquals(
            200L + SafProviderDiscoveryBackoff.SLOW_SUCCESS_CADENCE_MS,
            slow.nextAllowedAtMs,
        )
        assertTrue(
            backoff.permit("scope", 201L) is
                SafProviderDiscoveryPermit.SlowSuccessCadence,
        )
    }

    @Test
    fun failuresExponentiallyBackOffAndCap() {
        val backoff = SafProviderDiscoveryBackoff(
            baseDelayMs = 10L,
            maxDelayMs = 40L,
        )

        val first = backoff.recordFailure("scope", 100L, "partial")
        assertEquals(10L, first.delayMs)
        assertTrue(backoff.permit("scope", 109L) is SafProviderDiscoveryPermit.BackedOff)
        assertTrue(backoff.permit("scope", 110L) is SafProviderDiscoveryPermit.Allowed)

        val second = backoff.recordFailure("scope", 110L, "unavailable")
        assertEquals(20L, second.delayMs)
        val third = backoff.recordFailure("scope", 130L, "unavailable-again")
        assertEquals(40L, third.delayMs)
        val fourth = backoff.recordFailure("scope", 170L, "still-down")
        assertEquals(40L, fourth.delayMs)
    }

    @Test
    fun completeSuccessResetsFailureBackoff() {
        val backoff = SafProviderDiscoveryBackoff(
            baseDelayMs = 10L,
            maxDelayMs = 40L,
            slowSuccessThresholdMs = 1_000L,
            slowSuccessCadenceMs = 100L,
        )
        backoff.recordFailure("scope", 100L, "partial")

        backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 101L,
            wallTimeMs = 10L,
        )

        assertTrue(backoff.permit("scope", 101L) is SafProviderDiscoveryPermit.Allowed)
        val next = backoff.recordFailure("scope", 101L, "new-failure")
        assertEquals(1, next.failureCount)
        assertEquals(10L, next.delayMs)
    }

    @Test
    fun slowCompleteStartsCadenceButPlaybackReleaseCanBypassIt() {
        val backoff = SafProviderDiscoveryBackoff(
            baseDelayMs = 10L,
            maxDelayMs = 40L,
            slowSuccessThresholdMs = 100L,
            slowSuccessCadenceMs = 1_000L,
        )

        val complete = backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 100L,
            wallTimeMs = 120L,
        )

        assertTrue(complete.slowSuccess)
        assertEquals(1_100L, complete.nextAllowedAtMs)
        val limited = backoff.permit("scope", 101L)
        assertTrue(limited is SafProviderDiscoveryPermit.SlowSuccessCadence)
        assertTrue(
            backoff.permit(
                scopeKey = "scope",
                nowMs = 101L,
                bypassSlowSuccessCadence = true,
            ) is SafProviderDiscoveryPermit.Allowed,
        )
        assertTrue(backoff.permit("scope", 1_100L) is SafProviderDiscoveryPermit.Allowed)
    }

    @Test
    fun fastPostWalkDoesNotEraseCadenceStartedBySlowInitialWalk() {
        val backoff = SafProviderDiscoveryBackoff(
            baseDelayMs = 10L,
            maxDelayMs = 40L,
            slowSuccessThresholdMs = 100L,
            slowSuccessCadenceMs = 1_000L,
        )
        backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 100L,
            wallTimeMs = 120L,
        )

        backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 101L,
            wallTimeMs = 20L,
        )

        assertTrue(
            backoff.permit("scope", 200L) is
                SafProviderDiscoveryPermit.SlowSuccessCadence,
        )

        backoff.recordComplete(
            scopeKey = "scope",
            nowMs = 1_100L,
            wallTimeMs = 20L,
        )
        assertTrue(backoff.permit("scope", 1_100L) is SafProviderDiscoveryPermit.Allowed)
    }

    @Test
    fun sourceOrActivationScopeChangeDoesNotInheritOldProviderState() {
        val backoff = SafProviderDiscoveryBackoff(
            baseDelayMs = 10L,
            maxDelayMs = 40L,
            slowSuccessThresholdMs = 100L,
            slowSuccessCadenceMs = 1_000L,
        )
        backoff.recordFailure("folder-a|activation=1", 100L, "down")
        backoff.recordComplete(
            scopeKey = "folder-a|activation=1",
            nowMs = 100L,
            wallTimeMs = 120L,
        )

        assertTrue(
            backoff.permit("folder-b|activation=2", 101L) is
                SafProviderDiscoveryPermit.Allowed,
        )
    }
}
