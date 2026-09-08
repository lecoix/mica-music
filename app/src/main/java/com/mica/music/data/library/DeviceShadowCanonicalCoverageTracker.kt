package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalCoverageResult
import com.mica.music.data.scanner.DeviceShadowCanonicalSnapshot
import com.mica.music.data.scanner.LibraryEligibility

/**
 * In-memory S3 diagnostic owner. It never advances production checkpoints and never publishes.
 *
 * A delta only records which canonical aspects it could explain. The next successful Full Scan is
 * used as an independent catalog oracle to reveal changes that were not covered by shadow discovery.
 */
internal class DeviceShadowCanonicalCoverageTracker {
    private var baseline: DeviceShadowCanonicalSnapshot? = null
    private val coveredAspectsByStableObjectKey =
        LinkedHashMap<String, MutableSet<DeviceShadowCanonicalAspect>>()

    @Synchronized
    fun recordDelta(
        candidates: DeviceDeltaCandidatePlan,
        lyricsDiff: DeviceLyricsSidecarDiff,
        membershipPlan: AutoSyncMembershipPlan,
    ) {
        candidates.audioCandidates.forEach { candidate ->
            val key = candidate.canonicalStableObjectKey
            if (
                candidate.existingSongId == null &&
                candidate.primaryRow.eligibility == LibraryEligibility.ELIGIBLE
            ) {
                // A newly discovered eligible object can account for the complete catalog row once
                // the later Full Scan probes it.
                cover(key, DeviceShadowCanonicalAspect.entries.toSet())
            } else {
                // A changed audio object makes scanner-owned audio/tag facts candidates for reprobe.
                cover(
                    key,
                    setOf(
                        DeviceShadowCanonicalAspect.MEDIA_IDENTITY,
                        DeviceShadowCanonicalAspect.FILE_FINGERPRINT,
                        DeviceShadowCanonicalAspect.FOLDER_IDENTITY,
                        DeviceShadowCanonicalAspect.TAG_METADATA,
                        DeviceShadowCanonicalAspect.REPLAY_GAIN,
                        DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE,
                    ),
                )
            }
            if (candidate.sidecarChanged) {
                cover(key, setOf(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS))
            }
        }
        candidates.sidecarCandidates.forEach { candidate ->
            candidate.affectedSongIds.forEach { key ->
                cover(key, setOf(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS))
            }
        }
        lyricsDiff.affectedSongIds.forEach { key ->
            cover(key, setOf(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS))
        }
        membershipChanges(membershipPlan).forEach { change ->
            cover(change.stableObjectKey, setOf(DeviceShadowCanonicalAspect.MEMBERSHIP))
        }
    }

    @Synchronized
    fun acceptFullSnapshot(
        snapshot: DeviceShadowCanonicalSnapshot,
    ): DeviceShadowCanonicalCoverageResult {
        val previous = baseline
        if (previous == null) {
            baseline = snapshot
            coveredAspectsByStableObjectKey.clear()
            return DeviceShadowCanonicalCoverageResult.BaselineEstablished(
                snapshot.songsByStableObjectKey.size,
            )
        }
        if (previous.context != snapshot.context) {
            baseline = snapshot
            coveredAspectsByStableObjectKey.clear()
            return DeviceShadowCanonicalCoverageResult.ContextReset(
                previous = previous.context,
                current = snapshot.context,
                songCount = snapshot.songsByStableObjectKey.size,
            )
        }

        val diff = DeviceShadowCanonicalCatalog.diff(previous, snapshot)
        val uncovered = linkedMapOf<String, Set<DeviceShadowCanonicalAspect>>()
        val coveredKeys = linkedSetOf<String>()
        diff.changes.forEach { change ->
            val observedCoverage = coveredAspectsByStableObjectKey[change.stableObjectKey].orEmpty()
            val missing = change.aspects - observedCoverage
            if (missing.isEmpty()) {
                coveredKeys += change.stableObjectKey
            } else {
                uncovered[change.stableObjectKey] = missing
            }
        }

        baseline = snapshot
        coveredAspectsByStableObjectKey.clear()
        return DeviceShadowCanonicalCoverageResult.Compared(
            diff = diff,
            uncoveredAspectsByStableObjectKey = uncovered,
            coveredStableObjectKeys = coveredKeys,
        )
    }

    @Synchronized
    fun reset() {
        baseline = null
        coveredAspectsByStableObjectKey.clear()
    }

    private fun cover(
        stableObjectKey: String,
        aspects: Set<DeviceShadowCanonicalAspect>,
    ) {
        if (stableObjectKey.isBlank() || aspects.isEmpty()) return
        coveredAspectsByStableObjectKey
            .getOrPut(stableObjectKey) { linkedSetOf() }
            .addAll(aspects)
    }

    private fun membershipChanges(plan: AutoSyncMembershipPlan): List<MembershipChange> =
        when (plan) {
            is AutoSyncMembershipPlan.Apply -> plan.membershipChanges
            is AutoSyncMembershipPlan.Quarantine -> plan.membershipChanges
        }
}
