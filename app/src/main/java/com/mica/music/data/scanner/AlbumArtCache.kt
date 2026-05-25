package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.mica.music.data.Song
import java.io.File
import java.security.MessageDigest

/** 深度扫描写入的 `cache/album_art` 内嵌封面文件。 */
internal object AlbumArtCache {

    fun fileForKey(context: Context, cacheKey: String): File {
        val digest = digestForKey(cacheKey)
        return File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/$digest.jpg")
    }

    fun digestForKey(cacheKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    fun isCachedArtUri(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val path = Uri.parse(uriString).path ?: return false
        val root = albumArtDir(context).absolutePath
        return path.startsWith(root)
    }

    fun digestFromArtUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val name = Uri.parse(uriString).lastPathSegment ?: return null
        return name.removeSuffix(".jpg").takeIf { it.isNotEmpty() }
    }

    /** 删除曲库未引用的封面文件（保留 [songs] 中 `albumArtUri` 仍指向的 jpg）。 */
    fun pruneUnreferenced(context: Context, songs: List<Song>) {
        val dir = albumArtDir(context)
        if (!dir.exists()) return
        val keep = buildSet {
            songs.forEach { song ->
                if (isCachedArtUri(context, song.albumArtUri)) {
                    digestFromArtUri(song.albumArtUri)?.let { add(it) }
                }
            }
        }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.nameWithoutExtension !in keep) {
                file.delete()
            }
        }
    }

    private fun albumArtDir(context: Context): File =
        File(context.cacheDir, ScanCacheManager.DIR_ALBUM_ART)
}
