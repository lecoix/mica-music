package com.mica.music

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.scanner.AudioMetadataProbe
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.data.scanner.TrackDraft
import java.security.MessageDigest

internal data class ExternalAudioOpenRequest(
    val uri: Uri,
    val mimeType: String?,
)

internal fun parseExternalAudioOpenRequest(intent: Intent?): ExternalAudioOpenRequest? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val mimeType = intent.type
    if (mimeType != null && !mimeType.startsWith("audio/", ignoreCase = true)) return null
    val uri = intent.data
        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        ?: return null
    if (uri.scheme != "content") return null
    return ExternalAudioOpenRequest(uri = uri, mimeType = mimeType)
}

/** Persists a provider grant and reports whether this URI can survive a process restart. */
internal fun persistExternalAudioUriPermission(
    context: Context,
    intent: Intent?,
    request: ExternalAudioOpenRequest,
): Boolean {
    if (request.uri.scheme != "content") return false
    // MediaStore access is governed by the app's media permission, not a one-shot intent grant.
    if (request.uri.authority == "media") return true
    val resolver = context.contentResolver
    if (resolver.persistedUriPermissions.any { it.uri == request.uri }) return true
    val flags = intent?.flags ?: 0
    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
    val accessFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
    if (accessFlags == 0) return false
    return runCatching {
        resolver.takePersistableUriPermission(request.uri, accessFlags)
        resolver.persistedUriPermissions.any { it.uri == request.uri }
    }.getOrDefault(false)
}

internal fun mergeExternalAudioProbeResult(existing: Song?, probed: Song): Song {
    if (existing == null) return probed
    return probed.copy(
        albumArtUri = probed.albumArtUri ?: existing.albumArtUri,
        playbackUri = existing.playbackUri,
        videoCoverUri = existing.videoCoverUri,
        playCount = existing.playCount,
        totalListenSeconds = existing.totalListenSeconds,
        lastPlayedAtMs = existing.lastPlayedAtMs,
    )
}

internal fun transientExternalSongId(uri: Uri): String =
    "${TransientPlaybackCatalog.TRANSIENT_ID_PREFIX}${sha256(uri.toString())}"

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append("0123456789abcdef"[unsigned ushr 4])
            append("0123456789abcdef"[unsigned and 0x0f])
        }
    }
}

internal object ExternalAudioSongResolver {

    fun resolve(
        context: Context,
        request: ExternalAudioOpenRequest,
        librarySongs: List<Song>,
        transientCatalog: TransientPlaybackCatalog? = null,
    ): Song? {
        val uriText = request.uri.toString()
        val existing = librarySongs.firstOrNull { it.mediaUri == uriText }

        val resolver = context.contentResolver
        val readable = runCatching {
            resolver.openAssetFileDescriptor(request.uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return null

        val openable = queryOpenableMetadata(context, request.uri)
        val displayName = openable.displayName
            ?: request.uri.lastPathSegment?.substringAfterLast('/')
            ?: "audio"
        val sourceMime = firstUsefulMime(
            request.mimeType,
            runCatching { resolver.getType(request.uri) }.getOrNull(),
            existing?.metadata?.playbackMimeType,
        )
        val draft = TrackDraft(
            mediaStoreId = externalMediaStoreId(request.uri) ?: 0L,
            title = existing?.title ?: displayName.substringBeforeLast('.', displayName),
            artist = existing?.artist ?: "未知艺术家",
            album = existing?.album ?: "未知专辑",
            albumId = 0L,
            durationSec = existing?.durationSec ?: 0,
            mimeType = sourceMime,
            displayName = displayName,
            sizeBytes = openable.sizeBytes.takeIf { it > 0L } ?: existing?.sizeBytes ?: 0L,
            bitrateBpsFromStore = existing?.metadata?.bitrateKbps?.coerceAtLeast(0)?.times(1_000) ?: 0,
            mediaUri = uriText,
            coverColorArgb = existing?.coverColorArgb ?: CoverColorExtractor.FALLBACK_ARGB,
            year = existing?.year ?: 0,
            releaseDate = existing?.releaseDate.orEmpty(),
            folderPath = existing?.folderPath.orEmpty(),
            filePath = existing?.filePath?.ifBlank { displayName } ?: displayName,
            albumArtist = existing?.albumArtist.orEmpty(),
            copyright = existing?.copyright.orEmpty(),
            codecLabel = existing?.codecLabel.orEmpty(),
            dateAddedMs = existing?.dateAddedMs ?: 0L,
            dateModifiedMs = existing?.dateModifiedMs ?: 0L,
        )
        val probed = runCatching {
            AudioMetadataProbe.probeTrack(
                context = context,
                draft = draft,
                cachedSong = existing,
            ).song
        }.getOrNull()
        val baseSong = probed ?: existing ?: return null
        val transientSong = mergeExternalAudioProbeResult(existing = existing, probed = baseSong)
            .copy(
                id = transientExternalSongId(request.uri),
                source = SongSource.TRANSIENT_EXTERNAL,
                playCount = 0,
                totalListenSeconds = 0L,
                lastPlayedAtMs = 0L,
            )
        return transientCatalog?.replace(transientSong) ?: transientSong
    }

    private fun queryOpenableMetadata(context: Context, uri: Uri): OpenableMetadata {
        var displayName: String? = null
        var sizeBytes = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
            }
        }
        return OpenableMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    private fun firstUsefulMime(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() && it != "audio/*" }
            ?: candidates.firstOrNull { !it.isNullOrBlank() }
            ?: "audio/*"

    private fun externalMediaStoreId(uri: Uri): Long? =
        if (uri.authority == "media") {
            runCatching { ContentUris.parseId(uri) }.getOrNull()
        } else {
            null
        }

    private data class OpenableMetadata(
        val displayName: String?,
        val sizeBytes: Long,
    )
}
