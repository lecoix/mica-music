package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.DsdSupport
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.ReleaseDates
import com.mica.music.data.Song
import com.mica.music.data.SongIdentity
import com.mica.music.data.TrackMetadata
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsSlots
import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.local.SongEntity
import com.mica.music.util.DiagnosticLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
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
    val releaseDate: String = "",
    val folderPath: String = "",
    val filePath: String = "",
    val albumArtist: String = "",
    val copyright: String = "",
    val comment: String = "",
    val codecLabel: String = "",
    val dateAddedMs: Long = 0L,
    val dateModifiedMs: Long = 0L,
    val externalLyricsParent: DocumentFile? = null,
    val externalLyricsUris: List<String> = emptyList(),
    val externalLrcUris: List<String> = emptyList(),
    val externalTtmlUris: List<String> = emptyList(),
    val externalLyricsSignature: String = "",
)

/** Scanner-only result; [lyrics] never enters the runtime [Song] model. */
internal data class ScannedSong(
    val song: Song,
    val lyrics: LyricsProbeResult = LyricsProbeResult.NotProbed,
)

internal enum class AudioMetadataProbeArtifactPolicy {
    FULL_SCAN,
    READ_ONLY_CANONICAL,
}

internal data class TagInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val copyright: String,
    val comment: String,
    val durationSec: Int,
    val year: Int,
    val releaseDate: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val frontCoverBytes: ByteArray? = null,
    val lyricsCandidates: List<EmbeddedLyricsTextCandidate> = emptyList(),
)

/** Null means no authoritative album read; empty means the file has no album tag. */
internal fun resolveScannedAlbum(fileAlbum: String?, draftAlbum: String): String =
    MetadataTextFix.normalize(fileAlbum ?: draftAlbum).ifBlank { "未知专辑" }

internal fun mergeTagInfo(primary: TagInfo, fallback: TagInfo): TagInfo {
    val releaseDate = primary.releaseDate.ifBlank { fallback.releaseDate }
    return TagInfo(
        title = primary.title.ifBlank { fallback.title },
        artist = primary.artist.ifBlank { fallback.artist },
        album = primary.album.ifBlank { fallback.album },
        albumArtist = primary.albumArtist.ifBlank { fallback.albumArtist },
        copyright = primary.copyright.ifBlank { fallback.copyright },
        comment = primary.comment.ifBlank { fallback.comment },
        durationSec = primary.durationSec.takeIf { it > 0 } ?: fallback.durationSec,
        year = ReleaseDates.yearFromFullDate(releaseDate).takeIf { it > 0 }
            ?: primary.year.takeIf { it > 0 }
            ?: fallback.year,
        releaseDate = releaseDate,
        trackNumber = primary.trackNumber.takeIf { it > 0 } ?: fallback.trackNumber,
        discNumber = primary.discNumber.takeIf { it > 0 } ?: fallback.discNumber,
        frontCoverBytes = primary.frontCoverBytes ?: fallback.frontCoverBytes,
        lyricsCandidates = primary.lyricsCandidates + fallback.lyricsCandidates,
    )
}

object AudioMetadataProbe {

    internal fun hydrateArtwork(context: Context, song: Song): Song {
        val bytes = readEmbeddedArtworkBytes(context, song) ?: return song
        val albumArtUri = runCatching {
            AlbumArtCache.storeManagedArtworkForScan(context.applicationContext, song.id, bytes)
        }.getOrNull() ?: return song
        val coverColorArgb = CoverColorExtractor.fromBytes(bytes) ?: song.coverColorArgb
        return song.copy(
            albumArtUri = albumArtUri,
            coverColorArgb = coverColorArgb,
        )
    }

    internal fun readEmbeddedArtworkBytes(context: Context, song: Song): ByteArray? =
        readEmbeddedArtworkBytes(
            context = context,
            draft = TrackDraft(
                mediaStoreId = song.id.removePrefix("ms_").toLongOrNull() ?: 0L,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = 0L,
                durationSec = song.durationSec,
                mimeType = song.metadata.playbackMimeType,
                displayName = song.fileName,
                sizeBytes = song.sizeBytes,
                bitrateBpsFromStore = song.metadata.bitrateKbps.coerceAtLeast(0) * 1000,
                mediaUri = song.mediaUri,
                coverColorArgb = song.coverColorArgb,
                year = song.year,
                releaseDate = song.releaseDate,
                folderPath = song.folderPath,
                filePath = song.filePath,
                albumArtist = song.albumArtist,
                copyright = song.copyright,
                comment = song.comment,
                codecLabel = song.codecLabel,
                dateAddedMs = song.dateAddedMs,
                dateModifiedMs = song.dateModifiedMs,
                externalLyricsSignature = song.externalLyricsSignature,
            ),
        )

    internal fun readEmbeddedArtworkBytes(context: Context, song: SongEntity): ByteArray? {
        val draft = TrackDraft(
            mediaStoreId = song.id.removePrefix("ms_").toLongOrNull() ?: 0L,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = 0L,
            durationSec = song.durationSec,
            mimeType = song.playbackMimeType,
            displayName = song.fileName,
            sizeBytes = song.sizeBytes,
            bitrateBpsFromStore = song.bitrateKbps.coerceAtLeast(0) * 1000,
            mediaUri = song.mediaUri,
            coverColorArgb = song.coverColorArgb,
            year = song.year,
            releaseDate = song.releaseDate,
            folderPath = song.folderPath,
            filePath = song.filePath,
            albumArtist = song.albumArtist,
            copyright = song.copyright,
            codecLabel = song.codecLabel,
            dateAddedMs = song.dateAddedMs,
            dateModifiedMs = song.dateModifiedMs,
        )
        return readEmbeddedArtworkBytes(context, draft)
    }

    private fun readEmbeddedArtworkBytes(context: Context, draft: TrackDraft): ByteArray? {
        val appContext = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        if (draft.isDsdDraft()) {
            DsdMetadataReader.read(appContext, uri, draft)?.albumArtBytes
                ?.takeIf { it.size >= 256 }
                ?.let { return it }
        }
        TagLibReader.read(appContext, uri)?.frontCoverBytes
            ?.takeIf { it.size >= 256 }
            ?.let { return it }
        if (draft.isWav()) {
            readWavTagsViaJAudioTagger(appContext, uri)?.frontCoverBytes
                ?.takeIf { it.size >= 256 }
                ?.let { return it }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, appContext, uri)
            retriever.embeddedPicture?.takeIf { it.size >= 256 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val TAG = "AudioMetadataProbe"
    private const val FLAC_METADATA_TRACE = "ScanFlacMeta"
    internal const val CURRENT_METADATA_SCAN_VERSION = 3

    private val albumArtCache = ConcurrentHashMap<String, String?>()
    private val mp4CopyrightMarkers = listOf(
        "cprt".toByteArray(Charsets.US_ASCII),
    )
    private val mp4CommentMarkers = listOf(
        byteArrayOf(0xA9.toByte(), 'c'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte()),
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
        artifactPolicy: AudioMetadataProbeArtifactPolicy =
            AudioMetadataProbeArtifactPolicy.FULL_SCAN,
    ): ScannedSong {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        if (draft.isDsdDraft()) {
            profiler.measureOptional("dsdMetadata") {
                DsdMetadataReader.read(appCtx, uri, draft)
            }?.let { dsd ->
                return dsd.toScannedSong(
                    appCtx,
                    draft,
                    uri,
                    profiler,
                    cachedSong,
                    artifactPolicy,
                )
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
                profiler = profiler,
            )
        }
        val copyright = profiler.measureOptional("copyright") {
            readCopyright(appCtx, uri, lyricDraft)
        }
        val comment = profiler.measureOptional("comment") {
            readComment(appCtx, uri, lyricDraft)
        }
        val albumArtUri = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                profiler.measureOptional("albumArt") {
                    resolveAlbumArtFromStoreOnly(
                        context = context,
                        albumId = draft.albumId,
                        allowStoreFallback = isSafeForAlbumArtSharing(draft),
                    )
                }
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL -> null
        }
        val coverArgb = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                profiler.measureOptional("coverColor") {
                    resolveCoverColor(appCtx, null, uri, draft.albumId, albumArtUri)
                } ?: draft.coverColorArgb
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL ->
                cachedSong?.coverColorArgb ?: draft.coverColorArgb
        }
        return ScannedSong(
            song = draft.copy(coverColorArgb = coverArgb).toSong(
                appCtx,
                metadata,
                albumArtUri = albumArtUri,
                lyricsDocument = lyrics.selectedOrCached(cachedSong),
                copyrightOverride = copyright,
                commentOverride = comment,
                metadataScanVersion = 0,
                dateAddedMsOverride = draft.dateAddedMsFor(cachedSong),
            ).preserveLoudnessFrom(cachedSong),
            lyrics = lyrics,
        )
    }

    internal fun probeTrack(
        context: Context,
        draft: TrackDraft,
        profiler: ScanProfiler? = null,
        cachedSong: Song? = null,
        technicalProbeFailures: AtomicInteger? = null,
        artifactPolicy: AudioMetadataProbeArtifactPolicy =
            AudioMetadataProbeArtifactPolicy.FULL_SCAN,
    ): ScannedSong {
        val appCtx = context.applicationContext
        val uri = Uri.parse(draft.mediaUri)
        if (draft.isDsdDraft()) {
            profiler.measureOptional("dsdMetadata") {
                DsdMetadataReader.read(appCtx, uri, draft)
            }?.let { dsd ->
                return dsd.toScannedSong(
                    appCtx,
                    draft,
                    uri,
                    profiler,
                    cachedSong,
                    artifactPolicy,
                )
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
            TagLibReader.read(
                context = appCtx,
                uri = uri,
            )
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
                artifactPolicy,
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
        var resolvedAlbum = wavFallback?.let { resolveScannedAlbum(it.album, draft.album) }
        return try {
            profiler.measureOptional("retriever.setDataSource") {
                setRetrieverDataSource(retriever, appCtx, uri)
            }
            val retrieverTags = profiler.measureOptional("retriever.tags") {
                readTags(retriever, draft)
            }
            val tags = wavFallback?.let { mergeTagInfo(it, retrieverTags) } ?: retrieverTags
            val album = resolveScannedAlbum(tags.album, draft.album)
            resolvedAlbum = album
            val enriched = draft.copy(
                title = tags.title,
                artist = tags.artist,
                album = album,
                durationSec = tags.durationSec,
                year = tags.year,
                releaseDate = tags.releaseDate,
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
            val comment = tags.comment.ifBlank {
                profiler.measureOptional("comment") {
                    readComment(
                        appCtx,
                        uri,
                        enriched.copy(mimeType = metadata.playbackMimeType.ifBlank { enriched.mimeType }),
                    )
                }
            }
            val withMeta = enriched.copy(
                albumArtist = tags.albumArtist,
                copyright = copyright,
                comment = comment,
                codecLabel = trackProbe?.trackMime ?: metadata.playbackMimeType,
            )
            val wavCoverBytes = wavFallback?.frontCoverBytes?.takeIf { it.isNotEmpty() }
            val albumArtUri = when (artifactPolicy) {
                AudioMetadataProbeArtifactPolicy.FULL_SCAN -> {
                    val artKey = artCacheKey(withMeta)
                    profiler.measureOptional("albumArt") {
                        if (wavCoverBytes != null) {
                            resolveAlbumArtFromBytes(
                                appCtx,
                                wavCoverBytes,
                                artKey,
                                withMeta.albumId,
                                withMeta.scanSongId(),
                            )
                        } else {
                            resolveAlbumArt(
                                appCtx,
                                retriever,
                                artKey,
                                withMeta.albumId,
                                withMeta.scanSongId(),
                            )
                        }
                    }
                }
                AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL -> null
            }
            val lyrics = profiler.measureOptional("lyrics") {
                readScanLyrics(
                    appCtx,
                    withMeta.copy(mimeType = metadata.playbackMimeType.ifBlank { withMeta.mimeType }),
                    cachedSong,
                    retriever,
                    taglibLyricsCandidates = wavFallback?.lyricsCandidates.orEmpty(),
                    profiler = profiler,
                )
            }
            val coverArgb = when (artifactPolicy) {
                AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                    profiler.measureOptional("coverColor") {
                        wavCoverBytes?.let { CoverColorExtractor.fromBytes(it) }
                            ?: resolveCoverColor(
                                appCtx,
                                retriever,
                                uri,
                                withMeta.albumId,
                                albumArtUri,
                            )
                    } ?: withMeta.coverColorArgb
                AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL ->
                    cachedSong?.coverColorArgb ?: withMeta.coverColorArgb
            }
            ScannedSong(
                song = withMeta.copy(coverColorArgb = coverArgb).toSong(
                    appCtx,
                    metadata,
                    albumArtUri,
                    lyrics.selectedOrCached(cachedSong),
                    trackNumber = tags.trackNumber,
                    discNumber = tags.discNumber,
                    dateAddedMsOverride = draft.dateAddedMsFor(cachedSong),
                ),
                lyrics = lyrics,
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
                    profiler = profiler,
                )
            }
            val copyright = profiler.measureOptional("copyright") {
                readCopyright(appCtx, uri, lyricDraft)
            }
            val comment = profiler.measureOptional("comment") {
                readComment(appCtx, uri, lyricDraft)
            }
            ScannedSong(
                song = draft.copy(album = resolvedAlbum ?: resolveScannedAlbum(null, draft.album)).toSong(
                    appCtx,
                    metadata,
                    albumArtUri = when (artifactPolicy) {
                        AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                            profiler.measureOptional("albumArt") {
                                resolveAlbumArtFromStoreOnly(
                                    context = appCtx,
                                    albumId = draft.albumId,
                                    allowStoreFallback = isSafeForAlbumArtSharing(draft),
                                )
                            }
                        AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL -> null
                    },
                    lyricsDocument = lyrics.selectedOrCached(cachedSong),
                    copyrightOverride = copyright,
                    commentOverride = comment,
                    // Retry unverified metadata on the next explicit deep scan.
                    metadataScanVersion = if (resolvedAlbum == null) 2 else CURRENT_METADATA_SCAN_VERSION,
                    dateAddedMsOverride = draft.dateAddedMsFor(cachedSong),
                ),
                lyrics = lyrics,
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
        artifactPolicy: AudioMetadataProbeArtifactPolicy =
            AudioMetadataProbeArtifactPolicy.FULL_SCAN,
    ): ScannedSong {
        val primaryTags = TagInfo(
            title = tagLib.title,
            artist = tagLib.artist,
            album = tagLib.album,
            albumArtist = tagLib.albumArtist,
            copyright = tagLib.copyright,
            comment = tagLib.comment,
            durationSec = tagLib.durationSec,
            year = tagLib.year,
            releaseDate = tagLib.releaseDate,
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
        val artist = MetadataTextFix.normalize(
            tags.artist.takeIf { it.isNotBlank() }
                ?: albumArtist.takeIf { it.isNotBlank() }
                ?: draft.artist,
        )
        val album = resolveScannedAlbum(tags.album, draft.album)
        val durationSec = when {
            tags.durationSec > 0 -> tags.durationSec
            draft.durationSec > 0 -> draft.durationSec
            else -> 0
        }
        val year = tags.year.takeIf { it > 0 } ?: draft.year
        val releaseDate = tags.releaseDate.ifBlank { draft.releaseDate }

        val detectedContainer = trackProbe?.containerName
            ?: TrackMetadata.containerFromMime(draft.mimeType, draft.displayName)
        val bitsFromTagLib = tagLib.bitsPerSample.takeIf { it > 0 }
        val technicalResult = if (bitsFromTagLib != null) {
            ProbeResult.Ok(
                AudioTechnicalProbe.Result(
                    containerName = detectedContainer,
                    bitsPerSample = bitsFromTagLib,
                ),
            )
        } else {
            profiler.measureOptional("technical") {
                AudioTechnicalProbe.probe(
                    context = context,
                    uri = uri,
                    detectedContainer = detectedContainer,
                    mimeType = draft.mimeType,
                    displayName = draft.displayName,
                )
            }
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
        val comment = MetadataTextFix.normalize(tags.comment).ifBlank {
            profiler.measureOptional("comment") {
                readComment(
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
            releaseDate = releaseDate,
            albumArtist = albumArtist,
            copyright = copyright,
            comment = comment,
            codecLabel = trackProbe?.trackMime ?: playbackMime,
        )
        val albumArtUri = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN -> {
                val artKey = artCacheKey(withMeta)
                profiler.measureOptional("albumArt") {
                    resolveAlbumArtFromBytes(
                        context,
                        coverBytes,
                        artKey,
                        withMeta.albumId,
                        withMeta.scanSongId(),
                    )
                }
            }
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL -> null
        }
        val lyrics = profiler.measureOptional("lyrics") {
            readScanLyrics(
                context,
                withMeta.copy(mimeType = playbackMime.ifBlank { withMeta.mimeType }),
                cachedSong,
                retriever = retriever,
                taglibLyricsCandidates = tagLib.lyricsCandidates + wavFallback?.lyricsCandidates.orEmpty(),
                profiler = profiler,
            )
        }
        val coverArgb = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                profiler.measureOptional("coverColor") {
                    coverBytes?.let { CoverColorExtractor.fromBytes(it) }
                        ?: resolveCoverColor(context, null, uri, withMeta.albumId, albumArtUri)
                } ?: withMeta.coverColorArgb
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL ->
                cachedSong?.coverColorArgb ?: withMeta.coverColorArgb
        }
        return ScannedSong(
            song = withMeta.copy(coverColorArgb = coverArgb).toSong(
                context = context,
                metadata = metadata,
                albumArtUri = albumArtUri,
                lyricsDocument = lyrics.selectedOrCached(cachedSong),
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
                dateAddedMsOverride = draft.dateAddedMsFor(cachedSong),
            ).copy(replayGain = tagLib.replayGain).preserveLoudnessFrom(cachedSong),
            lyrics = lyrics,
        )
        } finally {
            retriever?.let { runCatching { it.release() } }
        }
    }

    private fun readScanLyrics(
        context: Context,
        draft: TrackDraft,
        cachedSong: Song?,
        retriever: MediaMetadataRetriever? = null,
        taglibLyricsCandidates: List<EmbeddedLyricsTextCandidate> = emptyList(),
        preparsedEmbeddedCandidates: List<LyricsDocument> = emptyList(),
        profiler: ScanProfiler? = null,
    ): LyricsProbeResult {
        val externalLrc = profiler.measureOptional("lyrics.externalLrc") {
            ExternalLyricsReader.probeDirectDocumentsDetailed(context, draft.externalLrcUris)
        }
        val externalTtml = profiler.measureOptional("lyrics.externalTtml") {
            ExternalLyricsReader.probeDirectDocumentsDetailed(context, draft.externalTtmlUris)
        }
        if (
            externalLrc is ExternalLyricsProbeResult.Failed ||
            externalTtml is ExternalLyricsProbeResult.Failed
        ) {
            return LyricsProbeResult.ReadFailed
        }
        val preservedSlots = linkedSetOf<com.mica.music.data.LyricsSlot>()
        val externalLrcDoc = when (externalLrc) {
            is ExternalLyricsProbeResult.Found -> externalLrc.document
            ExternalLyricsProbeResult.PermissionDenied -> {
                preservedSlots += com.mica.music.data.LyricsSlot.EXTERNAL_LRC
                null
            }
            is ExternalLyricsProbeResult.Failed -> return LyricsProbeResult.ReadFailed
        }
        val externalTtmlDoc = when (externalTtml) {
            is ExternalLyricsProbeResult.Found -> externalTtml.document
            ExternalLyricsProbeResult.PermissionDenied -> {
                preservedSlots += com.mica.music.data.LyricsSlot.EXTERNAL_TTML
                null
            }
            is ExternalLyricsProbeResult.Failed -> return LyricsProbeResult.ReadFailed
        }
        val embedded = profiler.measureOptional("lyrics.embeddedResolve") {
            EmbeddedLyricsResolver.resolve(
                preparsedCandidates = preparsedEmbeddedCandidates,
                tagLibCandidates = taglibLyricsCandidates,
                parse = ::parseLyricsTextForScan,
                retrieverFallback = {
                    profiler.measureOptional("lyrics.retriever") {
                        retriever?.let { readRetrieverLyrics(it) }
                    }
                },
                binaryFallback = {
                    profiler.measureOptional("lyrics.embeddedBinary") {
                        EmbeddedLyricsReader.probeFastEmbeddedDocument(
                            context = context,
                            uri = Uri.parse(draft.mediaUri),
                            mimeType = draft.mimeType,
                            displayName = draft.displayName,
                            profiler = profiler,
                        )
                    }
                },
            )
        }
        val embeddedDocument = when (embedded) {
            is ProbeResult.Failed -> return LyricsProbeResult.ReadFailed
            is ProbeResult.Ok -> embedded.value?.takeIf { it.lines.isNotEmpty() }
        }
        return LyricsProbeResult.Complete(
            slots = LyricsSlots(
                embedded = embeddedDocument,
                externalLrc = externalLrcDoc,
                externalTtml = externalTtmlDoc,
            ),
            preservedSlots = preservedSlots,
        )
    }

    private fun LyricsProbeResult.selectedOrCached(cachedSong: Song?): LyricsDocument = when (this) {
        is LyricsProbeResult.Complete -> {
            val selected = slots.selected()
            if (selected.lines.isNotEmpty() || preservedSlots.isEmpty()) {
                selected
            } else {
                cachedSong?.lyricsDocument ?: selected
            }
        }
        LyricsProbeResult.ReadFailed,
        LyricsProbeResult.NotProbed,
        -> cachedSong?.lyricsDocument ?: LyricsDocument()
    }

    private fun readRetrieverLyrics(retriever: MediaMetadataRetriever): LyricsDocument? {
        val candidates = mutableListOf<LyricsDocument>()
        for (key in retrieverLyricsKeys) {
            extractMetadataString(retriever, key)
                ?.let { MetadataTextFix.normalize(it) }
                ?.let { parseLyricsTextForScan(it) }
                ?.takeIf { it.lines.isNotEmpty() }
                ?.let { candidates += it }
        }
        return LyricsSanitizer.pickBestDocument(candidates)
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

    private fun readComment(context: Context, uri: Uri, draft: TrackDraft): String {
        if (!draft.mayContainMp4EmbeddedLyrics()) return ""
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = draft.mimeType,
            displayName = draft.displayName,
        ) ?: return ""
        return Mp4AtomTextReader.read(bytes, mp4CommentMarkers)
            ?.let { MetadataTextFix.normalize(it) }
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseLyricsTextForScan(raw: String): LyricsDocument? {
        if (raw.isBlank()) return null
        LyricsSanitizer.parseFilteredDocument(raw, LyricsOrigin.EMBEDDED)
            .takeIf { it.lines.isNotEmpty() }
            ?.let { return it }
        LyricsSanitizer.finalizeDocument(
            LrcParser.parseDocument(raw).copy(origin = LyricsOrigin.EMBEDDED),
        ).takeIf { it.lines.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalizeRelaxedDocument(raw, LyricsOrigin.EMBEDDED)
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
        val artist = MetadataTextFix.normalize(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: albumArtist.takeIf { it.isNotBlank() }
                ?: draft.artist,
        )
        // Preserve absence through the reader merge; MediaStore is not a tag reader.
        val album = MetadataTextFix.normalize(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
        )
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val durationSec = when {
            durationMs > 0 -> (durationMs / 1000).toInt()
            draft.durationSec > 0 -> draft.durationSec
            else -> 0
        }
        val releaseDate = ReleaseDates.canonicalFullDate(
            extractMetadataString(retriever, "date"),
        )
        val year = ReleaseDates.yearFromFullDate(releaseDate).takeIf { it > 0 }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
            ?: draft.year
        val copyright = MetadataTextFix.normalize(
            extractMetadataString(retriever, "copyright")
                ?.takeIf { it.isNotBlank() }
                ?: "",
        )
        val comment = MetadataTextFix.normalize(
            extractMetadataString(retriever, "comment")
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
            comment = comment,
            durationSec = durationSec,
            year = year,
            releaseDate = releaseDate,
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

    private fun DsdMetadataReader.Result.toScannedSong(
        context: Context,
        draft: TrackDraft,
        uri: Uri,
        profiler: ScanProfiler?,
        cachedSong: Song?,
        artifactPolicy: AudioMetadataProbeArtifactPolicy =
            AudioMetadataProbeArtifactPolicy.FULL_SCAN,
    ): ScannedSong {
        val title = tags.title.ifBlank { draft.title }
        val artist = tags.artist.ifBlank { draft.artist }
        val album = resolveScannedAlbum(tags.album.takeIf { it.isNotBlank() || albumTagRead }, draft.album)
        val albumArtist = tags.albumArtist
        val enriched = draft.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            copyright = tags.copyright,
            comment = tags.comment,
            durationSec = durationSec.takeIf { it > 0 } ?: draft.durationSec,
            year = tags.year.takeIf { it > 0 } ?: draft.year,
            releaseDate = tags.releaseDate.ifBlank { draft.releaseDate },
            codecLabel = DsdSupport.rateLabel(metadata.sampleRateHz) ?: "DSD",
            mimeType = metadata.playbackMimeType,
        )
        val lyrics = profiler.measureOptional("lyrics") {
            readScanLyrics(
                context,
                enriched,
                cachedSong,
                preparsedEmbeddedCandidates = listOfNotNull(embeddedLyricsDocument),
                profiler = profiler,
            )
        }
        val albumArtUri = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                profiler.measureOptional("albumArt") {
                    saveEmbeddedPictureBytes(context, albumArtBytes, enriched.scanSongId())
                }
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL -> null
        }
        val coverArgb = when (artifactPolicy) {
            AudioMetadataProbeArtifactPolicy.FULL_SCAN ->
                profiler.measureOptional("coverColor") {
                    albumArtBytes
                        ?.let { CoverColorExtractor.fromBytes(it) }
                        ?: resolveCoverColor(context, null, uri, enriched.albumId, albumArtUri)
                } ?: enriched.coverColorArgb
            AudioMetadataProbeArtifactPolicy.READ_ONLY_CANONICAL ->
                cachedSong?.coverColorArgb ?: enriched.coverColorArgb
        }
        return ScannedSong(
            song = enriched.copy(coverColorArgb = coverArgb).toSong(
                context = context,
                metadata = metadata,
                albumArtUri = albumArtUri,
                lyricsDocument = lyrics.selectedOrCached(cachedSong),
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
                dateAddedMsOverride = draft.dateAddedMsFor(cachedSong),
            ).preserveLoudnessFrom(cachedSong),
            lyrics = lyrics,
        )
    }

    private const val TRACK_ART_KEY_PREFIX = "track_"
    private val genericFolderAlbumNames = setOf(
        "music",
        "download",
        "downloads",
        "audio",
        "recordings",
        "sound",
        "sounds",
        "songs",
        "mp3",
        "flac",
        "qqmusic",
        "cloudmusic",
        "kwmusic",
        "kgmusic",
        "netease",
        "0",
    )

    /**
     * 仅在专辑名和艺人名均有效且非公共目录兜底专辑（如 Android MediaStore 对 `Music/`、
     * `Download/` 目录无标签歌曲自动合成的共享 `album_id`）时，才允许同专辑共享封面。
     */
    internal fun isSafeForAlbumArtSharing(draft: TrackDraft): Boolean {
        val album = draft.album.trim()
        if (album.isEmpty() || album == "未知专辑" || album.equals("<unknown>", ignoreCase = true)) {
            return false
        }
        val groupArtist = draft.albumArtist.trim().ifBlank { draft.artist.trim() }
        if (
            groupArtist.isEmpty() ||
            groupArtist == "未知艺人" ||
            groupArtist.equals("<unknown>", ignoreCase = true)
        ) {
            return false
        }
        val albumLower = album.lowercase()
        val folderLeaf = draft.folderPath.trim('/').substringAfterLast('/', "")
        if (albumLower in genericFolderAlbumNames &&
            (folderLeaf.isEmpty() || album.equals(folderLeaf, ignoreCase = true))
        ) {
            return false
        }
        return true
    }

    internal fun artCacheKey(draft: TrackDraft): String {
        if (!isSafeForAlbumArtSharing(draft)) {
            return "$TRACK_ART_KEY_PREFIX${draft.scanSongId()}"
        }
        val album = draft.album.trim().lowercase()
        val groupArtist = draft.albumArtist.trim().ifBlank { draft.artist.trim() }.lowercase()
        return if (draft.albumId > 0) {
            "ms_album_${draft.albumId}_${album}_$groupArtist"
        } else {
            "tags_${album}_$groupArtist"
        }
    }

    /**
     * 封面优先级：当前文件内嵌图 → 同专辑同艺人已缓存内嵌图 → MediaStore 专辑图（仅非污染专辑）。
     * 每首歌都会先读自己的 embeddedPicture，避免误用其它专辑/曲目封面。
     */
    private fun resolveAlbumArt(
        context: Context,
        retriever: MediaMetadataRetriever,
        artKey: String,
        albumId: Long,
        songId: String,
    ): String? {
        val shareable = !artKey.startsWith(TRACK_ART_KEY_PREFIX)
        saveEmbeddedPicture(context, retriever, songId)?.let { embedded ->
            if (shareable) albumArtCache[artKey] = embedded
            return embedded
        }

        if (!shareable) return null

        albumArtCache[artKey]?.let { return it }

        resolveAlbumArtFromStoreOnly(context, albumId)?.let { storeUri ->
            albumArtCache[artKey] = storeUri
            return storeUri
        }

        return null
    }

    /** TagLib 路径的封面解析：内嵌图字节 → 同专辑同艺人缓存 → MediaStore 专辑图（仅非污染专辑）。 */
    private fun resolveAlbumArtFromBytes(
        context: Context,
        coverBytes: ByteArray?,
        artKey: String,
        albumId: Long,
        songId: String,
    ): String? {
        val shareable = !artKey.startsWith(TRACK_ART_KEY_PREFIX)
        saveEmbeddedPictureBytes(context, coverBytes, songId)?.let { embedded ->
            if (shareable) albumArtCache[artKey] = embedded
            return embedded
        }

        if (!shareable) return null

        albumArtCache[artKey]?.let { return it }

        resolveAlbumArtFromStoreOnly(context, albumId)?.let { storeUri ->
            albumArtCache[artKey] = storeUri
            return storeUri
        }

        return null
    }

    private fun resolveAlbumArtFromStoreOnly(
        context: Context,
        albumId: Long,
        allowStoreFallback: Boolean = true,
    ): String? {
        if (!allowStoreFallback || albumId <= 0) return null
        val albumUri = ContentUris.withAppendedId(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            albumId,
        )
        return if (canOpen(context, albumUri)) albumUri.toString() else null
    }

    private fun saveEmbeddedPicture(
        context: Context,
        retriever: MediaMetadataRetriever,
        songId: String,
    ): String? {
        val bytes = retriever.embeddedPicture ?: return null
        return saveEmbeddedPictureBytes(context, bytes, songId)
    }

    private fun saveEmbeddedPictureBytes(
        context: Context,
        bytes: ByteArray?,
        songId: String,
    ): String? {
        if (bytes == null || bytes.size < 256) return null
        return AlbumArtCache.storeManagedArtworkForScan(context, songId, bytes)
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
        // The media URI points at the audio object, not artwork. Feeding it to BitmapFactory can
        // scan a very large audio stream while looking for an image header and used to amplify the
        // full-scan memory spike. Embedded/store artwork candidates above are the only valid image
        // sources here; keep the existing color when none is available.
        return null
    }

    private fun TrackDraft.toSong(
        context: Context,
        metadata: TrackMetadata,
        albumArtUri: String?,
        lyricsDocument: LyricsDocument = LyricsDocument(),
        copyrightOverride: String = "",
        commentOverride: String = "",
        trackNumber: Int = 0,
        discNumber: Int = 0,
        metadataScanVersion: Int = CURRENT_METADATA_SCAN_VERSION,
        dateAddedMsOverride: Long? = null,
    ): Song {
        val id = if (mediaStoreId > 0) "ms_$mediaStoreId" else SongIdentity.documentId(mediaUri)
        return Song(
            id = id,
            title = title,
            artist = artist,
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
            releaseDate = releaseDate,
            metadataScanVersion = metadataScanVersion,
            trackNumber = trackNumber,
            discNumber = discNumber,
            folderPath = folderPath,
            filePath = filePath,
            copyright = copyrightOverride.ifBlank { copyright },
            comment = commentOverride.ifBlank { comment },
            codecLabel = codecLabel,
            dateAddedMs = dateAddedMsOverride ?: dateAddedMs,
            dateModifiedMs = dateModifiedMs,
            externalLyricsSignature = externalLyricsSignature,
            lyricsDocument = lyricsDocument,
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
            val comment = tag.getFirst(FieldKey.COMMENT)?.trim().orEmpty()
            val lyrics = tag.getFirst(FieldKey.LYRICS)?.trim().orEmpty()
            val frontCoverBytes = tag.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }
            val rawDate = tag.getFirst(FieldKey.YEAR).orEmpty()
            val releaseDate = ReleaseDates.canonicalFullDate(rawDate)
            val year = ReleaseDates.yearFromFullDate(releaseDate).takeIf { it > 0 }
                ?: Regex("""\d{4}""")
                .find(rawDate)
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
                comment = comment,
                durationSec = audioFile.audioHeader?.trackLength?.coerceAtLeast(0) ?: 0,
                year = year,
                releaseDate = releaseDate,
                trackNumber = trackNumber,
                discNumber = discNumber,
                frontCoverBytes = frontCoverBytes,
                lyricsCandidates = lyrics.takeIf { it.isNotBlank() }
                    ?.let { listOf(EmbeddedLyricsTextCandidate("LYRICS", it)) }
                    .orEmpty(),
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

    private fun Song.preserveLoudnessFrom(cachedSong: Song?): Song {
        val cached = cachedSong?.loudnessAnalysis
            ?.takeIf { it.matchesSource(sizeBytes, dateModifiedMs) }
            ?: return this
        return copy(loudnessAnalysis = cached)
    }

    private fun TagLibReader.Result.hasCoreTagGaps(): Boolean =
        title.isBlank() || artist.isBlank() || album.isBlank() || albumArtist.isBlank()
}
