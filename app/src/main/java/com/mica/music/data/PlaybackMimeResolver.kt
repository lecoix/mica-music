package com.mica.music.data

import androidx.media3.common.MimeTypes

/**
 * 为 ExoPlayer 解析播放用 MIME。
 *
 * MediaStore 的 content URI 通常不带 `.m4a` 等后缀，若不在 MediaItem 上显式设置 MIME，
 * ExoPlayer 可能无法识别容器并直接报错（表现为自动「跳过」）。
 */
object PlaybackMimeResolver {
    private const val APE_CONTAINER_MIME = "audio/x-ape"

    fun resolve(
        storeMime: String,
        probeMime: String?,
        displayName: String?,
        mediaUri: String,
        containerName: String? = null,
    ): String {
        val extMime = mimeFromFileName(displayName) ?: mimeFromFileName(mediaUri)
        if (extMime == MimeTypes.APPLICATION_MP4) return MimeTypes.APPLICATION_MP4
        if (extMime == MimeTypes.AUDIO_AAC) return MimeTypes.AUDIO_AAC
        if (extMime == APE_CONTAINER_MIME) return APE_CONTAINER_MIME

        val store = storeMime.lowercase()
        if (store.contains("m4a") || store.contains("mp4")) {
            return MimeTypes.APPLICATION_MP4
        }
        if (store.contains("aac")) return MimeTypes.AUDIO_AAC

        val candidates = listOfNotNull(
            probeMime?.takeIf { isUseful(it) }?.let { normalizePlaybackMime(it, displayName) },
            storeMime.takeIf { isUseful(it) }?.let { normalizePlaybackMime(it, displayName) },
            extMime,
        )
        return candidates.firstOrNull() ?: MimeTypes.APPLICATION_MP4
    }

    private fun isUseful(mime: String): Boolean {
        val m = mime.lowercase()
        return m.isNotBlank() &&
            m != "application/octet-stream" &&
            m != "application/binary" &&
            !m.startsWith("application/vnd.android")
    }

    private fun mimeFromFileName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m4a", "m4b", "m4p", "mp4" -> MimeTypes.APPLICATION_MP4
            "aac" -> MimeTypes.AUDIO_AAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus" -> MimeTypes.AUDIO_OPUS
            "wma" -> "audio/x-ms-wma"
            "alac" -> "audio/alac"
            "ape", "mac" -> APE_CONTAINER_MIME
            "dsf" -> "audio/x-dsf"
            "dff", "dsdiff" -> "audio/x-dsdiff"
            else -> null
        }
    }

    /** m4a 在 ExoPlayer 里应按 MP4 容器解析，audio/mp4 容易走错提取器。 */
    private fun normalizePlaybackMime(mime: String, displayName: String?): String {
        val m = mime.lowercase()
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            ext in setOf("m4a", "m4b", "m4p", "mp4") -> MimeTypes.APPLICATION_MP4
            ext == "aac" -> MimeTypes.AUDIO_AAC
            DsdSupport.isDsdExtension(ext) -> mimeFromFileName(displayName) ?: "audio/dsd"
            DsdSupport.isDsdMime(m) -> m
            ext in setOf("ape", "mac") -> APE_CONTAINER_MIME
            m.contains("m4a") || m == "audio/mp4" || m.contains("mp4a") || m.contains("alac") ->
                MimeTypes.APPLICATION_MP4
            m.contains("aac") -> MimeTypes.AUDIO_AAC
            else -> mime
        }
    }
}
