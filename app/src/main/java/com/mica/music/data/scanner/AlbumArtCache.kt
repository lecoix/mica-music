package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.mica.music.data.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 封面两级缓存：
 * - `cache/album_art_thumb`：扫描与列表消费的紧凑缩略图（<=320px WebP，~10–18 KB/张，96 MiB 预算可常驻 5,000–9,000 张唯一封面）
 * - `cache/album_art`：播放页大图与外部 ContentProvider 按需物化的原图 LRU（48 MiB 预算）
 */
internal object AlbumArtCache {

    const val MAX_CACHE_BYTES = 48L * 1024L * 1024L
    const val MAX_THUMB_CACHE_BYTES = 96L * 1024L * 1024L
    const val THUMB_MAX_EDGE_PX = 320
    private const val THUMB_WEBP_QUALITY = 80
    private const val TRIM_TARGET_NUMERATOR = 3L
    private const val TRIM_TARGET_DENOMINATOR = 4L
    private const val AUTHORITY_SUFFIX = ".artwork"
    private const val PATH_SONG = "song"
    private const val CONTENT_PREFIX = "content_v1_"
    private const val WRITE_LOCK_COUNT = 64
    internal const val PRUNE_GRACE_PERIOD_MS = 5 * 60 * 1000L
    private val writeLocks = Array(WRITE_LOCK_COUNT) { Any() }
    private val thumbWriteLocks = Array(WRITE_LOCK_COUNT) { Any() }
    /**
     * Independent content-addressed writes share the read side. Maintenance takes the write
     * side so check/delete cannot overlap a write or an existing-file open.
     */
    private val artworkAccessLock = ReentrantReadWriteLock()
    private val knownCorruptContentKeys = ConcurrentHashMap.newKeySet<String>()
    private var trackedDirectoryPath: String? = null
    private var trackedFileBytes = mutableMapOf<String, Long>()
    private var trackedTotalBytes = 0L
    private var trackedThumbDirectoryPath: String? = null
    private var trackedThumbFileBytes = mutableMapOf<String, Long>()
    private var trackedThumbTotalBytes = 0L

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
        val contentKey = contentKeyFor(bytes)
        val contentDigest = contentKey.removePrefix(CONTENT_PREFIX)
        val target = File(currentAlbumArtDir(context), "$contentKey.jpg")
        val lockIndex = (target.name.hashCode() and Int.MAX_VALUE) % writeLocks.size

        return withArtworkReadAccess {
            synchronized(writeLocks[lockIndex]) {
                if (
                    target.isFile &&
                        target.length() == bytes.size.toLong() &&
                        sha256Hex(target) == contentDigest
                ) {
                    target.setLastModified(System.currentTimeMillis())
                    clearManagedArtworkFailureState(contentKey)
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
                    clearManagedArtworkFailureState(contentKey)
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
        val contentKey = file.nameWithoutExtension
        runCatching { storeThumbnailFromOriginalBytes(context, contentKey, bytes) }
        return buildManagedArtworkUri(context, songId, contentKey)
    }

    /**
     * 扫描专用写入：仅基于原图字节计算 canonical `contentKey`，并将压缩缩略图写入
     * `cache/album_art_thumb`。不把 1–5 MiB 原图囤进磁盘，避免 800 首即触发 200 MiB 淘汰。
     * 高清原图在播放页大图或外部 Provider 读取时再按需物化进 48 MiB 原图 LRU。
     */
    fun storeManagedArtworkForScan(context: Context, songId: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "Embedded artwork must not be empty" }
        val contentKey = contentKeyFor(bytes)
        runCatching { storeThumbnailFromOriginalBytes(context, contentKey, bytes) }
        clearManagedArtworkFailureState(contentKey)
        return buildManagedArtworkUri(context, songId, contentKey)
    }

    /** 将内嵌原图字节下采样并压缩为 <=320px WebP 缩略图，按 contentKey 去重。 */
    fun storeThumbnailFromOriginalBytes(
        context: Context,
        contentKey: String,
        rawBytes: ByteArray,
    ): File? {
        require(rawBytes.isNotEmpty()) { "Embedded artwork must not be empty" }
        val target = File(currentThumbDir(context), "$contentKey.webp")
        val lockIndex = (target.name.hashCode() and Int.MAX_VALUE) % thumbWriteLocks.size

        val written = withArtworkReadAccess {
            synchronized(thumbWriteLocks[lockIndex]) {
                if (target.isFile && target.length() > 0L) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withArtworkReadAccess target
                }
                val thumbBytes = encodeThumbnailBytes(rawBytes) ?: return@withArtworkReadAccess null
                writeAtomicCacheFile(target, thumbBytes, syncToDisk = false)
            }
        } ?: return null

        accountStoredThumbAndTrim(context, written)
        return written
    }

    /** 已有常驻原图文件时直接下采样生成缩略图，避免再次读音频文件。 */
    fun storeThumbnailFromResidentOriginal(
        context: Context,
        uriString: String?,
    ): File? {
        val managed = parseManagedArtworkUri(context, uriString) ?: return null
        val original = fileForManagedArtwork(context, uriString)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return null
        val target = File(currentThumbDir(context), "${managed.contentKey}.webp")
        val lockIndex = (target.name.hashCode() and Int.MAX_VALUE) % thumbWriteLocks.size

        val written = withArtworkReadAccess {
            synchronized(thumbWriteLocks[lockIndex]) {
                if (target.isFile && target.length() > 0L) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withArtworkReadAccess target
                }
                if (!original.isFile || original.length() <= 0L) return@withArtworkReadAccess null
                val thumbBytes = encodeThumbnailFromFile(original) ?: return@withArtworkReadAccess null
                writeAtomicCacheFile(target, thumbBytes, syncToDisk = false)
            }
        } ?: return null

        accountStoredThumbAndTrim(context, written)
        return written
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

    fun thumbFileForManagedArtwork(context: Context, uriString: String?): File? =
        parseManagedArtworkUri(context, uriString)
            ?.let { managed -> File(currentThumbDir(context), "${managed.contentKey}.webp") }

    /**
     * 返回当前磁盘上可读的 backing file（优先原图，次选缩略图），供华为 OXF-AN10 兼容层与宽高比快查使用。
     */
    fun residentBackingFileForManagedArtwork(context: Context, uriString: String?): File? =
        withArtworkReadAccess {
            val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess null
            if (managed.contentKey in knownCorruptContentKeys) return@withArtworkReadAccess null
            fileForManagedArtwork(context, uriString)
                ?.takeIf { it.isFile && it.length() > 0L }
                ?: thumbFileForManagedArtwork(context, uriString)
                    ?.takeIf { it.isFile && it.length() > 0L }
        }

    fun hasResidentOriginalArtwork(context: Context, uriString: String?): Boolean =
        withArtworkReadAccess {
            val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess false
            if (managed.contentKey in knownCorruptContentKeys) return@withArtworkReadAccess false
            val file = fileForManagedArtwork(context, uriString) ?: return@withArtworkReadAccess false
            file.isFile && file.length() > 0L
        }

    fun hasResidentThumbnailArtwork(context: Context, uriString: String?): Boolean =
        withArtworkReadAccess {
            val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess false
            if (managed.contentKey in knownCorruptContentKeys) return@withArtworkReadAccess false
            val file = thumbFileForManagedArtwork(context, uriString) ?: return@withArtworkReadAccess false
            file.isFile && file.length() > 0L
        }

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

    fun trimThumbToBudget(
        context: Context,
        maxBytes: Long = MAX_THUMB_CACHE_BYTES,
        protectedFile: File? = null,
    ) {
        require(maxBytes >= 0L)
        withArtworkMaintenanceAccess {
            val directory = currentThumbDir(context)
            val files = residentFiles(directory)
            val totalBytes = files.sumOf(File::length)
            if (totalBytes <= maxBytes) return
            val targetBytes = maxBytes * TRIM_TARGET_NUMERATOR / TRIM_TARGET_DENOMINATOR
            evictToTarget(files, totalBytes, targetBytes, protectedFile)
            val remainingFiles = residentFiles(directory)
            resetTrackedThumbState(directory, remainingFiles, remainingFiles.sumOf(File::length))
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

    private fun accountStoredThumbAndTrim(context: Context, storedFile: File) {
        withArtworkMaintenanceAccess {
            val directory = currentThumbDir(context)
            ensureTrackedThumbState(directory)
            val previousBytes = trackedThumbFileBytes.put(storedFile.absolutePath, storedFile.length()) ?: 0L
            trackedThumbTotalBytes += storedFile.length() - previousBytes
            if (trackedThumbTotalBytes <= MAX_THUMB_CACHE_BYTES) return
            evictToTarget(
                files = residentFiles(directory),
                totalBytes = trackedThumbTotalBytes,
                targetBytes = MAX_THUMB_CACHE_BYTES * TRIM_TARGET_NUMERATOR / TRIM_TARGET_DENOMINATOR,
                protectedFile = storedFile,
            )
            val remainingFiles = residentFiles(directory)
            resetTrackedThumbState(directory, remainingFiles, remainingFiles.sumOf(File::length))
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

    private fun ensureTrackedThumbState(directory: File) {
        if (trackedThumbDirectoryPath == directory.absolutePath) return
        val files = residentFiles(directory)
        resetTrackedThumbState(directory, files, files.sumOf(File::length))
    }

    private fun resetTrackedThumbState(directory: File, files: List<File>, totalBytes: Long) {
        trackedThumbDirectoryPath = directory.absolutePath
        trackedThumbFileBytes = files.associate { it.absolutePath to it.length() }.toMutableMap()
        trackedThumbTotalBytes = totalBytes
    }

    private fun writeAtomicCacheFile(target: File, bytes: ByteArray, syncToDisk: Boolean): File {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                if (syncToDisk) output.fd.sync()
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
            return target
        } finally {
            temporary.delete()
        }
    }

    internal fun encodeThumbnailBytes(rawBytes: ByteArray, maxEdgePx: Int = THUMB_MAX_EDGE_PX): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
        return compressBitmapToThumbnail(
            decoded = decoded,
            rawSizeBytes = rawBytes.size,
            rawMaxEdgePx = maxOf(bounds.outWidth, bounds.outHeight),
            rawBytesIfSmaller = rawBytes,
            maxEdgePx = maxEdgePx,
        )
    }

    private fun encodeThumbnailFromFile(file: File, maxEdgePx: Int = THUMB_MAX_EDGE_PX): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        return compressBitmapToThumbnail(
            decoded = decoded,
            rawSizeBytes = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rawMaxEdgePx = maxOf(bounds.outWidth, bounds.outHeight),
            rawBytesIfSmaller = null,
            maxEdgePx = maxEdgePx,
        )
    }

    private fun compressBitmapToThumbnail(
        decoded: Bitmap,
        rawSizeBytes: Int,
        rawMaxEdgePx: Int,
        rawBytesIfSmaller: ByteArray?,
        maxEdgePx: Int,
    ): ByteArray? {
        var scaled: Bitmap? = null
        try {
            val maxDim = maxOf(decoded.width, decoded.height)
            val targetBitmap = if (maxDim > maxEdgePx) {
                val scale = maxEdgePx.toFloat() / maxDim.toFloat()
                val w = (decoded.width * scale).toInt().coerceAtLeast(1)
                val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it !== decoded) scaled = it
                }
            } else {
                decoded
            }
            val output = ByteArrayOutputStream(12 * 1024)
            if (!targetBitmap.compress(thumbnailCompressFormat(), THUMB_WEBP_QUALITY, output)) {
                return null
            }
            val encoded = output.toByteArray()
            if (encoded.isEmpty()) return null
            return if (
                rawBytesIfSmaller != null &&
                rawMaxEdgePx <= maxEdgePx &&
                rawSizeBytes <= encoded.size
            ) {
                rawBytesIfSmaller
            } else {
                encoded
            }
        } finally {
            scaled?.recycle()
            decoded.recycle()
        }
    }

    private fun thumbnailCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdgePx || height / (sample * 2) >= maxEdgePx) {
            sample *= 2
        }
        return sample
    }

    private fun residentFiles(directory: File): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && (it.extension == "jpg" || it.extension == "webp") }
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

    fun contentKeyFor(bytes: ByteArray): String = "$CONTENT_PREFIX${sha256Hex(bytes)}"

    fun digestForKey(cacheKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
        val managed = parseManagedArtworkUri(context, uri)
        if (managed != null) {
            if (managed.contentKey in knownCorruptContentKeys) return false
            val file = fileForManagedArtwork(context, uri) ?: return true
            // Managed artwork is evictable: a missing resident file can be rematerialized lazily.
            // Normal health/reuse checks deliberately avoid hashing the full file. Strong content
            // verification is reserved for an actual image-load failure.
            return !file.exists() || (file.isFile && file.length() > 0L)
        }
        val file = albumArtFileFromUri(uri) ?: return false
        return file.isFile && file.length() > 0L
    }

    fun managedArtworkFileIsValid(context: Context, uriString: String?): Boolean =
        withArtworkReadAccess {
            val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess false
            val file = fileForManagedArtwork(context, uriString) ?: return@withArtworkReadAccess false
            if (!file.isFile || file.length() <= 0L) return@withArtworkReadAccess false
            runCatching {
                sha256Hex(file) == managed.contentKey.removePrefix(CONTENT_PREFIX)
            }.getOrDefault(false)
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

    /**
     * Opens an existing managed file while maintenance is excluded from the open.
     *
     * This is intentionally metadata-only. Hashing every resident cover on each open duplicated
     * hundreds of MiB of storage reads during startup/list binding. If Coil later fails to decode
     * the file, [ManagedArtworkRecovery] performs one strong verification for that content key.
     */
    fun openExistingManagedArtwork(
        context: Context,
        uriString: String?,
    ): ParcelFileDescriptor? = withArtworkReadAccess {
        val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess null
        if (managed.contentKey in knownCorruptContentKeys) return@withArtworkReadAccess null
        val file = fileForManagedArtwork(context, uriString)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return@withArtworkReadAccess null
        file.setLastModified(System.currentTimeMillis())
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            .getOrNull()
    }

    /**
     * 在读锁保护下打开已常驻的紧凑缩略图 FD，避免与后台 prune/trim 竞态删除产生 TOCTOU 失败。
     */
    fun openExistingThumbnailArtwork(
        context: Context,
        uriString: String?,
    ): ParcelFileDescriptor? = withArtworkReadAccess {
        val managed = parseManagedArtworkUri(context, uriString) ?: return@withArtworkReadAccess null
        if (managed.contentKey in knownCorruptContentKeys) return@withArtworkReadAccess null
        val file = thumbFileForManagedArtwork(context, uriString)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return@withArtworkReadAccess null
        file.setLastModified(System.currentTimeMillis())
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            .getOrNull()
    }

    fun isThumbnailFileDecodable(context: Context, uriString: String?): Boolean =
        withArtworkReadAccess {
            val file = thumbFileForManagedArtwork(context, uriString)
                ?.takeIf { it.isFile && it.length() > 0L }
                ?: return@withArtworkReadAccess false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            }.getOrDefault(false)
        }

    internal fun deleteManagedArtworkFiles(context: Context, contentKey: String) {
        withArtworkMaintenanceAccess {
            val origDir = currentAlbumArtDir(context)
            val thumbDir = currentThumbDir(context)
            File(origDir, "$contentKey.jpg").delete()
            File(thumbDir, "$contentKey.webp").delete()
            File(thumbDir, "$contentKey.jpg").delete()
            val origFiles = residentFiles(origDir)
            resetTrackedState(origDir, origFiles, origFiles.sumOf(File::length))
            val thumbFiles = residentFiles(thumbDir)
            resetTrackedThumbState(thumbDir, thumbFiles, thumbFiles.sumOf(File::length))
        }
    }

    internal fun markManagedArtworkCorrupt(contentKey: String) {
        knownCorruptContentKeys += contentKey
    }

    internal fun clearManagedArtworkFailureState(contentKey: String) {
        knownCorruptContentKeys -= contentKey
    }

    fun digestFromArtUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val name = Uri.parse(uriString).lastPathSegment ?: return null
        return name.removeSuffix(".jpg").takeIf { it.isNotEmpty() }
    }

    /** 删除曲库未引用的封面文件（保留 [songs] 中 `albumArtUri` 仍指向的原图与缩略图）。 */
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
            for (dir in listOf(
                currentAlbumArtDir(context),
                currentThumbDir(context),
                legacyAlbumArtDir(context),
            )) {
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
            val directory = currentAlbumArtDir(context)
            val files = residentFiles(directory)
            resetTrackedState(directory, files, files.sumOf(File::length))
            val thumbDirectory = currentThumbDir(context)
            val thumbFiles = residentFiles(thumbDirectory)
            resetTrackedThumbState(thumbDirectory, thumbFiles, thumbFiles.sumOf(File::length))
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

    private fun currentThumbDir(context: Context): File =
        File(context.cacheDir, ScanCacheManager.DIR_ALBUM_ART_THUMB)

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
