package com.mica.music.media

import android.content.Context
import androidx.media3.common.Player
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.TransientPlaybackCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight metadata needed to recreate the currently persisted external queue after a
 * process restart. Lyrics and playback statistics deliberately stay out of this snapshot.
 */
data class ServiceExternalSongSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationSec: Int,
    val containerName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int?,
    val bitrateKbps: Int,
    val channelCount: Int,
    val playbackMimeType: String,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val mediaUri: String,
    val playbackUri: String?,
    val fileName: String,
    val sizeBytes: Long,
    val year: Int,
    val releaseDate: String,
    val trackNumber: Int,
    val discNumber: Int,
    val folderPath: String,
    val filePath: String,
    val copyright: String,
    val codecLabel: String,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationSec = durationSec,
        metadata = TrackMetadata(
            containerName = containerName,
            sampleRateHz = sampleRateHz,
            bitsPerSample = bitsPerSample,
            bitrateKbps = bitrateKbps,
            channelCount = channelCount,
            playbackMimeType = playbackMimeType,
        ),
        albumArtUri = albumArtUri,
        coverColorArgb = coverColorArgb,
        mediaUri = mediaUri,
        playbackUri = playbackUri,
        fileName = fileName,
        sizeBytes = sizeBytes,
        year = year,
        releaseDate = releaseDate,
        trackNumber = trackNumber,
        discNumber = discNumber,
        folderPath = folderPath,
        filePath = filePath,
        copyright = copyright,
        codecLabel = codecLabel,
        dateAddedMs = dateAddedMs,
        dateModifiedMs = dateModifiedMs,
        replayGain = ReplayGainTags(
            trackGainDb = replayGainTrackDb,
            trackPeak = replayGainTrackPeak,
            albumGainDb = replayGainAlbumDb,
            albumPeak = replayGainAlbumPeak,
        ),
        lyricsLoaded = false,
        source = SongSource.TRANSIENT_EXTERNAL,
    )

    companion object {
        fun from(song: Song): ServiceExternalSongSnapshot = ServiceExternalSongSnapshot(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumArtist = song.albumArtist,
            durationSec = song.durationSec,
            containerName = song.metadata.containerName,
            sampleRateHz = song.metadata.sampleRateHz,
            bitsPerSample = song.metadata.bitsPerSample,
            bitrateKbps = song.metadata.bitrateKbps,
            channelCount = song.metadata.channelCount,
            playbackMimeType = song.metadata.playbackMimeType,
            albumArtUri = song.albumArtUri,
            coverColorArgb = song.coverColorArgb,
            mediaUri = song.mediaUri,
            playbackUri = song.playbackUri,
            fileName = song.fileName,
            sizeBytes = song.sizeBytes,
            year = song.year,
            releaseDate = song.releaseDate,
            trackNumber = song.trackNumber,
            discNumber = song.discNumber,
            folderPath = song.folderPath,
            filePath = song.filePath,
            copyright = song.copyright,
            codecLabel = song.codecLabel,
            dateAddedMs = song.dateAddedMs,
            dateModifiedMs = song.dateModifiedMs,
            replayGainTrackDb = song.replayGain.trackGainDb,
            replayGainTrackPeak = song.replayGain.trackPeak,
            replayGainAlbumDb = song.replayGain.albumGainDb,
            replayGainAlbumPeak = song.replayGain.albumPeak,
        )
    }
}

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
    val externalSongs: List<ServiceExternalSongSnapshot> = emptyList(),
)

internal data class ServiceQueueSnapshot(
    val songIds: List<String>,
    val revision: Long,
    val externalSongs: List<ServiceExternalSongSnapshot> = emptyList(),
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
            ServiceQueueSnapshot(
                songIds = snapshot.queueSongIds,
                revision = snapshot.queueRevision,
                externalSongs = snapshot.externalSongs,
            ),
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
            .put(
                KEY_EXTERNAL_SONGS,
                JSONArray().apply {
                    snapshot.externalSongs
                        .filter {
                            TransientPlaybackCatalog.isTransientId(it.id) &&
                                it.mediaUri.isNotBlank()
                        }
                        .forEach { put(it.toJson()) }
                },
            )
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
            val externalSongs = parseExternalSongs(queueJsonObject.optJSONArray(KEY_EXTERNAL_SONGS))
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
                externalSongs = externalSongs,
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
            val externalSongs = parseExternalSongs(json.optJSONArray(KEY_EXTERNAL_SONGS))
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
                externalSongs = externalSongs,
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

    internal fun migrateSongIds(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val snapshot = load() ?: return
        val queue = snapshot.queueSongIds.map { mapping[it] ?: it }
        val current = mapping[snapshot.currentSongId] ?: snapshot.currentSongId
        if (queue == snapshot.queueSongIds && current == snapshot.currentSongId) return
        save(
            snapshot.copy(
                queueSongIds = queue,
                currentSongId = current,
            ),
            sync = true,
        )
    }

    private fun parseExternalSongs(json: JSONArray?): List<ServiceExternalSongSnapshot> {
        if (json == null) return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                json.optJSONObject(index)?.toExternalSongOrNull()?.let(::add)
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "mica_service_playback_state"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        const val KEY_CURSOR_SNAPSHOT = "cursor_snapshot"
        const val KEY_QUEUE = "queue"
        const val KEY_EXTERNAL_SONGS = "external_songs"
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

private fun ServiceExternalSongSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("album_artist", albumArtist)
    put("duration_sec", durationSec)
    put("container", containerName)
    put("sample_rate_hz", sampleRateHz)
    put("bits_per_sample", bitsPerSample ?: -1)
    put("bitrate_kbps", bitrateKbps)
    put("channel_count", channelCount)
    put("mime", playbackMimeType)
    put("album_art_uri", albumArtUri)
    put("cover_color_argb", coverColorArgb)
    put("media_uri", mediaUri)
    put("playback_uri", playbackUri)
    put("file_name", fileName)
    put("size_bytes", sizeBytes)
    put("year", year)
    put("release_date", releaseDate)
    put("track_number", trackNumber)
    put("disc_number", discNumber)
    put("folder_path", folderPath)
    put("file_path", filePath)
    put("copyright", copyright)
    put("codec_label", codecLabel)
    put("date_added_ms", dateAddedMs)
    put("date_modified_ms", dateModifiedMs)
    replayGainTrackDb?.let { put("replay_gain_track_db", it.toDouble()) }
    replayGainTrackPeak?.let { put("replay_gain_track_peak", it.toDouble()) }
    replayGainAlbumDb?.let { put("replay_gain_album_db", it.toDouble()) }
    replayGainAlbumPeak?.let { put("replay_gain_album_peak", it.toDouble()) }
}

private fun JSONObject.toExternalSongOrNull(): ServiceExternalSongSnapshot? {
    val id = optString("id").takeIf(String::isNotBlank) ?: return null
    if (!TransientPlaybackCatalog.isTransientId(id)) return null
    val mediaUri = optString("media_uri").takeIf(String::isNotBlank) ?: return null
    return ServiceExternalSongSnapshot(
        id = id,
        title = optString("title"),
        artist = optString("artist"),
        album = optString("album"),
        albumArtist = optString("album_artist"),
        durationSec = optInt("duration_sec", 0).coerceAtLeast(0),
        containerName = optString("container"),
        sampleRateHz = optInt("sample_rate_hz", 0).coerceAtLeast(0),
        bitsPerSample = optInt("bits_per_sample", -1).takeIf { it > 0 },
        bitrateKbps = optInt("bitrate_kbps", 0).coerceAtLeast(0),
        channelCount = optInt("channel_count", 0).coerceAtLeast(0),
        playbackMimeType = optString("mime"),
        albumArtUri = optNullableString("album_art_uri"),
        coverColorArgb = optInt("cover_color_argb", 0),
        mediaUri = mediaUri,
        playbackUri = optNullableString("playback_uri"),
        fileName = optString("file_name"),
        sizeBytes = optLong("size_bytes", 0L).coerceAtLeast(0L),
        year = optInt("year", 0),
        releaseDate = optString("release_date"),
        trackNumber = optInt("track_number", 0).coerceAtLeast(0),
        discNumber = optInt("disc_number", 0).coerceAtLeast(0),
        folderPath = optString("folder_path"),
        filePath = optString("file_path"),
        copyright = optString("copyright"),
        codecLabel = optString("codec_label"),
        dateAddedMs = optLong("date_added_ms", 0L),
        dateModifiedMs = optLong("date_modified_ms", 0L),
        replayGainTrackDb = optFiniteFloat("replay_gain_track_db"),
        replayGainTrackPeak = optFiniteFloat("replay_gain_track_peak"),
        replayGainAlbumDb = optFiniteFloat("replay_gain_album_db"),
        replayGainAlbumPeak = optFiniteFloat("replay_gain_album_peak"),
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    takeIf { has(key) && !isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun JSONObject.optFiniteFloat(key: String): Float? =
    takeIf { has(key) && !isNull(key) }
        ?.optDouble(key)
        ?.takeIf(Double::isFinite)
        ?.toFloat()
