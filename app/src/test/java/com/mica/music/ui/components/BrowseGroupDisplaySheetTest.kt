package com.mica.music.ui.components

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.AppUiSettings
import com.mica.music.data.SortDirection
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseGroupDisplaySheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MicaSettingsStore.prefs(context).edit().clear().commit()
    }

    @Test
    fun albumSubtitleChoiceUpdatesRuntimeStateAndPersistence() {
        val uiSettings = AppUiSettings(context)
        composeRule.setContent {
            MicaTheme {
                BrowseGroupDisplaySheet(
                    sortFieldLabels = emptyList(),
                    selectedSortFieldIndex = 0,
                    currentDirection = SortDirection.ASC,
                    currentColumns = 4,
                    onDismiss = {},
                    onSortFieldSelected = {},
                    onDirectionSelected = {},
                    onColumnsSelected = {},
                    uiSettings = uiSettings,
                    isArtist = false,
                )
            }
        }

        composeRule.onNodeWithText("专辑副行").assertExists()
        composeRule.onNodeWithText("艺术家").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertFalse(uiSettings.browseListInfoVisibility.showAlbumSubtitleArtist)
            assertFalse(
                PlaybackUiPreferences.browseListInfoVisibility(context).showAlbumSubtitleArtist,
            )
        }
    }
}
