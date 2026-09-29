package com.mica.music.ui.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.mica.music.MicaMainActivity
import com.mica.music.data.AppLauncherIcon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherIconControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestAliasesMatchEnumAndOnlyDefaultIsEnabled() {
        AppLauncherIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                ComponentName(context.packageName, icon.aliasClassName),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(icon.name, MicaMainActivity::class.java.name, info.targetActivity)
            assertEquals(icon.name, icon == AppLauncherIcon.DEFAULT, info.enabled)
        }
    }

    @Test
    fun defaultIconIsDawnBeforeAnySwitch() {
        assertEquals(AppLauncherIcon.DAWN, LauncherIconController.current(context))
    }

    @Test
    fun applyLeavesExactlyTheSelectedAliasEnabled() {
        for (target in listOf(AppLauncherIcon.GOLD, AppLauncherIcon.CORAL, AppLauncherIcon.DAWN)) {
            LauncherIconController.apply(context, target)

            assertEquals(target, LauncherIconController.current(context))
            AppLauncherIcon.entries.forEach { icon ->
                val expected = if (icon == target) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                assertEquals(
                    "${target.name} -> ${icon.name}",
                    expected,
                    context.packageManager.getComponentEnabledSetting(
                        ComponentName(context.packageName, icon.aliasClassName),
                    ),
                )
            }
        }
    }
}
