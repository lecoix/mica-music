package com.mica.music.media

import android.content.Context
import androidx.media3.common.Player
import com.mica.music.data.PlaybackTuning
import org.json.JSONArray
import org.json.JSONObject

data class ServicePlaybackSnapshot(
    val queueSongIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val playWhenReady: Boolean,
    val qualityMode: AudioQualityMode,
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
    val queueRevision: Long = 0L,
    val currentSongId: String = queueSongIds.getOrNull(currentIndex).orEmpty(),
)

internal data class ServiceQueueSnapshot(
    val songIds: List<String>,
    val revision: Long,
)

internal data class ServicePlaybackCursor(
    val currentSongId: String,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val playWhenReady: Boolean,
    val qualityMode: AudioQualityMode,
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
    val queueRevision: Long,
)

internal data class ServicePlaybackRestore(
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
)

internal object ServicePlaybackRestoreResolver {
    fun resolve(
        snapshot: ServicePlaybackSnapshot,
        availableSongIds: List<String>,
    ): ServicePlaybackRestore? {
        if (availableSongIds.isEmpty()) return null
        val savedSongId = snapshot.queueSongIds.getOrNull(snapshot.currentIndex)
        val restoredIndex = savedSongId
            ?.let(availableSongIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: return null
        return ServicePlaybackRestore(
            currentIndex = restoredIndex,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            repeatMode = snapshot.repeatMode.takeIf {
                it == Player.REPEAT_MODE_OFF ||
                    it == Player.REPEAT_MODE_ONE ||
                    it == Player.REPEAT_MODE_ALL
            } ?: Player.REPEAT_MODE_OFF,
            shuffleEnabled = snapshot.shuffleEnabled,
            playbackTuning = snapshot.playbackTuning,
        )
    }
}

class ServicePlaybackStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(snapshot: ServicePlaybackSnapshot, sync: Boolean = false) {
        saveQueue(
            ServiceQueueSnapshot(snapshot.queueSongIds, snapshot.queueRevision),
            sync,
        )
        saveCursor(
            ServicePlaybackCursor(
                currentSongId = snapshot.currentSongId.ifBlank {
                    snapshot.queueSongIds.getOrNull(snapshot.currentIndex).orEmpty()
                },
                positionMs = snapshot.positionMs,
                repeatMode = snapshot.repeatMode,
                shuffleEnabled = snapshot.shuffleEnabled,
                playWhenReady = snapshot.playWhenReady,
                qualityMode = snapshot.qualityMode,
                playbackTuning = snapshot.playbackTuning,
                queueRevision = snapshot.queueRevision,
            ),
            sync,
        )
    }

    internal fun saveQueue(snapshot: ServiceQueueSnapshot, sync: Boolean = false) {
        val queue = JSONArray()
        snapshot.songIds.filter(String::isNotBlank).forEach(queue::put)
        val json = JSONObject()
            .put(KEY_QUEUE, queue)
            .put(KEY_QUEUE_REVISION, snapshot.revision)
        val editor = prefs.edit().putString(KEY_QUEUE_SNAPSHOT, json.toString())
        if (sync) editor.commit() else editor.apply()
        clearLegacy()
    }

    internal fun saveCursor(cursor: ServicePlaybackCursor, sync: Boolean = false) {
        val json = JSONObject()
            .put(KEY_CURRENT_SONG_ID, cursor.currentSongId)
            .put(KEY_POSITION_MS, cursor.positionMs.coerceAtLeast(0L))
            .put(KEY_REPEAT_MODE, cursor.repeatMode)
            .put(KEY_SHUFFLE_ENABLED, cursor.shuffleEnabled)
            .put(KEY_PLAY_WHEN_READY, cursor.playWhenReady)
            .put(KEY_QUALITY_MODE, cursor.qualityMode.name)
            .put(KEY_PLAYBACK_SPEED, cursor.playbackTuning.speed.toDouble())
            .put(KEY_PLAYBACK_PITCH_SEMITONES, cursor.playbackTuning.pitchSemitones.toDouble())
            .put(KEY_QUEUE_REVISION, cursor.queueRevision)
        val editor = prefs.edit().putString(KEY_CURSOR_SNAPSHOT, json.toString())
        if (sync) editor.commit() else editor.apply()
        clearLegacy()
    }

    fun load(): ServicePlaybackSnapshot? {
        val queueRaw = prefs.getString(KEY_QUEUE_SNAPSHOT, null)
        val cursorRaw = prefs.getString(KEY_CURSOR_SNAPSHOT, null)
        if (queueRaw == null || cursorRaw == null) {
            return loadCombinedSnapshot() ?: loadLegacySnapshot()
        }
        return runCatching {
            val queueJsonObject = JSONObject(queueRaw)
            val cursorJson = JSONObject(cursorRaw)
            val queueJson = queueJsonObject.getJSONArray(KEY_QUEUE)
            val queue = buildList {
                for (index in 0 until queueJson.length()) {
                    queueJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            if (queue.isEmpty()) return null
            val queueRevision = queueJsonObject.optLong(KEY_QUEUE_REVISION, 0L)
            val cursorRevision = cursorJson.optLong(KEY_QUEUE_REVISION, -1L)
            val currentSongId = cursorJson.optString(KEY_CURRENT_SONG_ID)
            val currentIndex = currentSongId
                .takeIf(String::isNotBlank)
                ?.let(queue::indexOf)
                ?.takeIf { it >= 0 }
                ?: 0
            ServicePlaybackSnapshot(
                queueSongIds = queue,
                currentIndex = currentIndex,
                positionMs = cursorJson.optLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
                repeatMode = cursorJson.optInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
                shuffleEnabled = cursorJson.optBoolean(KEY_SHUFFLE_ENABLED, false),
                playWhenReady = cursorJson.optBoolean(KEY_PLAY_WHEN_READY, false),
                qualityMode = runCatching {
                    AudioQualityMode.valueOf(
                        cursorJson.optString(KEY_QUALITY_MODE, AudioQualityMode.HIFI.name),
                    )
                }.getOrDefault(AudioQualityMode.HIFI),
                playbackTuning = PlaybackTuning.coerced(
                    speed = cursorJson.optDouble(
                        KEY_PLAYBACK_SPEED,
                        PlaybackTuning.DEFAULT_SPEED.toDouble(),
                    ).toFloat(),
                    pitchSemitones = cursorJson.optDouble(
                        KEY_PLAYBACK_PITCH_SEMITONES,
                        PlaybackTuning.DEFAULT_PITCH_SEMITONES.toDouble(),
                    ).toFloat(),
                ),
                queueRevision = queueRevision,
                currentSongId = currentSongId,
            )
                .takeIf { queueRevision == cursorRevision || currentSongId in queue }
        }.getOrNull()
    }

    private fun loadCombinedSnapshot(): ServicePlaybackSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val queueJson = json.getJSONArray(KEY_QUEUE)
            val queue = buildList {
                for (index in 0 until queueJson.length()) {
                    queueJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            if (queue.isEmpty()) return null
            val currentIndex = json.optInt(KEY_CURRENT_INDEX, 0).coerceIn(0, queue.lastIndex)
            ServicePlaybackSnapshot(
                queueSongIds = queue,
                currentIndex = currentIndex,
                positionMs = json.optLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
                repeatMode = json.optInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
                shuffleEnabled = json.optBoolean(KEY_SHUFFLE_ENABLED, false),
                playWhenReady = json.optBoolean(KEY_PLAY_WHEN_READY, false),
                qualityMode = runCatching {
                    AudioQualityMode.valueOf(
                        json.optString(KEY_QUALITY_MODE, AudioQualityMode.HIFI.name),
                    )
                }.getOrDefault(AudioQualityMode.HIFI),
                playbackTuning = PlaybackTuning.coerced(
                    speed = json.optDouble(
                        KEY_PLAYBACK_SPEED,
                        PlaybackTuning.DEFAULT_SPEED.toDouble(),
                    ).toFloat(),
                    pitchSemitones = json.optDouble(
                        KEY_PLAYBACK_PITCH_SEMITONES,
                        PlaybackTuning.DEFAULT_PITCH_SEMITONES.toDouble(),
                    ).toFloat(),
                ),
                currentSongId = queue[currentIndex],
            )
        }.getOrNull()
    }

    private fun loadLegacySnapshot(): ServicePlaybackSnapshot? {
        val legacy = appContext.getSharedPreferences(
            LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val songId = legacy.getString(LEGACY_KEY_SONG_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return ServicePlaybackSnapshot(
            queueSongIds = listOf(songId),
            currentIndex = 0,
            positionMs = legacy.getInt(LEGACY_KEY_POSITION_MS, 0).toLong().coerceAtLeast(0L),
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = PlaybackTuning(),
        )
    }

    fun clearLegacy() {
        appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_SONG_ID)
            .remove(LEGACY_KEY_POSITION_MS)
            .apply()
    }

    fun clear(sync: Boolean = false) {
        val editor = prefs.edit()
            .remove(KEY_SNAPSHOT)
            .remove(KEY_QUEUE_SNAPSHOT)
            .remove(KEY_CURSOR_SNAPSHOT)
        if (sync) editor.commit() else editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "mica_service_playback_state"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        const val KEY_CURSOR_SNAPSHOT = "cursor_snapshot"
        const val KEY_QUEUE = "queue"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_CURRENT_SONG_ID = "current_song_id"
        const val KEY_QUEUE_REVISION = "queue_revision"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        const val KEY_PLAY_WHEN_READY = "play_when_ready"
        const val KEY_QUALITY_MODE = "quality_mode"
        const val KEY_PLAYBACK_SPEED = "playback_speed"
        const val KEY_PLAYBACK_PITCH_SEMITONES = "playback_pitch_semitones"
        const val LEGACY_PREFS_NAME = "mica_playback_session"
        const val LEGACY_KEY_SONG_ID = "song_id"
        const val LEGACY_KEY_POSITION_MS = "position_ms"
    }
}
