package com.mica.music.flow

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MainActivity
import com.mica.music.data.preferences.UsageTutorialPreferences
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real MainActivity flow smoke.
 *
 * This deliberately runs only against the side-by-side QA application id so it cannot
 * consume or rewrite the user's ordinary Mica preferences while navigating the real UI.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySettingsFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchQaActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "Device flow tests must use -Pmica.qaSideBySide=true; actual=${context.packageName}",
            context.packageName.endsWith(".qa"),
        )

        UsageTutorialPreferences.complete(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun closeActivity() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun settingsSearchRoutesToAudioAndTutorialReplayReturnsToSettings() {
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("浏览设置").assertExists()

        compose.onNodeWithContentDescription("搜索设置").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ReplayGain")
        compose.onNodeWithText("ReplayGain").performClick()

        compose.onNodeWithText("音频与设备").assertExists()
        compose.onNodeWithText("ReplayGain").assertExists()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("浏览设置").assertExists()

        compose.onNodeWithContentDescription("搜索设置").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("教程")
        compose.onNodeWithText("重新查看教程").performClick()

        compose.onNodeWithText("MICA / 使用技巧").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("浏览设置").assertExists()
    }
}
