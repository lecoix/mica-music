package com.mica.music.data.library

import kotlin.math.max
import kotlin.math.min

/** Pure timing/cooldown rules used by [LibrarySyncScheduler]. */
internal object AutoSyncWakePolicy {
    fun usesCooldown(cause: LibraryOperationCause): Boolean =
        when (cause) {
            LibraryOperationCause.FOREGROUND_CATCH_UP,
            LibraryOperationCause.SAF_PERIODIC_VERIFY,
            -> true
            else -> false
        }

    fun pendingUsesCooldown(causes: Collection<LibraryOperationCause>): Boolean =
        causes.isNotEmpty() && causes.all(::usesCooldown)

    fun dueAtMs(
        timing: LibrarySyncSchedulerTiming,
        burstStartedAtMs: Long,
        lastDirtyAtMs: Long,
        nextAllowedAutoSyncAtMs: Long,
        usesCooldown: Boolean,
    ): Long {
        val trailingDeadline = lastDirtyAtMs + timing.debounceMs
        val starvationDeadline = burstStartedAtMs + timing.maxDebounceMs
        val debounceDeadline = min(trailingDeadline, starvationDeadline)
        return if (usesCooldown) {
            max(nextAllowedAutoSyncAtMs, debounceDeadline)
        } else {
            debounceDeadline
        }
    }

    fun wakeReason(
        timing: LibrarySyncSchedulerTiming,
        burstStartedAtMs: Long,
        lastDirtyAtMs: Long,
        nextAllowedAutoSyncAtMs: Long,
        usesCooldown: Boolean,
    ): AutoSyncWakeReason {
        val trailingDeadline = lastDirtyAtMs + timing.debounceMs
        val starvationDeadline = burstStartedAtMs + timing.maxDebounceMs
        val debounceDeadline = min(trailingDeadline, starvationDeadline)
        return when {
            usesCooldown && nextAllowedAutoSyncAtMs > debounceDeadline ->
                AutoSyncWakeReason.COOLDOWN
            starvationDeadline <= trailingDeadline -> AutoSyncWakeReason.MAX_DEBOUNCE
            else -> AutoSyncWakeReason.TRAILING_DEBOUNCE
        }
    }

    fun safeAdd(nowMs: Long, delayMs: Long): Long {
        require(delayMs >= 0L)
        return if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs
    }
}
