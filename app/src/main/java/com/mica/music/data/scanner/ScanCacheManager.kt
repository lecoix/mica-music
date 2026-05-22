package com.mica.music.data.scanner

import android.content.Context
import com.mica.music.data.Song
import java.io.File

/**
 * 扫描与播放产生的磁盘缓存。
 *
 * - [album_art]：深度扫描内嵌封面；启动时按曲库引用修剪未使用文件
 * - [lyrics_probe] / [lyrics_meta]：FFmpeg 探测临时文件
 * - [alac_stream] / [alac_flac]：播放转码/PCM；冷启动整目录清空
 */
internal object ScanCacheManager {

    const val DIR_ALBUM_ART = "album_art"
    const val DIR_LYRICS_PROBE = "lyrics_probe"
    const val DIR_LYRICS_META = "lyrics_meta"
    const val DIR_ALAC_STREAM = "alac_stream"
    const val DIR_ALAC_FLAC = "alac_flac"

    /** 冷启动：清空播放转码缓存（无进行中的解码会话）。 */
    fun clearPlaybackCache(context: Context) {
        deleteDirContents(File(context.cacheDir, DIR_ALAC_STREAM))
        deleteDirContents(File(context.cacheDir, DIR_ALAC_FLAC))
    }

    /** 冷启动：播放缓存 + 扫描探测临时目录。 */
    fun runStartupCacheCleanup(context: Context) {
        clearPlaybackCache(context)
        clearTransientScanCache(context)
    }

    /** 曲库就绪后：删除 [album_art] 中未被任何曲目引用的 jpg。 */
    fun pruneAlbumArtCache(context: Context, songs: List<Song>) {
        AlbumArtCache.pruneUnreferenced(context, songs)
    }

    /** 删除上次扫描可能残留的 FFmpeg 临时文件。 */
    fun clearTransientScanCache(context: Context) {
        deleteDirContents(File(context.cacheDir, DIR_LYRICS_PROBE))
        deleteDirContents(File(context.cacheDir, DIR_LYRICS_META))
    }

    fun probeTempDir(context: Context): File =
        File(context.cacheDir, DIR_LYRICS_PROBE).also { it.mkdirs() }

    fun metaTempDir(context: Context): File =
        File(context.cacheDir, DIR_LYRICS_META).also { it.mkdirs() }

    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
