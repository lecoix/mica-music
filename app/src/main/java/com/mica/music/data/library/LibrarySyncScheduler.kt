package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

internal data class LibrarySyncSchedulerTiming(
    val debounceMs: Long = 1_500L,
    val cooldownMs: Long = 60_000L,
    val maxDebounceMs: Long = 5_000L,
) {
    init {
        require(debounceMs >= 0L)
        require(cooldownMs >= 0L)
        require(maxDebounceMs >= debounceMs)
    }
}

internal enum class AutoSyncWakeReason {
    TRAILING_DEBOUNCE,
    MAX_DEBOUNCE,
    COOLDOWN,
    IN_PASS_FOLLOW_UP,
    RETRY_DUE,
}

internal data class AutoSyncShadowDiagnostic(
    val requestSequence: Long,
    val dirtySequenceAtStart: Long,
    val coalescedEventCount: Int,
    val causeCounts: Map<LibraryOperationCause, Int>,
    val wakeReason: AutoSyncWakeReason,
)
/**
 * Single owner for library operation launch ordering.
 *
 * Owns dirty/coalescing, AUTO ordering and follow-up cadence. Production observers enter only
 * through [markDirty]; source-specific discovery/publication authority remains inside the
 * orchestrator, so enabling FOLDER real-auto does not implicitly enable DEVICE authority writes.
 */
internal class LibrarySyncScheduler(
    private val backing: MusicLibraryBacking,
    private val execute: suspend (ScheduledLibraryOperation) -> Unit,
    private val timing: LibrarySyncSchedulerTiming = LibrarySyncSchedulerTiming(),
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val stateLock = Any()
    private var activeJob: Job? = null
    private var activeSequence: Long = 0L
    private var activeOperation: ScheduledLibraryOperation? = null
    private var activeAutoWakeReason: AutoSyncWakeReason? = null
    private var requestSequence: Long = 0L

    private var pendingFull: LibraryOperationRequest? = null
    private val pendingTargetedIds = linkedSetOf<String>()
    private var pendingArtworkRepair: AlbumArtRepairPlan? = null

    private var wakeJob: Job? = null
    private var wakeAtMs: Long? = null
    private var retryWakeJob: Job? = null
    private var retryWakeAtMs: Long? = null
    private var retryWakeSourceActivation: SourceActivation? = null
    private var retryWakeCause: LibraryOperationCause? = null
    private var dirtyBurstStartedAtMs: Long? = null
    private var lastDirtyAtMs: Long? = null
    private var pendingAutoCause: LibraryOperationCause? = null
    private val pendingAutoCauseCounts = linkedMapOf<LibraryOperationCause, Int>()
    private var pendingAutoEventCount: Int = 0
    private var nextAllowedAutoSyncAtMs: Long = 0L

    @Volatile
    var lastAutoShadowDiagnostic: AutoSyncShadowDiagnostic? = null
        private set

    @Volatile
    var dirtySequence: Long = 0L
        private set

    @Volatile
    var pendingDirty: Boolean = false
        private set

    fun submit(request: LibraryOperationRequest) {
        synchronized(stateLock) {
            if (backing.released || backing.releaseRequested) return
            when (request) {
                LibraryOperationRequest.Rescan,
                LibraryOperationRequest.ScanDeviceWide,
                LibraryOperationRequest.ScanLibraryFolder,
                -> {
                    pendingFull = request
                    // Explicit user FULL is the one operation allowed to preempt current work.
                    activeJob?.cancel()
                }

                is LibraryOperationRequest.TargetedRefresh -> {
                    pendingTargetedIds += request.songIds.filter(String::isNotBlank)
                }

                is LibraryOperationRequest.ArtworkRepair -> {
                    pendingArtworkRepair = request.plan
                }

                is LibraryOperationRequest.AutoSync -> {
                    // AUTO_SYNC is scheduler-owned; callers must signal dirty instead.
                    markDirtyLocked(request.cause)
                }
            }
            startNextLocked()
        }
    }

    fun markDirty(
        cause: LibraryOperationCause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
    ): Long = synchronized(stateLock) {
        markDirtyLocked(cause)
    }

    private fun markDirtyLocked(cause: LibraryOperationCause): Long {
        dirtySequence += 1L

        // Never repopulate an uninitialized or user-cleared library from background activity.
        if (backing.intentState != LibraryIntentState.ACTIVE) {
            return dirtySequence
        }

        val now = nowMs()
        pendingDirty = true
        pendingAutoCause = cause
        pendingAutoEventCount += 1
        pendingAutoCauseCounts[cause] = (pendingAutoCauseCounts[cause] ?: 0) + 1
        if (dirtyBurstStartedAtMs == null) dirtyBurstStartedAtMs = now
        lastDirtyAtMs = now

        // Dirty arriving while any operation is running is retained. AUTO specifically must not be
        // invalidated/cancelled; its completion path detects the higher dirtySequence and follows up.
        if (activeJob?.isActive != true) {
            scheduleDirtyWakeLocked(now)
        }
        return dirtySequence
    }

    /**
     * Scheduler-owned continuation for AUTO work that was deliberately left behind by a bounded
     * per-pass budget. This is not an external observer signal: it is accepted only while an AUTO
     * operation is active, then reuses the existing one-immediate-follow-up + debounce protection.
     */
    fun requestAutoContinuation(
        cause: LibraryOperationCause,
    ): Boolean = synchronized(stateLock) {
        if (
            activeOperation?.request?.mode != LibraryOperationMode.AUTO_SYNC ||
            backing.released ||
            backing.releaseRequested
        ) {
            return@synchronized false
        }
        markDirtyLocked(cause)
        true
    }

    /**
     * Replaces the current source's scheduler-owned retry wake without blocking unrelated dirty
     * signals before the deadline. A null delay clears an existing retry wake for this activation.
     *
     * The timer is intentionally in elapsed-realtime space even when the durable retry ledger uses
     * wall-clock timestamps: callers convert the persisted deadline to a remaining duration before
     * entering this seam. Source/activation fencing prevents an old timer from waking a replacement
     * library source.
     */
    fun replaceAutoRetryWake(
        cause: LibraryOperationCause,
        delayMs: Long?,
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
    ): Boolean = synchronized(stateLock) {
        if (
            activeOperation?.request?.mode != LibraryOperationMode.AUTO_SYNC ||
            backing.released ||
            backing.releaseRequested
        ) {
            return@synchronized false
        }
        val activeSource = backing.sourceState.active ?: return@synchronized false
        if (
            activeSource.sourceIdentity != sourceIdentity ||
            activeSource.activationEpoch != activationEpoch
        ) {
            return@synchronized false
        }

        cancelRetryWakeLocked()
        if (delayMs == null) return@synchronized true

        val normalizedDelayMs = delayMs.coerceAtLeast(0L)
        val now = nowMs()
        val dueAt = safeAdd(now, normalizedDelayMs)
        retryWakeAtMs = dueAt
        retryWakeSourceActivation = activeSource
        retryWakeCause = cause
        retryWakeJob = backing.scanScope.launch {
            delay(normalizedDelayMs)
            synchronized(stateLock) {
                if (
                    retryWakeAtMs != dueAt ||
                    retryWakeSourceActivation != activeSource ||
                    retryWakeCause != cause
                ) {
                    return@synchronized
                }
                retryWakeJob = null
                retryWakeAtMs = null
                retryWakeSourceActivation = null
                retryWakeCause = null

                val currentSource = backing.sourceState.active
                if (
                    currentSource != activeSource ||
                    backing.released ||
                    backing.releaseRequested ||
                    backing.intentState != LibraryIntentState.ACTIVE ||
                    !backing.isAutoSyncForeground
                ) {
                    // Durable retry/checkpoint state remains authoritative. A foreground catch-up
                    // will rediscover overdue work without letting this timer start background IO.
                    return@synchronized
                }

                markDirtyLocked(cause)
                if (activeJob?.isActive != true && autoEligibleLocked()) {
                    startAutoLocked(AutoSyncWakeReason.RETRY_DUE)
                }
            }
        }
        true
    }

    fun clearPendingDirty() {
        synchronized(stateLock) {
            pendingDirty = false
            pendingAutoCause = null
            pendingAutoEventCount = 0
            pendingAutoCauseCounts.clear()
            dirtyBurstStartedAtMs = null
            lastDirtyAtMs = null
            cancelWakeLocked()
        }
    }

    fun onEligibilityChanged() {
        synchronized(stateLock) {
            if (!pendingDirty || activeJob?.isActive == true) return
            scheduleDirtyWakeLocked(nowMs())
        }
    }

    fun cancelAll() {
        synchronized(stateLock) {
            pendingFull = null
            pendingTargetedIds.clear()
            pendingArtworkRepair = null
            pendingDirty = false
            pendingAutoCause = null
            pendingAutoEventCount = 0
            pendingAutoCauseCounts.clear()
            dirtyBurstStartedAtMs = null
            lastDirtyAtMs = null
            nextAllowedAutoSyncAtMs = 0L
            cancelWakeLocked()
            cancelRetryWakeLocked()
            activeJob?.cancel()
        }
    }

    private fun startNextLocked() {
        if (activeJob?.isActive == true) return

        val request = when {
            pendingFull != null -> pendingFull.also { pendingFull = null }
            pendingTargetedIds.isNotEmpty() -> {
                val ids = pendingTargetedIds.toSet()
                pendingTargetedIds.clear()
                LibraryOperationRequest.TargetedRefresh(ids)
            }
            pendingArtworkRepair != null -> {
                val plan = pendingArtworkRepair
                pendingArtworkRepair = null
                plan?.let { LibraryOperationRequest.ArtworkRepair(it) }
            }
            else -> null
        }

        if (request != null) {
            cancelWakeLocked()
            launchRequestLocked(request)
            return
        }

        if (pendingDirty) {
            scheduleDirtyWakeLocked(nowMs())
        }
    }

    private fun autoEligibleLocked(): Boolean =
        backing.intentState == LibraryIntentState.ACTIVE &&
            backing.accessState == LibraryAccessState.AVAILABLE &&
            backing.sourceState.active != null &&
            !backing.released &&
            !backing.releaseRequested

    private fun scheduleDirtyWakeLocked(now: Long) {
        if (!pendingDirty || activeJob?.isActive == true || !autoEligibleLocked()) return

        val dueAt = computeDirtyDueAtLocked(now)
        if (dueAt <= now) {
            startAutoLocked(resolveAutoWakeReasonLocked())
            return
        }
        if (wakeAtMs == dueAt && wakeJob?.isActive == true) return

        cancelWakeLocked()
        wakeAtMs = dueAt
        val waitMs = (dueAt - now).coerceAtLeast(0L)
        wakeJob = backing.scanScope.launch {
            delay(waitMs)
            synchronized(stateLock) {
                if (wakeAtMs != dueAt) return@synchronized
                wakeJob = null
                wakeAtMs = null
                if (!pendingDirty || activeJob?.isActive == true || !autoEligibleLocked()) {
                    return@synchronized
                }
                val currentNow = nowMs()
                val recomputed = computeDirtyDueAtLocked(currentNow)
                if (recomputed > currentNow) {
                    scheduleDirtyWakeLocked(currentNow)
                } else {
                    startAutoLocked(resolveAutoWakeReasonLocked())
                }
            }
        }
    }

    private fun computeDirtyDueAtLocked(now: Long): Long {
        val burstStart = dirtyBurstStartedAtMs ?: now.also { dirtyBurstStartedAtMs = it }
        val lastDirty = lastDirtyAtMs ?: burstStart.also { lastDirtyAtMs = it }
        val trailingDeadline = lastDirty + timing.debounceMs
        val starvationDeadline = burstStart + timing.maxDebounceMs
        val debounceDeadline = min(trailingDeadline, starvationDeadline)
        return max(nextAllowedAutoSyncAtMs, debounceDeadline)
    }

    private fun resolveAutoWakeReasonLocked(): AutoSyncWakeReason {
        val now = nowMs()
        val burstStart = dirtyBurstStartedAtMs ?: now
        val lastDirty = lastDirtyAtMs ?: burstStart
        val trailingDeadline = lastDirty + timing.debounceMs
        val starvationDeadline = burstStart + timing.maxDebounceMs
        val debounceDeadline = min(trailingDeadline, starvationDeadline)
        return when {
            nextAllowedAutoSyncAtMs > debounceDeadline -> AutoSyncWakeReason.COOLDOWN
            starvationDeadline <= trailingDeadline -> AutoSyncWakeReason.MAX_DEBOUNCE
            else -> AutoSyncWakeReason.TRAILING_DEBOUNCE
        }
    }

    private fun startAutoLocked(wakeReason: AutoSyncWakeReason) {
        if (!pendingDirty || !autoEligibleLocked() || activeJob?.isActive == true) return

        val cause = pendingAutoCause ?: LibraryOperationCause.FOREGROUND_CATCH_UP
        val eventCount = pendingAutoEventCount.coerceAtLeast(1)
        val causeCounts = pendingAutoCauseCounts.toMap()
        val nextSequence = requestSequence + 1L
        val diagnostic = AutoSyncShadowDiagnostic(
            requestSequence = nextSequence,
            dirtySequenceAtStart = dirtySequence,
            coalescedEventCount = eventCount,
            causeCounts = causeCounts,
            wakeReason = wakeReason,
        )
        lastAutoShadowDiagnostic = diagnostic
        activeAutoWakeReason = wakeReason
        DiagnosticLog.event(
            "LibraryAutoSync",
            "auto pass request=$nextSequence dirty=$dirtySequence events=$eventCount " +
                "wake=$wakeReason causes=$causeCounts",
        )

        pendingDirty = false
        pendingAutoCause = null
        pendingAutoEventCount = 0
        pendingAutoCauseCounts.clear()
        dirtyBurstStartedAtMs = null
        lastDirtyAtMs = null
        cancelWakeLocked()
        launchRequestLocked(LibraryOperationRequest.AutoSync(cause))
    }

    private fun launchRequestLocked(request: LibraryOperationRequest) {
        val sequence = ++requestSequence
        activeSequence = sequence
        val scheduled = ScheduledLibraryOperation(
            request = request,
            requestSequence = sequence,
            dirtySequenceAtStart = dirtySequence,
        )
        activeOperation = scheduled
        val job = backing.scanScope.launch {
            try {
                execute(scheduled)
            } finally {
                onFinished(sequence)
            }
        }
        activeJob = job
        backing.scanJob = job
    }

    private fun onFinished(sequence: Long) {
        synchronized(stateLock) {
            if (activeSequence != sequence) return
            val finished = activeOperation
            val finishedAutoWakeReason = activeAutoWakeReason
            activeJob = null
            activeOperation = null
            activeAutoWakeReason = null
            backing.scanJob = null

            val now = nowMs()
            val autoHadNewDirty = finished?.request?.mode == LibraryOperationMode.AUTO_SYNC &&
                dirtySequence > finished.dirtySequenceAtStart

            if (finished?.request?.mode == LibraryOperationMode.AUTO_SYNC) {
                if (autoHadNewDirty || pendingDirty) {
                    // Anything arriving during the pass gets one immediate follow-up, bypassing the
                    // normal cooldown/debounce. The next pass snapshots the newer dirtySequence.
                    pendingDirty = true
                    if (pendingAutoCause == null) {
                        pendingAutoCause = finished.request.cause
                    }
                    dirtyBurstStartedAtMs = dirtyBurstStartedAtMs ?: now
                    lastDirtyAtMs = lastDirtyAtMs ?: now
                } else {
                    nextAllowedAutoSyncAtMs = now + timing.cooldownMs
                    dirtyBurstStartedAtMs = null
                    lastDirtyAtMs = null
                }
            }

            // Explicit queued work always outranks AUTO follow-up.
            if (
                pendingFull != null ||
                pendingTargetedIds.isNotEmpty() ||
                pendingArtworkRepair != null
            ) {
                startNextLocked()
                return
            }

            if (
                autoHadNewDirty &&
                pendingDirty &&
                autoEligibleLocked() &&
                finishedAutoWakeReason != AutoSyncWakeReason.IN_PASS_FOLLOW_UP
            ) {
                // Preserve the low-latency guarantee for one in-pass follow-up, but never let
                // sustained provider churn recursively chain immediate passes. Dirty arriving
                // during the immediate follow-up remains pending and re-enters normal
                // debounce/max-debounce scheduling through startNextLocked().
                startAutoLocked(AutoSyncWakeReason.IN_PASS_FOLLOW_UP)
            } else {
                startNextLocked()
            }
        }
    }

    private fun cancelWakeLocked() {
        wakeJob?.cancel()
        wakeJob = null
        wakeAtMs = null
    }

    private fun cancelRetryWakeLocked() {
        retryWakeJob?.cancel()
        retryWakeJob = null
        retryWakeAtMs = null
        retryWakeSourceActivation = null
        retryWakeCause = null
    }

    private fun safeAdd(nowMs: Long, delayMs: Long): Long =
        if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs
}
