package com.mica.music.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal data class WidgetPlaybackSnapshot(
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val artworkKey: String = "",
    val artworkPath: String? = null,
)

internal object WidgetPlaybackGlanceState {
    private val mediaId = stringPreferencesKey("media_id")
    private val title = stringPreferencesKey("title")
    private val artist = stringPreferencesKey("artist")
    private val album = stringPreferencesKey("album")
    private val playing = booleanPreferencesKey("playing")
    private val buffering = booleanPreferencesKey("buffering")
    private val artworkKey = stringPreferencesKey("artwork_key")
    private val artworkPath = stringPreferencesKey("artwork_path")

    fun read(
        preferences: Preferences,
        fallback: WidgetPlaybackSnapshot,
    ): WidgetPlaybackSnapshot = WidgetPlaybackSnapshot(
        mediaId = preferences[mediaId] ?: fallback.mediaId,
        title = preferences[title] ?: fallback.title,
        artist = preferences[artist] ?: fallback.artist,
        album = preferences[album] ?: fallback.album,
        isPlaying = preferences[playing] ?: fallback.isPlaying,
        isBuffering = preferences[buffering] ?: fallback.isBuffering,
        artworkKey = preferences[artworkKey] ?: fallback.artworkKey,
        artworkPath = preferences[artworkPath] ?: fallback.artworkPath,
    )

    fun write(
        preferences: MutablePreferences,
        snapshot: WidgetPlaybackSnapshot,
    ) {
        preferences[mediaId] = snapshot.mediaId
        preferences[title] = snapshot.title
        preferences[artist] = snapshot.artist
        preferences[album] = snapshot.album
        preferences[playing] = snapshot.isPlaying
        preferences[buffering] = snapshot.isBuffering
        preferences[artworkKey] = snapshot.artworkKey
        snapshot.artworkPath?.let { preferences[artworkPath] = it } ?: preferences.remove(artworkPath)
    }
}

internal object WidgetPlaybackStateStore {
    private const val PREFS_NAME = "playback_widget_state"
    private const val KEY_MEDIA_ID = "media_id"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ALBUM = "album"
    private const val KEY_PLAYING = "playing"
    private const val KEY_BUFFERING = "buffering"
    private const val KEY_ARTWORK_KEY = "artwork_key"
    private const val KEY_ARTWORK_PATH = "artwork_path"

    fun load(context: Context): WidgetPlaybackSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetPlaybackSnapshot(
            mediaId = prefs.getString(KEY_MEDIA_ID, null).orEmpty(),
            title = prefs.getString(KEY_TITLE, null).orEmpty(),
            artist = prefs.getString(KEY_ARTIST, null).orEmpty(),
            album = prefs.getString(KEY_ALBUM, null).orEmpty(),
            isPlaying = prefs.getBoolean(KEY_PLAYING, false),
            isBuffering = prefs.getBoolean(KEY_BUFFERING, false),
            artworkKey = prefs.getString(KEY_ARTWORK_KEY, null).orEmpty(),
            artworkPath = prefs.getString(KEY_ARTWORK_PATH, null),
        )
    }

    fun save(context: Context, snapshot: WidgetPlaybackSnapshot) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEDIA_ID, snapshot.mediaId)
            .putString(KEY_TITLE, snapshot.title)
            .putString(KEY_ARTIST, snapshot.artist)
            .putString(KEY_ALBUM, snapshot.album)
            .putBoolean(KEY_PLAYING, snapshot.isPlaying)
            .putBoolean(KEY_BUFFERING, snapshot.isBuffering)
            .putString(KEY_ARTWORK_KEY, snapshot.artworkKey)
            .putString(KEY_ARTWORK_PATH, snapshot.artworkPath)
            .apply()
    }
}
