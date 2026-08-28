package com.mica.music.data

import android.content.Context
import org.json.JSONArray

/** 冷启动恢复的播放会话（当前曲 + 进度；不自动续播）。 */
data class PlaybackSession(
    val songId: String,
    val positionMs: Int,
    val shuffleEnabled: Boolean = false,
    val shuffleSourceIds: List<String> = emptyList(),
    val shuffleSeed: Long? = null,
)

object PlaybackSessionStore {

    private const val PREFS_NAME = "mica_playback_session"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_SHUFFLE_SOURCE_IDS = "shuffle_source_ids"
    private const val KEY_SHUFFLE_SEED = "shuffle_seed"

    fun save(context: Context, session: PlaybackSession?, sync: Boolean = false) {
        val editor = prefs(context).edit()
        if (session == null || session.songId.isBlank()) {
            if (sync) {
                editor
                    .remove(KEY_SONG_ID)
                    .remove(KEY_POSITION_MS)
                    .remove(KEY_SHUFFLE_ENABLED)
                    .remove(KEY_SHUFFLE_SOURCE_IDS)
                    .remove(KEY_SHUFFLE_SEED)
                    .commit()
            } else {
                editor
                    .remove(KEY_SONG_ID)
                    .remove(KEY_POSITION_MS)
                    .remove(KEY_SHUFFLE_ENABLED)
                    .remove(KEY_SHUFFLE_SOURCE_IDS)
                    .remove(KEY_SHUFFLE_SEED)
                    .apply()
            }
            return
        }
        editor
            .putString(KEY_SONG_ID, session.songId)
            .putInt(KEY_POSITION_MS, session.positionMs.coerceAtLeast(0))
            .putBoolean(KEY_SHUFFLE_ENABLED, session.shuffleEnabled)
        val sourceIds = session.shuffleSourceIds.filter { it.isNotBlank() }.distinct()
        if (session.shuffleEnabled && sourceIds.isNotEmpty()) {
            editor.putString(KEY_SHUFFLE_SOURCE_IDS, JSONArray(sourceIds).toString())
            session.shuffleSeed?.let { editor.putLong(KEY_SHUFFLE_SEED, it) }
                ?: editor.remove(KEY_SHUFFLE_SEED)
        } else {
            editor.remove(KEY_SHUFFLE_SOURCE_IDS)
            editor.remove(KEY_SHUFFLE_SEED)
        }
        if (sync) editor.commit() else editor.apply()
    }

    fun load(context: Context): PlaybackSession? {
        val prefs = prefs(context)
        val songId = prefs.getString(KEY_SONG_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return PlaybackSession(
            songId = songId,
            positionMs = prefs.getInt(KEY_POSITION_MS, 0).coerceAtLeast(0),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false),
            shuffleSourceIds = decodeSongIds(prefs.getString(KEY_SHUFFLE_SOURCE_IDS, null)),
            shuffleSeed = prefs.getLong(KEY_SHUFFLE_SEED, 0L).takeIf {
                prefs.contains(KEY_SHUFFLE_SEED)
            },
        )
    }

    fun clear(context: Context) = save(context, null)

    internal fun migrateSongIds(context: Context, mapping: Map<String, String>) {
        val current = load(context) ?: return
        val migrated = current.copy(
            songId = mapping[current.songId] ?: current.songId,
            shuffleSourceIds = current.shuffleSourceIds.map { mapping[it] ?: it },
        )
        if (migrated == current) return
        save(context, migrated, sync = true)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun decodeSongIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }
}
