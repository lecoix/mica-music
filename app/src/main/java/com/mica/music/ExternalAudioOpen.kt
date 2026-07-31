package com.mica.music

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.mica.music.data.Song
import com.mica.music.data.scanner.AudioMetadataProbe
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.data.scanner.TrackDraft

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

internal object ExternalAudioSongResolver {

    fun resolve(
        context: Context,
        request: ExternalAudioOpenRequest,
        librarySongs: List<Song>,
    ): Song? {
        val uriText = request.uri.toString()
        val existing = librarySongs.firstOrNull { it.mediaUri == uriText }

        val resolver = context.contentResolver
        val readable = runCatching {
            resolver.openAssetFileDescriptor(request.uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return existing

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
        }.getOrNull() ?: return existing
        return mergeExternalAudioProbeResult(existing = existing, probed = probed)
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
