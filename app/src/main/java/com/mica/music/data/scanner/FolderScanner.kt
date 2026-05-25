package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.documentfile.provider.DocumentFile
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
    )

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        options: ScanOptions = ScanOptions(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        AudioMetadataProbe.clearArtCache()
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanResult(emptyList(), 0)
        if (!root.isDirectory) return@withContext ScanResult(emptyList(), 0)

        val drafts = loadDrafts(context, root, options)
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

        val filtered = if (options.minDurationMs > 0) {
            songs.filter { s ->
                s.durationSec == 0 || s.durationSec * 1000L >= options.minDurationMs
            }
        } else {
            songs
        }
        val totalBytes = drafts.sumOf { it.sizeBytes }
        ScanResult(
            songs = filtered,
            totalSizeMb = (totalBytes / (1024 * 1024)).toInt(),
        )
    }

    private fun loadDrafts(
        context: Context,
        root: DocumentFile,
        options: ScanOptions,
    ): List<TrackDraft> {
        val files = mutableListOf<AudioFileEntry>()
        collectAudioFiles(root, "", files)

        val drafts = mutableListOf<TrackDraft>()
        var index = 0L
        val scannedAt = System.currentTimeMillis()
        for (entry in files) {
            val file = entry.file
            val uri = file.uri
            val name = file.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = file.type.orEmpty().ifBlank {
                when (ext) {
                    "flac" -> "audio/flac"
                    "m4a", "alac" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    "ogg", "opus" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    else -> "audio/*"
                }
            }
            if (!mime.startsWith("audio/") && ext !in audioExtensions) continue

            val title = name.substringBeforeLast('.').ifBlank { name }
            val size = file.length()
            val modifiedMs = file.lastModified().coerceAtLeast(0L)
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
            )
        }

        return drafts
    }

    private data class AudioFileEntry(
        val file: DocumentFile,
        val folderPath: String,
    )

    private fun collectAudioFiles(
        dir: DocumentFile,
        parentPath: String,
        out: MutableList<AudioFileEntry>,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val nextPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                collectAudioFiles(child, nextPath, out)
            } else if (child.isFile) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = child.type.orEmpty()
                if (mime.startsWith("audio/") || ext in audioExtensions) {
                    out += AudioFileEntry(file = child, folderPath = parentPath)
                }
            }
        }
    }

}
