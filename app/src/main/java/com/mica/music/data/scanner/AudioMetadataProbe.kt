package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.DsdSupport
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
    val externalLyricsParent: DocumentFile? = null,
    val externalLyricsUri: String? = null,
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
    private val mp4CopyrightMarkers = listOf(
        "cprt".toByteArray(Charsets.US_ASCII),
    )
    private val retrieverLyricsKeys = listOf(
        "lyrics",
        "LYRICS",
        "unsyncedlyrics",
        "UNSYNCEDLYRICS",
        "UNSYNCED LYRICS",
        "description",
    )

    fun clearArtCache() {
        albumArtCache.clear()
    }

    internal fun quickSong(
        context: Context,
        draft: TrackDraft,
        profiler: ScanProfiler? = null,
        cachedSong: Song? = null,
    ): Song {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        profiler.measureOptional("dsdMetadata") {
            DsdMetadataReader.read(appCtx, uri, draft)
        }?.let { dsd ->
            return dsd.toSong(appCtx, draft, uri, profiler, cachedSong)
        }
        val metadata = TrackMetadata.fallback(
            mimeType = draft.mimeType,
            bitrateBpsFromStore = draft.bitrateBpsFromStore,
            displayName = draft.displayName,
            mediaUri = draft.mediaUri,
        )
        val lyricDraft = draft.copy(mimeType = metadata.playbackMimeType.ifBlank { draft.mimeType })
        val lyrics = profiler.measureOptional("lyrics") {
            readScanLyrics(
                appCtx,
                lyricDraft,
                cachedSong,
            )
        }
        val copyright = profiler.measureOptional("copyright") {
            readCopyright(appCtx, uri, lyricDraft)
        }
        val albumArtUri = profiler.measureOptional("albumArt") {
            resolveAlbumArtFromStoreOnly(context, draft.albumId)
        }
        val coverArgb = profiler.measureOptional("coverColor") {
            resolveCoverColor(appCtx, null, uri, draft.albumId, albumArtUri)
        }
            ?: draft.coverColorArgb
        return draft.copy(coverColorArgb = coverArgb).toSong(
            appCtx,
            metadata,
            albumArtUri = albumArtUri,
            lyrics = lyrics,
            copyrightOverride = copyright,
        )
    }

    internal fun probeTrack(
        context: Context,
        draft: TrackDraft,
        profiler: ScanProfiler? = null,
        cachedSong: Song? = null,
    ): Song {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        profiler.measureOptional("dsdMetadata") {
            DsdMetadataReader.read(appCtx, uri, draft)
        }?.let { dsd ->
            return dsd.toSong(appCtx, draft, uri, profiler, cachedSong)
        }
        val trackProbe = profiler.measureOptional("mediaExtractor") {
            AudioTrackProbe.probe(appCtx, uri, draft.mimeType, draft.displayName)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            profiler.measureOptional("retriever.setDataSource") {
                setRetrieverDataSource(retriever, appCtx, uri)
            }
            val tags = profiler.measureOptional("retriever.tags") {
                readTags(retriever, draft)
            }
            val enriched = draft.copy(
                title = tags.title,
                artist = tags.artist,
                album = tags.album,
                durationSec = tags.durationSec,
                year = tags.year,
            )
            val metadata = profiler.measureOptional("retriever.metadata") {
                readMetadata(retriever, enriched, trackProbe, tags.durationSec)
            }
            val copyright = tags.copyright.ifBlank {
                profiler.measureOptional("copyright") {
                    readCopyright(
                        appCtx,
                        uri,
                        enriched.copy(mimeType = metadata.playbackMimeType.ifBlank { enriched.mimeType }),
                    )
                }
            }
            val withMeta = enriched.copy(
                albumArtist = tags.albumArtist,
                copyright = copyright,
                codecLabel = trackProbe?.trackMime ?: metadata.playbackMimeType,
            )
            val artKey = artCacheKey(withMeta)
            val albumArtUri = profiler.measureOptional("albumArt") {
                resolveAlbumArt(appCtx, retriever, artKey, withMeta.albumId, uri)
            }
            val lyrics = profiler.measureOptional("lyrics") {
                readScanLyrics(
                    appCtx,
                    withMeta.copy(mimeType = metadata.playbackMimeType.ifBlank { withMeta.mimeType }),
                    cachedSong,
                    retriever,
                )
            }
            val coverArgb = profiler.measureOptional("coverColor") {
                resolveCoverColor(appCtx, retriever, uri, withMeta.albumId, albumArtUri)
            }
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
            val lyricDraft = draft.copy(mimeType = metadata.playbackMimeType.ifBlank { draft.mimeType })
            val lyrics = profiler.measureOptional("lyrics") {
                readScanLyrics(
                    appCtx,
                    lyricDraft,
                    cachedSong,
                )
            }
            val copyright = profiler.measureOptional("copyright") {
                readCopyright(appCtx, uri, lyricDraft)
            }
            draft.toSong(
                appCtx,
                metadata,
                albumArtUri = profiler.measureOptional("albumArt") {
                    resolveAlbumArtFromStoreOnly(appCtx, draft.albumId)
                },
                lyrics = lyrics,
                copyrightOverride = copyright,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun <T> ScanProfiler?.measureOptional(stage: String, block: () -> T): T =
        this?.measure(stage, block) ?: block()

    private fun readScanLyrics(
        context: Context,
        draft: TrackDraft,
        cachedSong: Song?,
        retriever: MediaMetadataRetriever? = null,
    ): List<com.mica.music.data.LyricLine> {
        cachedSong?.lyrics?.takeIf { it.isNotEmpty() }?.let { return it }
        ExternalLyricsReader.readDirectUri(context, draft.externalLyricsUri)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        retriever?.let { readRetrieverLyrics(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val embedded = EmbeddedLyricsReader.readFastEmbeddedOnly(
            context = context,
            uri = Uri.parse(draft.mediaUri),
            mimeType = draft.mimeType,
            displayName = draft.displayName,
        )
        return embedded
    }

    private fun readRetrieverLyrics(retriever: MediaMetadataRetriever): List<com.mica.music.data.LyricLine>? {
        val candidates = mutableListOf<List<com.mica.music.data.LyricLine>>()
        for (key in retrieverLyricsKeys) {
            extractMetadataString(retriever, key)
                ?.let { MetadataTextFix.normalize(it) }
                ?.let { parseLyricsTextForScan(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { candidates += it }
        }
        return LyricsSanitizer.pickBest(candidates)
    }

    private fun readCopyright(context: Context, uri: Uri, draft: TrackDraft): String {
        if (!draft.mayContainMp4EmbeddedLyrics()) return ""
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = draft.mimeType,
            displayName = draft.displayName,
        ) ?: return ""
        return Mp4AtomTextReader.read(bytes, mp4CopyrightMarkers)
            ?.let { MetadataTextFix.normalize(it) }
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseLyricsTextForScan(raw: String): List<com.mica.music.data.LyricLine>? {
        if (raw.isBlank()) return null
        LyricsSanitizer.parseFiltered(raw).takeIf { it.isNotEmpty() }?.let { return it }
        LyricsSanitizer.finalize(LrcParser.parse(raw)).takeIf { it.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalizeRelaxed(raw)
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

    private fun DsdMetadataReader.Result.toSong(
        context: Context,
        draft: TrackDraft,
        uri: Uri,
        profiler: ScanProfiler?,
        cachedSong: Song?,
    ): Song {
        val title = tags.title.ifBlank { draft.title }
        val artist = ArtistNames.normalizeDisplay(tags.artist.ifBlank { draft.artist })
        val album = tags.album.ifBlank { draft.album }
        val albumArtist = tags.albumArtist
        val enriched = draft.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            copyright = tags.copyright,
            durationSec = durationSec.takeIf { it > 0 } ?: draft.durationSec,
            year = tags.year.takeIf { it > 0 } ?: draft.year,
            codecLabel = DsdSupport.rateLabel(metadata.sampleRateHz) ?: "DSD",
            mimeType = metadata.playbackMimeType,
        )
        val lyrics = profiler.measureOptional("lyrics") {
            readScanLyrics(context, enriched, cachedSong)
        }
        val artKey = artCacheKey(enriched)
        val albumArtUri = profiler.measureOptional("albumArt") {
            saveEmbeddedPictureBytes(context, albumArtBytes, artKey)
        }
        val coverArgb = profiler.measureOptional("coverColor") {
            albumArtBytes
                ?.let { CoverColorExtractor.fromBytes(it) }
                ?: resolveCoverColor(context, null, uri, enriched.albumId, albumArtUri)
        } ?: enriched.coverColorArgb
        return enriched.copy(coverColorArgb = coverArgb).toSong(
            context = context,
            metadata = metadata,
            albumArtUri = albumArtUri,
            lyrics = lyrics,
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

    private fun saveEmbeddedPictureBytes(
        context: Context,
        bytes: ByteArray?,
        cacheKey: String,
    ): String? {
        if (bytes == null || bytes.size < 256) return null
        val cacheFile = AlbumArtCache.fileForKey(context, cacheKey)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.toUri().toString()
        }
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
        copyrightOverride: String = "",
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
            copyright = copyrightOverride.ifBlank { copyright },
            codecLabel = codecLabel,
            dateAddedMs = dateAddedMs,
            dateModifiedMs = dateModifiedMs,
            lyrics = lyrics,
        )
    }
}
