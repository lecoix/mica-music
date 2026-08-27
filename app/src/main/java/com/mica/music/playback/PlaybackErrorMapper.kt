package com.mica.music.playback

import androidx.media3.common.PlaybackException

data class PlaybackErrorPresentation(
    val inlineMessage: String,
    val snackbarMessage: String? = null,
)

object PlaybackErrorMapper {

    fun toPresentation(
        error: PlaybackException,
        songTitle: String?,
    ): PlaybackErrorPresentation {
        usbPresentation(error)?.let { return it }
        return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> PlaybackErrorPresentation(
            inlineMessage = "网络不可用或连接超时",
            snackbarMessage = "网络不可用，请检查连接",
        )

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorPresentation(
            inlineMessage = "文件不存在",
            snackbarMessage = withSong(songTitle, "找不到音频文件，请重新扫描曲库"),
        )

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackErrorPresentation(
            inlineMessage = "无权读取音频文件",
            snackbarMessage = withSong(songTitle, "无法读取，请重新授权音乐目录"),
        )

        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> PlaybackErrorPresentation(inlineMessage = "无法读取音频文件")

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> PlaybackErrorPresentation(
            inlineMessage = "文件格式不受支持或已损坏",
            snackbarMessage = withSong(songTitle, "格式不受支持或文件已损坏"),
        )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackErrorPresentation(
            inlineMessage = "当前设备不支持此音频编码",
            snackbarMessage = withSong(songTitle, "当前设备不支持此音频编码"),
        )

        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            PlaybackErrorPresentation(inlineMessage = "音频解码失败")

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> PlaybackErrorPresentation(
            inlineMessage = "音频输出异常",
            snackbarMessage = "音频输出异常，请检查耳机或音频设备",
        )

        PlaybackException.ERROR_CODE_REMOTE_ERROR -> PlaybackErrorPresentation(
            inlineMessage = "播放服务异常",
            snackbarMessage = "播放服务异常，请稍后重试",
        )

        else -> PlaybackErrorPresentation(inlineMessage = "播放失败")
        }
    }

    private fun usbPresentation(error: Throwable): PlaybackErrorPresentation? {
        val messages = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull(Throwable::message)
            .toList()
        if (messages.any { it.contains("USB Exact PCM accepts only integer PCM", ignoreCase = true) }) {
            return PlaybackErrorPresentation(
                inlineMessage = "USB Exact PCM 不支持当前音频格式",
                snackbarMessage = "USB Exact PCM 仅支持整数 PCM16/24/32；DSD 请切换到 DoP、Native DSD，或关闭 USB 独占使用 Android 共享输出",
            )
        }
        val looksUsb = messages.any { message ->
            message.contains("USB", ignoreCase = true) ||
                message.contains("usbfs", ignoreCase = true) ||
                message.contains("SESSION_RETIRED", ignoreCase = true) ||
                message.contains("STALE_SESSION", ignoreCase = true) ||
                message.contains("TARGET_MISSING", ignoreCase = true)
        }
        if (!looksUsb) return null
        val disconnected = messages.any { message ->
            message.contains("No such device", ignoreCase = true) ||
                message.contains("ENODEV", ignoreCase = true) ||
                message.contains("TARGET_MISSING", ignoreCase = true) ||
                message.contains("SESSION_RETIRED", ignoreCase = true) ||
                message.contains("STALE_SESSION", ignoreCase = true)
        }
        return if (disconnected) {
            PlaybackErrorPresentation(
                inlineMessage = "USB 音频设备已断开",
                snackbarMessage = "USB 音频设备已断开，请重新连接并授权",
            )
        } else {
            PlaybackErrorPresentation(
                inlineMessage = "USB 音频输出异常",
                snackbarMessage = "USB 音频输出异常，请重新连接设备或重试",
            )
        }
    }

    private fun withSong(songTitle: String?, message: String): String =
        songTitle?.takeIf(String::isNotBlank)?.let { "「$it」$message" } ?: message
}
