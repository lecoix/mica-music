package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.testutil.SongFixtures
import com.mica.music.ui.theme.MicaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun miniPlayerExposesExpandAndPlaybackActionsWithMinimumTargets() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        style = MiniPlayerStyle.FLOATING_ISLAND,
                        song = SongFixtures.song("golden", "Golden Track").copy(albumArtUri = null),
                        isPlaying = false,
                        onPlayPause = {},
                        onNext = {},
                        onExpand = {},
                    )
                }
            }
        }

        composeRule.onNode(hasContentDescription("展开播放器：Golden Track") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Button))
        composeRule.onNode(hasContentDescription("播放") and hasClickAction())
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun currentSongRowIsSelectedClickableAndAtLeastFortyEightDpHigh() {
        composeRule.setContent {
            MicaTheme {
                SongRow(
                    song = SongFixtures.song("current", "Current Track"),
                    isCurrent = true,
                    isPlaying = false,
                    onClick = {},
                )
            }
        }

        composeRule.onNode(
            hasContentDescription("播放 Current Track，Artist 0") and hasClickAction(),
        )
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Button))
    }
}
