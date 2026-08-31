package com.mica.music.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mica.music.data.SongSource
import com.mica.music.testutil.SongFixtures
import com.mica.music.ui.theme.MicaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongActionMenuSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun remoteMenuKeepsLibraryActionsButHidesLocalFileWrites() {
        val song = SongFixtures.song("remote-menu", "Remote menu").copy(source = SongSource.REMOTE)

        composeRule.setContent {
            MicaTheme {
                SongActionMenuSheet(
                    song = song,
                    onDismiss = {},
                    onAction = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    showLibraryActions = true,
                    showFileActions = false,
                )
            }
        }

        composeRule.onNodeWithText("添加到歌单").assertExists()
        composeRule.onNodeWithText("下一首播放").assertExists()
        composeRule.onNodeWithText("分享").assertExists()
        composeRule.onNodeWithText("歌曲信息").assertExists()
        composeRule.onNodeWithText("使用Lyrico编辑音乐标签").assertDoesNotExist()
        composeRule.onNodeWithText("删除音乐").assertDoesNotExist()
    }
}
