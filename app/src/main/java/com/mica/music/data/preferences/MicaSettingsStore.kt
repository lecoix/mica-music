package com.mica.music.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * 单一 [SharedPreferences] 文件 `mica_settings` 的内部入口。
 * 分域门面读写仍落同一物理存储，避免用户数据迁移。
 */
internal object MicaSettingsStore {
    private const val PREFS_NAME = "mica_settings"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
