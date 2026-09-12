package com.mica.music.data.scanner

import android.content.Context
import com.mica.music.data.preferences.LibraryScanSettings

internal sealed interface DeviceAutoSyncShadowObservation {
    data object Disabled : DeviceAutoSyncShadowObservation

    data class BaselineCandidate(
        val snapshot: DeviceGenerationSnapshot.Available,
        val reason: DeviceGenerationReconcileReason,
    ) : DeviceAutoSyncShadowObservation

    data class NoChange(
        val snapshot: DeviceGenerationSnapshot.Available,
    ) : DeviceAutoSyncShadowObservation

    data class DeltaCandidate(
        val batch: DeviceMediaStoreDeltaBatch,
        val lyricsSidecarInventory: MediaStoreLyricsSidecarInventoryResult,
        val presenceInventory: PresenceInventory,
        val advanceTo: DeviceGenerationSnapshot.Available,
        val followUpRequired: Boolean,
    ) : DeviceAutoSyncShadowObservation

    data object LegacyTimestampFallbackRequired : DeviceAutoSyncShadowObservation

    data class ReconcileRequired(
        val detail: String,
        val followUpRequired: Boolean,
    ) : DeviceAutoSyncShadowObservation

    data class Unavailable(
        val detail: String,
    ) : DeviceAutoSyncShadowObservation
}


internal data class DeviceAutoSyncObservationScope(
    val trackedStableObjectKeys: Set<String> = emptySet(),
    val trackedLyricsKeys: Set<String> = emptySet(),
    val verifyTrackedStateOnNoChange: Boolean = false,
) {
    companion object {
        val EMPTY = DeviceAutoSyncObservationScope()
    }
}

internal sealed interface DeviceFullScanShadowAnchor {
    data object Disabled : DeviceFullScanShadowAnchor

    data class Available(
        val snapshot: DeviceGenerationSnapshot.Available,
    ) : DeviceFullScanShadowAnchor

    data object LegacyTimestampFallbackRequired : DeviceFullScanShadowAnchor

    data class Unavailable(
        val detail: String,
    ) : DeviceFullScanShadowAnchor
}

internal interface DeviceAutoSyncShadow {
    fun captureFullScanAnchor(configKey: String): DeviceFullScanShadowAnchor

    fun acceptFullScanAnchor(
        anchor: DeviceFullScanShadowAnchor,
        configKey: String,
    )

    fun observe(
        configKey: String,
        scope: DeviceAutoSyncObservationScope = DeviceAutoSyncObservationScope.EMPTY,
    ): DeviceAutoSyncShadowObservation

    /**
     * Restores a durable Full/AUTO generation anchor after process recreation. Existing in-memory
     * state wins; this must never move a live cursor backward.
     */
    fun restorePersistedAnchor(
        snapshot: DeviceGenerationSnapshot.Available,
        configKey: String,
    ): Boolean = false

    /**
     * Accepts only an already-bounded delta candidate. A BASELINE_MISSING observation can never
     * establish authority; baselines come exclusively from a successfully published Full Scan.
     */
    fun accept(
        observation: DeviceAutoSyncShadowObservation,
        configKey: String,
    )
}

internal object NoopDeviceAutoSyncShadow : DeviceAutoSyncShadow {
    override fun captureFullScanAnchor(configKey: String): DeviceFullScanShadowAnchor =
        DeviceFullScanShadowAnchor.Disabled

    override fun acceptFullScanAnchor(
        anchor: DeviceFullScanShadowAnchor,
        configKey: String,
    ) = Unit

    override fun observe(
        configKey: String,
        scope: DeviceAutoSyncObservationScope,
    ): DeviceAutoSyncShadowObservation = DeviceAutoSyncShadowObservation.Disabled

    override fun accept(
        observation: DeviceAutoSyncShadowObservation,
        configKey: String,
    ) = Unit
}

internal class AndroidDeviceAutoSyncShadow(
    private val context: Context,
    private val generationApi: DeviceMediaStoreGenerationApi =
        AndroidDeviceMediaStoreGenerationApi(context),
    private val cursor: DeviceGenerationShadowCursor = DeviceGenerationShadowCursor(),
    private val queryApiFactory: () -> DeviceMediaStoreDeltaQueryApi = {
        AndroidDeviceMediaStoreDeltaQueryApi(
            context = context,
            options = LibraryScanSettings.scanOptions(context),
        )
    },
    private val lyricsInventoryLoader:
        (Set<String>, DeviceMediaStoreDeltaBatch) -> MediaStoreLyricsSidecarInventoryResult =
        { trackedLyricsKeys, batch ->
            MediaStoreTrackedLyricsSidecarInventory.load(
                context = context,
                trackedLyricsKeys = trackedLyricsKeys,
                deltaBatch = batch,
            )
        },
    private val presenceInventoryLoader:
        (Set<String>, DeviceMediaStoreDeltaBatch) -> PresenceInventory =
        { trackedStableObjectKeys, batch ->
            MediaStoreTrackedPresenceInventory.load(
                context = context,
                options = LibraryScanSettings.scanOptions(context),
                trackedStableObjectKeys = trackedStableObjectKeys,
                deltaBatch = batch,
            )
        },
) : DeviceAutoSyncShadow {

    override fun captureFullScanAnchor(configKey: String): DeviceFullScanShadowAnchor =
        when (val snapshot = generationApi.read()) {
            DeviceGenerationSnapshot.LegacyTimestampFallbackRequired ->
                DeviceFullScanShadowAnchor.LegacyTimestampFallbackRequired
            is DeviceGenerationSnapshot.Unavailable ->
                DeviceFullScanShadowAnchor.Unavailable(snapshot.detail)
            is DeviceGenerationSnapshot.Available ->
                DeviceFullScanShadowAnchor.Available(snapshot)
        }

    override fun acceptFullScanAnchor(
        anchor: DeviceFullScanShadowAnchor,
        configKey: String,
    ) {
        if (anchor is DeviceFullScanShadowAnchor.Available) {
            cursor.advance(anchor.snapshot, configKey)
        }
    }

    override fun restorePersistedAnchor(
        snapshot: DeviceGenerationSnapshot.Available,
        configKey: String,
    ): Boolean = cursor.restoreIfEmpty(snapshot, configKey)

    override fun observe(
        configKey: String,
        scope: DeviceAutoSyncObservationScope,
    ): DeviceAutoSyncShadowObservation {
        val current = generationApi.read()
        return when (val plan = cursor.plan(current, configKey)) {
            DeviceGenerationPlan.LegacyTimestampFallbackRequired ->
                DeviceAutoSyncShadowObservation.LegacyTimestampFallbackRequired

            is DeviceGenerationPlan.Unavailable ->
                DeviceAutoSyncShadowObservation.Unavailable(plan.detail)

            is DeviceGenerationPlan.EstablishBaseline ->
                DeviceAutoSyncShadowObservation.BaselineCandidate(
                    snapshot = plan.snapshot,
                    reason = plan.reason,
                )

            is DeviceGenerationPlan.NoChange -> {
                if (scope.verifyTrackedStateOnNoChange) {
                    observeDelta(
                        DeviceGenerationPlan.Delta(plan.snapshot, plan.snapshot),
                        scope,
                    )
                } else {
                    DeviceAutoSyncShadowObservation.NoChange(plan.snapshot)
                }
            }

            is DeviceGenerationPlan.Delta -> observeDelta(plan, scope)
        }
    }

    override fun accept(
        observation: DeviceAutoSyncShadowObservation,
        configKey: String,
    ) {
        when (observation) {
            is DeviceAutoSyncShadowObservation.DeltaCandidate ->
                cursor.advance(observation.advanceTo, configKey)
            DeviceAutoSyncShadowObservation.Disabled,
            is DeviceAutoSyncShadowObservation.NoChange,
            is DeviceAutoSyncShadowObservation.BaselineCandidate,
            DeviceAutoSyncShadowObservation.LegacyTimestampFallbackRequired,
            is DeviceAutoSyncShadowObservation.ReconcileRequired,
            is DeviceAutoSyncShadowObservation.Unavailable,
            -> Unit
        }
    }

    private fun observeDelta(
        plan: DeviceGenerationPlan.Delta,
        scope: DeviceAutoSyncObservationScope,
    ): DeviceAutoSyncShadowObservation {
        val batch = DeviceMediaStoreDeltaReader.read(
            api = queryApiFactory(),
            from = plan.from,
            to = plan.to,
        )
        if (!batch.complete) {
            val conflicts = batch.identityConflicts.joinToString { conflict ->
                "${conflict.stableObjectKey}@${conflict.volumeNames}"
            }
            val partial = batch.statuses.filter {
                it.completeness != DiscoveryCompleteness.COMPLETE
            }.joinToString { status ->
                "${status.volumeName}/${status.channel}:${status.detail}"
            }
            return DeviceAutoSyncShadowObservation.ReconcileRequired(
                detail = buildString {
                    append("delta incomplete")
                    if (partial.isNotBlank()) append(" partial=[$partial]")
                    if (conflicts.isNotBlank()) append(" identityConflicts=[$conflicts]")
                },
                followUpRequired = false,
            )
        }

        val lyricsInventory = lyricsInventoryLoader(scope.trackedLyricsKeys, batch)
        if (!lyricsInventory.complete) {
            return DeviceAutoSyncShadowObservation.ReconcileRequired(
                detail = "lyrics sidecar inventory incomplete: ${lyricsInventory.status.detail}",
                followUpRequired = false,
            )
        }

        val presenceInventory = presenceInventoryLoader(scope.trackedStableObjectKeys, batch)

        val post = generationApi.read()
        val postAvailable = post as? DeviceGenerationSnapshot.Available
            ?: return DeviceAutoSyncShadowObservation.ReconcileRequired(
                detail = "post-delta generation state unavailable: $post",
                followUpRequired = false,
            )
        val validation = validatePostSnapshot(plan.to, postAvailable)
        if (validation.detail != null) {
            return DeviceAutoSyncShadowObservation.ReconcileRequired(
                detail = validation.detail,
                followUpRequired = true,
            )
        }
        return DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = batch,
            lyricsSidecarInventory = lyricsInventory,
            presenceInventory = presenceInventory,
            advanceTo = plan.to,
            followUpRequired = validation.hasNewerGeneration,
        )
    }

    private data class PostSnapshotValidation(
        val detail: String?,
        val hasNewerGeneration: Boolean,
    )

    private fun validatePostSnapshot(
        queriedTo: DeviceGenerationSnapshot.Available,
        post: DeviceGenerationSnapshot.Available,
    ): PostSnapshotValidation {
        if (queriedTo.volumes.keys != post.volumes.keys) {
            return PostSnapshotValidation(
                detail = "volume set changed during delta query",
                hasNewerGeneration = false,
            )
        }
        var newer = false
        queriedTo.volumes.forEach { (volumeName, upper) ->
            val after = requireNotNull(post.volumes[volumeName])
            if (after.providerVersion != upper.providerVersion) {
                return PostSnapshotValidation(
                    detail = "provider version changed during delta query for $volumeName",
                    hasNewerGeneration = false,
                )
            }
            if (after.generation < upper.generation) {
                return PostSnapshotValidation(
                    detail = "generation regressed during delta query for $volumeName",
                    hasNewerGeneration = false,
                )
            }
            if (after.generation > upper.generation) newer = true
        }
        return PostSnapshotValidation(detail = null, hasNewerGeneration = newer)
    }
}
