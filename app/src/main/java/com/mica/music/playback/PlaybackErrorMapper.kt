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
        isRemote: Boolean = false,
    ): PlaybackErrorPresentation {
        usbPresentation(error)?.let { return it }
        remotePresentation(error, songTitle, isRemote)?.let { return it }
        return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> networkUnavailable()

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

    private fun remotePresentation(
        error: PlaybackException,
        songTitle: String?,
        isRemote: Boolean,
    ): PlaybackErrorPresentation? {
        val messages = causeMessages(error)
        val httpCode = httpResponseCode(messages)
        val remoteTransport = isRemote || httpCode != null || messages.any(::looksRemoteTransport)
        if (httpCode == 401 || httpCode == 403 ||
            messages.any { it.contains("authentication failed", ignoreCase = true) } ||
            (isRemote && error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        ) {
            return PlaybackErrorPresentation(
                inlineMessage = "远程登录失败",
                snackbarMessage = "远程曲库登录失败，请到设置更新登录信息",
            )
        }
        if (messages.any { message ->
                message.contains("Remote playback request is unavailable") ||
                    message.contains("credential is unavailable", ignoreCase = true) ||
                    message.contains("share or configured path is unavailable", ignoreCase = true)
            }
        ) {
            return PlaybackErrorPresentation(
                inlineMessage = "远程来源不可用",
                snackbarMessage = "无法解析该远程歌曲，请检查来源是否已启用",
            )
        }
        if (httpCode == 404 || (isRemote && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)) {
            return PlaybackErrorPresentation(
                inlineMessage = "远程文件不存在",
                snackbarMessage = withSong(songTitle, "在服务器上找不到，请重新同步曲库"),
            )
        }
        if (httpCode != null || (isRemote && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)) {
            return PlaybackErrorPresentation(
                inlineMessage = "远程服务器返回错误",
                snackbarMessage = "远程服务器暂时无法提供音频，请稍后重试",
            )
        }
        if (!remoteTransport) return null
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) {
            return null
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        ) {
            if (messages.any { it.contains("connection failed", ignoreCase = true) }) {
                return networkUnavailable()
            }
            return PlaybackErrorPresentation(
                inlineMessage = "无法读取远程音频",
                snackbarMessage = "无法读取远程音频，请检查网络或来源连接",
            )
        }
        return null
    }

    private fun usbPresentation(error: Throwable): PlaybackErrorPresentation? {
        val messages = causeMessages(error)
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

    private fun networkUnavailable(): PlaybackErrorPresentation = PlaybackErrorPresentation(
        inlineMessage = "网络不可用或连接超时",
        snackbarMessage = "网络不可用，请检查连接",
    )

    private fun looksRemoteTransport(message: String): Boolean =
        message.contains("SMB ", ignoreCase = true) ||
            message.contains("WebDAV", ignoreCase = true) ||
            message.contains("Navidrome", ignoreCase = true) ||
            message.contains("mica-remote", ignoreCase = true) ||
            message.contains("Remote playback", ignoreCase = true)

    private fun httpResponseCode(messages: List<String>): Int? {
        val pattern = Regex("""Response code:\s*(\d+)""")
        for (message in messages) {
            pattern.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun causeMessages(error: Throwable): List<String> =
        generateSequence(error) { it.cause }.mapNotNull(Throwable::message).toList()

    private fun withSong(songTitle: String?, message: String): String =
        songTitle?.takeIf(String::isNotBlank)?.let { "「$it」$message" } ?: message
}
