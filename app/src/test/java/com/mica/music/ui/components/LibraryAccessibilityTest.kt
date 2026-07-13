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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricLine
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.toLyricsDocumentCompat
import com.mica.music.testutil.SongFixtures
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertEquals
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
    fun miniPlayerSwipeUsesConfiguredActions() {
        var previousCount = 0
        var nextCount = 0
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        style = MiniPlayerStyle.FLOATING_ISLAND,
                        song = SongFixtures.song("golden", "Golden Track").copy(albumArtUri = null),
                        isPlaying = false,
                        onPlayPause = {},
                        onPrevious = { previousCount++ },
                        onNext = { nextCount++ },
                        onExpand = {},
                        swipeEnabled = true,
                        leftSwipeAction = MiniPlayerSwipeAction.NEXT,
                        rightSwipeAction = MiniPlayerSwipeAction.PREVIOUS,
                    )
                }
            }
        }

        val miniPlayer = composeRule.onNodeWithTag("MiniPlayer")

        miniPlayer.performTouchInput { swipeLeft() }
        composeRule.runOnIdle {
            assertEquals(0, previousCount)
            assertEquals(1, nextCount)
        }

        miniPlayer.performTouchInput { swipeRight() }
        composeRule.runOnIdle {
            assertEquals(1, previousCount)
            assertEquals(1, nextCount)
        }
    }

    @Test
    fun miniPlayerShowsCurrentLyricWhilePlaying() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        style = MiniPlayerStyle.FLOATING_ISLAND,
                        song = SongFixtures.song("golden", "Golden Track").copy(albumArtUri = null),
                        isPlaying = true,
                        positionMs = 1_000,
                        onPlayPause = {},
                        onNext = {},
                        onExpand = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("line").assertExists()
        composeRule.onNodeWithText("Golden Track - Artist 0").assertExists()
    }

    @Test
    fun miniPlayerLyricsToggleCanHideCurrentLyric() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        style = MiniPlayerStyle.FLOATING_ISLAND,
                        song = SongFixtures.song("golden", "Golden Track").copy(albumArtUri = null),
                        isPlaying = true,
                        positionMs = 1_000,
                        miniPlayerLyricsEnabled = false,
                        onPlayPause = {},
                        onNext = {},
                        onExpand = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("line").assertDoesNotExist()
        composeRule.onNodeWithText("Golden Track").assertExists()
        composeRule.onNodeWithText("Artist 0").assertExists()
    }

    @Test
    fun miniPlayerFallsBackToSongTitleForBlankLyricLine() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        style = MiniPlayerStyle.FLOATING_ISLAND,
                        song = SongFixtures.song("golden", "Golden Track")
                            .copy(
                                albumArtUri = null,
                                lyricsDocument = listOf(LyricLine(0, "   "))
                                    .toLyricsDocumentCompat(),
                            ),
                        isPlaying = true,
                        positionMs = 0,
                        onPlayPause = {},
                        onNext = {},
                        onExpand = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Golden Track").assertExists()
        composeRule.onNodeWithText("Artist 0").assertExists()
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
