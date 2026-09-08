package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalSnapshot
import com.mica.music.data.scanner.DeviceShadowCanonicalSong
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.canonicalDeviceMediaIdentityUri
import com.mica.music.data.scanner.mediaStoreLyricsFolderPath
import com.mica.music.data.scanner.mediaStoreReadableFilePath

internal data class DeviceShadowCanonicalProjectionResult(
    val snapshot: DeviceShadowCanonicalSnapshot,
    val unresolvedAspectsByStableObjectKey: Map<String, Set<DeviceShadowCanonicalAspect>>,
    val quarantinedMembershipKeys: Set<String>,
) {
    val fullyResolved: Boolean
        get() = unresolvedAspectsByStableObjectKey.isEmpty() &&
            quarantinedMembershipKeys.isEmpty()
}

internal data class DeviceShadowResolvedCanonicalObject(
    val song: DeviceShadowCanonicalSong,
    val resolvedAspects: Set<DeviceShadowCanonicalAspect>,
)

internal data class DeviceShadowCanonicalEquivalenceResult(
    val projection: DeviceShadowCanonicalProjectionResult,
    val expected: DeviceShadowCanonicalSnapshot,
    val diff: DeviceShadowCanonicalDiff,
) {
    val fullyEquivalent: Boolean
        get() = projection.fullyResolved && diff.changes.isEmpty()
}

/**
 * Side-effect-free S3 candidate snapshot builder.
 *
 * Discovery-owned facts are projected directly. Scanner-owned facts that require heavy object IO
 * are accepted only through [resolvedObjectsByStableObjectKey]. Missing heavy results remain explicit
 * unresolved aspects rather than being copied from the old catalog and silently treated as equal.
 */
internal object DeviceShadowCanonicalProjector {
    private val DISCOVERY_RESOLVED_ASPECTS = setOf(
        DeviceShadowCanonicalAspect.MEMBERSHIP,
        DeviceShadowCanonicalAspect.MEDIA_IDENTITY,
        DeviceShadowCanonicalAspect.FILE_FINGERPRINT,
        DeviceShadowCanonicalAspect.FOLDER_IDENTITY,
    )

    fun project(
        baseline: DeviceShadowCanonicalSnapshot,
        candidates: DeviceDeltaCandidatePlan,
        lyricsDiff: DeviceLyricsSidecarDiff,
        membershipPlan: AutoSyncMembershipPlan,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
        resolvedObjectsByStableObjectKey: Map<String, DeviceShadowResolvedCanonicalObject> =
            emptyMap(),
    ): DeviceShadowCanonicalProjectionResult {
        require(!candidates.hasContradictions) {
            "canonical projection requires contradiction-free DEVICE candidates"
        }
        require(lyricsDiff.safeToAdvance) {
            "canonical projection requires verifiable lyrics sidecar inventory"
        }

        val songs = baseline.songsByStableObjectKey.toMutableMap()
        val unresolved = linkedMapOf<String, MutableSet<DeviceShadowCanonicalAspect>>()
        val quarantined = linkedSetOf<String>()

        candidates.audioCandidates.forEach { candidate ->
            val key = candidate.canonicalStableObjectKey
            when (candidate.primaryRow.eligibility) {
                LibraryEligibility.ELIGIBLE -> {
                    val resolved = resolvedObjectsByStableObjectKey[key]
                    val current = songs[key]
                    when {
                        resolved != null -> {
                            songs[key] = normalizeResolvedSong(
                                resolved = resolved.song,
                                stableObjectKey = key,
                                row = candidate.primaryRow,
                                folderCasingPlan = folderCasingPlan,
                            )
                            val unresolvedForResolved =
                                DeviceShadowCanonicalAspect.entries.toSet() -
                                    DISCOVERY_RESOLVED_ASPECTS -
                                    resolved.resolvedAspects
                            if (unresolvedForResolved.isNotEmpty()) {
                                unresolved.getOrPut(key) { linkedSetOf() }
                                    .addAll(unresolvedForResolved)
                            }
                        }

                        current != null -> {
                            songs[key] = patchDiscoveryFacts(
                                current = current,
                                stableObjectKey = key,
                                row = candidate.primaryRow,
                                folderCasingPlan = folderCasingPlan,
                            )
                            unresolved.getOrPut(key) { linkedSetOf() }.addAll(
                                setOf(
                                    DeviceShadowCanonicalAspect.TAG_METADATA,
                                    DeviceShadowCanonicalAspect.REPLAY_GAIN,
                                    DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE,
                                    DeviceShadowCanonicalAspect.VIDEO_COVER,
                                    DeviceShadowCanonicalAspect.MUSIC_VIDEO,
                                ),
                            )
                        }

                        else -> {
                            unresolved.getOrPut(key) { linkedSetOf() }.addAll(
                                DeviceShadowCanonicalAspect.entries,
                            )
                        }
                    }
                }

                LibraryEligibility.PENDING,
                LibraryEligibility.FILTERED_OUT,
                LibraryEligibility.TRASHED,
                -> Unit
            }
        }

        lyricsDiff.changes.forEach { change ->
            val current = songs[change.songId] ?: return@forEach
            songs[change.songId] = current.copy(
                externalLyricsSignature = change.observedSignature,
            )
            unresolved[change.songId]?.remove(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS)
        }

        when (membershipPlan) {
            is AutoSyncMembershipPlan.Apply -> {
                membershipPlan.membershipChanges.forEach { change ->
                    songs.remove(change.stableObjectKey)
                    unresolved.remove(change.stableObjectKey)
                }
            }

            is AutoSyncMembershipPlan.Quarantine -> {
                membershipPlan.membershipChanges.forEach { change ->
                    quarantined += change.stableObjectKey
                    unresolved.getOrPut(change.stableObjectKey) { linkedSetOf() }
                        .add(DeviceShadowCanonicalAspect.MEMBERSHIP)
                }
            }
        }

        return DeviceShadowCanonicalProjectionResult(
            snapshot = DeviceShadowCanonicalSnapshot(
                context = baseline.context,
                songsByStableObjectKey = songs.toSortedMap(),
            ),
            unresolvedAspectsByStableObjectKey = unresolved
                .mapValues { (_, aspects) -> aspects.toSet() }
                .filterValues(Set<DeviceShadowCanonicalAspect>::isNotEmpty),
            quarantinedMembershipKeys = quarantined,
        )
    }

    fun compare(
        projection: DeviceShadowCanonicalProjectionResult,
        expected: DeviceShadowCanonicalSnapshot,
    ): DeviceShadowCanonicalEquivalenceResult {
        require(projection.snapshot.context == expected.context) {
            "canonical projection and Full oracle must share the same DEVICE shadow context"
        }
        return DeviceShadowCanonicalEquivalenceResult(
            projection = projection,
            expected = expected,
            diff = DeviceShadowCanonicalCatalog.diff(projection.snapshot, expected),
        )
    }

    private fun normalizeResolvedSong(
        resolved: DeviceShadowCanonicalSong,
        stableObjectKey: String,
        row: com.mica.music.data.scanner.DeviceDeltaRow,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
    ): DeviceShadowCanonicalSong {
        val folder = effectiveFolderPath(stableObjectKey, row, folderCasingPlan)
        val fileName = row.displayName.ifBlank { resolved.fileName }
        return resolved.copy(
            stableObjectKey = stableObjectKey,
            mediaUri = canonicalDeviceMediaIdentityUri(stableObjectKey, row.mediaUri),
            fileName = fileName,
            sizeBytes = row.sizeBytes,
            dateModifiedMs = row.dateModifiedMs,
            folderPath = folder,
            filePath = reconcileSyntheticFilePath(
                currentFilePath = resolved.filePath,
                folderPath = folder,
                fileName = fileName,
            ),
        )
    }

    private fun patchDiscoveryFacts(
        current: DeviceShadowCanonicalSong,
        stableObjectKey: String,
        row: com.mica.music.data.scanner.DeviceDeltaRow,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
    ): DeviceShadowCanonicalSong {
        val folder = effectiveFolderPath(stableObjectKey, row, folderCasingPlan)
        val fileName = row.displayName.ifBlank { current.fileName }
        return current.copy(
            mediaUri = row.mediaUri,
            fileName = fileName,
            sizeBytes = row.sizeBytes,
            dateModifiedMs = row.dateModifiedMs,
            folderPath = folder,
            filePath = reconcileSyntheticFilePath(
                currentFilePath = current.filePath,
                folderPath = folder,
                fileName = fileName,
            ),
        )
    }

    private fun effectiveFolderPath(
        stableObjectKey: String,
        row: com.mica.music.data.scanner.DeviceDeltaRow,
        folderCasingPlan: DeviceDeltaFolderCasingPlan,
    ): String {
        val readable = mediaStoreReadableFilePath(row.relativePath, row.displayName)
        val observed = mediaStoreLyricsFolderPath(row.relativePath, readable)
            .trim()
            .replace('\\', '/')
            .trimEnd('/')
        return folderCasingPlan.effectiveFolderPath(stableObjectKey) ?: observed
    }

    private fun reconcileSyntheticFilePath(
        currentFilePath: String,
        folderPath: String,
        fileName: String,
    ): String {
        if (currentFilePath.trim().startsWith('/')) return currentFilePath
        return mediaStoreReadableFilePath(folderPath, fileName)
    }
}
