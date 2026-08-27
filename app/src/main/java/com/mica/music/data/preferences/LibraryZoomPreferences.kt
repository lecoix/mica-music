package com.mica.music.data.preferences

import android.content.Context

/** Independent list-zoom persistence, mirroring Poweramp's per-page zoom preferences. */
enum class LibraryZoomPage(val storageKey: String) {
    SONGS("songs"),
    SEARCH("search"),
    RECENT("recent"),
    PLAYLIST("playlist"),
    FOLDERS("folders"),
    ALBUM_DETAIL("album_detail"),
    ARTIST_DETAIL("artist_detail"),
}

object LibraryZoomPreferences {
    private const val KEY_PREFIX = "list_zoom_"

    fun presetId(
        context: Context,
        page: LibraryZoomPage,
        defaultId: String,
        validIds: Set<String>,
    ): String {
        val stored = MicaSettingsStore.prefs(context).getString(KEY_PREFIX + page.storageKey, null)
        return stored?.takeIf(validIds::contains) ?: defaultId
    }

    fun setPresetId(
        context: Context,
        page: LibraryZoomPage,
        presetId: String,
        validIds: Set<String>,
    ) {
        if (presetId !in validIds) return
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_PREFIX + page.storageKey, presetId)
            .apply()
    }
}
