package com.mica.music.media.dsd

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

internal data class DirectDsdSessionGeneration(
    val rendererGeneration: Long,
    val sessionGeneration: Long,
)

internal data class DirectDsdSeekDispatch(
    val requestId: Long,
    val session: DirectDsdSessionGeneration,
    val targetSourcePositionUs: Long,
)

internal enum class DirectDsdSeekResetMatch {
    NONE,
    MATCHED,
    MISMATCHED,
    STALE,
}

internal data class DirectDsdSeekResetDecision(
    val match: DirectDsdSeekResetMatch,
    val requestId: Long? = null,
    val targetSourcePositionUs: Long? = null,
)

/**
 * Process-local one-shot coordination between an app-owned Exo seek dispatch and the active
 * Direct-DSD renderer/session. The state is scoped to an exact renderer+transport generation;
 * reset consumption or an explicit playback pause always removes the pending request.
 */
internal class DirectDsdSeekDiscontinuityState(
    private val nowMs: () -> Long,
    private val maxPendingAgeMs: Long = MAX_PENDING_AGE_MS,
) {
    private enum class Phase {
        PUBLISHED,
        STOP_OBSERVED,
    }

    private data class Pending(
        val dispatch: DirectDsdSeekDispatch,
        val publishedAtMs: Long,
        val phase: Phase,
    )

    private var activeSession: DirectDsdSessionGeneration? = null
    private var pending: Pending? = null
    private var nextRequestId = 0L

    @Synchronized
    fun activateSession(session: DirectDsdSessionGeneration) {
        if (activeSession != session) {
            activeSession = session
            pending = null
        }
    }

    @Synchronized
    fun deactivateSession(session: DirectDsdSessionGeneration) {
        if (activeSession == session) activeSession = null
        if (pending?.dispatch?.session == session) pending = null
    }

    @Synchronized
    fun publishPlayingSeek(targetSourcePositionUs: Long): DirectDsdSeekDispatch? {
        val session = activeSession ?: return null
        val dispatch = DirectDsdSeekDispatch(
            requestId = ++nextRequestId,
            session = session,
            targetSourcePositionUs = targetSourcePositionUs,
        )
        pending = Pending(dispatch, nowMs(), Phase.PUBLISHED)
        return dispatch
    }

    /** Returns the matching request only for the first STOP of a live seek transaction. */
    @Synchronized
    fun observeStopped(session: DirectDsdSessionGeneration): DirectDsdSeekDispatch? {
        val current = pendingFor(session) ?: return null
        if (current.phase != Phase.PUBLISHED) {
            pending = null
            return null
        }
        pending = current.copy(phase = Phase.STOP_OBSERVED)
        return current.dispatch
    }

    /**
     * Consumes the current seek request. Only a playing reset after the matching STOP and at the
     * exact requested source position is accepted as the intended seek discontinuity.
     */
    @Synchronized
    fun consumePositionReset(
        session: DirectDsdSessionGeneration,
        sourcePositionUs: Long,
        isPlaying: Boolean,
    ): DirectDsdSeekResetDecision {
        val current = pending ?: return DirectDsdSeekResetDecision(DirectDsdSeekResetMatch.NONE)
        if (current.dispatch.session != session) {
            return DirectDsdSeekResetDecision(DirectDsdSeekResetMatch.NONE)
        }
        if (isExpired(current)) {
            pending = null
            return DirectDsdSeekResetDecision(
                DirectDsdSeekResetMatch.STALE,
                current.dispatch.requestId,
                current.dispatch.targetSourcePositionUs,
            )
        }
        pending = null
        val matched = current.phase == Phase.STOP_OBSERVED &&
            isPlaying &&
            current.dispatch.targetSourcePositionUs == sourcePositionUs
        return DirectDsdSeekResetDecision(
            if (matched) DirectDsdSeekResetMatch.MATCHED else DirectDsdSeekResetMatch.MISMATCHED,
            current.dispatch.requestId,
            current.dispatch.targetSourcePositionUs,
        )
    }

    @Synchronized
    fun cancelRequest(requestId: Long) {
        if (pending?.dispatch?.requestId == requestId) pending = null
    }

    @Synchronized
    fun cancelForPlaybackPause() {
        pending = null
    }

    @Synchronized
    fun clear() {
        activeSession = null
        pending = null
    }

    @Synchronized
    internal fun pendingForTest(): DirectDsdSeekDispatch? = pending?.dispatch

    private fun pendingFor(session: DirectDsdSessionGeneration): Pending? {
        val current = pending ?: return null
        if (current.dispatch.session != session) return null
        if (isExpired(current)) {
            pending = null
            return null
        }
        return current
    }

    private fun isExpired(value: Pending): Boolean = nowMs() - value.publishedAtMs > maxPendingAgeMs

    companion object {
        internal const val MAX_PENDING_AGE_MS = 1_000L
    }
}

internal object DirectDsdSeekDiscontinuityCoordinator {
    private val rendererIds = AtomicLong()
    private val state = DirectDsdSeekDiscontinuityState(SystemClock::elapsedRealtime)

    fun newRendererGeneration(): Long = rendererIds.incrementAndGet()

    fun activateSession(session: DirectDsdSessionGeneration) = state.activateSession(session)

    fun deactivateSession(session: DirectDsdSessionGeneration) = state.deactivateSession(session)

    fun publishPlayingSeek(targetPositionMs: Long): DirectDsdSeekDispatch? {
        val safePositionMs = targetPositionMs.coerceIn(0L, Long.MAX_VALUE / 1_000L)
        return state.publishPlayingSeek(safePositionMs * 1_000L)
    }

    fun observeStopped(session: DirectDsdSessionGeneration): DirectDsdSeekDispatch? =
        state.observeStopped(session)

    fun consumePositionReset(
        session: DirectDsdSessionGeneration,
        sourcePositionUs: Long,
        isPlaying: Boolean,
    ): DirectDsdSeekResetDecision = state.consumePositionReset(session, sourcePositionUs, isPlaying)

    fun cancelRequest(requestId: Long) = state.cancelRequest(requestId)

    fun cancelForPlaybackPause() = state.cancelForPlaybackPause()

    internal fun pendingForTest(): DirectDsdSeekDispatch? = state.pendingForTest()

    internal fun resetForTest() = state.clear()
}
