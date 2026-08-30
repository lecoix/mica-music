package com.mica.music.data.remote

import android.net.Uri
import java.util.Locale

internal data class RemoteSidecarArtworkCandidate(
    val fileName: String,
    val resourceId: String,
    val contentRevision: String,
    val sizeBytes: Long,
)

internal data class RemoteFileArtworkTarget(
    val resourceId: String,
    val contentRevision: String,
)

internal data class RemoteEmbeddedArtworkTarget(
    val resourceId: String,
    val contentRevision: String,
    val sizeBytes: Long,
)

/**
 * Stable opaque artwork payload for file-backed remote sources.
 *
 * The path is relative to the configured source root and never contains credentials. The content
 * revision is part of the opaque id so a changed sidecar image gets a new public content URI and
 * therefore cannot hit stale in-process artwork bytes.
 */
internal object RemoteFileArtworkIdCodec {
    private const val PREFIX = "file-v1"

    fun encode(resourceId: String, contentRevision: String): String {
        require(resourceId.isNotBlank()) { "Remote artwork resource id must not be blank" }
        return "$PREFIX:${Uri.encode(resourceId)}:${Uri.encode(contentRevision)}"
    }

    fun decode(value: String): RemoteFileArtworkTarget? {
        val parts = value.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val resourceId = Uri.decode(parts[1]).takeIf(String::isNotBlank) ?: return null
        return RemoteFileArtworkTarget(
            resourceId = resourceId,
            contentRevision = Uri.decode(parts[2]),
        )
    }
}

/** Stable opaque id for artwork embedded inside the remote audio file itself. */
internal object RemoteEmbeddedArtworkIdCodec {
    private const val PREFIX = "embedded-v1"

    fun encode(resourceId: String, contentRevision: String, sizeBytes: Long): String {
        require(resourceId.isNotBlank()) { "Remote embedded artwork resource id must not be blank" }
        require(sizeBytes >= 0L) { "Remote embedded artwork size must not be negative" }
        return "$PREFIX:${Uri.encode(resourceId)}:${Uri.encode(contentRevision)}:$sizeBytes"
    }

    fun decode(value: String): RemoteEmbeddedArtworkTarget? {
        val parts = value.split(':', limit = 4)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val resourceId = Uri.decode(parts[1]).takeIf(String::isNotBlank) ?: return null
        val sizeBytes = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return RemoteEmbeddedArtworkTarget(
            resourceId = resourceId,
            contentRevision = Uri.decode(parts[2]),
            sizeBytes = sizeBytes,
        )
    }
}

/** Track-specific sidecars are safe even in a directory containing many unrelated albums. */
internal fun selectRemoteTrackSidecarArtwork(
    trackFileName: String,
    candidates: List<RemoteSidecarArtworkCandidate>,
): RemoteSidecarArtworkCandidate? {
    if (candidates.isEmpty()) return null
    val trackStem = trackFileName.substringBeforeLast('.', trackFileName)
    return candidates
        .filter { candidate -> candidate.fileName.substringBeforeLast('.', candidate.fileName).equals(trackStem, true) }
        .minWithOrNull(REMOTE_ARTWORK_ORDER)
}

/** Conventional folder art is considered separately because it is unsafe in mixed-album folders. */
internal fun selectRemoteFolderSidecarArtwork(
    candidates: List<RemoteSidecarArtworkCandidate>,
): RemoteSidecarArtworkCandidate? = REMOTE_FOLDER_ARTWORK_STEMS.firstNotNullOfOrNull { preferredStem ->
    candidates
        .filter { candidate ->
            candidate.fileName.substringBeforeLast('.', candidate.fileName).equals(preferredStem, true)
        }
        .minWithOrNull(REMOTE_ARTWORK_ORDER)
}

/**
 * A generic Folder/cover/front image is only trustworthy when the complete directory contains one
 * track, or every track with browse metadata agrees on the same non-blank album. This deliberately
 * rejects Windows Media Player style mixed folders where Folder.jpg coexists with many album GUID
 * images but has no reliable per-track mapping.
 */
internal fun canUseRemoteFolderArtwork(tracks: List<RemoteTrackSummary>): Boolean {
    if (tracks.size == 1) return true
    if (tracks.isEmpty()) return false
    val albums = tracks.map { it.album.trim() }
    if (albums.any(String::isBlank)) return false
    return albums.map { it.lowercase(Locale.US) }.distinct().size == 1
}

internal fun isRemoteSidecarArtworkFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase(Locale.US) in REMOTE_ARTWORK_EXTENSIONS

internal fun remoteArtworkRevisionKey(contentRevision: String, sizeBytes: Long): String =
    contentRevision.ifBlank { "size=${sizeBytes.coerceAtLeast(0L)}" }

internal const val DEFAULT_REMOTE_ARTWORK_MAX_BYTES = 32L * 1024L * 1024L

private val REMOTE_ARTWORK_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
private val REMOTE_FOLDER_ARTWORK_STEMS = listOf("cover", "folder", "front")
private val REMOTE_ARTWORK_EXTENSION_ORDER = mapOf("jpg" to 0, "jpeg" to 1, "png" to 2, "webp" to 3)
private val REMOTE_ARTWORK_ORDER = compareBy<RemoteSidecarArtworkCandidate>(
    { REMOTE_ARTWORK_EXTENSION_ORDER[it.fileName.substringAfterLast('.', "").lowercase(Locale.US)] ?: Int.MAX_VALUE },
    { it.fileName.lowercase(Locale.US) },
    { it.resourceId },
)
