package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MicaSnackbarHostTest {

    @Test
    fun scanSummaryUsesLibraryCompletionStyle() {
        assertEquals(
            MicaSnackbarKind.ScanComplete,
            micaSnackbarKind("扫描完成，新增 12，更新 3"),
        )
        assertEquals(
            "曲库扫描完成" to "新增 12，更新 3",
            micaSnackbarCopy("扫描完成，新增 12，更新 3"),
        )
    }

    @Test
    fun sleepTimerMessagesUseSleepTimerStyle() {
        assertEquals(
            "睡眠定时已生效" to "将在 30 分钟后停止播放",
            micaSnackbarCopy("将在 30 分钟后停止播放"),
        )
        assertEquals(
            "睡眠定时已结束" to "播放已暂停",
            micaSnackbarCopy("睡眠定时已结束，播放已暂停"),
        )
    }

    @Test
    fun failuresUseErrorStyle() {
        assertEquals(
            MicaSnackbarKind.Error,
            micaSnackbarKind("无法分享此歌曲"),
        )
    }
}
