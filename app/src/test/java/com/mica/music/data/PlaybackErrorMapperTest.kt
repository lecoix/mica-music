package com.mica.music.data

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorMapperTest {

    @Test
    fun unknownRuntimeErrorDoesNotExposeRawExceptionText() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "Unexpected runtime error: renderer stack details",
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            ),
            songTitle = "Test Song",
        )

        assertEquals("播放失败", presentation.inlineMessage)
        assertNull(presentation.snackbarMessage)
    }

    @Test
    fun permissionErrorProvidesShortInlineAndActionableSnackbarMessages() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "SecurityException: provider denied access",
                null,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            ),
            songTitle = "Test Song",
        )

        assertEquals("无权读取音频文件", presentation.inlineMessage)
        assertEquals(
            "「Test Song」无法读取，请重新授权音乐目录",
            presentation.snackbarMessage,
        )
    }

    @Test
    fun decoderFailureStaysInlineWithoutSnackbar() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "FFmpeg decoder emitted a long internal error",
                null,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            ),
            songTitle = "Test Song",
        )

        assertEquals("音频解码失败", presentation.inlineMessage)
        assertNull(presentation.snackbarMessage)
    }
    @Test
    fun usbTransportDisconnectIsNotReportedAsFileReadFailure() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "USB DSD write failed: No such device",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
            songTitle = "Test Song",
        )

        assertEquals("USB 音频设备已断开", presentation.inlineMessage)
        assertEquals("USB 音频设备已断开，请重新连接并授权", presentation.snackbarMessage)
    }

    @Test
    fun usbTransportFailureGetsUsbOutputMessage() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "USB DSD session did not become active",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
            songTitle = "Test Song",
        )

        assertEquals("USB 音频输出异常", presentation.inlineMessage)
        assertEquals("USB 音频输出异常，请重新连接设备或重试", presentation.snackbarMessage)
    }

    @Test
    fun exactPcmRejectingDsdGetsActionableModeMessage() {
        val presentation = PlaybackErrorMapper.toPresentation(
            PlaybackException(
                "FfmpegAudioRenderer[DsdOnly] error",
                IllegalStateException("USB Exact PCM accepts only integer PCM16/PCM24/PCM32; encoding=4"),
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            ),
            songTitle = "DSD64",
        )

        assertEquals("USB Exact PCM 不支持当前音频格式", presentation.inlineMessage)
        assertEquals(
            "USB Exact PCM 仅支持整数 PCM16/24/32；DSD 请切换到 DoP、Native DSD，或关闭 USB 独占使用 Android 共享输出",
            presentation.snackbarMessage,
        )
    }

}
