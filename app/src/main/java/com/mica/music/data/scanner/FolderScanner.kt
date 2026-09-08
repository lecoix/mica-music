package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.LyricsScanBatch
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在用户通过 SAF 授权的目录树内递归扫描音频文件。
 */
internal object FolderScanner {

    private const val PROBE_PARALLELISM = MediaStoreScanner.PROBE_PARALLELISM
    private const val LYRICS_TRACE = "DEBUG-LYRICS-7C31"

    /**
     * One process-wide lane for automatic SAF metadata queries.
     *
     * A third-party DocumentsProvider may ignore both thread interruption and CancellationSignal
     * after a Binder query has entered provider code. Keeping one shared worker means cancellation
     * never creates a second concurrent AUTO query behind such a stuck provider. Manual/full scans
     * keep their existing synchronous path and are not routed through this lane.
     */
    private val autoProviderQueryExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(runnable, "mica-saf-auto-query-lane").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
    ) + DsdSupport.extensions

    internal suspend fun observeMetadata(
        context: Context,
        treeUri: Uri,
        options: ScanOptions = ScanOptions(),
    ): SafTreeMetadataSnapshot = withContext(Dispatchers.IO) {
        val profiler = ScanProfiler("FolderMetadata")
        val observationStartedAtMs = SystemClock.elapsedRealtime()
        val discoveryQueries = DiscoveryQueryCounter()
        fun observationStats() = SafTreeMetadataObservationStats(
            // Exact only for calls we own directly. DocumentFile fallback may hide extra provider
            // queries internally, so fallback listings are reported separately rather than folded
            // into providerQueryCount.
            providerQueryCount = discoveryQueries.directQueryCount,
            directQueryCount = discoveryQueries.directQueryCount,
            fallbackListingCount = discoveryQueries.fallbackListingCount,
            wallTimeMs = (SystemClock.elapsedRealtime() - observationStartedAtMs).coerceAtLeast(0L),
        )
        val loaded = SafAutoQuerySession(context).use { querySession ->
            profiler.measureSuspend("loadDrafts") {
                loadDrafts(
                    context = context,
                    treeUri = treeUri,
                    root = null,
                    options = options,
                    profiler = profiler,
                    discoveryQueries = discoveryQueries,
                    querySession = querySession,
                    allowDocumentFileFallback = false,
                )
            }
        }
        SafTreeMetadataSnapshot(
            entries = loaded.drafts.map { draft ->
                SafTreeMetadataEntry(
                    stableObjectKey = draft.scanSongId(),
                    mediaUri = draft.mediaUri,
                    fileName = draft.displayName.orEmpty(),
                    folderPath = draft.folderPath,
                    filePath = draft.filePath,
                    mimeType = draft.mimeType,
                    sizeBytes = draft.sizeBytes,
                    lastModifiedMs = draft.dateModifiedMs,
                    externalLyricsSignature = draft.externalLyricsSignature,
                    probeDraft = draft,
                )
            },
            discoveryReport = loaded.discoveryReport,
            videoCovers = loaded.videoCovers,
            observationStats = observationStats(),
        )
    }

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        options: ScanOptions = ScanOptions(),
        cachedSongs: List<Song> = emptyList(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        val profiler = ScanProfiler("Folder")
        AudioMetadataProbe.clearArtCache()
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext unavailableFolderResult(
                profiler = profiler,
                detail = "Cannot resolve SAF tree",
            )
        if (!root.isDirectory) {
            return@withContext unavailableFolderResult(
                profiler = profiler,
                detail = "SAF root is not a directory",
            )
        }

        val loaded = profiler.measureSuspend("loadDrafts") {
            loadDrafts(context, treeUri, root, options, profiler)
        }
        val drafts = loaded.drafts
        if (drafts.isEmpty()) {
            return@withContext ScanResult(
                songs = emptyList(),
                totalSizeMb = 0,
                performanceSummary = profiler.finish(total = 0, reused = 0, probed = 0),
                discoveryReport = loaded.discoveryReport,
                folderVideoFiles = loaded.videoCovers,
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
            songs = MusicVideoMatcher.attach(
                attachVideoCovers(songs, loaded.videoCovers),
                loaded.videoCovers,
            ),
            totalSizeMb = (totalBytes / (1024 * 1024)).toInt(),
            performanceSummary = summary,
            probeStats = ScanProbeStats(
                technicalFailed = technicalFailed.get(),
                lyricsReadFailed = lyricsReadFailed.get(),
            ),
            discoveryReport = loaded.discoveryReport,
            folderVideoFiles = loaded.videoCovers,
        )
    }

    private fun unavailableFolderResult(
        profiler: ScanProfiler,
        detail: String,
    ): ScanResult = ScanResult(
        songs = emptyList(),
        totalSizeMb = 0,
        performanceSummary = profiler.finish(total = 0, reused = 0, probed = 0),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.UNAVAILABLE,
                detail = detail,
            ),
        ),
    )

    private suspend fun loadDrafts(
        context: Context,
        treeUri: Uri,
        root: DocumentFile?,
        options: ScanOptions,
        profiler: ScanProfiler,
        discoveryQueries: DiscoveryQueryCounter? = null,
        querySession: SafAutoQuerySession? = null,
        allowDocumentFileFallback: Boolean = true,
    ): LoadedFolderFiles {
        val files = mutableListOf<AudioFileEntry>()
        val lyricFiles = mutableListOf<LyricFileEntry>()
        val videoCovers = mutableListOf<VideoCoverFile>()
        var queryError: Throwable? = null
        try {
            profiler.measureSuspend("loadDrafts.query") {
                val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                collectLibraryFiles(
                    context = context,
                    treeUri = treeUri,
                    documentId = rootDocumentId,
                    parentPath = "",
                    options = options,
                    audioOut = files,
                    lyricOut = lyricFiles,
                    videoOut = videoCovers,
                    discoveryQueries = discoveryQueries,
                    querySession = querySession,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            queryError = error
        }
        val loadedByQuery = queryError == null
        val fallbackComplete = if (
            !loadedByQuery &&
            allowDocumentFileFallback &&
            root != null
        ) {
            files.clear()
            lyricFiles.clear()
            videoCovers.clear()
            profiler.measure("loadDrafts.fallback") {
                collectLibraryFilesFallback(
                    dir = root,
                    parentPath = "",
                    options = options,
                    audioOut = files,
                    lyricOut = lyricFiles,
                    videoOut = videoCovers,
                    discoveryQueries = discoveryQueries,
                )
            }
        } else {
            loadedByQuery
        }
        val discoveryStatus = when {
            loadedByQuery -> DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            )
            else -> DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.PARTIAL,
                detail = buildString {
                    append(
                        queryError?.message.orEmpty()
                            .ifBlank { "DocumentsContract query failed" },
                    )
                    if (allowDocumentFileFallback) {
                        append("; DocumentFile fallback ")
                        append(if (fallbackComplete) "used" else "incomplete")
                    } else {
                        append("; automatic observation fallback disabled")
                    }
                    append(" (never deletion-authoritative)")
                },
            )
        }
        val lyricsByAudioKey = lyricFiles.groupBy { lyricsKey(it.folderPath, it.baseName) }
        val audioKeys = files.mapTo(linkedSetOf()) { entry ->
            lyricsKey(entry.folderPath, entry.name.substringBeforeLast('.').trim())
        }
        val matchedLyrics = lyricFiles.filter { lyricsKey(it.folderPath, it.baseName) in audioKeys }
        DiagnosticLog.event(
            LYRICS_TRACE,
            "folder-index audio=${files.size} sidecars=${lyricFiles.size} " +
                "matched=${matchedLyrics.size} query=$loadedByQuery; " +
                "sidecarEntries=${lyricFiles.take(20).joinToString(" | ") { "${it.folderPath}/${it.baseName} uri=${it.uri}" }}",
        )
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
            val externalLyricsRefs = lyricsByAudioKey[lyricsKey(entry.folderPath, title)].orEmpty()
            val typedExternalLyricsRefs = externalLyricsRefs.toExternalLyricsRefs()
            val externalLyricsUris = typedExternalLyricsRefs.externalLyricsUris()
            if (externalLyricsUris.isNotEmpty()) {
                DiagnosticLog.event(
                    LYRICS_TRACE,
                    "folder-pair audio=${entry.folderPath}/$name sidecars=${externalLyricsUris.size} " +
                        "uris=${externalLyricsUris.joinToString()}",
                )
            }
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
                externalLyricsUris = externalLyricsUris,
                externalLrcUris = typedExternalLyricsRefs.externalLyricsUris("lrc"),
                externalTtmlUris = typedExternalLyricsRefs.externalLyricsUris("ttml"),
                externalLyricsSignature = typedExternalLyricsRefs.externalLyricsSignature(),
            )
        }

        return LoadedFolderFiles(
            drafts = drafts,
            videoCovers = videoCovers,
            discoveryReport = DiscoveryReport.of(discoveryStatus),
        )
    }

    private data class LoadedFolderFiles(
        val drafts: List<TrackDraft>,
        val videoCovers: List<VideoCoverFile>,
        val discoveryReport: DiscoveryReport,
    )

    private data class DiscoveryQueryCounter(
        var directQueryCount: Int = 0,
        var fallbackListingCount: Int = 0,
    )

    private data class SafDocumentRow(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    private class SafProviderQueryLaneBusyException(
        uri: Uri,
    ) : IllegalStateException(
        "AUTO SAF provider query lane busy; prior provider query still running: $uri",
    )

    private class SafAutoQuerySession(
        context: Context,
    ) : Closeable {
        private val resolver = context.contentResolver
        private val executor = autoProviderQueryExecutor

        suspend fun queryChildren(
            uri: Uri,
            projection: Array<String>,
        ): List<SafDocumentRow> = queryOnWorker(uri, projection)

        private suspend fun queryOnWorker(
            uri: Uri,
            projection: Array<String>,
        ): List<SafDocumentRow> = suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val futureRef = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
                futureRef.get()?.cancel(true)
            }
            val future = try {
                executor.submit {
                    try {
                        val rows = resolver.query(
                            uri,
                            projection,
                            null,
                            null,
                            null,
                            cancellationSignal,
                        )?.use { cursor ->
                            FolderScanner.readSafDocumentRows(cursor)
                        } ?: error("Cannot query SAF children: $uri")
                        if (continuation.isActive) {
                            runCatching { continuation.resume(rows) }
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            runCatching { continuation.resumeWithException(error) }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (continuation.isActive) {
                    runCatching {
                        continuation.resumeWithException(
                            SafProviderQueryLaneBusyException(uri),
                        )
                    }
                }
                return@suspendCancellableCoroutine
            }
            futureRef.set(future)
            if (!continuation.isActive) {
                cancellationSignal.cancel()
                future.cancel(true)
            }
        }

        override fun close() {
            // The executor is process-wide by design and has no task queue. Individual in-flight
            // work is cancelled through its Future + CancellationSignal; shutting the lane here
            // would let a later observation create a parallel lane while an uncooperative provider
            // is still running.
        }
    }

    private fun queryChildRowsSync(
        context: Context,
        uri: Uri,
        projection: Array<String>,
    ): List<SafDocumentRow> =
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            readSafDocumentRows(cursor)
        } ?: error("Cannot query SAF children: $uri")

    private fun readSafDocumentRows(
        cursor: android.database.Cursor,
    ): List<SafDocumentRow> {
        val documentIdCol = cursor.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        )
        val nameCol = cursor.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val rows = ArrayList<SafDocumentRow>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(documentIdCol) ?: continue
            val name = cursor.getString(nameCol) ?: continue
            rows += SafDocumentRow(
                documentId = documentId,
                name = name,
                mimeType = cursor.getStringOrEmpty(mimeCol),
                sizeBytes = cursor.getLongOrZero(sizeCol),
                lastModifiedMs = cursor.getLongOrZero(modifiedCol),
            )
        }
        return rows
    }

    private data class AudioFileEntry(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val folderPath: String,
    )

    private data class LyricFileEntry(
        val uri: Uri,
        val folderPath: String,
        val baseName: String,
        val extension: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    private suspend fun collectLibraryFiles(
        context: Context,
        treeUri: Uri,
        documentId: String,
        parentPath: String,
        options: ScanOptions,
        audioOut: MutableList<AudioFileEntry>,
        lyricOut: MutableList<LyricFileEntry>,
        videoOut: MutableList<VideoCoverFile>,
        discoveryQueries: DiscoveryQueryCounter? = null,
        querySession: SafAutoQuerySession? = null,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        discoveryQueries?.let { it.directQueryCount += 1 }
        val rows = if (querySession != null) {
            querySession.queryChildren(childrenUri, projection)
        } else {
            queryChildRowsSync(context, childrenUri, projection)
        }
        for (row in rows) {
            val childDocumentId = row.documentId
            val name = row.name
            val mime = row.mimeType
            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val nextPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                if (!ExcludedScanDirectories.isExcluded(nextPath, options.excludedDirectories)) {
                    collectLibraryFiles(
                        context = context,
                        treeUri = treeUri,
                        documentId = childDocumentId,
                        parentPath = nextPath,
                        options = options,
                        audioOut = audioOut,
                        lyricOut = lyricOut,
                        videoOut = videoOut,
                        discoveryQueries = discoveryQueries,
                        querySession = querySession,
                    )
                }
            } else {
                collectFileEntry(
                    uri = childUri,
                    name = name,
                    mime = mime,
                    size = row.sizeBytes,
                    lastModified = row.lastModifiedMs,
                    folderPath = parentPath,
                    audioOut = audioOut,
                    lyricOut = lyricOut,
                    videoOut = videoOut,
                )
            }
        }
    }

    private fun collectLibraryFilesFallback(
        dir: DocumentFile,
        parentPath: String,
        options: ScanOptions,
        audioOut: MutableList<AudioFileEntry>,
        lyricOut: MutableList<LyricFileEntry>,
        videoOut: MutableList<VideoCoverFile>,
        discoveryQueries: DiscoveryQueryCounter? = null,
    ): Boolean {
        discoveryQueries?.let { it.fallbackListingCount += 1 }
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
        var complete = true
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val nextPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                if (!ExcludedScanDirectories.isExcluded(nextPath, options.excludedDirectories)) {
                    if (!collectLibraryFilesFallback(
                            child,
                            nextPath,
                            options,
                            audioOut,
                            lyricOut,
                            videoOut,
                            discoveryQueries,
                        )
                    ) {
                        complete = false
                    }
                }
            } else if (child.isFile) {
                collectFileEntry(
                    uri = child.uri,
                    name = name,
                    mime = child.type.orEmpty(),
                    size = child.length(),
                    lastModified = child.lastModified(),
                    folderPath = parentPath,
                    audioOut = audioOut,
                    lyricOut = lyricOut,
                    videoOut = videoOut,
                )
            }
        }
        return complete
    }

    private fun collectFileEntry(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        lastModified: Long,
        folderPath: String,
        audioOut: MutableList<AudioFileEntry>,
        lyricOut: MutableList<LyricFileEntry>,
        videoOut: MutableList<VideoCoverFile>,
    ) {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext == "mp4" -> {
                val baseName = name.substringBeforeLast('.')
                if (baseName.isNotBlank()) {
                    videoOut += VideoCoverFile(
                        uri = uri.toString(),
                        folderPath = folderPath,
                        baseName = baseName,
                        sizeBytes = size,
                        lastModifiedMs = lastModified,
                    )
                }
            }
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
            ext == "lrc" || ext == "ttml" -> {
                val baseName = name.substringBeforeLast('.').trim()
                if (baseName.isNotEmpty()) {
                    lyricOut += LyricFileEntry(
                        uri = uri,
                        folderPath = folderPath,
                        baseName = baseName,
                        extension = ext,
                        sizeBytes = size,
                        lastModifiedMs = lastModified,
                    )
                }
            }
        }
    }

    private fun List<LyricFileEntry>.toExternalLyricsRefs(): List<ExternalLyricsRef> =
        map { entry ->
            ExternalLyricsRef(
                uri = entry.uri.toString(),
                sizeBytes = entry.sizeBytes,
                dateModifiedMs = entry.lastModifiedMs,
                extension = entry.extension,
            )
        }

    private fun android.database.Cursor.getStringOrEmpty(columnIndex: Int): String =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""

    private fun android.database.Cursor.getLongOrZero(columnIndex: Int): Long =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L

    private fun lyricsKey(folderPath: String, baseName: String): String =
        "${folderPath.trim('/').lowercase()}\u0001${baseName.trim().lowercase()}"

}

private fun List<ScannedSong>.filterForDuration(options: ScanOptions): List<ScannedSong> =
    if (options.minDurationMs <= 0) this else filter { scanned ->
        scanned.song.durationSec == 0 || scanned.song.durationSec * 1000L >= options.minDurationMs
    }
