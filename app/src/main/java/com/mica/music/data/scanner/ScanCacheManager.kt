package com.mica.music.data.scanner

import android.content.Context
import java.io.File

/**
 * 扫描与歌词探测产生的临时缓存（非曲库音频本身）。
 *
 * - [album_art]：深度扫描写入的内嵌封面 JPEG（有意保留，体积较小）
 * - [lyrics_probe] / [lyrics_meta]：FFmpeg 探测用临时文件，应在用后删除；扫描开始前也会整目录清理
 * - [alac_flac] / [alac_stream]：ALAC 播放转码缓存，与扫描无关
 */
internal object ScanCacheManager {

    const val DIR_ALBUM_ART = "album_art"
    const val DIR_LYRICS_PROBE = "lyrics_probe"
    const val DIR_LYRICS_META = "lyrics_meta"

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
