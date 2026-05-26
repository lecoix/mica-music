package com.mica.music.data.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import com.mica.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class ScanResult(
    val songs: List<Song>,
    val totalSizeMb: Int,
)

/**
 * MediaStore 快速列表 + [AudioMetadataProbe] 并行探测（封面与真实音质）。
 */
object MediaStoreScanner {

    private const val PROBE_PARALLELISM = 6

    suspend fun scan(
        context: Context,
        options: ScanOptions = ScanOptions(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        AudioMetadataProbe.clearArtCache()
        val drafts = loadDrafts(context, options)
        if (drafts.isEmpty()) return@withContext ScanResult(emptyList(), 0)

        val songs = if (!options.deepMetadataProbe) {
            onProgress?.invoke(drafts.size, drafts.size)
            drafts.map { AudioMetadataProbe.quickSong(context, it) }
        } else {
            val total = drafts.size
            val done = AtomicInteger(0)
            val semaphore = Semaphore(PROBE_PARALLELISM)
            coroutineScope {
                drafts.map { draft ->
                    async {
                        semaphore.withPermit {
                            val song = AudioMetadataProbe.probeTrack(context, draft)
                            onProgress?.invoke(done.incrementAndGet(), total)
                            song
                        }
                    }
                }.awaitAll()
            }
        }

        val totalBytes = drafts.sumOf { it.sizeBytes }
        ScanResult(
            songs = songs,
            totalSizeMb = (totalBytes / (1024 * 1024)).toInt(),
        )
    }

    private fun loadDrafts(context: Context, options: ScanOptions): List<TrackDraft> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

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
        val durationClause = if (options.minDurationMs > 0) {
            " AND ${MediaStore.Audio.Media.DURATION} >= ${options.minDurationMs}"
        } else {
            ""
        }
        val selection = "$musicClause$durationClause"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val cursor = context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?: return emptyList()

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
                    relativePathCol >= 0 -> relativePath
                        ?.trimEnd('/')
                        ?.substringBeforeLast('/', "")
                        .orEmpty()
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
                val externalLyricsUri = findExternalLyricsUri(
                    context = context,
                    displayName = displayName,
                    relativePath = relativePath,
                    absolutePath = filePath,
                )
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
                    externalLyricsUri = externalLyricsUri,
                    dateAddedMs = dateAddedMs,
                    dateModifiedMs = dateModifiedMs,
                )
            }
        }
        return drafts
    }

    private fun findExternalLyricsUri(
        context: Context,
        displayName: String?,
        relativePath: String,
        absolutePath: String,
    ): String? = runCatching {
        val baseName = displayName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@runCatching null
        val lrcName = "$baseName.lrc"
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
        )
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        selectionParts += "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) = ?"
        selectionArgs += lrcName.lowercase()

        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            null
        }
        if (relativePathColumn != null && relativePath.isNotBlank()) {
            projection += relativePathColumn
            selectionParts += "$relativePathColumn = ?"
            selectionArgs += relativePath
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && absolutePath.contains('/')) {
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Files.FileColumns.DATA
            projection += dataColumn
            selectionParts += "$dataColumn = ?"
            selectionArgs += "${absolutePath.substringBeforeLast('/')}/$lrcName"
        }

        context.contentResolver.query(
            filesUri,
            projection.toTypedArray(),
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.equals(lrcName, ignoreCase = true)) continue
                return@runCatching ContentUris.withAppendedId(filesUri, cursor.getLong(idCol)).toString()
            }
        }
        null
    }.getOrNull()

}
