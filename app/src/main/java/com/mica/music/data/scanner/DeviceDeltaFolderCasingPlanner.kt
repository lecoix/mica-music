package com.mica.music.data.scanner

import com.mica.music.data.Song
import java.util.Locale

internal data class DeviceFolderIdentityProbe(
    val mediaUri: String,
    val filePath: String,
    val folderPath: String,
)

internal fun interface DeviceFolderIdentityResolver {
    fun resolve(probe: DeviceFolderIdentityProbe): MediaStoreDirectoryIdentity?
}

internal data class DeviceFolderCasingDecision(
    val stableObjectKey: String,
    val observedFolderPath: String,
    val effectiveFolderPath: String,
    val reconciled: Boolean,
    val identityVerified: Boolean,
)

internal data class DeviceDeltaFolderCasingPlan(
    val decisions: List<DeviceFolderCasingDecision>,
    val unresolvedCaseCollisions: Int,
    val distinctPhysicalCaseCollisions: Int,
) {
    fun effectiveFolderPath(stableObjectKey: String): String? =
        decisions.firstOrNull { it.stableObjectKey == stableObjectKey }?.effectiveFolderPath

    companion object {
        val Empty = DeviceDeltaFolderCasingPlan(
            decisions = emptyList(),
            unresolvedCaseCollisions = 0,
            distinctPhysicalCaseCollisions = 0,
        )
    }
}

/**
 * Local S3 casing reconciliation using the same physical-directory rule as the Full scanner.
 *
 * Current catalog rows stand in for unchanged rows that a Full scan would still enumerate. The
 * candidate replaces its previous catalog row, so an existing object's old casing is deliberately
 * excluded before the preferred spelling is calculated.
 */
internal object DeviceDeltaFolderCasingPlanner {
    fun plan(
        candidates: DeviceDeltaCandidatePlan,
        currentSongs: List<Song>,
        identityResolver: DeviceFolderIdentityResolver,
    ): DeviceDeltaFolderCasingPlan {
        if (candidates.audioCandidates.isEmpty()) return DeviceDeltaFolderCasingPlan.Empty

        val decisions = mutableListOf<DeviceFolderCasingDecision>()
        var unresolved = 0
        var distinctPhysical = 0

        candidates.audioCandidates.forEach { candidate ->
            val observed = candidate.primaryRow.toFolderMember(candidate.canonicalStableObjectKey)
            val observedFolder = normalizedFolderPath(observed.folderPath)
            if (observedFolder.isBlank()) {
                decisions += DeviceFolderCasingDecision(
                    stableObjectKey = candidate.canonicalStableObjectKey,
                    observedFolderPath = observedFolder,
                    effectiveFolderPath = observedFolder,
                    reconciled = false,
                    identityVerified = false,
                )
                return@forEach
            }

            val folded = observedFolder.lowercase(Locale.ROOT)
            val members = buildList {
                currentSongs.asSequence()
                    .filter { it.id != candidate.existingSongId }
                    .map { song -> song.toFolderMember() }
                    .filter {
                        normalizedFolderPath(it.folderPath).lowercase(Locale.ROOT) == folded
                    }
                    .forEach(::add)
                add(observed)
            }

            val byExactPath = members.groupBy { normalizedFolderPath(it.folderPath) }
            if (byExactPath.size < 2) {
                decisions += DeviceFolderCasingDecision(
                    stableObjectKey = candidate.canonicalStableObjectKey,
                    observedFolderPath = observedFolder,
                    effectiveFolderPath = observedFolder,
                    reconciled = false,
                    identityVerified = false,
                )
                return@forEach
            }

            val identitiesByPath = linkedMapOf<String, MediaStoreDirectoryIdentity>()
            var unresolvedCollision = false
            byExactPath.forEach { (path, pathMembers) ->
                val identity = pathMembers.asSequence()
                    .mapNotNull { member ->
                        identityResolver.resolve(
                            DeviceFolderIdentityProbe(
                                mediaUri = member.mediaUri,
                                filePath = member.filePath,
                                folderPath = member.folderPath,
                            ),
                        )
                    }
                    .firstOrNull()
                if (identity == null) {
                    unresolvedCollision = true
                } else {
                    identitiesByPath[path] = identity
                }
            }

            if (unresolvedCollision || identitiesByPath.size != byExactPath.size) {
                unresolved += 1
                decisions += DeviceFolderCasingDecision(
                    stableObjectKey = candidate.canonicalStableObjectKey,
                    observedFolderPath = observedFolder,
                    effectiveFolderPath = observedFolder,
                    reconciled = false,
                    identityVerified = false,
                )
                return@forEach
            }

            if (identitiesByPath.values.toSet().size != 1) {
                distinctPhysical += 1
                decisions += DeviceFolderCasingDecision(
                    stableObjectKey = candidate.canonicalStableObjectKey,
                    observedFolderPath = observedFolder,
                    effectiveFolderPath = observedFolder,
                    reconciled = false,
                    identityVerified = true,
                )
                return@forEach
            }

            val preferred = byExactPath.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<FolderMember>>> { it.value.size }
                        .thenBy { it.key },
                )
                .first()
                .key
            decisions += DeviceFolderCasingDecision(
                stableObjectKey = candidate.canonicalStableObjectKey,
                observedFolderPath = observedFolder,
                effectiveFolderPath = preferred,
                reconciled = preferred != observedFolder,
                identityVerified = true,
            )
        }

        return DeviceDeltaFolderCasingPlan(
            decisions = decisions.sortedBy(DeviceFolderCasingDecision::stableObjectKey),
            unresolvedCaseCollisions = unresolved,
            distinctPhysicalCaseCollisions = distinctPhysical,
        )
    }

    private data class FolderMember(
        val stableObjectKey: String,
        val mediaUri: String,
        val filePath: String,
        val folderPath: String,
    )

    private fun DeviceDeltaRow.toFolderMember(stableObjectKey: String): FolderMember {
        val readablePath = mediaStoreReadableFilePath(relativePath, displayName)
        val folder = mediaStoreLyricsFolderPath(relativePath, readablePath)
        return FolderMember(
            stableObjectKey = stableObjectKey,
            mediaUri = mediaUri,
            filePath = readablePath,
            folderPath = folder,
        )
    }

    private fun Song.toFolderMember(): FolderMember = FolderMember(
        stableObjectKey = id,
        mediaUri = mediaUri,
        filePath = filePath.ifBlank {
            mediaStoreReadableFilePath(folderPath, fileName)
        },
        folderPath = mediaStoreLyricsFolderPath(folderPath, filePath),
    )
}
