package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.media.AlacPlayback
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class TrackDraft(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationSec: Int,
    val mimeType: String,
    val displayName: String?,
    val sizeBytes: Long,
    val bitrateBpsFromStore: Int,
    val mediaUri: String,
    val coverColorArgb: Int,
    val year: Int = 0,
    val folderPath: String = "",
    val filePath: String = "",
    val albumArtist: String = "",
    val copyright: String = "",
    val codecLabel: String = "",
    val dateAddedMs: Long = 0L,
    val dateModifiedMs: Long = 0L,
)

private data class TagInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val copyright: String,
    val durationSec: Int,
    val year: Int,
)

object AudioMetadataProbe {

    private val albumArtCache = ConcurrentHashMap<String, String?>()

    fun clearArtCache() {
        albumArtCache.clear()
    }

    internal fun quickSong(context: Context, draft: TrackDraft): Song {
        val metadata = TrackMetadata.fallback(
            mimeType = draft.mimeType,
            bitrateBpsFromStore = draft.bitrateBpsFromStore,
            displayName = draft.displayName,
            mediaUri = draft.mediaUri,
        )
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        val lyrics = EmbeddedLyricsReader.read(appCtx, uri, draft.mimeType, draft.displayName, draft.filePath)
        val albumArtUri = resolveAlbumArtFromStoreOnly(context, draft.albumId)
        val coverArgb = resolveCoverColor(appCtx, null, uri, draft.albumId, albumArtUri)
            ?: draft.coverColorArgb
        return draft.copy(coverColorArgb = coverArgb).toSong(
            appCtx,
            metadata,
            albumArtUri = albumArtUri,
            lyrics = lyrics,
        )
    }

    internal fun probeTrack(context: Context, draft: TrackDraft): Song {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        val trackProbe = AudioTrackProbe.probe(appCtx, uri, draft.mimeType, draft.displayName)
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, appCtx, uri)
            val tags = readTags(retriever, draft)
            val enriched = draft.copy(
                title = tags.title,
                artist = tags.artist,
                album = tags.album,
                durationSec = tags.durationSec,
                year = tags.year,
            )
            val metadata = readMetadata(retriever, enriched, trackProbe, tags.durationSec)
            val encoderSettings = EncoderSettingsReader.read(appCtx, uri, retriever)
            val withMeta = enriched.copy(
                albumArtist = tags.albumArtist,
                copyright = tags.copyright,
                codecLabel = encoderSettings.ifBlank {
                    trackProbe?.trackMime ?: metadata.playbackMimeType
                },
            )
            val artKey = artCacheKey(withMeta)
            val albumArtUri = resolveAlbumArt(appCtx, retriever, artKey, withMeta.albumId, uri)
            val lyrics = EmbeddedLyricsReader.read(appCtx, uri, withMeta.mimeType, withMeta.displayName, withMeta.filePath)
            val coverArgb = resolveCoverColor(appCtx, retriever, uri, withMeta.albumId, albumArtUri)
                ?: withMeta.coverColorArgb
            withMeta.copy(coverColorArgb = coverArgb).toSong(appCtx, metadata, albumArtUri, lyrics)
        } catch (_: Exception) {
            val metadata = if (trackProbe != null) {
                TrackMetadata.fallback(
                    mimeType = trackProbe.trackMime ?: draft.mimeType,
                    bitrateBpsFromStore = draft.bitrateBpsFromStore,
                    displayName = draft.displayName,
                    mediaUri = draft.mediaUri,
                ).copy(
                    containerName = trackProbe.containerName,
                    playbackMimeType = trackProbe.playbackMimeType,
                )
            } else {
                TrackMetadata.fallback(
                    mimeType = draft.mimeType,
                    bitrateBpsFromStore = draft.bitrateBpsFromStore,
                    displayName = draft.displayName,
                    mediaUri = draft.mediaUri,
                )
            }
            val lyrics = EmbeddedLyricsReader.read(appCtx, uri, draft.mimeType, draft.displayName, draft.filePath)
            draft.toSong(
                appCtx,
                metadata,
                albumArtUri = resolveAlbumArtFromStoreOnly(appCtx, draft.albumId),
                lyrics = lyrics,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** [MediaMetadataRetriever.extractMetadata] 的字符串 key 在部分 SDK 绑定中不可用，用反射读取。 */
    private fun extractMetadataString(retriever: MediaMetadataRetriever, key: String): String? =
        runCatching {
            val method = MediaMetadataRetriever::class.java.getMethod(
                "extractMetadata",
                String::class.java,
            )
            method.invoke(retriever, key) as? String
        }.getOrNull()

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, context: Context, uri: Uri) {
        try {
            retriever.setDataSource(context, uri)
        } catch (_: Exception) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("无法打开音频文件：$uri")
        }
    }

    private fun readTags(retriever: MediaMetadataRetriever, draft: TrackDraft): TagInfo {
        val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val title = MetadataTextFix.titleFromTagsOrFilename(
            tagTitle = rawTitle,
            displayName = draft.displayName,
            fallbackTitle = draft.title,
        )
        val albumArtist = MetadataTextFix.normalize(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "",
        )
        val artist = ArtistNames.normalizeDisplay(
            MetadataTextFix.normalize(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?: albumArtist.takeIf { it.isNotBlank() }
                    ?: draft.artist,
            ),
        )
        val album = MetadataTextFix.normalize(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: draft.album,
        )
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val durationSec = when {
            durationMs > 0 -> (durationMs / 1000).toInt()
            draft.durationSec > 0 -> draft.durationSec
            else -> 0
        }
        val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?.toIntOrNull()?.coerceAtLeast(0) ?: draft.year
        val copyright = MetadataTextFix.normalize(
            extractMetadataString(retriever, "copyright")
                ?.takeIf { it.isNotBlank() }
                ?: "",
        )
        return TagInfo(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            copyright = copyright,
            durationSec = durationSec,
            year = year,
        )
    }

    private fun readMetadata(
        retriever: MediaMetadataRetriever,
        draft: TrackDraft,
        trackProbe: AudioTrackProbe.Result?,
        durationSec: Int,
    ): TrackMetadata {
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            ?: trackProbe?.trackMime
            ?: draft.mimeType
        val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull() ?: 0
        val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toIntOrNull() ?: draft.bitrateBpsFromStore
        val channels = 2

        val bits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()
        } else {
            null
        }

        val durationForBitrate = durationSec.coerceAtLeast(1)
        val bitrateKbps = when {
            bitrateBps > 0 -> bitrateBps / 1000
            draft.sizeBytes > 0 -> ((draft.sizeBytes * 8L) / durationForBitrate / 1000L).toInt().coerceAtLeast(0)
            else -> 0
        }

        val container = trackProbe?.containerName ?: TrackMetadata.containerFromMime(mime)
        val playbackMime = trackProbe?.playbackMimeType ?: PlaybackMimeResolver.resolve(
            storeMime = draft.mimeType,
            probeMime = mime,
            displayName = draft.displayName,
            mediaUri = draft.mediaUri,
            containerName = container,
        )
        return TrackMetadata(
            containerName = container,
            sampleRateHz = sampleRate.coerceAtLeast(0),
            bitsPerSample = bits,
            bitrateKbps = bitrateKbps,
            channelCount = channels.coerceAtLeast(1),
            playbackMimeType = playbackMime,
        )
    }

    private fun artCacheKey(draft: TrackDraft): String = when {
        draft.albumId > 0 -> "ms_album_${draft.albumId}"
        else -> {
            val album = draft.album.trim()
            val artist = draft.artist.trim()
            if (album.isNotEmpty() && album != "未知专辑") {
                "tags_${album.lowercase()}_${artist.lowercase()}"
            } else {
                "track_${draft.mediaUri.hashCode()}"
            }
        }
    }

    private fun trackArtCacheKey(mediaUri: Uri): String =
        "embed_${mediaUri.toString().hashCode()}"

    /**
     * 封面优先级：当前文件内嵌图 → 同专辑已缓存内嵌图 → MediaStore 专辑图。
     * 每首歌都会先读自己的 embeddedPicture，避免误用其它专辑/曲目封面。
     */
    private fun resolveAlbumArt(
        context: Context,
        retriever: MediaMetadataRetriever,
        artKey: String,
        albumId: Long,
        mediaUri: Uri,
    ): String? {
        val trackKey = trackArtCacheKey(mediaUri)
        saveEmbeddedPicture(context, retriever, trackKey, mediaUri)?.let { embedded ->
            albumArtCache[artKey] = embedded
            return embedded
        }

        albumArtCache[artKey]?.let { return it }

        resolveAlbumArtFromStoreOnly(context, albumId)?.let { storeUri ->
            albumArtCache[artKey] = storeUri
            return storeUri
        }

        return null
    }

    private fun resolveAlbumArtFromStoreOnly(context: Context, albumId: Long): String? {
        if (albumId <= 0) return null
        val albumUri = ContentUris.withAppendedId(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            albumId,
        )
        return if (canOpen(context, albumUri)) albumUri.toString() else null
    }

    private fun saveEmbeddedPicture(
        context: Context,
        retriever: MediaMetadataRetriever,
        cacheKey: String,
        mediaUri: Uri,
    ): String? {
        val cacheFile = AlbumArtCache.fileForKey(context, cacheKey)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.toUri().toString()
        }
        val bytes = retriever.embeddedPicture ?: return null
        if (bytes.size < 256) return null
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(bytes)
        return cacheFile.toUri().toString()
    }

    private fun canOpen(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { }
            true
        }.getOrDefault(false)

    /** 优先内嵌图 / 封面 URI / 专辑图，采样靠下区域主色。 */
    private fun resolveCoverColor(
        context: Context,
        retriever: MediaMetadataRetriever?,
        mediaUri: Uri,
        albumId: Long,
        albumArtUri: String?,
    ): Int? {
        retriever?.embeddedPicture
            ?.let { CoverColorExtractor.fromBytes(it) }
            ?.let { return it }
        if (!albumArtUri.isNullOrBlank()) {
            CoverColorExtractor.fromUri(context, Uri.parse(albumArtUri))?.let { return it }
        }
        if (albumId > 0) {
            val albumUri = ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                albumId,
            )
            if (canOpen(context, albumUri)) {
                CoverColorExtractor.fromUri(context, albumUri)?.let { return it }
            }
        }
        return CoverColorExtractor.fromUri(context, mediaUri)
    }

    private fun TrackDraft.toSong(
        context: Context,
        metadata: TrackMetadata,
        albumArtUri: String?,
        lyrics: List<com.mica.music.data.LyricLine> = emptyList(),
    ): Song {
        val id = if (mediaStoreId > 0) "ms_$mediaStoreId" else "doc_${mediaUri.hashCode()}"
        val cachedAlac = if (metadata.containerName == "ALAC") {
            AlacPlayback.cachedFlacUri(context, id)
        } else {
            null
        }
        return Song(
            id = id,
            title = title,
            artist = ArtistNames.normalizeDisplay(artist),
            album = album,
            albumArtist = albumArtist,
            durationSec = durationSec,
            metadata = metadata,
            coverColorArgb = coverColorArgb,
            albumArtUri = albumArtUri,
            mediaUri = mediaUri,
            playbackUri = cachedAlac,
            fileName = displayName ?: title,
            sizeBytes = sizeBytes,
            year = year,
            folderPath = folderPath,
            filePath = filePath,
            copyright = copyright,
            codecLabel = codecLabel,
            dateAddedMs = dateAddedMs,
            dateModifiedMs = dateModifiedMs,
            lyrics = lyrics,
        )
    }
}
