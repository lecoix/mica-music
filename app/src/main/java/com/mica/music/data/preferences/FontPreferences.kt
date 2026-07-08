package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.AppFontSelection
import com.mica.music.data.AppFontSource

/** 全局字体与歌词字体偏好。导入文件能力后续接入，当前默认保持系统字体。 */
object FontPreferences {
    private const val KEY_GLOBAL_FONT_SOURCE = "global_font_source"
    private const val KEY_GLOBAL_FONT_NAME = "global_font_name"
    private const val KEY_GLOBAL_FONT_PATH = "global_font_path"

    private const val KEY_LYRIC_FONT_SOURCE = "lyric_font_source"
    private const val KEY_LYRIC_FONT_NAME = "lyric_font_name"
    private const val KEY_LYRIC_FONT_PATH = "lyric_font_path"

    fun globalFont(context: Context): AppFontSelection =
        readFontSelection(context, KEY_GLOBAL_FONT_SOURCE, KEY_GLOBAL_FONT_NAME, KEY_GLOBAL_FONT_PATH)

    fun setGlobalFont(context: Context, selection: AppFontSelection) {
        writeFontSelection(context, selection, KEY_GLOBAL_FONT_SOURCE, KEY_GLOBAL_FONT_NAME, KEY_GLOBAL_FONT_PATH)
    }

    fun lyricFont(context: Context): AppFontSelection =
        readFontSelection(context, KEY_LYRIC_FONT_SOURCE, KEY_LYRIC_FONT_NAME, KEY_LYRIC_FONT_PATH)

    fun setLyricFont(context: Context, selection: AppFontSelection) {
        writeFontSelection(context, selection, KEY_LYRIC_FONT_SOURCE, KEY_LYRIC_FONT_NAME, KEY_LYRIC_FONT_PATH)
    }

    private fun readFontSelection(
        context: Context,
        sourceKey: String,
        nameKey: String,
        pathKey: String,
    ): AppFontSelection {
        val prefs = MicaSettingsStore.prefs(context)
        val source = AppFontSource.fromStorage(prefs.getString(sourceKey, null))
        val name = prefs.getString(nameKey, "") ?: ""
        val path = prefs.getString(pathKey, "") ?: ""
        if (source == AppFontSource.IMPORTED && path.isBlank()) return AppFontSelection.SystemDefault
        return AppFontSelection(source = source, displayName = name, filePath = path)
    }

    private fun writeFontSelection(
        context: Context,
        selection: AppFontSelection,
        sourceKey: String,
        nameKey: String,
        pathKey: String,
    ) {
        MicaSettingsStore.prefs(context).edit()
            .putString(sourceKey, selection.source.storageValue)
            .putString(nameKey, selection.displayName)
            .putString(pathKey, selection.filePath)
            .apply()
    }
}
