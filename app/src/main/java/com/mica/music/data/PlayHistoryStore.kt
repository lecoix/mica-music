package com.mica.music.data

import android.content.Context

data class PlayStats(
    val count: Int,
    val lastPlayedAtMs: Long,
)

/**
 * 播放次数与最近播放时间（按 [Song.id]）。
 * 沿用原 `mica_play_counts` 偏好文件，兼容已有播放次数数据。
 */
object PlayHistoryStore {

    private const val PREFS_NAME = "mica_play_counts"
    private const val KEY_RECENT_IDS = "recent_song_ids"
    private const val RECENT_MAX = 500
    private const val LAST_PLAYED_PREFIX = "lp_"

    fun getStats(context: Context, songId: String): PlayStats =
        PlayStats(
            count = prefs(context).getInt(songId, 0),
            lastPlayedAtMs = prefs(context).getLong(lastPlayedKey(songId), 0L),
        )

    fun recordPlay(context: Context, songId: String): PlayStats {
        val previous = getStats(context, songId)
        val stats = PlayStats(
            count = previous.count + 1,
            lastPlayedAtMs = System.currentTimeMillis(),
        )
        val editor = prefs(context).edit()
            .putInt(songId, stats.count)
            .putLong(lastPlayedKey(songId), stats.lastPlayedAtMs)
        editor.putString(KEY_RECENT_IDS, prependRecent(songId, prefs(context)))
        editor.apply()
        return stats
    }

    fun recentSongIds(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT_IDS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun prependRecent(songId: String, prefs: android.content.SharedPreferences): String {
        val current = prefs.getString(KEY_RECENT_IDS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() && it != songId }
            ?: emptyList()
        return (listOf(songId) + current).take(RECENT_MAX).joinToString(",")
    }

    private fun lastPlayedKey(songId: String) = "$LAST_PLAYED_PREFIX$songId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
