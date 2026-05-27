package com.mica.music.data.scanner

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes
import com.mica.music.data.DsdSupport
import com.mica.music.data.TrackMetadata

/**
 * 用 [MediaExtractor] 读取真实音轨 MIME（比 MediaStore / Retriever 更可靠，尤其 m4a）。
 */
internal object AudioTrackProbe {

    data class Result(
        val trackMime: String?,
        val containerName: String,
        val playbackMimeType: String,
    )

    fun probe(
        context: Context,
        uri: Uri,
        storeMime: String,
        displayName: String?,
    ): Result? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!trackMime.startsWith("audio/")) continue
                val container = containerFromTrackMime(trackMime)
                return Result(
                    trackMime = trackMime,
                    containerName = container,
                    playbackMimeType = playbackMimeFor(trackMime, storeMime, displayName),
                )
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun containerFromTrackMime(trackMime: String): String =
        when {
            trackMime.contains("alac", ignoreCase = true) -> "ALAC"
            trackMime.contains("mp4a", ignoreCase = true) ||
                trackMime.contains("aac", ignoreCase = true) -> "AAC"
            trackMime.contains("flac", ignoreCase = true) -> "FLAC"
            trackMime.contains("mpeg", ignoreCase = true) ||
                trackMime.contains("mp3", ignoreCase = true) -> "MP3"
            trackMime.contains("opus", ignoreCase = true) -> "OGG"
            trackMime.contains("vorbis", ignoreCase = true) -> "OGG"
            trackMime.contains("wav", ignoreCase = true) -> "WAV"
            else -> TrackMetadata.containerFromMime(trackMime)
        }

    /**
     * ExoPlayer 对本地 m4a 需用 **容器** MIME（application/mp4），不能用 audio/mp4a-latm。
     */
    private fun playbackMimeFor(
        trackMime: String,
        storeMime: String,
        displayName: String?,
    ): String {
        val ext = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        // m4a/mp4 容器内的 ALAC 仍按 MP4 解封装；勿用 audio/alac 作为 MediaItem MIME
        return when (ext) {
            "m4a", "m4b", "m4p", "mp4", "aac" -> MimeTypes.APPLICATION_MP4
            "mp3" -> MimeTypes.AUDIO_MPEG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus" -> MimeTypes.AUDIO_OPUS
            "dsf", "dff", "dsdiff" -> DsdSupport.mimeForExtension(ext)
            else -> when {
                DsdSupport.isDsdMime(trackMime) || DsdSupport.isDsdMime(storeMime) ->
                    DsdSupport.mimeForExtension(ext)
                trackMime.contains("mp4a", ignoreCase = true) ||
                    trackMime.contains("aac", ignoreCase = true) ||
                    storeMime.contains("mp4", ignoreCase = true) ||
                    storeMime.contains("m4a", ignoreCase = true) ->
                    MimeTypes.APPLICATION_MP4
                trackMime.contains("flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
                trackMime.contains("mpeg", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
                trackMime.contains("ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
                trackMime.contains("opus", ignoreCase = true) -> MimeTypes.AUDIO_OPUS
                storeMime.startsWith("audio/", ignoreCase = true) -> storeMime
                else -> MimeTypes.APPLICATION_MP4
            }
        }
    }
}
