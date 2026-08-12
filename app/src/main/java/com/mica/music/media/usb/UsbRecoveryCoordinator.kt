package com.mica.music.media.usb

internal data class UsbRecoveryEpoch(
    val id: Long,
    val sessionGeneration: Long,
)

internal enum class UsbRecoveryTrigger {
    TRANSPORT_ERROR,
    DEGRADED_TRANSPORT,
    STALLED_PROGRESS,
}

internal enum class UsbRecoveryActionKind {
    FRESH_OPEN,
}

internal data class UsbRecoveryAction(
    val epoch: UsbRecoveryEpoch,
    val generation: Long,
    val actionId: Long,
    val kind: UsbRecoveryActionKind,
    val trigger: UsbRecoveryTrigger,
    val attempt: Int,
)

internal enum class UsbRecoveryAckOutcome {
    SUCCEEDED,
    FAILED,
}

internal sealed interface UsbRecoveryRequestResult {
    data class Issued(val action: UsbRecoveryAction) : UsbRecoveryRequestResult
    data class AwaitingAck(val action: UsbRecoveryAction) : UsbRecoveryRequestResult
    data class BackingOff(val retryAfterMs: Long) : UsbRecoveryRequestResult
    data class BudgetExhausted(val attempts: Int) : UsbRecoveryRequestResult
    data object Resolved : UsbRecoveryRequestResult
    data object StaleEpoch : UsbRecoveryRequestResult
}

internal data class UsbRecoverySnapshot(
    val epoch: UsbRecoveryEpoch,
    val issuedFreshOpenAttempts: Int,
    val pendingAction: UsbRecoveryAction?,
    val lastAckOutcome: UsbRecoveryAckOutcome?,
    val nextAttemptAtElapsedRealtimeMs: Long,
    val resolved: Boolean,
)

/**
 * Process-local recovery protocol. It only issues typed actions; a separate policy owner must
 * execute a playback-stack rebuild and ACK the exact action. No transport action occurs here.
 */
internal class UsbRecoveryCoordinator(
    private val maxFreshOpenAttemptsPerEpoch: Int = 3,
    private val backoffBaseMs: Long = 1_000L,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val beforeAckPublication: (UsbRecoveryAction) -> Unit = {},
) {
    private val lock = Any()
    private var nextEpochId = 0L
    private var nextActionGeneration = 0L
    private var nextActionId = 0L
    private var state: State? = null

    init {
        require(maxFreshOpenAttemptsPerEpoch > 0)
        require(backoffBaseMs > 0L)
    }

    fun beginEpoch(sessionGeneration: Long): UsbRecoveryEpoch = synchronized(lock) {
        UsbRecoveryEpoch(
            id = ++nextEpochId,
            sessionGeneration = sessionGeneration,
        ).also { state = State(epoch = it) }
    }

    fun requestFreshOpen(
        epoch: UsbRecoveryEpoch,
        trigger: UsbRecoveryTrigger,
    ): UsbRecoveryRequestResult = synchronized(lock) {
        val current = state ?: return@synchronized UsbRecoveryRequestResult.StaleEpoch
        if (current.epoch != epoch) return@synchronized UsbRecoveryRequestResult.StaleEpoch
        if (current.resolved) return@synchronized UsbRecoveryRequestResult.Resolved
        current.pendingAction?.let {
            return@synchronized UsbRecoveryRequestResult.AwaitingAck(it)
        }
        if (current.issuedFreshOpenAttempts >= maxFreshOpenAttemptsPerEpoch) {
            return@synchronized UsbRecoveryRequestResult.BudgetExhausted(
                current.issuedFreshOpenAttempts,
            )
        }
        val retryAfterMs = current.nextAttemptAtElapsedRealtimeMs - elapsedRealtimeMs()
        if (retryAfterMs > 0L) {
            return@synchronized UsbRecoveryRequestResult.BackingOff(retryAfterMs)
        }

        val action = UsbRecoveryAction(
            epoch = epoch,
            generation = ++nextActionGeneration,
            actionId = ++nextActionId,
            kind = UsbRecoveryActionKind.FRESH_OPEN,
            trigger = trigger,
            attempt = current.issuedFreshOpenAttempts + 1,
        )
        current.issuedFreshOpenAttempts++
        current.pendingAction = action
        UsbRecoveryRequestResult.Issued(action)
    }

    /** Returns false for duplicate, stale, or mismatched ACKs without mutating current state. */
    fun acknowledge(
        action: UsbRecoveryAction,
        outcome: UsbRecoveryAckOutcome,
    ): Boolean {
        beforeAckPublication(action)
        return synchronized(lock) {
            val current = state ?: return@synchronized false
            if (current.epoch != action.epoch || current.pendingAction != action) {
                return@synchronized false
            }
            current.pendingAction = null
            current.lastAckOutcome = outcome
            if (outcome == UsbRecoveryAckOutcome.SUCCEEDED) {
                current.resolved = true
            } else {
                current.nextAttemptAtElapsedRealtimeMs = elapsedRealtimeMs() +
                    backoffBaseMs * (1L shl (action.attempt - 1))
            }
            true
        }
    }

    fun snapshot(): UsbRecoverySnapshot = synchronized(lock) {
        val current = checkNotNull(state) { "Recovery epoch has not started" }
        UsbRecoverySnapshot(
            epoch = current.epoch,
            issuedFreshOpenAttempts = current.issuedFreshOpenAttempts,
            pendingAction = current.pendingAction,
            lastAckOutcome = current.lastAckOutcome,
            nextAttemptAtElapsedRealtimeMs = current.nextAttemptAtElapsedRealtimeMs,
            resolved = current.resolved,
        )
    }

    private data class State(
        val epoch: UsbRecoveryEpoch,
        var issuedFreshOpenAttempts: Int = 0,
        var pendingAction: UsbRecoveryAction? = null,
        var lastAckOutcome: UsbRecoveryAckOutcome? = null,
        var nextAttemptAtElapsedRealtimeMs: Long = 0L,
        var resolved: Boolean = false,
    )
}
