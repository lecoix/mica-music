package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.data.Song
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock

/** 深度扫描写入的 `cache/album_art` 内嵌封面文件。 */
internal object AlbumArtCache {

    const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
    private const val TRIM_TARGET_NUMERATOR = 3L
    private const val TRIM_TARGET_DENOMINATOR = 4L
    private const val AUTHORITY_SUFFIX = ".artwork"
    private const val PATH_SONG = "song"
    private const val CONTENT_PREFIX = "content_v1_"
    private const val WRITE_LOCK_COUNT = 64
    internal const val PRUNE_GRACE_PERIOD_MS = 5 * 60 * 1000L
    private val writeLocks = Array(WRITE_LOCK_COUNT) { Any() }
    /**
     * Independent content-addressed writes share the read side. Maintenance takes the write
     * side so check/delete cannot overlap a write or an existing-file open.
     */
    private val artworkAccessLock = ReentrantReadWriteLock()
    private var trackedDirectoryPath: String? = null
    private var trackedFileBytes = mutableMapOf<String, Long>()
    private var trackedTotalBytes = 0L

    data class ManagedArtwork(
        val songId: String,
        val contentKey: String,
    )

    data class Health(
        val songs: Int,
        val albumArtUris: Int,
        val cachedArtUris: Int,
        val missingCachedArtUris: Int,
        val missingSamples: List<String>,
        val currentCachedArtUris: Int = 0,
        val legacyCachedArtUris: Int = 0,
    ) {
        val needsRepair: Boolean
            get() = missingCachedArtUris > 0 || legacyCachedArtUris > 0

        fun toLogMessage(): String =
            "songs=$songs albumArtUris=$albumArtUris cachedArtUris=$cachedArtUris " +
                "currentCachedArtUris=$currentCachedArtUris legacyCachedArtUris=$legacyCachedArtUris " +
                "missingCachedArtUris=$missingCachedArtUris " +
                "missingSamples=${missingSamples.joinToString(limit = 8)}"
    }

    fun fileForKey(context: Context, cacheKey: String): File {
        val digest = digestForKey(cacheKey)
        return File(currentAlbumArtDir(context), "$digest.jpg")
    }

    /** Stores exact embedded-art bytes once, shared by every track with the same content. */
    fun storeEmbeddedPicture(context: Context, bytes: ByteArray): File {
        require(bytes.isNotEmpty()) { "Embedded artwork must not be empty" }
        val contentDigest = sha256Hex(bytes)
        val target = File(currentAlbumArtDir(context), "$CONTENT_PREFIX$contentDigest.jpg")
        val lockIndex = (target.name.hashCode() and Int.MAX_VALUE) % writeLocks.size

        return withArtworkReadAccess {
            synchronized(writeLocks[lockIndex]) {
                if (target.isFile && target.length() == bytes.size.toLong()) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withArtworkReadAccess target
                }

                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.part")
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    if (target.exists() && !target.delete()) {
                        error("Unable to replace cached album art: ${target.absolutePath}")
                    }
                    check(temporary.renameTo(target)) {
                        "Unable to publish cached album art: ${target.absolutePath}"
                    }
                    check(target.isFile && target.length() == bytes.size.toLong()) {
                        "Cached album art was not published completely: ${target.absolutePath}"
                    }
                    return@withArtworkReadAccess target
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    fun storeManagedArtwork(context: Context, songId: String, bytes: ByteArray): String {
        val file = storeEmbeddedPicture(context, bytes)
        accountStoredFileAndTrim(context, file)
        return buildManagedArtworkUri(context, songId, file.nameWithoutExtension)
    }

    fun buildManagedArtworkUri(context: Context, songId: String, contentKey: String): String =
        Uri.Builder()
            .scheme("content")
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(PATH_SONG)
            .appendPath(songId)
            .appendPath(contentKey)
            .build()
            .toString()

    fun parseManagedArtworkUri(context: Context, uriString: String?): ManagedArtwork? {
        if (uriString.isNullOrBlank()) return null
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content" || uri.authority != context.packageName + AUTHORITY_SUFFIX) return null
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != PATH_SONG) return null
        val contentKey = segments[2]
        if (!contentKey.startsWith(CONTENT_PREFIX) ||
            contentKey.removePrefix(CONTENT_PREFIX).length != 64
        ) {
            return null
        }
        return ManagedArtwork(songId = segments[1], contentKey = contentKey)
    }

    fun fileForManagedArtwork(context: Context, uriString: String?): File? =
        parseManagedArtworkUri(context, uriString)
            ?.let { managed -> File(currentAlbumArtDir(context), "${managed.contentKey}.jpg") }

    fun trimToBudget(
        context: Context,
        maxBytes: Long = MAX_CACHE_BYTES,
        protectedFile: File? = null,
    ) {
        require(maxBytes >= 0L)
        withArtworkMaintenanceAccess {
            val directory = currentAlbumArtDir(context)
            val files = residentFiles(directory)
            val totalBytes = files.sumOf(File::length)
            if (totalBytes <= maxBytes) return
            val targetBytes = maxBytes * TRIM_TARGET_NUMERATOR / TRIM_TARGET_DENOMINATOR
            evictToTarget(files, totalBytes, targetBytes, protectedFile)
            val remainingFiles = residentFiles(directory)
            resetTrackedState(directory, remainingFiles, remainingFiles.sumOf(File::length))
        }
    }

    private fun accountStoredFileAndTrim(context: Context, storedFile: File) {
        withArtworkMaintenanceAccess {
            val directory = currentAlbumArtDir(context)
            ensureTrackedState(directory)
            val previousBytes = trackedFileBytes.put(storedFile.absolutePath, storedFile.length()) ?: 0L
            trackedTotalBytes += storedFile.length() - previousBytes
            if (trackedTotalBytes <= MAX_CACHE_BYTES) return
            evictToTarget(
                files = residentFiles(directory),
                totalBytes = trackedTotalBytes,
                targetBytes = MAX_CACHE_BYTES * TRIM_TARGET_NUMERATOR / TRIM_TARGET_DENOMINATOR,
                protectedFile = storedFile,
            )
            val remainingFiles = residentFiles(directory)
            resetTrackedState(directory, remainingFiles, remainingFiles.sumOf(File::length))
        }
    }

    private fun ensureTrackedState(directory: File) {
        if (trackedDirectoryPath == directory.absolutePath) return
        val files = residentFiles(directory)
        resetTrackedState(directory, files, files.sumOf(File::length))
    }

    private fun resetTrackedState(directory: File, files: List<File>, totalBytes: Long) {
        trackedDirectoryPath = directory.absolutePath
        trackedFileBytes = files.associate { it.absolutePath to it.length() }.toMutableMap()
        trackedTotalBytes = totalBytes
    }

    private fun residentFiles(directory: File): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }
            .orEmpty()

    private fun evictToTarget(
        files: List<File>,
        totalBytes: Long,
        targetBytes: Long,
        protectedFile: File?,
    ): Long {
        var remainingBytes = totalBytes
        files
            .map { file -> Triple(file, file.lastModified(), file.absolutePath) }
            .sortedWith(compareBy<Triple<File, Long, String>> { it.second }.thenBy { it.third })
            .forEach { (file) ->
                if (remainingBytes <= targetBytes) return remainingBytes
                if (file.absolutePath == protectedFile?.absolutePath) return@forEach
                val bytes = file.length()
                if (file.delete()) remainingBytes -= bytes
            }
        return remainingBytes
    }

    fun digestForKey(cacheKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun isCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        if (parseManagedArtworkUri(context, uriString) != null) return true
        val file = albumArtFileFromUri(uriString) ?: return false
        val path = file.absolutePath
        return path.isUnderDir(currentAlbumArtDir(context)) ||
            path.isUnderDir(legacyAlbumArtDir(context))
    }

    fun isLegacyCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        if (parseManagedArtworkUri(context, uriString) != null) return false
        val file = albumArtFileFromUri(uriString) ?: return false
        return file.absolutePath.isUnderDir(currentAlbumArtDir(context)) ||
            file.absolutePath.isUnderDir(legacyAlbumArtDir(context))
    }

    fun isCurrentCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return parseManagedArtworkUri(context, uriString) != null
    }

    fun isCachedArtReadable(context: Context, uriString: String?): Boolean {
        val uri = uriString ?: return true
        if (!isCachedArtUri(context, uri)) return true
        if (parseManagedArtworkUri(context, uri) != null) return true
        val file = albumArtFileFromUri(uri) ?: return false
        return file.isFile && file.length() > 0L
    }

    fun hasReadableCachedArt(context: Context, song: Song): Boolean =
        isCachedArtReadable(context, song.albumArtUri)

    fun health(context: Context, songs: List<Song>): Health {
        val missing = mutableListOf<String>()
        var albumArtUris = 0
        var cachedArtUris = 0
        var currentCachedArtUris = 0
        var legacyCachedArtUris = 0
        songs.forEach { song ->
            val uri = song.albumArtUri
            if (uri.isNullOrBlank()) return@forEach
            albumArtUris++
            if (isCachedArtUri(context, uri)) {
                cachedArtUris++
                if (isCurrentCachedArtUri(context, uri)) currentCachedArtUris++
                if (isLegacyCachedArtUri(context, uri)) legacyCachedArtUris++
                if (!isCachedArtReadable(context, uri)) {
                    val name = albumArtFileFromUri(uri)?.name
                        ?: Uri.parse(uri).lastPathSegment.orEmpty()
                    missing += "${song.id}:$name"
                }
            }
        }
        return Health(
            songs = songs.size,
            albumArtUris = albumArtUris,
            cachedArtUris = cachedArtUris,
            missingCachedArtUris = missing.size,
            missingSamples = missing.take(8),
            currentCachedArtUris = currentCachedArtUris,
            legacyCachedArtUris = legacyCachedArtUris,
        )
    }

    /** Opens an existing managed file while maintenance is excluded from the open. */
    fun openExistingManagedArtwork(
        context: Context,
        uriString: String?,
    ): ParcelFileDescriptor? = withArtworkReadAccess {
        val file = fileForManagedArtwork(context, uriString)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return@withArtworkReadAccess null
        file.setLastModified(System.currentTimeMillis())
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun digestFromArtUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val name = Uri.parse(uriString).lastPathSegment ?: return null
        return name.removeSuffix(".jpg").takeIf { it.isNotEmpty() }
    }

    /** 删除曲库未引用的封面文件（保留 [songs] 中 `albumArtUri` 仍指向的 jpg）。 */
    fun pruneUnreferenced(
        context: Context,
        songs: List<Song>,
        minimumAgeMs: Long = PRUNE_GRACE_PERIOD_MS,
        nowMs: () -> Long = System::currentTimeMillis,
        beforeDelete: (File) -> Unit = {},
    ) {
        require(minimumAgeMs >= 0L)
        val keep = HashSet<String>(songs.size)
        for (song in songs) {
            if (isCachedArtUri(context, song.albumArtUri)) {
                digestFromArtUri(song.albumArtUri)?.let(keep::add)
            }
        }

        val cutoffMs = nowMs() - minimumAgeMs
        withArtworkMaintenanceAccess {
            for (dir in listOf(currentAlbumArtDir(context), legacyAlbumArtDir(context))) {
                if (!dir.exists()) continue
                for (file in dir.listFiles().orEmpty()) {
                    if (!file.isFile) continue
                    if (file.lastModified() > cutoffMs) continue
                    if (file.nameWithoutExtension !in keep) {
                        beforeDelete(file)
                        file.delete()
                    }
                }
            }
        }
    }

    private inline fun <T> withArtworkReadAccess(block: () -> T): T {
        val lock = artworkAccessLock.readLock()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> withArtworkMaintenanceAccess(block: () -> T): T {
        val lock = artworkAccessLock.writeLock()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun currentAlbumArtDir(context: Context): File =
        File(context.cacheDir, ScanCacheManager.DIR_ALBUM_ART)

    private fun legacyAlbumArtDir(context: Context): File =
        File(context.noBackupFilesDir, ScanCacheManager.DIR_ALBUM_ART)

    private fun albumArtFileFromUri(uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return null
        return runCatching { File(java.net.URI(uriString)) }
            .getOrElse { uri.path?.let(::File) }
    }

    private fun String.isUnderDir(dir: File): Boolean {
        val root = dir.absolutePath.trimEnd(File.separatorChar, '/', '\\')
        return this == root || startsWith("$root${File.separator}")
    }
}
