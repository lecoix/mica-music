package com.mica.music.data

import android.content.Context

/** 收藏歌曲 ID 集合。 */
object FavoriteStore {

    private const val PREFS_NAME = "mica_favorites"
    private const val KEY_IDS = "favorite_ids"

    fun isFavorite(context: Context, songId: String): Boolean =
        getIds(context).contains(songId)

    fun getIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    fun remove(context: Context, songId: String) {
        val ids = getIds(context).toMutableSet()
        if (ids.remove(songId)) {
            prefs(context).edit().putStringSet(KEY_IDS, ids).apply()
        }
    }

    /** @return 切换后的收藏状态 */
    fun toggle(context: Context, songId: String): Boolean {
        val ids = getIds(context).toMutableSet()
        val nowFavorite = if (songId in ids) {
            ids.remove(songId)
            false
        } else {
            ids.add(songId)
            true
        }
        prefs(context).edit().putStringSet(KEY_IDS, ids).apply()
        return nowFavorite
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
