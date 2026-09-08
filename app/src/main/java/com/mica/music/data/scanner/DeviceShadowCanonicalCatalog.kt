package com.mica.music.data.scanner

import android.net.Uri
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata

/**
 * Side-effect-free S3 shadow projection.
 *
 * This intentionally models only canonical catalog facts available from [Song]. It excludes
 * runtime stats, lazy lyrics payload, dynamic cover color, loudness analysis and playback/session
 * state. Lyrics resource-head equality and hidden/filter-state equality require their own canonical
 * source and are separate S3 gates.
 */
internal data class DeviceShadowCanonicalContext(
    val sourceIdentityStorageKey: String,
    val activationEpoch: Long,
    val configFingerprint: String,
    val providerIdentityDomain: String,
)

internal fun DeviceGenerationSnapshot.Available.providerIdentityDomainKey(): String =
    volumes.toSortedMap().entries.joinToString(
        prefix = "mediastore:",
        separator = ";",
    ) { (volumeName, state) ->
        "$volumeName:${state.providerVersion}"
    }

internal enum class DeviceShadowCanonicalAspect {
    MEMBERSHIP,
    MEDIA_IDENTITY,
    FILE_FINGERPRINT,
    FOLDER_IDENTITY,
    TAG_METADATA,
    EXTERNAL_LYRICS,
    VIDEO_COVER,
    MUSIC_VIDEO,
    REPLAY_GAIN,
    EMBEDDED_LYRICS_PROBE,
}

internal data class DeviceShadowCanonicalSong(
    val stableObjectKey: String,
    val mediaUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val dateAddedMs: Long,
    val folderPath: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationSec: Int,
    val metadata: TrackMetadata,
    val year: Int,
    val releaseDate: String,
    val metadataScanVersion: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val copyright: String,
    val comment: String,
    val codecLabel: String,
    val externalLyricsSignature: String,
    val embeddedLyricsProbeRevision: String,
    val replayGain: ReplayGainTags,
    val videoCoverUri: String?,
    val videoCoverRevision: String,
    val musicVideoUri: String?,
    val musicVideoRevision: String,
)

internal data class DeviceShadowCanonicalSnapshot(
    val context: DeviceShadowCanonicalContext,
    val songsByStableObjectKey: Map<String, DeviceShadowCanonicalSong>,
)

internal data class DeviceShadowCanonicalChange(
    val stableObjectKey: String,
    val aspects: Set<DeviceShadowCanonicalAspect>,
)

internal data class DeviceShadowCanonicalDiff(
    val changes: List<DeviceShadowCanonicalChange>,
) {
    val changedStableObjectKeys: Set<String>
        get() = changes.mapTo(linkedSetOf(), DeviceShadowCanonicalChange::stableObjectKey)
}

internal sealed interface DeviceShadowCanonicalCoverageResult {
    data class BaselineEstablished(
        val songCount: Int,
    ) : DeviceShadowCanonicalCoverageResult

    data class ContextReset(
        val previous: DeviceShadowCanonicalContext,
        val current: DeviceShadowCanonicalContext,
        val songCount: Int,
    ) : DeviceShadowCanonicalCoverageResult

    data class Compared(
        val diff: DeviceShadowCanonicalDiff,
        val uncoveredAspectsByStableObjectKey: Map<String, Set<DeviceShadowCanonicalAspect>>,
        val coveredStableObjectKeys: Set<String>,
    ) : DeviceShadowCanonicalCoverageResult {
        val fullyCovered: Boolean
            get() = uncoveredAspectsByStableObjectKey.isEmpty()
    }
}

internal object DeviceShadowCanonicalCatalog {
    fun snapshot(
        songs: List<Song>,
        context: DeviceShadowCanonicalContext,
    ): DeviceShadowCanonicalSnapshot {
        val canonical = LinkedHashMap<String, DeviceShadowCanonicalSong>(songs.size)
        songs.forEach { song ->
            val stableKey = song.id
            val previous = canonical.put(stableKey, song.toDeviceShadowCanonicalSong(stableKey))
            require(previous == null) {
                "duplicate canonical DEVICE stable object key: $stableKey"
            }
        }
        return DeviceShadowCanonicalSnapshot(
            context = context,
            songsByStableObjectKey = canonical.toSortedMap(),
        )
    }

    fun diff(
        before: DeviceShadowCanonicalSnapshot,
        after: DeviceShadowCanonicalSnapshot,
    ): DeviceShadowCanonicalDiff {
        require(before.context == after.context) {
            "canonical snapshots must share the same shadow context"
        }
        val keys = (before.songsByStableObjectKey.keys + after.songsByStableObjectKey.keys).toSortedSet()
        val changes = keys.mapNotNull { key ->
            val old = before.songsByStableObjectKey[key]
            val new = after.songsByStableObjectKey[key]
            val aspects = when {
                old == null || new == null -> setOf(DeviceShadowCanonicalAspect.MEMBERSHIP)
                else -> changedAspects(old, new)
            }
            aspects.takeIf(Set<DeviceShadowCanonicalAspect>::isNotEmpty)?.let {
                DeviceShadowCanonicalChange(key, it)
            }
        }
        return DeviceShadowCanonicalDiff(changes)
    }

    private fun changedAspects(
        old: DeviceShadowCanonicalSong,
        new: DeviceShadowCanonicalSong,
    ): Set<DeviceShadowCanonicalAspect> = buildSet {
        if (old.mediaUri != new.mediaUri || old.fileName != new.fileName) {
            add(DeviceShadowCanonicalAspect.MEDIA_IDENTITY)
        }
        if (
            old.sizeBytes != new.sizeBytes ||
            old.dateModifiedMs != new.dateModifiedMs
        ) {
            add(DeviceShadowCanonicalAspect.FILE_FINGERPRINT)
        }
        if (old.dateAddedMs != new.dateAddedMs) {
            add(DeviceShadowCanonicalAspect.MEMBERSHIP)
        }
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


internal fun canonicalDeviceMediaIdentityUri(
    stableObjectKey: String,
    mediaUri: String,
): String {
    if (!stableObjectKey.startsWith("ms_")) return mediaUri
    val parsed = runCatching { Uri.parse(mediaUri) }.getOrNull() ?: return mediaUri
    return if (
        parsed.scheme.equals("content", ignoreCase = true) &&
        parsed.authority.equals("media", ignoreCase = true)
    ) {
        "mediastore:$stableObjectKey"
    } else {
        mediaUri
    }
}

internal fun Song.toDeviceShadowCanonicalSong(
    stableObjectKey: String,
): DeviceShadowCanonicalSong = DeviceShadowCanonicalSong(
    stableObjectKey = stableObjectKey,
    mediaUri = canonicalDeviceMediaIdentityUri(stableObjectKey, mediaUri),
    fileName = fileName,
    sizeBytes = sizeBytes,
    dateModifiedMs = dateModifiedMs,
    dateAddedMs = dateAddedMs,
    folderPath = folderPath,
    filePath = filePath,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    metadata = metadata,
    year = year,
    releaseDate = releaseDate,
    metadataScanVersion = metadataScanVersion,
    trackNumber = trackNumber,
    discNumber = discNumber,
    copyright = copyright,
    comment = comment,
    codecLabel = codecLabel,
    externalLyricsSignature = externalLyricsSignature,
    embeddedLyricsProbeRevision = embeddedLyricsProbeRevision,
    replayGain = replayGain,
    videoCoverUri = videoCoverUri,
    videoCoverRevision = videoCoverRevision,
    musicVideoUri = musicVideoUri,
    musicVideoRevision = musicVideoRevision,
)
