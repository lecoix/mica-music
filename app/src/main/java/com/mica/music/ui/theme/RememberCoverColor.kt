package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.mica.music.data.Song

/**
 * 播放页用：使用曲库扫描阶段已持久化的封面主色，并做舒适度柔化。
 *
 * 切歌时在此重复解码封面做 Palette 取色会与封面/背景加载争抢 I/O，造成可见掉帧。
 */
@Composable
fun rememberCoverColor(
    song: Song,
    @Suppress("UNUSED_PARAMETER")
    sampleArtwork: Boolean = true,
): Color {
    val isDark = MicaTheme.colors.isDark
    return remember(song.coverColorArgb, isDark) {
        PlayerBackgroundBlend.comfortColor(
            Color(song.coverColorArgb),
            isDark,
        )
    }
}
