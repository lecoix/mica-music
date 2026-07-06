package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import org.json.JSONArray

/** 曲库与 Home 浏览排序偏好（歌曲列表、专辑/艺术家网格）。 */
object LibraryBrowseSettings {
    private const val KEY_SONG_SORT_FIELD = "song_sort_field"
    private const val KEY_SONG_SORT_DIRECTION = "song_sort_direction"
    private const val KEY_ALBUM_BROWSE_SORT_FIELD = "album_browse_sort_field"
    private const val KEY_ALBUM_BROWSE_SORT_DIRECTION = "album_browse_sort_direction"
    private const val KEY_ALBUM_BROWSE_GRID_COLUMNS = "album_browse_grid_columns"
    private const val KEY_ARTIST_BROWSE_SORT_FIELD = "artist_browse_sort_field"
    private const val KEY_ARTIST_BROWSE_SORT_DIRECTION = "artist_browse_sort_direction"
    private const val KEY_ARTIST_BROWSE_GRID_COLUMNS = "artist_browse_grid_columns"
    private const val KEY_CUSTOM_SONG_ORDER = "custom_song_order"
    private const val KEY_CUSTOM_SONG_ORDER_LOCKED = "custom_song_order_locked"

    fun songSortField(context: Context): SongSortField =
        SongSortField.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_SONG_SORT_FIELD, null))

    fun songSortDirection(context: Context): SortDirection =
        SortDirection.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_SONG_SORT_DIRECTION, null))

    fun setSongSort(context: Context, field: SongSortField, direction: SortDirection) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_SONG_SORT_FIELD, field.storageValue)
            .putString(KEY_SONG_SORT_DIRECTION, direction.storageValue)
            .apply()
    }

    fun customSongOrderIds(context: Context): List<String> {
        val raw = MicaSettingsStore.prefs(context).getString(KEY_CUSTOM_SONG_ORDER, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    add(array.getString(i))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setCustomSongOrderIds(context: Context, ids: List<String>) {
        val array = JSONArray().apply { ids.forEach { put(it) } }
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_CUSTOM_SONG_ORDER, array.toString())
            .apply()
    }

    fun customSongOrderLocked(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_CUSTOM_SONG_ORDER_LOCKED, false)

    fun setCustomSongOrderLocked(context: Context, locked: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_CUSTOM_SONG_ORDER_LOCKED, locked)
            .apply()
    }

    fun albumBrowseSortField(context: Context): AlbumBrowseSortField =
        AlbumBrowseSortField.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_ALBUM_BROWSE_SORT_FIELD, null),
        )

    fun albumBrowseSortDirection(context: Context): SortDirection =
        SortDirection.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_ALBUM_BROWSE_SORT_DIRECTION, null),
        )

    fun albumBrowseGridColumns(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_ALBUM_BROWSE_GRID_COLUMNS, 1).coerceIn(1, 4)

    fun setAlbumBrowseSort(context: Context, field: AlbumBrowseSortField, direction: SortDirection) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_ALBUM_BROWSE_SORT_FIELD, field.storageValue)
            .putString(KEY_ALBUM_BROWSE_SORT_DIRECTION, direction.storageValue)
            .apply()
    }

    fun setAlbumBrowseGridColumns(context: Context, columns: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_ALBUM_BROWSE_GRID_COLUMNS, columns.coerceIn(1, 4))
            .apply()
    }

    fun artistBrowseSortField(context: Context): ArtistBrowseSortField =
        ArtistBrowseSortField.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_ARTIST_BROWSE_SORT_FIELD, null),
        )

    fun artistBrowseSortDirection(context: Context): SortDirection =
        SortDirection.fromStorage(
            MicaSettingsStore.prefs(context).getString(KEY_ARTIST_BROWSE_SORT_DIRECTION, null),
        )

    fun artistBrowseGridColumns(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_ARTIST_BROWSE_GRID_COLUMNS, 1).coerceIn(1, 4)

    fun setArtistBrowseSort(context: Context, field: ArtistBrowseSortField, direction: SortDirection) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_ARTIST_BROWSE_SORT_FIELD, field.storageValue)
            .putString(KEY_ARTIST_BROWSE_SORT_DIRECTION, direction.storageValue)
            .apply()
    }

    fun setArtistBrowseGridColumns(context: Context, columns: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_ARTIST_BROWSE_GRID_COLUMNS, columns.coerceIn(1, 4))
            .apply()
    }
}
