package com.mica.music.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 启动时探测安装包属性，供 perf 变体验证（不依赖 [com.mica.music.BuildConfig]，
 * 避免 AGP 8+ 未开启 buildConfig 时自定义 buildType 编译失败）。
 */
object PerfPackageProbe {
    private const val TAG = "PerfPackageProbe"

    fun logPackageFlags(context: Context) {
        val appInfo = context.applicationInfo
        val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        val summary = listOf(
            "debuggable" to debuggable,
            "package" to context.packageName,
            "versionName" to versionName,
        ).joinToString(separator = ", ") { entry: Pair<String, Any> ->
            "${entry.first}=${entry.second}"
        }
        Log.i(TAG, summary)
    }
}
