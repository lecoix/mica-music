package com.mica.music.data

import android.content.Context

data class PlayStats(
    val count: Int,
    val lastPlayedAtMs: Long,
    val totalListenSeconds: Long = 0L,
)

/** Immutable play-stat lookup captured for one library preparation pass. */
internal class PlayStatsSnapshot private constructor(
    private val statsBySongId: Map<String, PlayStats>,
) {
    operator fun get(songId: String): PlayStats = statsBySongId[songId] ?: EMPTY

    companion object {
        private val EMPTY = PlayStats(count = 0, lastPlayedAtMs = 0L, totalListenSeconds = 0L)

        fun from(statsBySongId: Map<String, PlayStats>): PlayStatsSnapshot =
            PlayStatsSnapshot(statsBySongId)
    }
}

/**
 * 播放次数与最近播放时间（按 [Song.id]）。
 * 沿用原 `mica_play_counts` 偏好文件，兼容已有播放次数数据。
 */
object PlayHistoryStore {

    private const val PREFS_NAME = "mica_play_counts"
    private const val KEY_RECENT_IDS = "recent_song_ids"
    private const val RECENT_MAX = 500
    private const val LAST_PLAYED_PREFIX = "lp_"
    private const val LISTEN_SECONDS_PREFIX = "listen_sec_"

    fun getStats(context: Context, songId: String): PlayStats {
        val preferences = prefs(context)
        return PlayStats(
            count = preferences.getInt(songId, 0),
            lastPlayedAtMs = preferences.getLong(lastPlayedKey(songId), 0L),
            totalListenSeconds = preferences.getLong(listenSecondsKey(songId), 0L).coerceAtLeast(0L),
        )
    }

    /** Read the preference file once, then expose only the requested songs' non-empty stats. */
    internal fun snapshotStats(context: Context, songIds: Collection<String>): PlayStatsSnapshot {
        val ids = songIds.asSequence().filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return PlayStatsSnapshot.from(emptyMap())
        val values = prefs(context).all
        val stats = HashMap<String, PlayStats>()
        ids.forEach { songId ->
            val count = values.intValue(songId)
            val lastPlayedAtMs = values.longValue(lastPlayedKey(songId))
            val totalListenSeconds = values.longValue(listenSecondsKey(songId)).coerceAtLeast(0L)
            if (count != 0 || lastPlayedAtMs != 0L || totalListenSeconds != 0L) {
                stats[songId] = PlayStats(count, lastPlayedAtMs, totalListenSeconds)
            }
        }
        return PlayStatsSnapshot.from(stats)
    }

    fun recordPlay(context: Context, songId: String): PlayStats {
        val previous = getStats(context, songId)
        val stats = PlayStats(
            count = previous.count + 1,
            lastPlayedAtMs = System.currentTimeMillis(),
            totalListenSeconds = previous.totalListenSeconds,
        )
        val editor = prefs(context).edit()
            .putInt(songId, stats.count)
            .putLong(lastPlayedKey(songId), stats.lastPlayedAtMs)
        editor.putString(KEY_RECENT_IDS, prependRecent(songId, prefs(context)))
        editor.apply()
        return stats
    }

    fun recordListenSeconds(context: Context, songId: String, seconds: Long): PlayStats {
        if (seconds <= 0L) return getStats(context, songId)
        val previous = getStats(context, songId)
        val stats = previous.copy(
            totalListenSeconds = (previous.totalListenSeconds + seconds).coerceAtLeast(0L),
        )
        prefs(context).edit()
            .putLong(listenSecondsKey(songId), stats.totalListenSeconds)
            .apply()
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

    private fun listenSecondsKey(songId: String) = "$LISTEN_SECONDS_PREFIX$songId"

    private fun Map<String, *>.intValue(key: String): Int =
        (this[key] as? Number)?.toInt() ?: 0

    private fun Map<String, *>.longValue(key: String): Long =
        (this[key] as? Number)?.toLong() ?: 0L

    internal fun migrateSongIds(context: Context, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val preferences = prefs(context)
        val editor = preferences.edit()
        mapping.forEach { (oldId, newId) ->
            val oldStats = getStats(context, oldId)
            val newStats = getStats(context, newId)
            if (oldStats.count > 0 || oldStats.lastPlayedAtMs > 0L || oldStats.totalListenSeconds > 0L) {
                editor.putInt(newId, maxOf(oldStats.count, newStats.count))
                editor.putLong(lastPlayedKey(newId), maxOf(oldStats.lastPlayedAtMs, newStats.lastPlayedAtMs))
                editor.putLong(
                    listenSecondsKey(newId),
                    maxOf(oldStats.totalListenSeconds, newStats.totalListenSeconds),
                )
            }
            editor.remove(oldId)
                .remove(lastPlayedKey(oldId))
                .remove(listenSecondsKey(oldId))
        }
        val recent = recentSongIds(context)
            .map { mapping[it] ?: it }
            .distinct()
            .take(RECENT_MAX)
        editor.putString(KEY_RECENT_IDS, recent.joinToString(","))
        editor.commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
