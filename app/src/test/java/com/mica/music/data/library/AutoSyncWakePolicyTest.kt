package com.mica.music.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncWakePolicyTest {
    private val timing = LibrarySyncSchedulerTiming(
        debounceMs = 1_500L,
        cooldownMs = 60_000L,
        maxDebounceMs = 5_000L,
    )

    @Test
    fun cooldownAppliesOnlyToCatchUpAndPeriodicVerification() {
        assertTrue(AutoSyncWakePolicy.usesCooldown(LibraryOperationCause.FOREGROUND_CATCH_UP))
        assertTrue(AutoSyncWakePolicy.usesCooldown(LibraryOperationCause.SAF_PERIODIC_VERIFY))
        assertFalse(AutoSyncWakePolicy.usesCooldown(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY))
        assertFalse(AutoSyncWakePolicy.pendingUsesCooldown(emptyList()))
        assertTrue(
            AutoSyncWakePolicy.pendingUsesCooldown(
                listOf(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
            ),
        )
        assertFalse(
            AutoSyncWakePolicy.pendingUsesCooldown(
                listOf(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
            ),
        )
    }

    @Test
    fun dueAtUsesTrailingDeadlineUntilMaxDebounceWins() {
        assertEquals(
            3_500L,
            AutoSyncWakePolicy.dueAtMs(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 2_000L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = false,
            ),
        )
        assertEquals(
            6_000L,
            AutoSyncWakePolicy.dueAtMs(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 5_500L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = false,
            ),
        )
    }

    @Test
    fun cooldownCanDelayOtherwiseReadyDebounce() {
        assertEquals(
            60_000L,
            AutoSyncWakePolicy.dueAtMs(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 2_000L,
                nextAllowedAutoSyncAtMs = 60_000L,
                usesCooldown = true,
            ),
        )
        assertEquals(
            AutoSyncWakeReason.COOLDOWN,
            AutoSyncWakePolicy.wakeReason(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 2_000L,
                nextAllowedAutoSyncAtMs = 60_000L,
                usesCooldown = true,
            ),
        )
    }

    @Test
    fun wakeReasonDistinguishesTrailingAndMaxDebounce() {
        assertEquals(
            AutoSyncWakeReason.TRAILING_DEBOUNCE,
            AutoSyncWakePolicy.wakeReason(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 2_000L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = false,
            ),
        )
        assertEquals(
            AutoSyncWakeReason.MAX_DEBOUNCE,
            AutoSyncWakePolicy.wakeReason(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 5_500L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = false,
            ),
        )
    }

    @Test
    fun pureForegroundCatchUpBypassesDebounceButStillRespectsCooldown() {
        assertEquals(
            1_000L,
            AutoSyncWakePolicy.dueAtMs(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 1_000L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = true,
                bypassDebounce = true,
            ),
        )
        assertEquals(
            AutoSyncWakeReason.FOREGROUND_CATCH_UP,
            AutoSyncWakePolicy.wakeReason(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 1_000L,
                nextAllowedAutoSyncAtMs = 0L,
                usesCooldown = true,
                bypassDebounce = true,
            ),
        )
        assertEquals(
            60_000L,
            AutoSyncWakePolicy.dueAtMs(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 1_000L,
                nextAllowedAutoSyncAtMs = 60_000L,
                usesCooldown = true,
                bypassDebounce = true,
            ),
        )
        assertEquals(
            AutoSyncWakeReason.COOLDOWN,
            AutoSyncWakePolicy.wakeReason(
                timing = timing,
                burstStartedAtMs = 1_000L,
                lastDirtyAtMs = 1_000L,
                nextAllowedAutoSyncAtMs = 60_000L,
                usesCooldown = true,
                bypassDebounce = true,
            ),
        )
    }

    @Test
    fun safeAddSaturatesAtLongMaxValue() {
        assertEquals(Long.MAX_VALUE, AutoSyncWakePolicy.safeAdd(Long.MAX_VALUE - 5L, 10L))
        assertEquals(15L, AutoSyncWakePolicy.safeAdd(5L, 10L))
    }
}
