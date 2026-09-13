package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.MusicVideoMatcher
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.VideoCoverFile
import com.mica.music.data.scanner.attachVideoCovers
import com.mica.music.data.scanner.hasSameObservedRevision

internal enum class SafShadowRelationIssueKind {
    AUDIO_WORK_UNRESOLVED,
    FOLDER_OBSERVATION_CHANGED,
    INCOMPLETE_INVENTORY,
}

internal data class SafShadowRelationIssue(
    val folderPath: String,
    val kind: SafShadowRelationIssueKind,
    val detail: String = "",
)

internal data class SafShadowRelationRematchResult(
    val resolvedSongsByStableObjectKey: Map<String, Song>,
    val resolvedFolderPaths: Set<String>,
    val unresolvedStableObjectKeys: Set<String>,
    val issues: List<SafShadowRelationIssue>,
) {
    val unresolvedFolderPaths: Set<String>
        get() = issues.mapTo(linkedSetOf(), SafShadowRelationIssue::folderPath)

    val hasWork: Boolean
        get() = resolvedFolderPaths.isNotEmpty() || issues.isNotEmpty()
}

/**
 * Side-effect-free SAF relation rematch using the same matchers as Folder Full Scan.
 *
 * MP4 inventory is already collected by the metadata walk. Only folders that currently expose MP4
 * files or had a published video relation need recomputation. If any audio object in such a folder
 * still lacks a validated probe result, the whole folder remains unresolved because MV uniqueness
 * can depend on all audio members in the basename family.
 */
internal object SafShadowRelationRematcher {
    fun potentialAffectedFolders(
        currentSongs: List<Song>,
        audioWorkEntries: Collection<SafTreeMetadataEntry>,
        removedStableObjectKeys: Set<String>,
        changedVideoFolderPaths: Set<String>,
        observedVideoFolderPaths: Set<String>,
    ): Set<String> {
        val currentById = currentSongs.associateBy(Song::id)
        val currentRelationFolders = currentSongs.asSequence()
            .filter { it.videoCoverUri != null || it.musicVideoUri != null }
            .mapTo(linkedSetOf(), Song::folderPath)
        val relationCapableFolders =
            changedVideoFolderPaths + observedVideoFolderPaths + currentRelationFolders
        return buildSet {
            addAll(changedVideoFolderPaths)
            audioWorkEntries.forEach { entry ->
                if (entry.folderPath in relationCapableFolders) add(entry.folderPath)
                currentById[entry.stableObjectKey]?.folderPath
                    ?.takeIf { it in relationCapableFolders }
                    ?.let(::add)
            }
            removedStableObjectKeys.forEach { key ->
                currentById[key]?.folderPath
                    ?.takeIf { it in relationCapableFolders }
                    ?.let(::add)
            }
        }
    }

    fun rematch(
        snapshot: SafTreeMetadataSnapshot,
        currentSongs: List<Song>,
        audioWorkEntries: Collection<SafTreeMetadataEntry>,
        removedStableObjectKeys: Set<String>,
        resolvedAudioSongsByStableObjectKey: Map<String, Song>,
        affectedFolderPaths: Set<String>,
    ): SafShadowRelationRematchResult {
        val affectedFolders = affectedFolderPaths
        if (affectedFolders.isEmpty()) {
            return SafShadowRelationRematchResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                resolvedFolderPaths = emptySet(),
                unresolvedStableObjectKeys = emptySet(),
                issues = emptyList(),
            )
        }

        val currentById = currentSongs.associateBy(Song::id)
        val workEntriesByKey = linkedMapOf<String, SafTreeMetadataEntry>()
        audioWorkEntries.forEach { entry ->
            workEntriesByKey[entry.stableObjectKey] = entry
        }

        val projectedById = currentById.toMutableMap()
        removedStableObjectKeys.forEach(projectedById::remove)
        resolvedAudioSongsByStableObjectKey.forEach { (key, song) ->
            val entry = workEntriesByKey[key]
            projectedById[key] = if (entry == null) {
                song
            } else {
                song.copy(
                    id = key,
                    mediaUri = entry.mediaUri,
                    fileName = entry.fileName,
                    folderPath = entry.folderPath,
                    filePath = entry.filePath,
                    sizeBytes = entry.sizeBytes,
                    dateModifiedMs = entry.lastModifiedMs,
                    externalLyricsSignature = entry.externalLyricsSignature,
                )
            }
        }

        val unresolvedWorkKeys = workEntriesByKey.keys - resolvedAudioSongsByStableObjectKey.keys
        val unstableFolders = linkedSetOf<String>()
        unresolvedWorkKeys.forEach { key ->
            currentById[key]?.folderPath?.let(unstableFolders::add)
            workEntriesByKey[key]?.folderPath?.let(unstableFolders::add)
        }

        val issues = mutableListOf<SafShadowRelationIssue>()
        if (!snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)) {
            affectedFolders.forEach { folder ->
                issues += SafShadowRelationIssue(
                    folderPath = folder,
                    kind = SafShadowRelationIssueKind.INCOMPLETE_INVENTORY,
                    detail = "SAF tree inventory is not deletion/relation authoritative",
                )
            }
            return SafShadowRelationRematchResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                resolvedFolderPaths = emptySet(),
                unresolvedStableObjectKeys = projectedById.values
                    .filterTo(linkedSetOf()) { it.folderPath in affectedFolders }
                    .mapTo(linkedSetOf(), Song::id),
                issues = issues,
            )
        }

        val resolved = linkedMapOf<String, Song>()
        val resolvedFolders = linkedSetOf<String>()
        val weakVideoResourceKeys = linkedSetOf<String>()
        affectedFolders.sorted().forEach { folder ->
            if (folder in unstableFolders) {
                issues += SafShadowRelationIssue(
                    folderPath = folder,
                    kind = SafShadowRelationIssueKind.AUDIO_WORK_UNRESOLVED,
                    detail = "audio member changed but did not produce a validated probe result",
                )
                return@forEach
            }
            val songsInFolder = projectedById.values.filter { it.folderPath == folder }
            if (songsInFolder.isEmpty()) {
                resolvedFolders += folder
                return@forEach
            }
            val videosInFolder = snapshot.videoCovers.filter { it.folderPath == folder }
            val rematched = MusicVideoMatcher.attach(
                attachVideoCovers(songsInFolder, videosInFolder),
                videosInFolder,
            )
            val weakVideoUris = videosInFolder.asSequence()
                .filter { it.sizeBytes <= 0L || it.lastModifiedMs <= 0L }
                .mapTo(hashSetOf(), VideoCoverFile::uri)
            rematched.forEach { song ->
                resolved[song.id] = song
                if (
                    song.videoCoverUri in weakVideoUris ||
                    song.musicVideoUri in weakVideoUris
                ) {
                    weakVideoResourceKeys += song.id
                }
            }
            resolvedFolders += folder
        }

        val unresolvedKeys = projectedById.values.asSequence()
            .filter {
                it.folderPath in unstableFolders &&
                    it.folderPath in affectedFolders
            }
            .mapTo(linkedSetOf(), Song::id)
            .apply { addAll(weakVideoResourceKeys) }
        return SafShadowRelationRematchResult(
            resolvedSongsByStableObjectKey = resolved.toMap(),
            resolvedFolderPaths = resolvedFolders,
            unresolvedStableObjectKeys = unresolvedKeys,
            issues = issues.toList(),
        )
    }
}

internal object SafShadowRelationPostValidator {
    fun validate(
        initialSnapshot: SafTreeMetadataSnapshot,
        postSnapshot: SafTargetedMetadataSnapshot,
        provisional: SafShadowRelationRematchResult,
    ): SafShadowRelationRematchResult {
        if (!provisional.hasWork) return provisional

        val issues = provisional.issues.toMutableList()
        val invalidFolders = linkedSetOf<String>()
        provisional.resolvedFolderPaths.forEach { folder ->
            when {
                !initialSnapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE) ||
                    !postSnapshot.isFolderComplete(folder) -> {
                    invalidFolders += folder
                    issues += SafShadowRelationIssue(
                        folderPath = folder,
                        kind = SafShadowRelationIssueKind.INCOMPLETE_INVENTORY,
                        detail = "targeted post-rematch SAF folder inventory is incomplete",
                    )
                }
                !sameFolderObservation(initialSnapshot, postSnapshot, folder) -> {
                    invalidFolders += folder
                    issues += SafShadowRelationIssue(
                        folderPath = folder,
                        kind = SafShadowRelationIssueKind.FOLDER_OBSERVATION_CHANGED,
                        detail = "audio/video inventory changed during relation rematch window",
                    )
                }
            }
        }

        val invalidatedKeys = provisional.resolvedSongsByStableObjectKey.values.asSequence()
            .filter { it.folderPath in invalidFolders }
            .mapTo(linkedSetOf(), Song::id)
        return SafShadowRelationRematchResult(
            resolvedSongsByStableObjectKey =
                provisional.resolvedSongsByStableObjectKey.filterValues {
                    it.folderPath !in invalidFolders
                },
            resolvedFolderPaths = provisional.resolvedFolderPaths - invalidFolders,
            unresolvedStableObjectKeys =
                provisional.unresolvedStableObjectKeys + invalidatedKeys,
            issues = issues.toList(),
        )
    }

    fun validate(
        initialSnapshot: SafTreeMetadataSnapshot,
        postSnapshot: SafTreeMetadataSnapshot,
        provisional: SafShadowRelationRematchResult,
    ): SafShadowRelationRematchResult {
        if (!provisional.hasWork) return provisional

        val issues = provisional.issues.toMutableList()
        val invalidFolders = linkedSetOf<String>()
        provisional.resolvedFolderPaths.forEach { folder ->
            when {
                !initialSnapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE) ||
                    !postSnapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE) -> {
                    invalidFolders += folder
                    issues += SafShadowRelationIssue(
                        folderPath = folder,
                        kind = SafShadowRelationIssueKind.INCOMPLETE_INVENTORY,
                        detail = "post-rematch SAF inventory is incomplete",
                    )
                }
                !sameFolderObservation(initialSnapshot, postSnapshot, folder) -> {
                    invalidFolders += folder
                    issues += SafShadowRelationIssue(
                        folderPath = folder,
                        kind = SafShadowRelationIssueKind.FOLDER_OBSERVATION_CHANGED,
                        detail = "audio/video inventory changed during relation rematch window",
                    )
                }
            }
        }

        val invalidatedKeys = provisional.resolvedSongsByStableObjectKey.values.asSequence()
            .filter { it.folderPath in invalidFolders }
            .mapTo(linkedSetOf(), Song::id)
        return SafShadowRelationRematchResult(
            resolvedSongsByStableObjectKey =
                provisional.resolvedSongsByStableObjectKey.filterValues {
                    it.folderPath !in invalidFolders
                },
            resolvedFolderPaths = provisional.resolvedFolderPaths - invalidFolders,
            unresolvedStableObjectKeys =
                provisional.unresolvedStableObjectKeys + invalidatedKeys,
            issues = issues.toList(),
        )
    }

    private fun sameFolderObservation(
        initial: SafTreeMetadataSnapshot,
        post: SafTargetedMetadataSnapshot,
        folderPath: String,
    ): Boolean {
        val initialEntries = initial.entries
            .asSequence()
            .filter { it.folderPath == folderPath }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val postEntries = post.entries
            .asSequence()
            .filter { it.folderPath == folderPath }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        if (initialEntries.keys != postEntries.keys) return false
        if (initialEntries.any { (key, entry) ->
                !entry.hasSameObservedRevision(postEntries.getValue(key))
            }
        ) {
            return false
        }
        return videoObservation(initial.videoCovers, folderPath) ==
            videoObservation(post.videoCovers, folderPath)
    }

    private fun sameFolderObservation(
        initial: SafTreeMetadataSnapshot,
        post: SafTreeMetadataSnapshot,
        folderPath: String,
    ): Boolean {
        val initialEntries = initial.entries
            .asSequence()
            .filter { it.folderPath == folderPath }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val postEntries = post.entries
            .asSequence()
            .filter { it.folderPath == folderPath }
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        if (initialEntries.keys != postEntries.keys) return false
        if (initialEntries.any { (key, entry) ->
                !entry.hasSameObservedRevision(postEntries.getValue(key))
            }
        ) {
            return false
        }

        return videoObservation(initial.videoCovers, folderPath) ==
            videoObservation(post.videoCovers, folderPath)
    }

    private fun videoObservation(
        files: List<VideoCoverFile>,
        folderPath: String,
    ): List<VideoCoverFile> = files.asSequence()
        .filter { it.folderPath == folderPath }
        .sortedWith(
            compareBy<VideoCoverFile>(
                VideoCoverFile::uri,
                VideoCoverFile::baseName,
                VideoCoverFile::sizeBytes,
                VideoCoverFile::lastModifiedMs,
            ),
        )
        .toList()
}
