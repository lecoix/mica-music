package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.DsdSupport
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.media.AlacPlayback
import com.mica.music.util.DiagnosticLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

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
    val externalLyricsUris: List<String> = emptyList(),
    val externalLyricsSignature: String = "",
)

internal data class TagInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val copyright: String,
    val durationSec: Int,
    val year: Int,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val frontCoverBytes: ByteArray? = null,
)

internal fun mergeTagInfo(primary: TagInfo, fallback: TagInfo): TagInfo = TagInfo(
    title = primary.title.ifBlank { fallback.title },
    artist = primary.artist.ifBlank { fallback.artist },
    album = primary.album.ifBlank { fallback.album },
    albumArtist = primary.albumArtist.ifBlank { fallback.albumArtist },
    copyright = primary.copyright.ifBlank { fallback.copyright },
    durationSec = primary.durationSec.takeIf { it > 0 } ?: fallback.durationSec,
    year = primary.year.takeIf { it > 0 } ?: fallback.year,
    trackNumber = primary.trackNumber.takeIf { it > 0 } ?: fallback.trackNumber,
    discNumber = primary.discNumber.takeIf { it > 0 } ?: fallback.discNumber,
    frontCoverBytes = primary.frontCoverBytes ?: fallback.frontCoverBytes,
)

object AudioMetadataProbe {

    private const val TAG = "AudioMetadataProbe"
    private const val FLAC_METADATA_TRACE = "ScanFlacMeta"

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
        if (draft.isDsdDraft()) {
            profiler.measureOptional("dsdMetadata") {
                DsdMetadataReader.read(appCtx, uri, draft)
            }?.let { dsd ->
                return dsd.toSong(appCtx, draft, uri, profiler, cachedSong)
            }
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
        technicalProbeFailures: AtomicInteger? = null,
    ): Song {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        if (draft.isDsdDraft()) {
            profiler.measureOptional("dsdMetadata") {
                DsdMetadataReader.read(appCtx, uri, draft)
            }?.let { dsd ->
                return dsd.toSong(appCtx, draft, uri, profiler, cachedSong)
            }
        }
        val requiresTrackProbe = draft.requiresAudioTrackProbe()
        val trackProbe = if (requiresTrackProbe) {
            profiler.measureOptional("mediaExtractor") {
                AudioTrackProbe.probe(appCtx, uri, draft.mimeType, draft.displayName)
            }
        } else {
            null
        }
        val tagLibResult = profiler.measureOptional("taglib") {
            TagLibReader.read(appCtx, uri)
        }
        if (tagLibResult != null) {
            val wavFallback = if (
                draft.isWav() &&
                (tagLibResult.hasCoreTagGaps() || tagLibResult.frontCoverBytes?.isEmpty() != false)
            ) {
                profiler.measureOptional("jaudiotagger.wav") {
                    readWavTagsViaJAudioTagger(appCtx, uri)
                }
            } else {
                null
            }
            return tagLibSong(
                appCtx,
                draft,
                uri,
                trackProbe,
                requiresTrackProbe,
                tagLibResult,
                wavFallback,
                profiler,
                cachedSong,
                technicalProbeFailures,
            )
        }
        // TagLib 整体失败时，WAV 仍先尝试 JAudioTagger，再由 Retriever/MediaStore 补空字段。
        val wavFallback = if (draft.isWav()) {
            profiler.measureOptional("jaudiotagger.wav") {
                readWavTagsViaJAudioTagger(appCtx, uri)
            }
        } else {
            null
        }
        logFlacMetadataTrace(
            draft = draft,
            path = "taglib-miss",
            requiresTrackProbe = requiresTrackProbe,
            trackProbe = trackProbe,
            sourceMime = draft.mimeType,
            detectedContainer = trackProbe?.containerName
                ?: TrackMetadata.containerFromMime(draft.mimeType, draft.displayName),
            finalContainer = null,
            bits = null,
            sampleRateHz = null,
            tagLibOk = false,
        )
        val retriever = MediaMetadataRetriever()
        return try {
            profiler.measureOptional("retriever.setDataSource") {
                setRetrieverDataSource(retriever, appCtx, uri)
            }
            val retrieverTags = profiler.measureOptional("retriever.tags") {
                readTags(retriever, draft)
            }
            val tags = wavFallback?.let { mergeTagInfo(it, retrieverTags) } ?: retrieverTags
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
            val wavCoverBytes = wavFallback?.frontCoverBytes?.takeIf { it.isNotEmpty() }
            val albumArtUri = profiler.measureOptional("albumArt") {
                if (wavCoverBytes != null) {
                    resolveAlbumArtFromBytes(appCtx, wavCoverBytes, artKey, withMeta.albumId, uri)
                } else {
                    resolveAlbumArt(appCtx, retriever, artKey, withMeta.albumId, uri)
                }
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
                wavCoverBytes?.let { CoverColorExtractor.fromBytes(it) }
                    ?: resolveCoverColor(appCtx, retriever, uri, withMeta.albumId, albumArtUri)
            }
                ?: withMeta.coverColorArgb
            withMeta.copy(coverColorArgb = coverArgb).toSong(
                appCtx,
                metadata,
                albumArtUri,
                lyrics,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
            )
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

    /** TagLib 成功读取后的组装路径，与 retriever 路径字段语义保持一致。 */
    private fun tagLibSong(
        context: Context,
        draft: TrackDraft,
        uri: Uri,
        trackProbe: AudioTrackProbe.Result?,
        requiresTrackProbe: Boolean,
        tagLib: TagLibReader.Result,
        wavFallback: TagInfo?,
        profiler: ScanProfiler?,
        cachedSong: Song?,
        technicalProbeFailures: AtomicInteger? = null,
    ): Song {
        val primaryTags = TagInfo(
            title = tagLib.title,
            artist = tagLib.artist,
            album = tagLib.album,
            albumArtist = tagLib.albumArtist,
            copyright = tagLib.copyright,
            durationSec = tagLib.durationSec,
            year = tagLib.year,
            trackNumber = tagLib.trackNumber,
            discNumber = tagLib.discNumber,
            frontCoverBytes = tagLib.frontCoverBytes,
        )
        // 必须在 Retriever/MediaStore 写入默认值之前合并，否则 WAV 兜底永远不会触发。
        val tagsWithWavFallback = wavFallback?.let { mergeTagInfo(primaryTags, it) } ?: primaryTags
        val needsRetrieverSupplement = listOf(
            tagsWithWavFallback.title,
            tagsWithWavFallback.artist,
            tagsWithWavFallback.album,
            tagsWithWavFallback.albumArtist,
        ).any { it.isBlank() } || tagLib.year <= 0 ||
            tagLib.frontCoverBytes == null || tagLib.frontCoverBytes.isEmpty()
        val retriever = if (needsRetrieverSupplement) {
            MediaMetadataRetriever().let { candidate ->
                if (runCatching { setRetrieverDataSource(candidate, context, uri) }.isSuccess) {
                    candidate
                } else {
                    runCatching { candidate.release() }
                    null
                }
            }
        } else {
            null
        }
        val tags = retriever?.let { mergeTagInfo(tagsWithWavFallback, readTags(it, draft)) }
            ?: tagsWithWavFallback

        val coverBytes = tagsWithWavFallback.frontCoverBytes?.takeIf { it.isNotEmpty() }
            ?: retriever?.embeddedPicture?.takeIf { it.isNotEmpty() }
        try {
        val title = MetadataTextFix.titleFromTagsOrFilename(
            tagTitle = tags.title,
            displayName = draft.displayName,
            fallbackTitle = draft.title,
        )
        val albumArtist = MetadataTextFix.normalize(tags.albumArtist)
        val artist = ArtistNames.normalizeDisplay(
            MetadataTextFix.normalize(
                tags.artist.takeIf { it.isNotBlank() }
                    ?: albumArtist.takeIf { it.isNotBlank() }
                    ?: draft.artist,
            ),
        )
        val album = MetadataTextFix.normalize(tags.album.takeIf { it.isNotBlank() } ?: draft.album)
        val durationSec = when {
            tags.durationSec > 0 -> tags.durationSec
            draft.durationSec > 0 -> draft.durationSec
            else -> 0
        }
        val year = tags.year.takeIf { it > 0 } ?: draft.year

        val detectedContainer = trackProbe?.containerName
            ?: TrackMetadata.containerFromMime(draft.mimeType, draft.displayName)
        val technicalResult = profiler.measureOptional("technical") {
            AudioTechnicalProbe.probe(
                context = context,
                uri = uri,
                detectedContainer = detectedContainer,
                mimeType = draft.mimeType,
                displayName = draft.displayName,
            )
        }
        if (technicalResult is ProbeResult.Failed) {
            technicalProbeFailures?.incrementAndGet()
        }
        val technical = technicalResult.technicalValue()
        val container = technical.containerName ?: detectedContainer
        val bits = technical.bitsPerSample
        val durationForBitrate = durationSec.coerceAtLeast(1)
        val bitrateKbps = when {
            tagLib.bitrateKbps > 0 -> tagLib.bitrateKbps
            draft.bitrateBpsFromStore > 0 -> draft.bitrateBpsFromStore / 1000
            draft.sizeBytes > 0 ->
                ((draft.sizeBytes * 8L) / durationForBitrate / 1000L).toInt().coerceAtLeast(0)
            else -> 0
        }
        val playbackMime = trackProbe?.playbackMimeType ?: PlaybackMimeResolver.resolve(
            storeMime = draft.mimeType,
            probeMime = null,
            displayName = draft.displayName,
            mediaUri = draft.mediaUri,
            containerName = container,
        )
        val metadata = TrackMetadata(
            containerName = container,
            sampleRateHz = tagLib.sampleRateHz.coerceAtLeast(0),
            bitsPerSample = bits,
            bitrateKbps = bitrateKbps,
            channelCount = tagLib.channelCount.coerceAtLeast(1),
            playbackMimeType = playbackMime,
        )
        logFlacMetadataTrace(
            draft = draft,
            path = "taglib",
            requiresTrackProbe = requiresTrackProbe,
            trackProbe = trackProbe,
            sourceMime = draft.mimeType,
            detectedContainer = detectedContainer,
            finalContainer = container,
            bits = bits,
            sampleRateHz = metadata.sampleRateHz,
            tagLibOk = true,
        )
        val copyright = MetadataTextFix.normalize(tags.copyright).ifBlank {
            profiler.measureOptional("copyright") {
                readCopyright(
                    context,
                    uri,
                    draft.copy(mimeType = playbackMime.ifBlank { draft.mimeType }),
                )
            }
        }
        val withMeta = draft.copy(
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            year = year,
            albumArtist = albumArtist,
            copyright = copyright,
            codecLabel = trackProbe?.trackMime ?: playbackMime,
        )
        val artKey = artCacheKey(withMeta)
        val albumArtUri = profiler.measureOptional("albumArt") {
            resolveAlbumArtFromBytes(context, coverBytes, artKey, withMeta.albumId, uri)
        }
        val lyrics = profiler.measureOptional("lyrics") {
            readScanLyrics(
                context,
                withMeta.copy(mimeType = playbackMime.ifBlank { withMeta.mimeType }),
                cachedSong,
                retriever = retriever,
                taglibLyricsCandidates = tagLib.lyricsCandidates,
            )
        }
        val coverArgb = profiler.measureOptional("coverColor") {
            coverBytes?.let { CoverColorExtractor.fromBytes(it) }
                ?: resolveCoverColor(context, null, uri, withMeta.albumId, albumArtUri)
        } ?: withMeta.coverColorArgb
        return withMeta.copy(coverColorArgb = coverArgb).toSong(
            context = context,
            metadata = metadata,
            albumArtUri = albumArtUri,
            lyrics = lyrics,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
        ).copy(replayGain = tagLib.replayGain)
        } finally {
            retriever?.let { runCatching { it.release() } }
        }
    }

    private fun readScanLyrics(
        context: Context,
        draft: TrackDraft,
        cachedSong: Song?,
        retriever: MediaMetadataRetriever? = null,
        taglibLyricsCandidates: List<String> = emptyList(),
    ): List<com.mica.music.data.LyricLine> {
        ExternalLyricsReader.readDirectUris(context, draft.externalLyricsUris)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        cachedSong?.lyrics?.takeIf { it.isNotEmpty() }?.let { return it }
        taglibLyricsCandidates
            .mapNotNull { parseLyricsTextForScan(MetadataTextFix.normalize(it)) }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { LyricsSanitizer.pickBest(it) }
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
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: throw IllegalStateException("无法打开音频文件：$uri")
            } catch (_: Exception) {
                val tmp = java.io.File.createTempFile("wav_", ".wav", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    retriever.setDataSource(tmp.absolutePath)
                } finally {
                    runCatching { tmp.delete() }
                }
            }
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
        val trackNumber = MetadataTextFix.parseTrackNumber(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?: extractMetadataString(retriever, "tracknumber"),
        )
        val discNumber = MetadataTextFix.parseDiscNumber(extractMetadataString(retriever, "discnumber"))
        return TagInfo(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            copyright = copyright,
            durationSec = durationSec,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
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

        val container = trackProbe?.containerName
            ?: TrackMetadata.containerFromMime(mime, draft.displayName)
        val playbackMime = trackProbe?.playbackMimeType ?: PlaybackMimeResolver.resolve(
            storeMime = draft.mimeType,
            probeMime = mime,
            displayName = draft.displayName,
            mediaUri = draft.mediaUri,
            containerName = container,
        )
        val metadata = TrackMetadata(
            containerName = container,
            sampleRateHz = sampleRate.coerceAtLeast(0),
            bitsPerSample = bits,
            bitrateKbps = bitrateKbps,
            channelCount = channels.coerceAtLeast(1),
            playbackMimeType = playbackMime,
        )
        logFlacMetadataTrace(
            draft = draft,
            path = "retriever",
            requiresTrackProbe = draft.requiresAudioTrackProbe(),
            trackProbe = trackProbe,
            sourceMime = mime,
            detectedContainer = container,
            finalContainer = container,
            bits = bits,
            sampleRateHz = metadata.sampleRateHz,
            tagLibOk = false,
        )
        return metadata
    }

    private fun logFlacMetadataTrace(
        draft: TrackDraft,
        path: String,
        requiresTrackProbe: Boolean,
        trackProbe: AudioTrackProbe.Result?,
        sourceMime: String?,
        detectedContainer: String?,
        finalContainer: String?,
        bits: Int?,
        sampleRateHz: Int?,
        tagLibOk: Boolean,
    ) {
        if (!shouldTraceFlacMetadata(draft, trackProbe, sourceMime, detectedContainer, finalContainer)) return
        val ext = draft.displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val reason = when {
            bits != null -> "bits-ok"
            !tagLibOk && path == "taglib-miss" -> "taglib-miss"
            path == "retriever" -> "retriever-bits-null"
            detectedContainer == "FLAC" || finalContainer == "FLAC" || ext == "flac" -> "flac-bits-null"
            else -> "not-flac-detected"
        }
        DiagnosticLog.event(
            FLAC_METADATA_TRACE,
            "path=$path reason=$reason name=${draft.displayName.orEmpty()} ext=$ext " +
                "draftMime=${draft.mimeType.ifBlank { "<blank>" }} " +
                "sourceMime=${sourceMime.orEmpty().ifBlank { "<blank>" }} " +
                "requiresExtractor=$requiresTrackProbe extractorRan=${trackProbe != null} " +
                "trackMime=${trackProbe?.trackMime.orEmpty().ifBlank { "<none>" }} " +
                "trackContainer=${trackProbe?.containerName.orEmpty().ifBlank { "<none>" }} " +
                "detected=$detectedContainer final=$finalContainer bits=${bits ?: -1} " +
                "sampleRate=${sampleRateHz ?: -1} taglibOk=$tagLibOk",
        )
    }

    private fun shouldTraceFlacMetadata(
        draft: TrackDraft,
        trackProbe: AudioTrackProbe.Result?,
        sourceMime: String?,
        detectedContainer: String?,
        finalContainer: String?,
    ): Boolean {
        val ext = draft.displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext == "flac" ||
            draft.mimeType.contains("flac", ignoreCase = true) ||
            sourceMime?.contains("flac", ignoreCase = true) == true ||
            trackProbe?.trackMime?.contains("flac", ignoreCase = true) == true ||
            trackProbe?.containerName == "FLAC" ||
            detectedContainer == "FLAC" ||
            finalContainer == "FLAC"
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
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
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
        saveEmbeddedPicture(context, retriever, trackKey)?.let { embedded ->
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

    /** TagLib 路径的封面解析：内嵌图字节 → 同专辑缓存 → MediaStore 专辑图。 */
    private fun resolveAlbumArtFromBytes(
        context: Context,
        coverBytes: ByteArray?,
        artKey: String,
        albumId: Long,
        mediaUri: Uri,
    ): String? {
        saveEmbeddedPictureBytes(context, coverBytes, trackArtCacheKey(mediaUri))?.let { embedded ->
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
    ): String? {
        val bytes = retriever.embeddedPicture ?: return null
        return saveEmbeddedPictureBytes(context, bytes, cacheKey)
    }

    private fun saveEmbeddedPictureBytes(
        context: Context,
        bytes: ByteArray?,
        cacheKey: String,
    ): String? {
        if (bytes == null || bytes.size < 256) return null
        val cacheFile = AlbumArtCache.fileForKey(context, embeddedArtCacheKey(cacheKey, bytes))
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.toUri().toString()
        }
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(bytes)
        return cacheFile.toUri().toString()
    }

    private fun embeddedArtCacheKey(cacheKey: String, bytes: ByteArray): String =
        "$cacheKey:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

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
        trackNumber: Int = 0,
        discNumber: Int = 0,
    ): Song {
        val id = if (mediaStoreId > 0) "ms_$mediaStoreId" else "doc_${mediaUri.hashCode()}"
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
            playbackUri = null,
            fileName = displayName ?: title,
            sizeBytes = sizeBytes,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            folderPath = folderPath,
            filePath = filePath,
            copyright = copyrightOverride.ifBlank { copyright },
            codecLabel = codecLabel,
            dateAddedMs = dateAddedMs,
            dateModifiedMs = dateModifiedMs,
            externalLyricsSignature = externalLyricsSignature,
            lyrics = lyrics,
        )
    }
    /**
     * JAudioTagger 兜底读取 WAV ID3v2 标签。
     * TagLib 对 WAV 的 ID3v2 帧（TIT2, TPE1 等）映射不全，
     * JAudioTagger 兜底读取 WAV ID3v2 标签。
     * TagLib 对 WAV 的 ID3v2 帧（TIT2, TPE1 等）映射不全，
     * 而 MediaMetadataRetriever 在部分设备上也读不到，
     * 因此当前两步字段为空时用 JAudioTagger 补位。
     *
     * 参考 PixelPlayer 做法：先拷临时文件再读取，用完后清理。
     */
    private fun readWavTagsViaJAudioTagger(context: Context, uri: Uri): TagInfo? {
        val tmp = File.createTempFile("wav_", ".wav", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            // 抑制 JAudioTagger 的冗余日志
            java.util.logging.Logger.getLogger("org.jaudiotagger").level =
                java.util.logging.Level.OFF

            val audioFile = AudioFileIO.read(tmp)
            val tag = audioFile.tag ?: return null

            val title = tag.getFirst(FieldKey.TITLE)?.trim().orEmpty()
            val artist = tag.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
            val album = tag.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
            val albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST)?.trim().orEmpty()
            val copyright = tag.getFirst(FieldKey.COPYRIGHT)?.trim().orEmpty()
            val frontCoverBytes = tag.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }
            val year = Regex("""\d{4}""")
                .find(tag.getFirst(FieldKey.YEAR).orEmpty())
                ?.value
                ?.toIntOrNull()
                ?: 0
            val trackNumber = MetadataTextFix.parseTrackNumber(tag.getFirst(FieldKey.TRACK))
            val discNumber = MetadataTextFix.parseDiscNumber(tag.getFirst(FieldKey.DISC_NO))
            if (title.isBlank() && artist.isBlank() && album.isBlank() && albumArtist.isBlank()) {
                return null
            }
            TagInfo(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                copyright = copyright,
                durationSec = audioFile.audioHeader?.trackLength?.coerceAtLeast(0) ?: 0,
                year = year,
                trackNumber = trackNumber,
                discNumber = discNumber,
                frontCoverBytes = frontCoverBytes,
            )
        } catch (error: Exception) {
            Log.w(TAG, "JAudioTagger failed to read WAV metadata: $uri", error)
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun TrackDraft.isWav(): Boolean {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in setOf("wav", "wave") || mimeType.equals("audio/wav", true) ||
            mimeType.equals("audio/x-wav", true)
    }

    private fun TagLibReader.Result.hasCoreTagGaps(): Boolean =
        title.isBlank() || artist.isBlank() || album.isBlank() || albumArtist.isBlank()
}
