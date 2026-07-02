package com.mica.music.data

import android.content.Context

/** 冷启动恢复的播放会话（当前曲 + 进度；不自动续播）。 */
data class PlaybackSession(
    val songId: String,
    val positionMs: Int,
    val shuffleEnabled: Boolean = false,
)

object PlaybackSessionStore {

    private const val PREFS_NAME = "mica_playback_session"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"

    fun save(context: Context, session: PlaybackSession?, sync: Boolean = false) {
        val editor = prefs(context).edit()
        if (session == null || session.songId.isBlank()) {
            if (sync) {
                editor.remove(KEY_SONG_ID).remove(KEY_POSITION_MS).remove(KEY_SHUFFLE_ENABLED).commit()
            } else {
                editor.remove(KEY_SONG_ID).remove(KEY_POSITION_MS).remove(KEY_SHUFFLE_ENABLED).apply()
            }
            return
        }
        editor
            .putString(KEY_SONG_ID, session.songId)
            .putInt(KEY_POSITION_MS, session.positionMs.coerceAtLeast(0))
            .putBoolean(KEY_SHUFFLE_ENABLED, session.shuffleEnabled)
        if (sync) editor.commit() else editor.apply()
    }

    fun load(context: Context): PlaybackSession? {
        val prefs = prefs(context)
        val songId = prefs.getString(KEY_SONG_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return PlaybackSession(
            songId = songId,
            positionMs = prefs.getInt(KEY_POSITION_MS, 0).coerceAtLeast(0),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false),
        )
    }

    fun clear(context: Context) = save(context, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
