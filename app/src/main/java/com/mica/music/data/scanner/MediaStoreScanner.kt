package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.data.LyricsDocument
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.LyricsScanBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

data class ScanResult(
    val songs: List<Song>,
    val totalSizeMb: Int,
    val performanceSummary: String = "",
    val probeStats: ScanProbeStats = ScanProbeStats(),
)

/**
 * MediaStore 快速列表 + [AudioMetadataProbe] 并行探测（封面与真实音质）。
 */
object MediaStoreScanner {

    internal const val LYRICS_SCAN_BATCH_SIZE = 6
    internal const val PROBE_PARALLELISM = 8
    internal val FILE_EXTENSION_FALLBACKS = DsdSupport.extensions + "ape"

    suspend fun scan(
        context: Context,
        options: ScanOptions = ScanOptions(),
        cachedSongs: List<Song> = emptyList(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        val profiler = ScanProfiler("MediaStore")
        AudioMetadataProbe.clearArtCache()
        val drafts = profiler.measure("loadDrafts") { loadDrafts(context, options) }
        if (drafts.isEmpty()) {
            return@withContext ScanResult(
                songs = emptyList(),
                totalSizeMb = 0,
                performanceSummary = profiler.finish(total = 0, reused = 0, probed = 0),
            )
        }
        val cachedById = cachedSongs.associateBy { it.id }
        val reused = AtomicInteger(0)
        val probed = AtomicInteger(0)
        val technicalFailed = AtomicInteger(0)
        val lyricsReadFailed = AtomicInteger(0)

        val songs = mutableListOf<Song>()
        if (!options.deepMetadataProbe) {
            var done = 0
            drafts.chunked(PROBE_PARALLELISM).forEach { chunk ->
                val batch = chunk.map { draft ->
                val forceRefreshLyrics = draft.forceRefreshLyricsFor(options)
                val forceRefreshArtwork = draft.forceRefreshArtworkFor(options)
                draft.reusableCachedSong(
                    context = context,
                    cachedById = cachedById,
                    requireDirectLyrics = draft.externalLyricsUris.isNotEmpty(),
                    requireFreshEmbeddedLyrics = draft.mayContainMp4EmbeddedLyrics(),
                    forceRefreshLyrics = forceRefreshLyrics,
                    forceRefreshArtwork = forceRefreshArtwork,
                    onReuseMiss = profiler::recordReuseMiss,
                )?.let { song -> ScannedSong(song).also { reused.incrementAndGet() } }
                    ?: profiler.measure("quickSong") {
                        probed.incrementAndGet()
                        AudioMetadataProbe.quickSong(
                            context = context,
                            draft = draft,
                            profiler = profiler,
                            cachedSong = draft.unchangedCachedSongForProbe(
                                cachedById,
                                forceRefreshLyrics,
                            ),
                        )
                    }
                }
                songs += persistScannedLyricsBatches(
                    batch.filterForDuration(options),
                    lyricsReadFailed,
                    profiler,
                    onLyricsBatch,
                )
                done += chunk.size
                onProgress?.invoke(done, drafts.size)
            }
        } else {
            val total = drafts.size
            val done = AtomicInteger(0)
            val semaphore = Semaphore(PROBE_PARALLELISM)
            drafts.chunked(PROBE_PARALLELISM).forEach { chunk ->
                val batch = coroutineScope {
                    chunk.map { draft ->
                    async {
                        semaphore.withPermit {
                            val forceRefreshLyrics = draft.forceRefreshLyricsFor(options)
                            val forceRefreshArtwork = draft.forceRefreshArtworkFor(options)
                            val song = draft.reusableCachedSong(
                                context = context,
                                cachedById = cachedById,
                                requireDeepMetadata = true,
                                requireDirectLyrics = draft.externalLyricsUris.isNotEmpty(),
                                requireFreshEmbeddedLyrics = draft.mayContainMp4EmbeddedLyrics(),
                                forceRefreshLyrics = forceRefreshLyrics,
                                forceRefreshArtwork = forceRefreshArtwork,
                                onReuseMiss = profiler::recordReuseMiss,
                            )
                                ?.let { cached -> ScannedSong(cached).also { reused.incrementAndGet() } }
                                ?: profiler.measure("probeTrack") {
                                    probed.incrementAndGet()
                                    AudioMetadataProbe.probeTrack(
                                        context = context,
                                        draft = draft,
                                        profiler = profiler,
                                        cachedSong = draft.unchangedCachedSongForProbe(
                                            cachedById,
                                            forceRefreshLyrics,
                                        ),
                                        technicalProbeFailures = technicalFailed,
                                    )
                                }
                            onProgress?.invoke(done.incrementAndGet(), total)
                            song
                        }
                    }
                }.awaitAll()
                }
                songs += persistScannedLyricsBatches(
                    batch.filterForDuration(options),
                    lyricsReadFailed,
                    profiler,
                    onLyricsBatch,
                )
            }
        }

        val totalBytes = drafts.sumOf { it.sizeBytes }
        val summary = profiler.finish(
            total = drafts.size,
            reused = reused.get(),
            probed = probed.get(),
        )
        ScanResult(
            songs = songs.map { it.copy(videoCoverUri = null) },
            totalSizeMb = (totalBytes / (1024 * 1024)).toInt(),
            performanceSummary = summary,
            probeStats = ScanProbeStats(
                technicalFailed = technicalFailed.get(),
                lyricsReadFailed = lyricsReadFailed.get(),
            ),
        )
    }

    private fun loadDrafts(context: Context, options: ScanOptions): List<TrackDraft> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val lyricsByAudioKey = loadLyricsIndex(context)

        val baseProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val projection: Array<String> = buildList {
            addAll(baseProjection)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.BITRATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val musicClause = if (options.includeNonMusicByMime) {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                "OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val durationClause = mediaStoreDurationClause(options.minDurationMs)
        val selection = "$musicClause$durationClause"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val cursor = context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?: throw IOException("MediaStore audio query returned no cursor")

        val drafts = mutableListOf<TrackDraft>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val displayNameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1
            val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.Audio.Media.DATA)
            } else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "未知标题"
                val artist = c.getString(artistCol)
                    ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                    ?: "未知艺人"
                val album = c.getString(albumCol)
                    ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                    ?: "未知专辑"
                val albumId = c.getLong(albumIdCol)
                val durationMs = c.getLong(durationCol)
                val mime = c.getString(mimeCol).orEmpty()
                val displayName = c.getString(displayNameCol)
                val size = c.getLong(sizeCol)
                val bitrateBps = if (bitrateCol >= 0) c.getInt(bitrateCol) else 0
                val year = if (yearCol >= 0) c.getInt(yearCol).coerceAtLeast(0) else 0
                val dateAddedMs = if (dateAddedCol >= 0) c.getLong(dateAddedCol) * 1000L else 0L
                val dateModifiedMs = if (dateModifiedCol >= 0) c.getLong(dateModifiedCol) * 1000L else 0L
                val relativePath = if (relativePathCol >= 0) c.getString(relativePathCol).orEmpty() else ""
                val folderPath = when {
                    relativePathCol >= 0 -> relativePath.trim('/')
                    dataCol >= 0 -> c.getString(dataCol)
                        ?.substringBeforeLast('/', "")
                        .orEmpty()
                    else -> ""
                }
                val filePath = when {
                    dataCol >= 0 -> c.getString(dataCol).orEmpty()
                    relativePathCol >= 0 && !displayName.isNullOrBlank() -> {
                        val rel = relativePath.trimStart('/')
                        if (rel.isBlank()) displayName else "$rel$displayName"
                    }
                    else -> ""
                }
                if (ExcludedScanDirectories.isExcluded(folderPath, options.excludedDirectories)) {
                    continue
                }
                val externalLyricsRefs = displayName
                    ?.substringBeforeLast('.')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { baseName ->
                        lyricsByAudioKey[lyricsKey(lyricsFolderPath(relativePath, filePath), baseName)]
                    }.orEmpty()
                val externalLyricsUris = externalLyricsRefs.externalLyricsUris()
                val uri = ContentUris.withAppendedId(collection, id)

                drafts += TrackDraft(
                    mediaStoreId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    durationSec = (durationMs / 1000).toInt(),
                    mimeType = mime,
                    displayName = displayName,
                    sizeBytes = size,
                    bitrateBpsFromStore = bitrateBps,
                    mediaUri = uri.toString(),
                    coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
                    year = year,
                    folderPath = folderPath,
                    filePath = filePath,
                    externalLyricsUris = externalLyricsUris,
                    externalLrcUris = externalLyricsRefs.externalLyricsUris("lrc"),
                    externalTtmlUris = externalLyricsRefs.externalLyricsUris("ttml"),
                    externalLyricsSignature = externalLyricsRefs.externalLyricsSignature(),
                    dateAddedMs = dateAddedMs,
                    dateModifiedMs = dateModifiedMs,
                )
            }
        }
        val existingKeys = drafts
            .map { draft -> mediaStoreDuplicateKey(draft.mediaUri, draft.filePath, draft.sizeBytes) }
            .toMutableSet()
        drafts += loadExtendedAudioFileDrafts(context, options, lyricsByAudioKey, existingKeys)
        return drafts
    }

    private fun loadExtendedAudioFileDrafts(
        context: Context,
        options: ScanOptions,
        lyricsByAudioKey: Map<String, List<ExternalLyricsRef>>,
        existingKeys: MutableSet<String>,
    ): List<TrackDraft> = runCatching {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            null
        }
        if (relativePathColumn != null) {
            projection += relativePathColumn
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            projection += MediaStore.Files.FileColumns.DATA
        }
        val selection = FILE_EXTENSION_FALLBACKS.joinToString(" OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val args = FILE_EXTENSION_FALLBACKS.map { "%.$it" }.toTypedArray()
        val out = mutableListOf<TrackDraft>()
        context.contentResolver.query(
            filesUri,
            projection.toTypedArray(),
            selection,
            args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateAddedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val dateModifiedCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = relativePathColumn?.let { c.getColumnIndex(it) } ?: -1
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            } else {
                -1
            }
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in FILE_EXTENSION_FALLBACKS) continue
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(filesUri, id)
                val mime = c.getStringOrEmpty(mimeCol).ifBlank {
                    if (ext == "ape") "audio/x-ape" else DsdSupport.mimeForExtension(ext)
                }
                val size = c.getLongOrZero(sizeCol)
                val relativePath = if (relativePathCol >= 0) c.getString(relativePathCol).orEmpty() else ""
                val filePath = when {
                    dataCol >= 0 -> c.getString(dataCol).orEmpty()
                    relativePath.isNotBlank() -> relativePath.trimStart('/') + name
                    else -> ""
                }
                val key = mediaStoreDuplicateKey(uri.toString(), filePath, size)
                if (!existingKeys.add(key)) continue
                val folderPath = lyricsFolderPath(relativePath, filePath)
                if (ExcludedScanDirectories.isExcluded(folderPath, options.excludedDirectories)) {
                    continue
                }
                val baseName = name.substringBeforeLast('.').trim()
                val externalLyricsRefs = lyricsByAudioKey[lyricsKey(folderPath, baseName)].orEmpty()
                out += TrackDraft(
                    mediaStoreId = id,
                    title = baseName.ifBlank { name },
                    artist = "未知艺人",
                    album = "未知专辑",
                    albumId = 0L,
                    durationSec = 0,
                    mimeType = mime,
                    displayName = name,
                    sizeBytes = size,
                    bitrateBpsFromStore = 0,
                    mediaUri = uri.toString(),
                    coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
                    folderPath = folderPath,
                    filePath = filePath,
                    externalLyricsUris = externalLyricsRefs.externalLyricsUris(),
                    externalLrcUris = externalLyricsRefs.externalLyricsUris("lrc"),
                    externalTtmlUris = externalLyricsRefs.externalLyricsUris("ttml"),
                    externalLyricsSignature = externalLyricsRefs.externalLyricsSignature(),
                    dateAddedMs = if (dateAddedCol >= 0) c.getLong(dateAddedCol) * 1000L else 0L,
                    dateModifiedMs = if (dateModifiedCol >= 0) c.getLong(dateModifiedCol) * 1000L else 0L,
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun loadLyricsIndex(context: Context): Map<String, List<ExternalLyricsRef>> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )

        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            null
        }
        if (relativePathColumn != null) {
            projection += relativePathColumn
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Files.FileColumns.DATA
            projection += dataColumn
        }

        val out = LinkedHashMap<String, MutableList<ExternalLyricsRef>>()
        val cursor = context.contentResolver.query(
            filesUri,
            projection.toTypedArray(),
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ? OR " +
                "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?",
            arrayOf("%.lrc", "%.ttml"),
            null,
        ) ?: throw IOException("MediaStore lyrics query returned no cursor")
        cursor.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateModifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relativePathCol = relativePathColumn?.let { cursor.getColumnIndex(it) } ?: -1
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.endsWith(".lrc", ignoreCase = true) &&
                    !name.endsWith(".ttml", ignoreCase = true)
                ) continue
                val baseName = name.substringBeforeLast('.').trim()
                if (baseName.isEmpty()) continue
                val folderPath = when {
                    relativePathCol >= 0 -> cursor.getString(relativePathCol).orEmpty()
                    dataCol >= 0 -> cursor.getString(dataCol).orEmpty().substringBeforeLast('/', "")
                    else -> ""
                }
                val uri = ContentUris.withAppendedId(filesUri, cursor.getLong(idCol)).toString()
                out.getOrPut(lyricsKey(folderPath, baseName)) { mutableListOf() } += ExternalLyricsRef(
                    uri = uri,
                    sizeBytes = cursor.getLongOrZero(sizeCol),
                    dateModifiedMs = if (dateModifiedCol >= 0) cursor.getLong(dateModifiedCol) * 1000L else 0L,
                    extension = name.substringAfterLast('.', "").lowercase(),
                )
            }
        }
        return out.mapValues { (_, refs) -> refs.distinctBy { it.uri } }
    }

    private fun lyricsFolderPath(relativePath: String, absolutePath: String): String = when {
        relativePath.isNotBlank() -> relativePath
        '/' in absolutePath -> absolutePath.substringBeforeLast('/', "")
        else -> ""
    }

    private fun lyricsKey(folderPath: String, baseName: String): String =
        "${folderPath.trim('/').lowercase()}\u0001${baseName.trim().lowercase()}"

    private fun mediaStoreDuplicateKey(mediaUri: String, filePath: String, sizeBytes: Long): String =
        "${filePath.ifBlank { mediaUri }.lowercase()}\u0001${sizeBytes.coerceAtLeast(0L)}"

    private fun android.database.Cursor.getStringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

}

internal fun mediaStoreDurationClause(minDurationMs: Long): String {
    if (minDurationMs <= 0L) return ""
    val duration = MediaStore.Audio.Media.DURATION
    return " AND ($duration IS NULL OR $duration <= 0 OR $duration >= $minDurationMs)"
}

internal fun shouldKeepScannedDuration(durationSec: Int, minDurationMs: Long): Boolean =
    minDurationMs <= 0L || durationSec == 0 || durationSec * 1000L >= minDurationMs

private fun List<ScannedSong>.filterForDuration(options: ScanOptions): List<ScannedSong> =
    filter { scanned -> shouldKeepScannedDuration(scanned.song.durationSec, options.minDurationMs) }

internal suspend fun persistScannedLyricsBatch(
    songs: List<ScannedSong>,
    lyricsReadFailures: AtomicInteger? = null,
    profiler: ScanProfiler? = null,
    onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
): List<Song> {
    val readFailedCount = songs.count { it.lyrics is LyricsProbeResult.ReadFailed }
    lyricsReadFailures?.addAndGet(readFailedCount)
    val payloads = if (onLyricsBatch == null) {
        emptyList()
    } else {
        songs.mapNotNull { scanned ->
            (scanned.lyrics as? LyricsProbeResult.Complete)?.let { completed ->
                ScannedSongLyrics(scanned.song.id, scanned.song.lyricsCacheRevision, completed.slots)
            }
        }
    }
    if (onLyricsBatch != null && (payloads.isNotEmpty() || readFailedCount > 0)) {
        val batch = LyricsScanBatch(payloads, readFailedCount)
        if (profiler == null) {
            onLyricsBatch(batch)
        } else {
            profiler.measureSuspend("lyrics.persist") { onLyricsBatch(batch) }
        }
    }
    return songs.map(::retainScannedSong)
}

private fun retainScannedSong(scanned: ScannedSong): Song {
    val song = when (scanned.lyrics) {
        is LyricsProbeResult.Complete -> scanned.song.copy(
            embeddedLyricsProbeRevision = scanned.song.embeddedLyricsProbeRevisionForCurrentFile(),
        )
        LyricsProbeResult.ReadFailed,
        LyricsProbeResult.NotProbed,
        -> scanned.song
    }
    return if (scanned.lyrics is LyricsProbeResult.NotProbed) song else song.copy(
        lyricsDocument = LyricsDocument(),
        lyricsLoaded = false,
    )
}

internal suspend fun persistScannedLyricsBatches(
    songs: List<ScannedSong>,
    lyricsReadFailures: AtomicInteger? = null,
    profiler: ScanProfiler? = null,
    onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val retained = ArrayList<Song>(songs.size)
    songs.chunked(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE).forEach { batch ->
        retained += persistScannedLyricsBatch(batch, lyricsReadFailures, profiler, onLyricsBatch)
    }
    return retained
}
