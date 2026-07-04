package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.mica.music.data.Song
import java.io.File
import java.security.MessageDigest

/** 深度扫描写入的 `cache/album_art` 内嵌封面文件。 */
internal object AlbumArtCache {

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

    fun digestForKey(cacheKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    fun isCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val file = albumArtFileFromUri(uriString) ?: return false
        val path = file.absolutePath
        return path.isUnderDir(currentAlbumArtDir(context)) ||
            path.isUnderDir(legacyAlbumArtDir(context))
    }

    fun isLegacyCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val file = albumArtFileFromUri(uriString) ?: return false
        return file.absolutePath.isUnderDir(legacyAlbumArtDir(context))
    }

    fun isCurrentCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val file = albumArtFileFromUri(uriString) ?: return false
        return file.absolutePath.isUnderDir(currentAlbumArtDir(context))
    }

    fun isCachedArtReadable(context: Context, uriString: String?): Boolean {
        val uri = uriString ?: return true
        if (!isCachedArtUri(context, uri)) return true
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

    fun digestFromArtUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val name = Uri.parse(uriString).lastPathSegment ?: return null
        return name.removeSuffix(".jpg").takeIf { it.isNotEmpty() }
    }

    /** 删除曲库未引用的封面文件（保留 [songs] 中 `albumArtUri` 仍指向的 jpg）。 */
    fun pruneUnreferenced(context: Context, songs: List<Song>) {
        val keep = buildSet {
            songs.forEach { song ->
                if (isCachedArtUri(context, song.albumArtUri)) {
                    digestFromArtUri(song.albumArtUri)?.let { add(it) }
                }
            }
        }
        listOf(currentAlbumArtDir(context), legacyAlbumArtDir(context)).forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.nameWithoutExtension !in keep) {
                    file.delete()
                }
            }
        }
    }

    private fun currentAlbumArtDir(context: Context): File =
        File(context.noBackupFilesDir, ScanCacheManager.DIR_ALBUM_ART)

    private fun legacyAlbumArtDir(context: Context): File =
        File(context.cacheDir, ScanCacheManager.DIR_ALBUM_ART)

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
