package com.mica.music.ui.screens.tutorial

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.mica.music.data.preferences.UsageTutorialPreferences
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.components.SongSortChoices
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.settings.SettingsCategoryList
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageTutorialTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun resetTutorial() {
        MicaImageLoaders.init(context)
        context.deleteSharedPreferences("mica_usage_tutorial")
        context.deleteSharedPreferences("mica_settings")
    }

    @Test fun sharedSortChoicesPreserveApplySemantics() {
        var applied: Pair<SongSortField, SortDirection>? = null
        compose.setContent {
            MicaTheme(darkTheme = false) {
                SongSortChoices(SongSortField.CUSTOM, SortDirection.DESC, true, true) { field, direction -> applied = field to direction }
            }
        }
        compose.onNodeWithText("自定义·锁定").performClick()
        assertEquals(SongSortField.CUSTOM to SortDirection.ASC, applied)
        compose.onNodeWithText(SongSortField.TITLE.label).performClick()
        assertEquals(SongSortField.TITLE to SortDirection.DESC, applied)
    }

    @Test fun freshInstallCompletionAndReplayThroughSettings() {
        UsageTutorialPreferences.initialize(context)
        assertFalse(UsageTutorialPreferences.isCompleted(context))
        var scanning by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) {
                    var replay by remember { mutableStateOf(false) }
                    Column { SettingsCategoryList("", {}, { replay = true }) }
                    UsageTutorialScanInvitation(scanInProgress = { scanning })
                    if (replay) UsageTutorialDialog { replay = false }
                }
            }
        }
        compose.onNodeWithText(UsageTip.DRAWER.title).assertDoesNotExist()
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
        compose.runOnIdle { scanning = true }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertIsDisplayed()
        compose.onNodeWithText("是").performClick()
        UsageTip.entries.forEachIndexed { index, tip ->
            compose.onNodeWithText(tip.title).assertIsDisplayed()
            if (index < UsageTip.entries.lastIndex) {
                compose.onNodeWithText("下一步").performClick()
                compose.runOnIdle { assertEquals(index + 1, UsageTutorialPreferences.page(context)) }
            }
        }
        compose.onNodeWithText("开始使用").performClick()
        compose.runOnIdle {
            assertTrue(UsageTutorialPreferences.isCompleted(context))
            UsageTutorialPreferences.savePage(context, 3)
            assertEquals(0, UsageTutorialPreferences.page(context))
            UsageTutorialPreferences.initialize(context)
            assertTrue(UsageTutorialPreferences.isCompleted(context))
        }
        compose.onNodeWithText("重新查看教程").performClick()
        compose.onNodeWithText(UsageTip.DRAWER.title).assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("重新查看教程").assertExists()
        assertEquals(0, UsageTutorialPreferences.page(context))
    }

    @Test fun decliningFirstScanInvitationDoesNotStopScanOrAskAgain() {
        UsageTutorialPreferences.initialize(context)
        var scanning by mutableStateOf(false)
        var mount by mutableStateOf(true)
        compose.setContent {
            MicaTheme(darkTheme = false) {
                if (mount) UsageTutorialScanInvitation(scanInProgress = { scanning })
            }
        }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
        compose.runOnIdle { scanning = true }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("是").getUnclippedBoundsInRoot().left < compose.onNodeWithText("否").getUnclippedBoundsInRoot().left)
        compose.onNode(isDialog()).captureRoboImage("../../../build/reports/usage-tutorial/scan-question.png")
        compose.onNodeWithText("否").performClick()
        assertTrue(scanning)
        assertTrue(UsageTutorialPreferences.isCompleted(context))
        compose.runOnIdle { scanning = false; mount = false }
        compose.runOnIdle { mount = true; scanning = true }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
    }

    @Test fun oldInstallAndExternalOpenDoNotShowScanInvitation() {
        UsageTutorialPreferences.initialize(context)
        var enabled by mutableStateOf(false)
        var scanning by mutableStateOf(true)
        compose.setContent { MicaTheme { UsageTutorialScanInvitation({ scanning }, enabled) } }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
        // Disabled collection must not claim the first opportunity; it remains available on normal entry.
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
        compose.runOnIdle {
            scanning = false
            context.deleteSharedPreferences("mica_usage_tutorial")
            context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE).edit().putBoolean("old", true).commit()
            UsageTutorialPreferences.initialize(context)
        }
        compose.runOnIdle { enabled = true; scanning = true }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertDoesNotExist()
    }

    @Test fun scanInvitationAndAcceptedPageSurviveActivityStateRestoration() {
        UsageTutorialPreferences.initialize(context)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) { UsageTutorialScanInvitation({ true }) }
            }
        }
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("扫描中，是否打开使用技巧？").assertIsDisplayed()
        compose.onNodeWithText("是").performClick()
        compose.onNodeWithText("下一步").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(UsageTip.ZOOM.title).assertIsDisplayed()
        compose.onNodeWithText("跳过").performClick()
        assertFalse(UsageTutorialPreferences.claimScanInvitation(context))
    }

    @Test fun existingInstallationIsNotInterrupted() {
        context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE).edit().putBoolean("existing_setting", true).commit()
        UsageTutorialPreferences.initialize(context)
        assertTrue(UsageTutorialPreferences.isCompleted(context))
    }

    @Test fun interruptedTutorialResumesAndSkipPersists() {
        UsageTutorialPreferences.initialize(context)
        UsageTutorialPreferences.savePage(context, 2)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) {
                    var open by remember { mutableStateOf(true) }
                    if (open) UsageTutorialDialog(firstRun = true) { open = false }
                }
            }
        }
        compose.onNodeWithText(UsageTip.LOCATE.title).assertIsDisplayed()
        compose.onNodeWithText("上一步").performClick()
        compose.onNodeWithText(UsageTip.ZOOM.title).assertIsDisplayed()
        compose.onNodeWithText("跳过").performClick()
        assertTrue(UsageTutorialPreferences.isCompleted(context))
    }

    @Test fun captureAllPagesAtPhoneWidth() = capturePages(dark = false)

    @Test fun captureDarkPages() = capturePages(dark = true)

    @Test fun locateScrollsExistingHighlightedSongIntoView() {
        var time by mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) {
                    Box(Modifier.size(360.dp, 420.dp).micaAppBackground()) { TutorialScene(UsageTip.LOCATE, time) }
                }
            }
        }
        val current = compose.onNodeWithContentDescription("播放 夜航，Mica Sessions")
        current.assertIsSelected().assertIsNotDisplayed()
        compose.runOnIdle { time = .65f }
        current.assertIsSelected()
        compose.runOnIdle { time = 1f }
        current.assertIsSelected().assertIsDisplayed()
        compose.onNodeWithContentDescription("播放 晨光，Mica Sessions").assertIsNotDisplayed()
    }

    @Test fun sortMovesTwoSongsThenLocksWithoutResettingOrder() {
        var time by mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) {
                    Box(Modifier.size(360.dp, 420.dp).micaAppBackground()) { TutorialScene(UsageTip.SORT, time) }
                }
            }
        }
        fun order(): List<Int> = (0..3).sortedBy { compose.onNodeWithTag("sort-tutorial-$it").getUnclippedBoundsInRoot().top.value }
        compose.onAllNodesWithContentDescription("拖动排序").assertCountEquals(0)
        compose.runOnIdle { time = .28f }
        compose.onAllNodesWithContentDescription("拖动排序").assertCountEquals(4)
        assertEquals(listOf(0, 1, 2, 3), order())
        compose.runOnIdle { time = .47f }
        assertEquals(listOf(1, 2, 0, 3), order())
        compose.runOnIdle { time = .65f }
        assertEquals(listOf(1, 3, 2, 0), order())
        compose.runOnIdle { time = .84f }
        compose.onAllNodesWithText("自定义·锁定", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription("拖动排序").assertCountEquals(0)
        compose.runOnIdle { time = 1f }
        assertEquals(listOf(1, 3, 2, 0), order())
        compose.onAllNodesWithContentDescription("拖动排序").assertCountEquals(0)
    }

    @Test fun captureLessonStages() {
        var tip by mutableStateOf(UsageTip.SORT)
        var time by mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) { UsageTutorialIllustration(tip, previewTime = time) }
            }
        }
        val stages = mapOf(
            UsageTip.SORT to listOf(0f, .16f, .28f, .38f, .47f, .56f, .65f, .77f, .84f, 1f),
            UsageTip.LOCATE to listOf(0f, .6f, 1f),
            UsageTip.MENU to listOf(0f, .36f, 1f),
        )
        stages.forEach { (lesson, times) ->
            times.forEachIndexed { index, frame ->
                compose.runOnIdle { tip = lesson; time = frame }
                compose.onNodeWithContentDescription("${lesson.instruction} ${lesson.result}。真实界面组件演示，不需要操作。").assertExists()
                compose.onRoot().captureRoboImage("../../../build/reports/usage-tutorial/stage-${lesson.name.lowercase()}-$index.png")
            }
        }
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeepsNavigationReachable() {
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = false) { UsageTutorialScreen(0, {}, {}) }
            }
        }
        compose.onNodeWithText("下一步").assertIsDisplayed()
        compose.onNodeWithText("跳过").assertIsDisplayed()
    }

    private fun capturePages(dark: Boolean) {
        val preferencesBefore = context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE).all.toMap()
        var page by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(MicaMotion.LocalEnabled provides false) {
                MicaTheme(darkTheme = dark) { UsageTutorialScreen(page, { page = it }, {}) }
            }
        }
        UsageTip.entries.forEachIndexed { index, tip ->
            compose.runOnIdle { page = index }
            compose.onNodeWithText(tip.title).assertIsDisplayed()
            compose.onNodeWithText(if (index == UsageTip.entries.lastIndex) "开始使用" else "下一步").assertIsDisplayed()
            // Review artifacts, not golden images of an unapproved design.
            val directory = File("build/reports/usage-tutorial").apply { mkdirs() }
            // Project Roborazzi strategy resolves paths from app/src/test/snapshots, even absolute Windows paths.
            compose.onRoot().captureRoboImage("../../../build/reports/usage-tutorial/${if (dark) "dark" else "light"}-$index.png")
        }
        assertEquals(preferencesBefore, context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE).all)
        assertTrue(TutorialSongs.all { it.mediaUri.isEmpty() && it.albumArtUri == null })
    }
}
