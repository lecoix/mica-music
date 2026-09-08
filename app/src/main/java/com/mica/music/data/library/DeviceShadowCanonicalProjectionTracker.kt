package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalSnapshot

internal sealed interface DeviceShadowCanonicalProjectionGateResult {
    data class BaselineEstablished(
        val songCount: Int,
    ) : DeviceShadowCanonicalProjectionGateResult

    data class ContextReset(
        val songCount: Int,
    ) : DeviceShadowCanonicalProjectionGateResult

    data class Compared(
        val equivalence: DeviceShadowCanonicalEquivalenceResult,
        val normalizedPendingKeepKeys: Set<String> = emptySet(),
    ) : DeviceShadowCanonicalProjectionGateResult
}

/**
 * Accumulates side-effect-free DEVICE delta projections until the next independently published
 * Full Scan becomes the oracle.
 */
internal class DeviceShadowCanonicalProjectionTracker {
    private var baseline: DeviceShadowCanonicalSnapshot? = null
    private var projection: DeviceShadowCanonicalProjectionResult? = null
    private val pendingKeepKeys = linkedSetOf<String>()

    @Synchronized
    fun recordDelta(
        candidates: DeviceDeltaCandidatePlan,
        lyricsDiff: DeviceLyricsSidecarDiff,
        membershipPlan: AutoSyncMembershipPlan,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
        resolvedObjectsByStableObjectKey: Map<String, DeviceShadowResolvedCanonicalObject> =
            emptyMap(),
        membershipAudit: DeviceShadowMembershipAudit? = null,
    ) {
        val baselineSnapshot = baseline ?: return
        val currentProjection = projection ?: cleanProjection(baselineSnapshot)
        val next = DeviceShadowCanonicalProjector.project(
            baseline = currentProjection.snapshot,
            candidates = candidates,
            lyricsDiff = lyricsDiff,
            membershipPlan = membershipPlan,
            folderCasingPlan = folderCasingPlan,
            resolvedObjectsByStableObjectKey = resolvedObjectsByStableObjectKey,
        )

        val removedKeys = when (membershipPlan) {
            is AutoSyncMembershipPlan.Apply ->
                membershipPlan.membershipChanges.mapTo(linkedSetOf(), MembershipChange::stableObjectKey)
            is AutoSyncMembershipPlan.Quarantine -> emptySet()
        }
        val mergedUnresolved = linkedMapOf<String, MutableSet<DeviceShadowCanonicalAspect>>()
        currentProjection.unresolvedAspectsByStableObjectKey.forEach { (key, aspects) ->
            if (key !in removedKeys && key in next.snapshot.songsByStableObjectKey) {
                val resolvedAspects =
                    resolvedObjectsByStableObjectKey[key]?.resolvedAspects.orEmpty()
                val stillUnresolved = aspects - resolvedAspects
                if (stillUnresolved.isNotEmpty()) {
                    mergedUnresolved.getOrPut(key) { linkedSetOf() }.addAll(stillUnresolved)
                }
            }
        }
        next.unresolvedAspectsByStableObjectKey.forEach { (key, aspects) ->
            mergedUnresolved.getOrPut(key) { linkedSetOf() }.addAll(aspects)
        }

        val quarantined = linkedSetOf<String>().apply {
            addAll(currentProjection.quarantinedMembershipKeys)
            removeAll(removedKeys)
            addAll(next.quarantinedMembershipKeys)
        }

        projection = next.copy(
            unresolvedAspectsByStableObjectKey = mergedUnresolved
                .mapValues { (_, aspects) -> aspects.toSet() }
                .filterValues(Set<DeviceShadowCanonicalAspect>::isNotEmpty),
            quarantinedMembershipKeys = quarantined,
        )
        if (membershipAudit != null) {
            pendingKeepKeys.clear()
            pendingKeepKeys += membershipAudit.pendingKeepKeys.filter {
                it in next.snapshot.songsByStableObjectKey
            }
        }
    }

    @Synchronized
    fun acceptFullSnapshot(
        snapshot: DeviceShadowCanonicalSnapshot,
    ): DeviceShadowCanonicalProjectionGateResult {
        val previous = baseline
        if (previous == null) {
            baseline = snapshot
            projection = cleanProjection(snapshot)
            pendingKeepKeys.clear()
            return DeviceShadowCanonicalProjectionGateResult.BaselineEstablished(
                snapshot.songsByStableObjectKey.size,
            )
        }
        if (previous.context != snapshot.context) {
            baseline = snapshot
            projection = cleanProjection(snapshot)
            pendingKeepKeys.clear()
            return DeviceShadowCanonicalProjectionGateResult.ContextReset(
                snapshot.songsByStableObjectKey.size,
            )
        }

        val candidate = projection ?: cleanProjection(previous)
        val normalizedPendingKeepKeys = pendingKeepKeys.filterTo(linkedSetOf()) { key ->
            key !in snapshot.songsByStableObjectKey &&
                key in candidate.snapshot.songsByStableObjectKey
        }
        val canonicalExpected = if (normalizedPendingKeepKeys.isEmpty()) {
            snapshot
        } else {
            snapshot.copy(
                songsByStableObjectKey = buildMap {
                    putAll(snapshot.songsByStableObjectKey)
                    normalizedPendingKeepKeys.forEach { key ->
                        candidate.snapshot.songsByStableObjectKey[key]?.let { put(key, it) }
                    }
                }.toSortedMap(),
            )
        }
        val equivalence = DeviceShadowCanonicalProjector.compare(candidate, canonicalExpected)
        baseline = snapshot
        projection = cleanProjection(snapshot)
        pendingKeepKeys.clear()
        return DeviceShadowCanonicalProjectionGateResult.Compared(
            equivalence = equivalence,
            normalizedPendingKeepKeys = normalizedPendingKeepKeys,
        )
    }

    @Synchronized
    fun reset() {
        baseline = null
        projection = null
        pendingKeepKeys.clear()
    }

    private fun cleanProjection(
        snapshot: DeviceShadowCanonicalSnapshot,
    ) = DeviceShadowCanonicalProjectionResult(
        snapshot = snapshot,
        unresolvedAspectsByStableObjectKey = emptyMap(),
        quarantinedMembershipKeys = emptySet(),
    )
}
