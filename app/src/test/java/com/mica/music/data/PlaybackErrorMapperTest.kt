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
}
