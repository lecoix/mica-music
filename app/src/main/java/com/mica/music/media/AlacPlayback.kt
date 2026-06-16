package com.mica.music.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.mica.music.data.Song
import java.io.File

/**
 * 播放输出统一走 [AlacAudioTrackEngine]（FFmpeg → PCM → AudioTrack）。
 */
object AlacPlayback {

    private const val CACHE_DIR = "alac_flac"

    fun isAlac(song: Song): Boolean =
        song.metadata.containerName == "ALAC" ||
            song.metadata.playbackMimeType.contains("alac", ignoreCase = true)

    /** 全部格式均使用 PCM / AudioTrack 软件解码播放。 */
    @Suppress("UNUSED_PARAMETER")
    fun useStreamPlayback(context: Context): Boolean = true

    fun cachedFlacUri(context: Context, songId: String): String? {
        val file = cachedFlacFile(context, songId)
        return if (file.exists() && file.length() > 0) file.toUri().toString() else null
    }

    fun cachedFlacFile(context: Context, songId: String): File =
        File(context.applicationContext.cacheDir, "$CACHE_DIR/$songId.flac")

    fun transcodeToFlac(context: Context, song: Song): String? {
        val appCtx = context.applicationContext
        cachedFlacUri(appCtx, song.id)?.let { return it }

        val outFile = cachedFlacFile(appCtx, song.id)
        outFile.parentFile?.mkdirs()
        val format = AlacPcmFormat.fromSong(song)
        AlacFfmpegHelper.init(appCtx)
        val tempTarget = File(appCtx.cacheDir, "$CACHE_DIR/${song.id}_in.m4a")
        val input = FfmpegInputResolver.resolveForFfmpeg(
            context = appCtx,
            uri = Uri.parse(song.mediaUri),
            tempFile = tempTarget,
        ) ?: return null
        return try {
            val base = File(outFile.parent, outFile.nameWithoutExtension)
            val result = AlacFfmpegHelper.decodeAlac(
                input.file,
                base,
                format,
                preference = AlacFfmpegHelper.OutputPreference.FLAC_FILE,
            )
            val flac = result?.file?.takeIf { it.extension.equals("flac", true) }
            if (flac != null && flac != outFile) {
                flac.copyTo(outFile, overwrite = true)
                flac.delete()
            }
            if (outFile.exists() && outFile.length() > 0) outFile.toUri().toString() else null
        } finally {
            if (input.deleteOnRelease) {
                input.file.delete()
            }
        }
    }
}
