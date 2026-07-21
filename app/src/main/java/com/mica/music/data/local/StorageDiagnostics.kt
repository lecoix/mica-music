package com.mica.music.data.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mica.music.data.Song
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.data.scanner.ScanCacheManager
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class StoragePayloadStats(
    val label: String,
    val rows: Long,
    val payloadBytes: Long,
)

internal data class AlbumArtFileObservation(
    val lengthBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val contentDigest: String?,
)

internal data class AlbumArtStorageStats(
    val fileCount: Int,
    val totalFileBytes: Long,
    val averageFileBytes: Long,
    val p50FileBytes: Long,
    val p95FileBytes: Long,
    val maxFileBytes: Long,
    val readableImageFiles: Int,
    val unknownDimensionFiles: Int,
    val p50LongestEdgePx: Int,
    val p95LongestEdgePx: Int,
    val maxLongestEdgePx: Int,
    val hashedFiles: Int,
    val hashFailures: Int,
    val duplicateGroups: Int,
    val redundantFiles: Int,
    val deduplicatableBytes: Long,
) {
    companion object {
        val Empty = AlbumArtStorageStats(
            fileCount = 0,
            totalFileBytes = 0,
            averageFileBytes = 0,
            p50FileBytes = 0,
            p95FileBytes = 0,
            maxFileBytes = 0,
            readableImageFiles = 0,
            unknownDimensionFiles = 0,
            p50LongestEdgePx = 0,
            p95LongestEdgePx = 0,
            maxLongestEdgePx = 0,
            hashedFiles = 0,
            hashFailures = 0,
            duplicateGroups = 0,
            redundantFiles = 0,
            deduplicatableBytes = 0,
        )
    }
}

internal data class StorageDiagnosticsSnapshot(
    val privateDataBytes: Long,
    val cacheBytes: Long,
    val filesBytes: Long,
    val noBackupBytes: Long,
    val albumArtBytes: Long,
    val albumArtStats: AlbumArtStorageStats,
    val legacyAlbumArtBytes: Long,
    val databaseFilesBytes: Long,
    val databaseMainBytes: Long,
    val databaseWalBytes: Long,
    val databaseShmBytes: Long,
    val databaseAllocatedBytes: Long,
    val databaseFreeBytes: Long,
    val songCount: Long,
    val lyricsBySlot: List<StoragePayloadStats>,
    val legacyLyrics: StoragePayloadStats,
    val pendingLyrics: StoragePayloadStats,
) {
    fun toReportText(): String = buildString {
        appendLine("Storage diagnostics:")
        appendLine("Private app data: ${formatStorageBytes(privateDataBytes)}")
        appendLine("Cache: ${formatStorageBytes(cacheBytes)}")
        appendLine("Files: ${formatStorageBytes(filesBytes)}")
        appendLine("No-backup files: ${formatStorageBytes(noBackupBytes)}")
        appendLine("Album art: ${formatStorageBytes(albumArtBytes)}")
        appendLine("Album art budget: ${formatStorageBytes(AlbumArtCache.MAX_CACHE_BYTES)}")
        appendLine("Album art files: ${albumArtStats.fileCount}")
        appendLine(
            "  size: avg=${formatStorageBytes(albumArtStats.averageFileBytes)} " +
                "p50=${formatStorageBytes(albumArtStats.p50FileBytes)} " +
                "p95=${formatStorageBytes(albumArtStats.p95FileBytes)} " +
                "max=${formatStorageBytes(albumArtStats.maxFileBytes)}",
        )
        appendLine(
            "  long edge: readable=${albumArtStats.readableImageFiles} " +
                "unknown=${albumArtStats.unknownDimensionFiles} " +
                "p50=${albumArtStats.p50LongestEdgePx}px " +
                "p95=${albumArtStats.p95LongestEdgePx}px " +
                "max=${albumArtStats.maxLongestEdgePx}px",
        )
        appendLine(
            "  exact duplicates: groups=${albumArtStats.duplicateGroups} " +
                "redundantFiles=${albumArtStats.redundantFiles} " +
                "reclaimable=${formatStorageBytes(albumArtStats.deduplicatableBytes)}",
        )
        appendLine(
            "  hashing: hashed=${albumArtStats.hashedFiles} failed=${albumArtStats.hashFailures}",
        )
        appendLine("Legacy album art cache: ${formatStorageBytes(legacyAlbumArtBytes)}")
        appendLine("Database files: ${formatStorageBytes(databaseFilesBytes)}")
        appendLine("  main: ${formatStorageBytes(databaseMainBytes)}")
        appendLine("  WAL: ${formatStorageBytes(databaseWalBytes)}")
        appendLine("  SHM: ${formatStorageBytes(databaseShmBytes)}")
        appendLine("SQLite allocated pages: ${formatStorageBytes(databaseAllocatedBytes)}")
        appendLine("SQLite free pages: ${formatStorageBytes(databaseFreeBytes)}")
        appendLine("Songs: $songCount")
        appendLine("Lyrics payloads:")
        lyricsBySlot.sortedBy(StoragePayloadStats::label).forEach { stats ->
            appendLine("  ${stats.label}: rows=${stats.rows} payload=${formatStorageBytes(stats.payloadBytes)}")
        }
        appendLine(
            "  ${legacyLyrics.label}: rows=${legacyLyrics.rows} " +
                "payload=${formatStorageBytes(legacyLyrics.payloadBytes)}",
        )
        appendLine(
            "  ${pendingLyrics.label}: rows=${pendingLyrics.rows} " +
                "payload=${formatStorageBytes(pendingLyrics.payloadBytes)}",
        )
    }.trimEnd()
}

internal object StorageDiagnostics {
    suspend fun verifyAlbumArtOnDemandRecovery(context: Context, songs: List<Song>): String =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val candidate = songs.asSequence()
                .mapNotNull { song ->
                    val managed = AlbumArtCache.parseManagedArtworkUri(appContext, song.albumArtUri)
                        ?: return@mapNotNull null
                    val resident = AlbumArtCache.fileForManagedArtwork(appContext, song.albumArtUri)
                        ?.takeIf { it.isFile && it.length() > 0L }
                        ?: return@mapNotNull null
                    Triple(song, managed, resident)
                }
                .firstOrNull()
                ?: return@withContext "Album art recovery: skipped (no resident managed artwork)"

            val (song, managed, resident) = candidate
            val beforeBytes = resident.length()
            if (!resident.delete()) {
                return@withContext "Album art recovery: failed to evict ${resident.name}"
            }
            val digest = runCatching {
                appContext.contentResolver.openInputStream(Uri.parse(song.albumArtUri))?.use { input ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) hash.update(buffer, 0, read)
                    }
                    hash.digest().joinToString("") { "%02x".format(it) }
                }
            }.getOrNull()
            val expected = managed.contentKey.removePrefix("content_v1_")
            val restored = resident.isFile && resident.length() > 0L
            val matches = digest == expected
            "Album art recovery: song=${song.id} evicted=${formatStorageBytes(beforeBytes)} " +
                "restored=$restored hashMatches=$matches"
        }

    suspend fun collect(context: Context): StorageDiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val albumArtDir = File(appContext.cacheDir, ScanCacheManager.DIR_ALBUM_ART)
        val albumArtStats = inspectAlbumArtDirectory(albumArtDir)
        val database = MicaDatabase.get(appContext).openHelper.readableDatabase
        val databaseFile = appContext.getDatabasePath(MicaDatabase.DATABASE_NAME)
        val databaseParent = databaseFile.parentFile
        val databaseFiles = databaseParent
            ?.listFiles { file -> file.name.startsWith(MicaDatabase.DATABASE_NAME) }
            .orEmpty()

        val pageSize = database.scalarLong("PRAGMA page_size")
        val pageCount = database.scalarLong("PRAGMA page_count")
        val freePages = database.scalarLong("PRAGMA freelist_count")

        StorageDiagnosticsSnapshot(
            privateDataBytes = directoryBytes(File(appContext.applicationInfo.dataDir)),
            cacheBytes = directoryBytes(appContext.cacheDir),
            filesBytes = directoryBytes(appContext.filesDir),
            noBackupBytes = directoryBytes(appContext.noBackupFilesDir),
            albumArtBytes = albumArtStats.totalFileBytes,
            albumArtStats = albumArtStats,
            legacyAlbumArtBytes = directoryBytes(
                File(appContext.noBackupFilesDir, ScanCacheManager.DIR_ALBUM_ART),
            ),
            databaseFilesBytes = databaseFiles.sumOf(File::length),
            databaseMainBytes = databaseFile.length(),
            databaseWalBytes = File(databaseFile.path + "-wal").length(),
            databaseShmBytes = File(databaseFile.path + "-shm").length(),
            databaseAllocatedBytes = saturatedMultiply(pageSize, pageCount),
            databaseFreeBytes = saturatedMultiply(pageSize, freePages),
            songCount = database.scalarLong("SELECT COUNT(*) FROM songs"),
            lyricsBySlot = database.lyricsSlotStats(),
            legacyLyrics = database.payloadStats(
                label = "songs.lyricsJson",
                sql = """
                    SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(lyricsJson AS BLOB))), 0)
                    FROM songs
                    WHERE lyricsJson <> '' AND lyricsJson <> '[]'
                """.trimIndent(),
            ),
            pendingLyrics = database.payloadStats(
                label = "song_lyrics_pending",
                sql = """
                    SELECT COUNT(*), COALESCE(SUM(
                        COALESCE(LENGTH(CAST(embeddedJson AS BLOB)), 0) +
                        COALESCE(LENGTH(CAST(externalLrcJson AS BLOB)), 0) +
                        COALESCE(LENGTH(CAST(externalTtmlJson AS BLOB)), 0)
                    ), 0)
                    FROM song_lyrics_pending
                """.trimIndent(),
            ),
        )
    }
}

internal fun summarizeAlbumArtObservations(
    observations: List<AlbumArtFileObservation>,
    hashFailures: Int,
): AlbumArtStorageStats {
    if (observations.isEmpty()) return AlbumArtStorageStats.Empty.copy(hashFailures = hashFailures)

    val sizes = observations.map { it.lengthBytes.coerceAtLeast(0) }.sorted()
    val longestEdges = observations.mapNotNull { observation ->
        val width = observation.widthPx?.takeIf { it > 0 } ?: return@mapNotNull null
        val height = observation.heightPx?.takeIf { it > 0 } ?: return@mapNotNull null
        maxOf(width, height)
    }.sorted()
    val duplicateGroups = observations
        .filter { it.contentDigest != null }
        .groupBy { "${it.lengthBytes}:${it.contentDigest}" }
        .values
        .filter { it.size > 1 }
    val totalBytes = sizes.fold(0L, ::saturatedAdd)
    val deduplicatableBytes = duplicateGroups.fold(0L) { total, group ->
        group.drop(1).fold(total) { groupTotal, observation ->
            saturatedAdd(groupTotal, observation.lengthBytes.coerceAtLeast(0))
        }
    }

    return AlbumArtStorageStats(
        fileCount = observations.size,
        totalFileBytes = totalBytes,
        averageFileBytes = totalBytes / observations.size,
        p50FileBytes = sizes.nearestRankPercentile(50),
        p95FileBytes = sizes.nearestRankPercentile(95),
        maxFileBytes = sizes.lastOrNull() ?: 0,
        readableImageFiles = longestEdges.size,
        unknownDimensionFiles = observations.size - longestEdges.size,
        p50LongestEdgePx = longestEdges.nearestRankPercentile(50),
        p95LongestEdgePx = longestEdges.nearestRankPercentile(95),
        maxLongestEdgePx = longestEdges.lastOrNull() ?: 0,
        hashedFiles = observations.count { it.contentDigest != null },
        hashFailures = hashFailures,
        duplicateGroups = duplicateGroups.size,
        redundantFiles = duplicateGroups.sumOf { it.size - 1 },
        deduplicatableBytes = deduplicatableBytes,
    )
}

private suspend fun inspectAlbumArtDirectory(root: File): AlbumArtStorageStats {
    if (!root.exists()) return AlbumArtStorageStats.Empty
    val observations = mutableListOf<AlbumArtFileObservation>()
    var hashFailures = 0
    forEachFile(root) { file ->
        currentCoroutineContext().ensureActive()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
        val digest = runCatching { file.sha256() }
            .onFailure { hashFailures++ }
            .getOrNull()
        observations += AlbumArtFileObservation(
            lengthBytes = file.length(),
            widthPx = options.outWidth.takeIf { it > 0 },
            heightPx = options.outHeight.takeIf { it > 0 },
            contentDigest = digest,
        )
    }
    return summarizeAlbumArtObservations(observations, hashFailures)
}

private suspend fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private suspend inline fun forEachFile(root: File, crossinline action: suspend (File) -> Unit) {
    val pending = ArrayDeque<File>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val file = pending.removeLast()
        if (file.isDirectory) {
            file.listFiles()?.forEach(pending::add)
        } else if (file.isFile) {
            action(file)
        }
    }
}

private fun List<Long>.nearestRankPercentile(percent: Int): Long {
    if (isEmpty()) return 0
    val rank = ((size * percent) + 99) / 100
    return this[(rank - 1).coerceIn(indices)]
}

private fun List<Int>.nearestRankPercentile(percent: Int): Int {
    if (isEmpty()) return 0
    val rank = ((size * percent) + 99) / 100
    return this[(rank - 1).coerceIn(indices)]
}

private fun SupportSQLiteDatabase.lyricsSlotStats(): List<StoragePayloadStats> =
    query(
        """
            SELECT slot, COUNT(*), COALESCE(SUM(LENGTH(CAST(lyricsJson AS BLOB))), 0)
            FROM song_lyrics
            GROUP BY slot
        """.trimIndent(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    StoragePayloadStats(
                        label = cursor.getString(0),
                        rows = cursor.getLong(1),
                        payloadBytes = cursor.getLong(2),
                    ),
                )
            }
        }
    }

private fun SupportSQLiteDatabase.payloadStats(label: String, sql: String): StoragePayloadStats =
    query(sql).use { cursor ->
        if (cursor.moveToFirst()) {
            StoragePayloadStats(label, cursor.getLong(0), cursor.getLong(1))
        } else {
            StoragePayloadStats(label, 0, 0)
        }
    }

private fun SupportSQLiteDatabase.scalarLong(sql: String): Long = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else 0
}

private fun directoryBytes(root: File): Long {
    if (!root.exists()) return 0
    var total = 0L
    val pending = ArrayDeque<File>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val file = pending.removeLast()
        if (file.isDirectory) {
            file.listFiles()?.forEach(pending::add)
        } else if (file.isFile) {
            total = saturatedAdd(total, file.length())
        }
    }
    return total
}

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left <= 0 || right <= 0) 0 else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun formatStorageBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    if (safeBytes < 1024) return "$safeBytes B"
    val kib = safeBytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.2f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.2f MiB", mib)
    return String.format(Locale.US, "%.2f GiB", mib / 1024.0)
}
