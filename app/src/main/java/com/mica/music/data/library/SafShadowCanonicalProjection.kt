package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalSong
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.toDeviceShadowCanonicalSong

internal data class SafShadowCanonicalContext(
    val sourceIdentityStorageKey: String,
    val activationEpoch: Long,
    val configFingerprint: String,
)

internal data class SafShadowCanonicalSnapshot(
    val context: SafShadowCanonicalContext,
    val songsByStableObjectKey: Map<String, DeviceShadowCanonicalSong>,
)

internal data class SafShadowCanonicalChange(
    val stableObjectKey: String,
    val aspects: Set<DeviceShadowCanonicalAspect>,
)

internal data class SafShadowCanonicalDiff(
    val changes: List<SafShadowCanonicalChange>,
)

internal object SafShadowCanonicalCatalog {
    fun snapshot(
        songs: List<Song>,
        context: SafShadowCanonicalContext,
    ): SafShadowCanonicalSnapshot {
        val canonical = LinkedHashMap<String, DeviceShadowCanonicalSong>(songs.size)
        songs.forEach { song ->
            val key = song.id
            val previous = canonical.put(key, song.toDeviceShadowCanonicalSong(key))
            require(previous == null) {
                "duplicate canonical SAF stable object key: $key"
            }
        }
        return SafShadowCanonicalSnapshot(
            context = context,
            songsByStableObjectKey = canonical.toSortedMap(),
        )
    }

    fun diff(
        before: SafShadowCanonicalSnapshot,
        after: SafShadowCanonicalSnapshot,
    ): SafShadowCanonicalDiff {
        require(before.context == after.context) {
            "canonical SAF snapshots must share the same shadow context"
        }
        val keys = (before.songsByStableObjectKey.keys + after.songsByStableObjectKey.keys)
            .toSortedSet()
        return SafShadowCanonicalDiff(
            changes = keys.mapNotNull { key ->
                val old = before.songsByStableObjectKey[key]
                val new = after.songsByStableObjectKey[key]
                val aspects = when {
                    old == null || new == null ->
                        setOf(DeviceShadowCanonicalAspect.MEMBERSHIP)
                    else -> changedAspects(old, new)
                }
                aspects.takeIf(Set<DeviceShadowCanonicalAspect>::isNotEmpty)?.let {
                    SafShadowCanonicalChange(key, it)
                }
            },
        )
    }

    private fun changedAspects(
        old: DeviceShadowCanonicalSong,
        new: DeviceShadowCanonicalSong,
    ): Set<DeviceShadowCanonicalAspect> = buildSet {
        if (old.mediaUri != new.mediaUri || old.fileName != new.fileName) {
            add(DeviceShadowCanonicalAspect.MEDIA_IDENTITY)
        }
        if (old.sizeBytes != new.sizeBytes || old.dateModifiedMs != new.dateModifiedMs) {
            add(DeviceShadowCanonicalAspect.FILE_FINGERPRINT)
        }
        // SAF DocumentsProvider has no canonical DATE_ADDED field. FolderScanner synthesizes
        // this value from the scan wall clock, so comparing it would make two equivalent Full
        // scans differ purely because they ran at different times. Membership is represented by
        // stable-object presence/absence for SAF.
        if (old.folderPath != new.folderPath || old.filePath != new.filePath) {
            add(DeviceShadowCanonicalAspect.FOLDER_IDENTITY)
        }
        if (
            old.title != new.title ||
            old.artist != new.artist ||
            old.album != new.album ||
            old.albumArtist != new.albumArtist ||
            old.durationSec != new.durationSec ||
            old.metadata != new.metadata ||
            old.year != new.year ||
            old.releaseDate != new.releaseDate ||
            old.metadataScanVersion != new.metadataScanVersion ||
            old.trackNumber != new.trackNumber ||
            old.discNumber != new.discNumber ||
            old.copyright != new.copyright ||
            old.comment != new.comment ||
            old.codecLabel != new.codecLabel
        ) {
            add(DeviceShadowCanonicalAspect.TAG_METADATA)
        }
        if (old.externalLyricsSignature != new.externalLyricsSignature) {
            add(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS)
        }
        if (old.videoCoverUri != new.videoCoverUri || old.videoCoverRevision != new.videoCoverRevision) {
            add(DeviceShadowCanonicalAspect.VIDEO_COVER)
        }
        if (old.musicVideoUri != new.musicVideoUri || old.musicVideoRevision != new.musicVideoRevision) {
            add(DeviceShadowCanonicalAspect.MUSIC_VIDEO)
        }
        if (old.replayGain != new.replayGain) {
            add(DeviceShadowCanonicalAspect.REPLAY_GAIN)
        }
        if (old.embeddedLyricsProbeRevision != new.embeddedLyricsProbeRevision) {
            add(DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE)
        }
    }
}

internal data class SafShadowCanonicalProjectionResult(
    val snapshot: SafShadowCanonicalSnapshot,
    val unresolvedAspectsByStableObjectKey: Map<String, Set<DeviceShadowCanonicalAspect>>,
) {
    val fullyResolved: Boolean
        get() = unresolvedAspectsByStableObjectKey.isEmpty()
}

internal data class SafShadowCanonicalEquivalenceResult(
    val projection: SafShadowCanonicalProjectionResult,
    val expected: SafShadowCanonicalSnapshot,
    val diff: SafShadowCanonicalDiff,
) {
    val fullyEquivalent: Boolean
        get() = projection.fullyResolved && diff.changes.isEmpty()
}

internal object SafShadowCanonicalProjector {
    val SUCCESSFUL_PROBE_RESOLVED_ASPECTS: Set<DeviceShadowCanonicalAspect> = setOf(
        DeviceShadowCanonicalAspect.TAG_METADATA,
        DeviceShadowCanonicalAspect.EXTERNAL_LYRICS,
        DeviceShadowCanonicalAspect.REPLAY_GAIN,
        DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE,
    )

    val RELATION_RESOLVED_ASPECTS: Set<DeviceShadowCanonicalAspect> = setOf(
        DeviceShadowCanonicalAspect.VIDEO_COVER,
        DeviceShadowCanonicalAspect.MUSIC_VIDEO,
    )

    fun project(
        baseline: SafShadowCanonicalSnapshot,
        verifyPlan: SafFastVerifyPlan,
        resolvedSongsByStableObjectKey: Map<String, Song>,
        resolvedRelationSongsByStableObjectKey: Map<String, Song> = emptyMap(),
        unresolvedRelationStableObjectKeys: Set<String> = emptySet(),
        unresolvedAudioResourceStableObjectKeys: Set<String> = emptySet(),
        audioWorkEntries: Collection<SafTreeMetadataEntry> =
            verifyPlan.added + verifyPlan.changed + verifyPlan.unknownFingerprint,
    ): SafShadowCanonicalProjectionResult {
        val songs = baseline.songsByStableObjectKey.toMutableMap()
        val unresolved = linkedMapOf<String, MutableSet<DeviceShadowCanonicalAspect>>()
        val workByKey = linkedMapOf<String, SafTreeMetadataEntry>()
        audioWorkEntries.forEach { entry ->
            workByKey[entry.stableObjectKey] = entry
        }

        workByKey.forEach { (key, entry) ->
            val resolved = resolvedSongsByStableObjectKey[key]
            val current = songs[key]
            when {
                resolved != null -> {
                    songs[key] = normalizeResolvedSong(
                        resolved = resolved.toDeviceShadowCanonicalSong(key),
                        entry = entry,
                    )
                    if (key in unresolvedAudioResourceStableObjectKeys) {
                        unresolved.getOrPut(key) { linkedSetOf() }.addAll(
                            setOf(
                                DeviceShadowCanonicalAspect.EXTERNAL_LYRICS,
                                DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE,
                            ),
                        )
                    }
                }

                current != null -> {
                    songs[key] = patchDiscoveryFacts(current, entry)
                    unresolved.getOrPut(key) { linkedSetOf() }.addAll(
                        setOf(
                            DeviceShadowCanonicalAspect.TAG_METADATA,
                            DeviceShadowCanonicalAspect.EXTERNAL_LYRICS,
                            DeviceShadowCanonicalAspect.REPLAY_GAIN,
                            DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE,
                        ),
                    )
                }

                else -> {
                    unresolved.getOrPut(key) { linkedSetOf() }
                        .addAll(DeviceShadowCanonicalAspect.entries)
                }
            }
        }

        verifyPlan.removedStableObjectKeys.forEach { key ->
            songs.remove(key)
            unresolved.remove(key)
        }

        resolvedRelationSongsByStableObjectKey.forEach { (key, relationSong) ->
            val current = songs[key] ?: return@forEach
            songs[key] = current.copy(
                videoCoverUri = relationSong.videoCoverUri,
                videoCoverRevision = relationSong.videoCoverRevision,
                musicVideoUri = relationSong.musicVideoUri,
                musicVideoRevision = relationSong.musicVideoRevision,
            )
            unresolved[key]?.removeAll(RELATION_RESOLVED_ASPECTS)
        }
        unresolvedRelationStableObjectKeys.forEach { key ->
            if (key in songs) {
                unresolved.getOrPut(key) { linkedSetOf() }
                    .addAll(RELATION_RESOLVED_ASPECTS)
            }
        }

        return SafShadowCanonicalProjectionResult(
            snapshot = SafShadowCanonicalSnapshot(
                context = baseline.context,
                songsByStableObjectKey = songs.toSortedMap(),
            ),
            unresolvedAspectsByStableObjectKey = unresolved
                .mapValues { (_, aspects) -> aspects.toSet() }
                .filterValues(Set<DeviceShadowCanonicalAspect>::isNotEmpty),
        )
    }

    fun compare(
        projection: SafShadowCanonicalProjectionResult,
        expected: SafShadowCanonicalSnapshot,
    ): SafShadowCanonicalEquivalenceResult {
        require(projection.snapshot.context == expected.context)
        return SafShadowCanonicalEquivalenceResult(
            projection = projection,
            expected = expected,
            diff = SafShadowCanonicalCatalog.diff(projection.snapshot, expected),
        )
    }

    private fun normalizeResolvedSong(
        resolved: DeviceShadowCanonicalSong,
        entry: SafTreeMetadataEntry,
    ): DeviceShadowCanonicalSong = resolved.copy(
        stableObjectKey = entry.stableObjectKey,
        mediaUri = entry.mediaUri,
        fileName = entry.fileName,
        sizeBytes = entry.sizeBytes,
        dateModifiedMs = entry.lastModifiedMs,
        folderPath = entry.folderPath,
        filePath = entry.filePath,
        externalLyricsSignature = entry.externalLyricsSignature,
    )

    private fun patchDiscoveryFacts(
        current: DeviceShadowCanonicalSong,
        entry: SafTreeMetadataEntry,
    ): DeviceShadowCanonicalSong = current.copy(
        mediaUri = entry.mediaUri,
        fileName = entry.fileName,
        sizeBytes = entry.sizeBytes,
        dateModifiedMs = entry.lastModifiedMs,
        folderPath = entry.folderPath,
        filePath = entry.filePath,
        externalLyricsSignature = entry.externalLyricsSignature,
    )
}

internal sealed interface SafShadowCanonicalProjectionGateResult {
    data class BaselineEstablished(
        val songCount: Int,
    ) : SafShadowCanonicalProjectionGateResult

    data class ContextReset(
        val previous: SafShadowCanonicalContext,
        val current: SafShadowCanonicalContext,
        val songCount: Int,
    ) : SafShadowCanonicalProjectionGateResult

    data class Compared(
        val equivalence: SafShadowCanonicalEquivalenceResult,
    ) : SafShadowCanonicalProjectionGateResult
}

internal class SafShadowCanonicalProjectionTracker {
    private var baseline: SafShadowCanonicalSnapshot? = null
    private var projection: SafShadowCanonicalProjectionResult? = null

    @Synchronized
    fun recordDelta(
        verifyPlan: SafFastVerifyPlan,
        resolvedSongsByStableObjectKey: Map<String, Song>,
        resolvedRelationSongsByStableObjectKey: Map<String, Song> = emptyMap(),
        unresolvedRelationStableObjectKeys: Set<String> = emptySet(),
        unresolvedAudioResourceStableObjectKeys: Set<String> = emptySet(),
        audioWorkEntries: Collection<SafTreeMetadataEntry> =
            verifyPlan.added + verifyPlan.changed + verifyPlan.unknownFingerprint,
    ) {
        val baselineSnapshot = baseline ?: return
        val currentProjection = projection ?: cleanProjection(baselineSnapshot)
        val next = SafShadowCanonicalProjector.project(
            baseline = currentProjection.snapshot,
            verifyPlan = verifyPlan,
            resolvedSongsByStableObjectKey = resolvedSongsByStableObjectKey,
            resolvedRelationSongsByStableObjectKey = resolvedRelationSongsByStableObjectKey,
            unresolvedRelationStableObjectKeys = unresolvedRelationStableObjectKeys,
            unresolvedAudioResourceStableObjectKeys =
                unresolvedAudioResourceStableObjectKeys,
            audioWorkEntries = audioWorkEntries,
        )

        val removed = verifyPlan.removedStableObjectKeys
        val audioResolvedKeys = resolvedSongsByStableObjectKey.keys
        val relationResolvedKeys = resolvedRelationSongsByStableObjectKey.keys
        val mergedUnresolved = linkedMapOf<String, MutableSet<DeviceShadowCanonicalAspect>>()

        currentProjection.unresolvedAspectsByStableObjectKey.forEach { (key, aspects) ->
            if (key in removed || key !in next.snapshot.songsByStableObjectKey) return@forEach
            var stillUnresolved = aspects
            if (
                key in audioResolvedKeys &&
                key !in currentProjection.snapshot.songsByStableObjectKey
            ) {
                // A previously deferred NEW object had no canonical row to patch, so the prior
                // round conservatively marked every aspect unresolved. Once a later round
                // successfully resolves and inserts that object, discard that historical debt and
                // let this round's projector re-add only capabilities that are still unresolved.
                stillUnresolved = emptySet()
            } else if (key in audioResolvedKeys) {
                stillUnresolved -= SafShadowCanonicalProjector.SUCCESSFUL_PROBE_RESOLVED_ASPECTS
            }
            if (key in relationResolvedKeys) {
                stillUnresolved -= SafShadowCanonicalProjector.RELATION_RESOLVED_ASPECTS
            }
            if (stillUnresolved.isNotEmpty()) {
                mergedUnresolved.getOrPut(key) { linkedSetOf() }.addAll(stillUnresolved)
            }
        }
        next.unresolvedAspectsByStableObjectKey.forEach { (key, aspects) ->
            mergedUnresolved.getOrPut(key) { linkedSetOf() }.addAll(aspects)
        }

        projection = next.copy(
            unresolvedAspectsByStableObjectKey = mergedUnresolved
                .mapValues { (_, aspects) -> aspects.toSet() }
                .filterValues(Set<DeviceShadowCanonicalAspect>::isNotEmpty),
        )
    }

    @Synchronized
    fun resolvedAudioStableObjectKeys(
        entries: Collection<SafTreeMetadataEntry>,
    ): Set<String> {
        val currentProjection = projection ?: return emptySet()
        return entries.asSequence()
            .filter { entry ->
                val canonical = currentProjection.snapshot
                    .songsByStableObjectKey[entry.stableObjectKey]
                    ?: return@filter false
                val unresolved = currentProjection
                    .unresolvedAspectsByStableObjectKey[entry.stableObjectKey]
                    .orEmpty()
                unresolved.none {
                    it in SafShadowCanonicalProjector.SUCCESSFUL_PROBE_RESOLVED_ASPECTS
                } && canonical.matchesObservedEntry(entry)
            }
            .mapTo(linkedSetOf(), SafTreeMetadataEntry::stableObjectKey)
    }

    @Synchronized
    fun acceptFullSnapshot(
        snapshot: SafShadowCanonicalSnapshot,
    ): SafShadowCanonicalProjectionGateResult {
        val previous = baseline
        if (previous == null) {
            baseline = snapshot
            projection = cleanProjection(snapshot)
            return SafShadowCanonicalProjectionGateResult.BaselineEstablished(
                songCount = snapshot.songsByStableObjectKey.size,
            )
        }
        if (previous.context != snapshot.context) {
            baseline = snapshot
            projection = cleanProjection(snapshot)
            return SafShadowCanonicalProjectionGateResult.ContextReset(
                previous = previous.context,
                current = snapshot.context,
                songCount = snapshot.songsByStableObjectKey.size,
            )
        }

        val candidate = projection ?: cleanProjection(previous)
        val equivalence = SafShadowCanonicalProjector.compare(candidate, snapshot)
        baseline = snapshot
        projection = cleanProjection(snapshot)
        return SafShadowCanonicalProjectionGateResult.Compared(equivalence)
    }

    @Synchronized
    fun reset() {
        baseline = null
        projection = null
    }

    private fun DeviceShadowCanonicalSong.matchesObservedEntry(
        entry: SafTreeMetadataEntry,
    ): Boolean =
        mediaUri == entry.mediaUri &&
            fileName == entry.fileName &&
            sizeBytes == entry.sizeBytes &&
            dateModifiedMs == entry.lastModifiedMs &&
            folderPath == entry.folderPath &&
            filePath == entry.filePath &&
            externalLyricsSignature == entry.externalLyricsSignature

    private fun cleanProjection(
        snapshot: SafShadowCanonicalSnapshot,
    ) = SafShadowCanonicalProjectionResult(
        snapshot = snapshot,
        unresolvedAspectsByStableObjectKey = emptyMap(),
    )
}
