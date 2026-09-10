package com.mica.music.flow

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    private lateinit var qaActivity: MainActivity
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

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

        val application = context.applicationContext as Application
        val resumedActivity = AtomicReference<MainActivity?>()
        val resumedLatch = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    resumedActivity.set(activity)
                    resumedLatch.countDown()
                }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)

        // ActivityScenario uses Instrumentation.startActivitySync(), which can hang on MIUI 13 /
        // Android 12 even though the same explicit QA activity starts normally from shell.
        // Launch through UiAutomation's shell identity while keeping the real activity and Compose UI.
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        val shellOutput = instrumentation.uiAutomation
            .executeShellCommand("am start -W -f 0x10008000 -n $component")
            .let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .use { it.readText() }
            }
        assertTrue("QA MainActivity shell launch failed: $shellOutput", shellOutput.contains("Status: ok"))
        assertTrue(
            "QA MainActivity did not reach RESUMED; shell=$shellOutput",
            resumedLatch.await(10, TimeUnit.SECONDS),
        )
        qaActivity = checkNotNull(resumedActivity.get())
        compose.waitForIdle()
    }

    @After
    fun closeActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (::qaActivity.isInitialized) {
            instrumentation.runOnMainSync {
                if (!qaActivity.isFinishing) qaActivity.finish()
            }
            instrumentation.waitForIdleSync()
        }
        lifecycleCallbacks?.let { callbacks ->
            val application = instrumentation.targetContext.applicationContext as Application
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        lifecycleCallbacks = null
    }

    @Test
    fun settingsSearchRoutesToAudioAndTutorialReplayReturnsToSettings() {
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("浏览设置").assertExists()

        compose.onNodeWithContentDescription("搜索设置").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ReplayGain")
        compose.onNode(hasText("ReplayGain").and(hasSetTextAction().not())).performClick()

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
