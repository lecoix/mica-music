package com.mica.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 用户保存的均衡器自定义预设。 */
data class EqCustomProfile(
    val name: String,
    val levels: List<Short>,
)

/**
 * 均衡器选项：
 * - [System] 系统预设（索引对应 [android.media.audiofx.Equalizer]）
 * - [Draft] 未命名自定义（频段存 [AppPreferences.equalizerBandLevels]）
 * - [Saved] 已保存的自定义配置
 */
sealed class EqSelection {
    data class System(val index: Int) : EqSelection()
    data object Draft : EqSelection()
    data class Saved(val name: String) : EqSelection()
}

object EqCustomProfileStore {

    private const val PREFS_NAME = "mica_eq_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_SELECTION = "selection"

    fun getSelection(context: Context): EqSelection {
        val raw = prefs(context).getString(KEY_SELECTION, null)
        if (raw != null) return parseSelection(raw)
        // 迁移旧版仅 preset index 的存储
        val legacy = AppPreferences.equalizerPresetIndex(context)
        return when {
            legacy == AppPreferences.EQ_PRESET_CUSTOM -> EqSelection.Draft
            legacy >= 0 -> EqSelection.System(legacy)
            else -> EqSelection.System(0)
        }
    }

    fun setSelection(context: Context, selection: EqSelection) {
        prefs(context).edit().putString(KEY_SELECTION, encodeSelection(selection)).apply()
        when (selection) {
            is EqSelection.System -> AppPreferences.setEqualizerPresetIndex(context, selection.index)
            EqSelection.Draft -> AppPreferences.setEqualizerPresetIndex(context, AppPreferences.EQ_PRESET_CUSTOM)
            is EqSelection.Saved -> AppPreferences.setEqualizerPresetIndex(context, AppPreferences.EQ_PRESET_CUSTOM)
        }
    }

    fun listProfiles(context: Context): List<EqCustomProfile> {
        val json = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    val levels = obj.getJSONArray("levels").let { arr ->
                        (0 until arr.length()).map { arr.getInt(it).toShort() }
                    }
                    add(EqCustomProfile(name, levels))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfile(context: Context, profile: EqCustomProfile) {
        val list = listProfiles(context).toMutableList()
        list.removeAll { it.name == profile.name }
        list.add(profile)
        persistProfiles(context, list)
        setSelection(context, EqSelection.Saved(profile.name))
    }

    fun deleteProfile(context: Context, name: String) {
        val list = listProfiles(context).filterNot { it.name == name }
        persistProfiles(context, list)
        if (getSelection(context) is EqSelection.Saved && (getSelection(context) as EqSelection.Saved).name == name) {
            setSelection(context, EqSelection.Draft)
        }
    }

    fun findProfile(context: Context, name: String): EqCustomProfile? =
        listProfiles(context).firstOrNull { it.name == name }

    private fun persistProfiles(context: Context, profiles: List<EqCustomProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("name", profile.name)
                    put("levels", JSONArray(profile.levels.map { it.toInt() }))
                },
            )
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun encodeSelection(selection: EqSelection): String = when (selection) {
        is EqSelection.System -> "system:${selection.index}"
        EqSelection.Draft -> "draft"
        is EqSelection.Saved -> "saved:${selection.name}"
    }

    private fun parseSelection(raw: String): EqSelection = when {
        raw == "draft" -> EqSelection.Draft
        raw.startsWith("system:") -> EqSelection.System(raw.removePrefix("system:").toIntOrNull() ?: 0)
        raw.startsWith("saved:") -> EqSelection.Saved(raw.removePrefix("saved:"))
        else -> EqSelection.System(0)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
