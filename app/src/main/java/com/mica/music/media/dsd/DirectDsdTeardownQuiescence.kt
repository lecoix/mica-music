package com.mica.music.media.dsd

internal enum class DirectDsdTeardownQuiesceOutcome {
    NO_ACTIVE_SESSION,
    NO_ACTIVE_GAP,
    QUIESCED,
}

internal fun interface DirectDsdPauseGapQuiescer {
    /** Returns true only when an active/failed GAP was synchronously stopped and joined. */
    fun quiesceIfGapActive(): Boolean
}

/**
 * Process-local registration for the exact Direct renderer/session that currently owns pause GAP
 * liveness. The callback is invoked while this state lock is held, so a newer registration cannot
 * overtake an in-progress quiesce and a stale unregister cannot clear a newer session.
 *
 * This lock is deliberately independent of the P2 USB owner/session lock. Quiescing may block while
 * joining the GAP worker, but it never does so while owner invalidation/release is in progress.
 */
internal class DirectDsdTeardownQuiescenceState {
    private data class Registration(
        val session: DirectDsdSessionGeneration,
        val quiescer: DirectDsdPauseGapQuiescer,
    )

    private var active: Registration? = null

    @Synchronized
    fun register(
        session: DirectDsdSessionGeneration,
        quiescer: DirectDsdPauseGapQuiescer,
    ): Boolean {
        val current = active
        if (current != null && session.isOlderThan(current.session)) return false
        active = Registration(session, quiescer)
        return true
    }

    @Synchronized
    fun unregister(session: DirectDsdSessionGeneration): Boolean {
        if (active?.session != session) return false
        active = null
        return true
    }

    @Synchronized
    fun quiesceActive(): DirectDsdTeardownQuiesceOutcome {
        val current = active ?: return DirectDsdTeardownQuiesceOutcome.NO_ACTIVE_SESSION
        return if (current.quiescer.quiesceIfGapActive()) {
            DirectDsdTeardownQuiesceOutcome.QUIESCED
        } else {
            DirectDsdTeardownQuiesceOutcome.NO_ACTIVE_GAP
        }
    }

    @Synchronized
    fun quiesceBeforeOwnerInvalidation(
        onQuiesced: (DirectDsdTeardownQuiesceOutcome) -> Unit = {},
        invalidateOwner: () -> Unit,
    ): DirectDsdTeardownQuiesceOutcome {
        val outcome = quiesceActive()
        onQuiesced(outcome)
        invalidateOwner()
        return outcome
    }

    @Synchronized
    internal fun activeSessionForTest(): DirectDsdSessionGeneration? = active?.session

    private fun DirectDsdSessionGeneration.isOlderThan(other: DirectDsdSessionGeneration): Boolean =
        rendererGeneration < other.rendererGeneration ||
            (rendererGeneration == other.rendererGeneration && sessionGeneration < other.sessionGeneration)
}

internal object DirectDsdTeardownQuiescenceCoordinator {
    private val state = DirectDsdTeardownQuiescenceState()

    fun register(
        session: DirectDsdSessionGeneration,
        quiescer: DirectDsdPauseGapQuiescer,
    ): Boolean = state.register(session, quiescer)

    fun unregister(session: DirectDsdSessionGeneration): Boolean = state.unregister(session)

    fun quiesceBeforeOwnerInvalidation(
        onQuiesced: (DirectDsdTeardownQuiesceOutcome) -> Unit = {},
        invalidateOwner: () -> Unit,
    ): DirectDsdTeardownQuiesceOutcome =
        state.quiesceBeforeOwnerInvalidation(onQuiesced, invalidateOwner)
}
