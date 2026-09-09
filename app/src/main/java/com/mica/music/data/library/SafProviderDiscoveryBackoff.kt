package com.mica.music.data.library

internal sealed interface SafProviderDiscoveryPermit {
    data object Allowed : SafProviderDiscoveryPermit

    data class BackedOff(
        val failureCount: Int,
        val nextAllowedAtMs: Long,
        val remainingMs: Long,
        val lastFailureDetail: String,
    ) : SafProviderDiscoveryPermit

    data class SlowSuccessCadence(
        val nextAllowedAtMs: Long,
        val remainingMs: Long,
        val lastCompletedWallTimeMs: Long,
        val slowSuccessThresholdMs: Long,
        val cadenceMs: Long,
    ) : SafProviderDiscoveryPermit
}

internal data class SafProviderDiscoveryFailureState(
    val failureCount: Int,
    val nextAllowedAtMs: Long,
    val delayMs: Long,
    val detail: String,
)

internal data class SafProviderDiscoveryCompleteState(
    val slowSuccess: Boolean,
    val wallTimeMs: Long,
    val slowSuccessThresholdMs: Long,
    val nextAllowedAtMs: Long,
    val cadenceMs: Long,
)

/**
 * In-memory S4 provider discovery guard.
 *
 * Scope changes reset both guards. PARTIAL / UNAVAILABLE discovery uses an exponential failure
 * breaker. COMPLETE discovery clears the failure breaker, but a slow successful walk starts a
 * separate cadence guard so a provider that is correct-but-expensive is not hammered by repeated
 * automatic dirty signals.
 * All nowMs/deadline values belong to the same monotonic elapsed-realtime clock. They are
 * process-local and must never be persisted as RetryLedger wall-clock timestamps.
 *
 * The cadence guard is deliberately not a timeout: the successful walk is allowed to complete.
 * Provider cancellation remains cooperative through the DocumentsContract query CancellationSignal.
 */
internal class SafProviderDiscoveryBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val slowSuccessThresholdMs: Long = SLOW_SUCCESS_THRESHOLD_MS,
    private val slowSuccessCadenceMs: Long = SLOW_SUCCESS_CADENCE_MS,
) {
    init {
        require(baseDelayMs >= 0L)
        require(maxDelayMs >= baseDelayMs)
        require(slowSuccessThresholdMs >= 0L)
        require(slowSuccessCadenceMs >= 0L)
    }

    private var scopeKey: String? = null

    private var failureCount: Int = 0
    private var failureNextAllowedAtMs: Long = 0L
    private var lastFailureDetail: String = ""

    private var slowSuccessNextAllowedAtMs: Long = 0L
    private var lastSlowSuccessWallTimeMs: Long = 0L

    @Synchronized
    fun permit(
        scopeKey: String,
        nowMs: Long,
        bypassSlowSuccessCadence: Boolean = false,
    ): SafProviderDiscoveryPermit {
        ensureScope(scopeKey)
        if (failureCount > 0 && nowMs < failureNextAllowedAtMs) {
            return SafProviderDiscoveryPermit.BackedOff(
                failureCount = failureCount,
                nextAllowedAtMs = failureNextAllowedAtMs,
                remainingMs = (failureNextAllowedAtMs - nowMs).coerceAtLeast(0L),
                lastFailureDetail = lastFailureDetail,
            )
        }
        if (
            !bypassSlowSuccessCadence &&
            nowMs < slowSuccessNextAllowedAtMs
        ) {
            return SafProviderDiscoveryPermit.SlowSuccessCadence(
                nextAllowedAtMs = slowSuccessNextAllowedAtMs,
                remainingMs = (slowSuccessNextAllowedAtMs - nowMs).coerceAtLeast(0L),
                lastCompletedWallTimeMs = lastSlowSuccessWallTimeMs,
                slowSuccessThresholdMs = slowSuccessThresholdMs,
                cadenceMs = slowSuccessCadenceMs,
            )
        }
        return SafProviderDiscoveryPermit.Allowed
    }

    @Synchronized
    fun recordFailure(
        scopeKey: String,
        nowMs: Long,
        detail: String,
    ): SafProviderDiscoveryFailureState {
        ensureScope(scopeKey)
        failureCount += 1
        val delay = delayForAttempt(failureCount)
        failureNextAllowedAtMs = safeAdd(nowMs, delay)
        lastFailureDetail = detail
        return SafProviderDiscoveryFailureState(
            failureCount = failureCount,
            nextAllowedAtMs = failureNextAllowedAtMs,
            delayMs = delay,
            detail = detail,
        )
    }

    @Synchronized
    fun recordComplete(
        scopeKey: String,
        nowMs: Long,
        wallTimeMs: Long,
    ): SafProviderDiscoveryCompleteState {
        ensureScope(scopeKey)
        failureCount = 0
        failureNextAllowedAtMs = 0L
        lastFailureDetail = ""

        val normalizedWallTimeMs = wallTimeMs.coerceAtLeast(0L)
        val slowSuccess =
            slowSuccessCadenceMs > 0L &&
                normalizedWallTimeMs >= slowSuccessThresholdMs
        if (slowSuccess) {
            slowSuccessNextAllowedAtMs = safeAdd(nowMs, slowSuccessCadenceMs)
            lastSlowSuccessWallTimeMs = normalizedWallTimeMs
        } else if (nowMs >= slowSuccessNextAllowedAtMs) {
            slowSuccessNextAllowedAtMs = 0L
            lastSlowSuccessWallTimeMs = 0L
        }

        return SafProviderDiscoveryCompleteState(
            slowSuccess = slowSuccess,
            wallTimeMs = normalizedWallTimeMs,
            slowSuccessThresholdMs = slowSuccessThresholdMs,
            nextAllowedAtMs = slowSuccessNextAllowedAtMs,
            cadenceMs = if (slowSuccess) slowSuccessCadenceMs else 0L,
        )
    }

    @Synchronized
    fun reset() {
        scopeKey = null
        clearScopeState()
    }

    private fun ensureScope(nextScopeKey: String) {
        if (scopeKey == nextScopeKey) return
        scopeKey = nextScopeKey
        clearScopeState()
    }

    private fun clearScopeState() {
        failureCount = 0
        failureNextAllowedAtMs = 0L
        lastFailureDetail = ""
        slowSuccessNextAllowedAtMs = 0L
        lastSlowSuccessWallTimeMs = 0L
    }

    private fun delayForAttempt(attempt: Int): Long {
        if (baseDelayMs == 0L) return 0L
        var delay = baseDelayMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay = (delay * 2L).coerceAtMost(maxDelayMs)
        }
        return delay.coerceAtMost(maxDelayMs)
    }

    private fun safeAdd(nowMs: Long, delayMs: Long): Long =
        if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs

    companion object {
        const val DEFAULT_BASE_DELAY_MS = 30_000L
        const val DEFAULT_MAX_DELAY_MS = 5L * 60L * 1000L

        /**
         * S4-frozen slow-success guard for COMPLETE SAF metadata discovery.
         *
         * Real ExternalStorageProvider 10,272-entry playback-coexistence runs completed in
         * 12.5-23.1 s while app-owned direct/serialized smoke stayed below 1.2 s, so 10 s cleanly
         * classifies the repeatedly observed expensive-provider regime without timing out a valid
         * walk. The 15-minute cadence caps such automatic re-walks at four per hour; measured
         * provider + Mica UID CPU overhead was about 5.5 s median / 8.9 s max per real walk.
         * PLAYBACK_IO_RELEASE may bypass this cadence but never the failure breaker.
         */
        const val SLOW_SUCCESS_THRESHOLD_MS = 10_000L
        const val SLOW_SUCCESS_CADENCE_MS = 15L * 60L * 1000L
    }
}
