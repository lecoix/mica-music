package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在用户通过 SAF 授权的目录树内递归扫描音频文件。
 */
object FolderScanner {

    private const val PROBE_PARALLELISM = 6

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
    ) + DsdSupport.extensions

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        options: ScanOptions = ScanOptions(),
        cachedSongs: List<Song> = emptyList(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        val profiler = ScanProfiler("Folder")
        AudioMetadataProbe.clearArtCache()
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanResult(
                songs = emptyList(),
                totalSizeMb = 0,
                performanceSummary = profiler.finish(total = 0, reused = 0, probed = 0),
            )
        if (!root.isDirectory) {
            return@withContext ScanResult(
                songs = emptyList(),
                totalSizeMb = 0,
                performanceSummary = profiler.finish(total = 0, reused = 0, probed = 0),
            )
        }

        val drafts = profiler.measure("loadDrafts") { loadDrafts(context, treeUri, root, profiler) }
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

        val songs = if (!options.deepMetadataProbe) {
            onProgress?.invoke(drafts.size, drafts.size)
            drafts.map { draft ->
                draft.reusableCachedSong(
                    cachedById = cachedById,
                    requireDirectLyrics = !draft.externalLyricsUri.isNullOrBlank(),
                    requireFreshEmbeddedLyrics = draft.mayContainMp4EmbeddedLyrics(),
                )?.also { reused.incrementAndGet() }
                    ?: profiler.measure("quickSong") {
                        probed.incrementAndGet()
                        AudioMetadataProbe.quickSong(
                            context = context,
                            draft = draft,
                            profiler = profiler,
                            cachedSong = draft.unchangedCachedSong(cachedById),
                        )
                    }
            }
        } else {
            val total = drafts.size
            val done = AtomicInteger(0)
            val semaphore = Semaphore(PROBE_PARALLELISM)
            coroutineScope {
                drafts.map { draft ->
                    async {
                        semaphore.withPermit {
                            val song = draft.reusableCachedSong(
                                cachedById = cachedById,
                                requireDeepMetadata = true,
                                requireDirectLyrics = !draft.externalLyricsUri.isNullOrBlank(),
                                requireFreshEmbeddedLyrics = draft.mayContainMp4EmbeddedLyrics(),
                            )
                                ?.also { reused.incrementAndGet() }
                                ?: profiler.measure("probeTrack") {
                                    probed.incrementAndGet()
                                    AudioMetadataProbe.probeTrack(
                                        context = context,
                                        draft = draft,
                                        profiler = profiler,
                                        cachedSong = draft.unchangedCachedSong(cachedById),
                                    )
                                }
                            onProgress?.invoke(done.incrementAndGet(), total)
                            song
                        }
                    }
                }.awaitAll()
            }
        }

        val filtered = if (options.minDurationMs > 0) {
            songs.filter { s ->
                s.durationSec == 0 || s.durationSec * 1000L >= options.minDurationMs
            }
        } else {
            songs
        }
        val totalBytes = drafts.sumOf { it.sizeBytes }
        val summary = profiler.finish(
            total = drafts.size,
            reused = reused.get(),
            probed = probed.get(),
        )
        ScanResult(
            songs = filtered,
            totalSizeMb = (totalBytes / (1024 * 1024)).toInt(),
            performanceSummary = summary,
        )
    }

    private fun loadDrafts(
        context: Context,
        treeUri: Uri,
        root: DocumentFile,
        profiler: ScanProfiler,
    ): List<TrackDraft> {
        val files = mutableListOf<AudioFileEntry>()
        val lrcFiles = mutableListOf<LrcFileEntry>()
        val loadedByQuery = runCatching {
            profiler.measure("loadDrafts.query") {
                val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                collectLibraryFiles(context, treeUri, rootDocumentId, "", files, lrcFiles)
            }
        }.isSuccess
        if (!loadedByQuery) {
            files.clear()
            lrcFiles.clear()
            profiler.measure("loadDrafts.fallback") {
                collectLibraryFilesFallback(root, "", files, lrcFiles)
            }
        }
        val lrcByAudioKey = lrcFiles.associateBy { lyricsKey(it.folderPath, it.baseName) }

        val drafts = mutableListOf<TrackDraft>()
        val scannedAt = System.currentTimeMillis()
        for (entry in files) {
            val uri = entry.uri
            val name = entry.name
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = entry.mimeType.ifBlank {
                when (ext) {
                    "flac" -> "audio/flac"
                    "m4a", "alac" -> "audio/mp4"
                    "dsf", "dff", "dsdiff" -> DsdSupport.mimeForExtension(ext)
                    "mp3" -> "audio/mpeg"
                    "ogg", "opus" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    else -> "audio/*"
                }
            }
            if (!mime.startsWith("audio/") && ext !in audioExtensions) continue

            val title = name.substringBeforeLast('.').ifBlank { name }
            val externalLyricsUri = lrcByAudioKey[lyricsKey(entry.folderPath, title)]
                ?.uri
                ?.toString()
            val size = entry.sizeBytes
            val modifiedMs = entry.lastModifiedMs.coerceAtLeast(0L)
            val filePath = buildString {
                if (entry.folderPath.isNotBlank()) {
                    append(entry.folderPath.trimEnd('/'))
                    append('/')
                }
                append(name)
            }
            drafts += TrackDraft(
                mediaStoreId = 0L,
                title = title,
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
                folderPath = entry.folderPath,
                filePath = filePath,
                dateAddedMs = scannedAt,
                dateModifiedMs = modifiedMs,
                externalLyricsParent = null,
                externalLyricsUri = externalLyricsUri,
            )
        }

        return drafts
    }

    private data class AudioFileEntry(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val folderPath: String,
    )

    private data class LrcFileEntry(
        val uri: Uri,
        val folderPath: String,
        val baseName: String,
    )

    private fun collectLibraryFiles(
        context: Context,
        treeUri: Uri,
        documentId: String,
        parentPath: String,
        audioOut: MutableList<AudioFileEntry>,
        lrcOut: MutableList<LrcFileEntry>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val documentIdCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val childDocumentId = cursor.getString(documentIdCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getStringOrEmpty(mimeCol)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val nextPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    collectLibraryFiles(context, treeUri, childDocumentId, nextPath, audioOut, lrcOut)
                } else {
                    collectFileEntry(
                        uri = childUri,
                        name = name,
                        mime = mime,
                        size = cursor.getLongOrZero(sizeCol),
                        lastModified = cursor.getLongOrZero(modifiedCol),
                        folderPath = parentPath,
                        audioOut = audioOut,
                        lrcOut = lrcOut,
                    )
                }
            }
        } ?: error("Cannot query SAF children: $childrenUri")
    }

    private fun collectLibraryFilesFallback(
        dir: DocumentFile,
        parentPath: String,
        audioOut: MutableList<AudioFileEntry>,
        lrcOut: MutableList<LrcFileEntry>,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val nextPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                collectLibraryFilesFallback(child, nextPath, audioOut, lrcOut)
            } else if (child.isFile) {
                collectFileEntry(
                    uri = child.uri,
                    name = name,
                    mime = child.type.orEmpty(),
                    size = child.length(),
                    lastModified = child.lastModified(),
                    folderPath = parentPath,
                    audioOut = audioOut,
                    lrcOut = lrcOut,
                )
            }
        }
    }

    private fun collectFileEntry(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        lastModified: Long,
        folderPath: String,
        audioOut: MutableList<AudioFileEntry>,
        lrcOut: MutableList<LrcFileEntry>,
    ) {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("audio/") || ext in audioExtensions -> {
                audioOut += AudioFileEntry(
                    uri = uri,
                    name = name,
                    mimeType = mime,
                    sizeBytes = size,
                    lastModifiedMs = lastModified,
                    folderPath = folderPath,
                )
            }
            ext == "lrc" -> {
                val baseName = name.substringBeforeLast('.').trim()
                if (baseName.isNotEmpty()) {
                    lrcOut += LrcFileEntry(uri = uri, folderPath = folderPath, baseName = baseName)
                }
            }
        }
    }

    private fun android.database.Cursor.getStringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun lyricsKey(folderPath: String, baseName: String): String =
        "${folderPath.trim('/').lowercase()}\u0001${baseName.trim().lowercase()}"

}
