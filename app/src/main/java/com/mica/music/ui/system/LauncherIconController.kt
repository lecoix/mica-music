package com.mica.music.ui.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.mica.music.data.AppLauncherIcon

/** 桌面图标切换；当前图标以 PackageManager 中 alias 的启用状态为唯一事实来源。 */
object LauncherIconController {

    fun current(context: Context): AppLauncherIcon =
        AppLauncherIcon.entries.firstOrNull { isEnabled(context, it) } ?: AppLauncherIcon.DEFAULT

    /** 先启用目标再停用其余，保证任意时刻至少有一个桌面入口。 */
    fun apply(context: Context, icon: AppLauncherIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, icon),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppLauncherIcon.entries
            .filter { it != icon }
            .forEach {
                pm.setComponentEnabledSetting(
                    component(context, it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private fun isEnabled(context: Context, icon: AppLauncherIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppLauncherIcon.DEFAULT
            else -> false
        }

    private fun component(context: Context, icon: AppLauncherIcon) =
        ComponentName(context.packageName, icon.aliasClassName)
}
