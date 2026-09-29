package com.mica.music.ui.screens.settings

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.mica.music.audio.loudness.LoudnessScanPort
import com.mica.music.audio.loudness.LoudnessScanState
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.preferences.PreferencesTestFixtures
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.usb.UsbHybridDiagnosticsPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual settings screen, real preference owners and Compose navigation/scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class SettingsOrganizationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var library: MusicLibrary
    private lateinit var settings: AppUiSettings
    private val loudness = mockk<LoudnessScanPort> {
        every { state } returns MutableStateFlow(LoudnessScanState())
    }
    private val usb = object : UsbHybridDiagnosticsPort {
        override fun buildReport() = "test"
    }

    @Before fun prepare() {
        PreferencesTestFixtures.clearMicaSettings(compose.activity)
        library = MusicLibrary(compose.activity)
        settings = AppUiSettings(compose.activity)
    }

    @After fun release() {
        library.release()
        unmockkStatic(Toast::class)
    }

    private fun open(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MicaTheme(darkTheme = false) {
                SettingsScreen(
                    library, settings, loudness, usb,
                    onBack = {}, onOpenMetadataDebug = {}, onOpenSpatialAudio = {}, onOpenSoundFx = {},
                )
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun search(query: String, result: String) {
        compose.onNodeWithContentDescription("搜索设置").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.onNode(hasText(result) and !hasSetTextAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun back() { compose.onNodeWithContentDescription("返回").performClick() }

    @Test fun appearanceContainsTwoEntriesAndMiniLyricsPersistAfterReturning() {
        open()
        compose.onNodeWithText("外观").performClick()
        compose.onNodeWithText("壁纸").assertIsDisplayed()
        compose.onNodeWithTag("settings:appearance.wallpaper-overlay").assertDoesNotExist()
        compose.onNodeWithTag("settings:appearance.mini-player-lyrics").assertDoesNotExist()
        compose.onRoot().captureRoboImage(
            "build/reports/settings-organization/appearance.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        compose.onNodeWithText("壁纸").performClick()
        compose.onNodeWithText("自定义壁纸").assertIsDisplayed()
        back()
        compose.onNodeWithText("迷你播放栏").performScrollTo().performClick()
        compose.onNodeWithText("逐字").performClick()
        compose.onRoot().captureRoboImage(
            "build/reports/settings-organization/mini-player.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        back()
        compose.onNodeWithText("迷你播放栏").performScrollTo().performClick()
        compose.runOnIdle {
            val restored = AppUiSettings(compose.activity)
            assertTrue(restored.miniPlayerLyricsEnabled)
            assertTrue(restored.miniPlayerWordLyricsEnabled)
        }
    }

    @Test fun searchOpensInfoRowAndPreservesQueryAndSavedText() {
        settings.updatePlayerInfoVisibility(settings.playerInfoVisibility.copy(showCustomText = false))
        open()
        search("自定义文字", "信息行：自定义文字")
        compose.onNodeWithTag("settings:playback.info-custom-text").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithTag("settings:playback.info-custom-text").performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Mica test")
        compose.runOnIdle {
            val restored = AppUiSettings(compose.activity).playerInfoVisibility
            assertTrue(restored.showCustomText)
            assertEquals("Mica test", restored.customText)
        }
        back()
        compose.onNode(hasSetTextAction()).assertTextEquals("自定义文字")
        compose.onNodeWithText("信息行：自定义文字").assertIsDisplayed()
    }

    @Test fun playbackInfoEntryOpensDetailsAndReturnsToParent() {
        open()
        compose.onNodeWithText("播放页").performClick()
        compose.onNodeWithTag("settings:playback.info-format").assertDoesNotExist()
        compose.onNodeWithText("信息行").performScrollTo().performClick()
        compose.onNodeWithTag("settings:playback.info-format").assertIsDisplayed()
        back()
        compose.onNodeWithText("播放页").assertIsDisplayed()
        compose.onNodeWithTag("settings:playback.info-format").assertDoesNotExist()
    }

    @Test fun lyriconSearchScrollsToActualToggleAndPersists() {
        settings.updateLyriconLyricsEnabled(false)
        open()
        search("Lyricon", "词幕歌词")
        compose.onNodeWithTag("settings:lyrics.lyricon").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(AppUiSettings(compose.activity).lyriconLyricsEnabled) }
    }

    @Test fun missingWallpaperShowsPrerequisiteWithoutChangingPreferences() {
        open()
        search("壁纸模糊度", "壁纸模糊度")
        compose.onNodeWithText("壁纸模糊度：请先选择自定义壁纸", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings:appearance.wallpaper-blur").assertDoesNotExist()
        compose.onNodeWithText("自定义壁纸").assertIsDisplayed()
        compose.runOnIdle { assertNull(settings.customWallpaperPath) }
        back()
        compose.onNode(hasSetTextAction()).assertTextEquals("壁纸模糊度")
    }

    @Test fun wallpaperPickerCallbackCropAndApplyPersistThroughNewEntry() {
        // Compose 1.7's unconfined test effect context can resume after real IO on
        // the worker. Stub only Toast (requires a Looper); picker dispatch, import,
        // crop UI, commit, file and preference assertions remain real.
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        val image = File(compose.activity.cacheDir, "settings-wallpaper.png")
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        open()
        compose.onNodeWithText("外观").performClick()
        compose.onNodeWithText("壁纸").performClick()
        compose.onNodeWithText("自定义壁纸").performClick()
        compose.runOnUiThread {
            val request = shadowOf(compose.activity).nextStartedActivityForResult
            assertNotNull(request)
            shadowOf(compose.activity).receiveResult(
                request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(image)),
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("应用").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertNull(settings.customWallpaperPath) }
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(10_000) { settings.customWallpaperPath != null }
        compose.runOnIdle {
            val restored = AppUiSettings(compose.activity)
            assertEquals(settings.customWallpaperPath, restored.customWallpaperPath)
            assertTrue(File(requireNotNull(restored.customWallpaperPath)).exists())
        }
        compose.onRoot().captureRoboImage(
            "build/reports/settings-organization/wallpaper.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        back()
        compose.onNodeWithText("壁纸").assertIsDisplayed()
    }

    @Test fun hiddenThemeOptionDoesNotSwitchThemeAndBackReturnsToSearch() {
        settings.updatePlayerCoverFlowMode(PlayerCoverFlowMode.PARTICLE_COVER)
        open()
        search("音乐 MV", "音乐 MV")
        compose.onNodeWithText("音乐 MV：", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings:playback.music-video").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PlayerCoverFlowMode.PARTICLE_COVER, settings.playerCoverFlowMode) }
        back()
        compose.onNode(hasSetTextAction()).assertTextEquals("音乐 MV")
    }

    @Test fun infoSearchDestinationSurvivesStateRestorationAndSystemBack() {
        val restoration = StateRestorationTester(compose)
        open(restoration)
        search("Hi-Res 标志", "Hi-Res 标志")
        compose.onNodeWithTag("settings:playback.hires-badge").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings:playback.hires-badge").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            "build/reports/settings-organization/info-search.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertTextEquals("Hi-Res 标志")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeSearchBringsInfoTargetIntoShortViewport() {
        open()
        search("Hi-Res 标志", "Hi-Res 标志")
        compose.onNodeWithTag("settings:playback.hires-badge").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            "build/reports/settings-organization/info-search-landscape.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }
}
