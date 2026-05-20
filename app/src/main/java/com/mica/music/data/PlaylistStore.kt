package com.mica.music.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class UserPlaylist(
    val id: String,
    val name: String,
    val songIds: List<String>,
    val sortField: SongSortField = SongSortField.CUSTOM,
    val sortDirection: SortDirection = SortDirection.ASC,
)

/**
 * 用户歌单（轻量持久化）。侧栏歌单浏览等完整能力见 [docs/TODO.md]。
 */
class PlaylistStore(context: Context) {

    private val appContext = context.applicationContext

    var playlists by mutableStateOf(loadPlaylists())
        private set

    var revision by mutableIntStateOf(0)
        private set

    fun createPlaylist(name: String): UserPlaylist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "歌单名不能为空" }
        val playlist = UserPlaylist(
            id = "pl_${System.currentTimeMillis()}",
            name = trimmed,
            songIds = emptyList(),
        )
        playlists = playlists + playlist
        persist()
        return playlist
    }

    fun addSongToPlaylist(playlistId: String, songId: String): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        if (songId in target.songIds) return true
        val updated = target.copy(songIds = target.songIds + songId)
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    fun playlistById(id: String): UserPlaylist? = playlists.find { it.id == id }

    fun deletePlaylist(id: String): Boolean {
        val before = playlists.size
        playlists = playlists.filterNot { it.id == id }
        if (playlists.size == before) return false
        persist()
        revision++
        return true
    }

    fun updateSort(playlistId: String, field: SongSortField, direction: SortDirection): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        if (target.sortField == field && target.sortDirection == direction) return true
        val updated = target.copy(sortField = field, sortDirection = direction)
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    fun moveSongInPlaylist(playlistId: String, fromIndex: Int, toIndex: Int): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val ids = playlists[index].songIds.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return false
        val moved = ids.removeAt(fromIndex)
        ids.add(toIndex, moved)
        val target = playlists[index]
        val updated = target.copy(
            songIds = ids,
            sortField = SongSortField.CUSTOM,
        )
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    fun songsForPlaylist(playlistId: String, resolveSong: (String) -> Song?): List<Song> {
        val playlist = playlistById(playlistId) ?: return emptyList()
        val songs = playlist.songIds.mapNotNull(resolveSong)
        return if (playlist.sortField == SongSortField.CUSTOM) {
            songs
        } else {
            SongSorter.sort(songs, playlist.sortField, playlist.sortDirection)
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        if (songId !in target.songIds) return false
        val updated = target.copy(songIds = target.songIds.filterNot { it == songId })
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    fun removeSongFromAllPlaylists(songId: String) {
        var changed = false
        playlists = playlists.map { playlist ->
            if (songId !in playlist.songIds) playlist
            else {
                changed = true
                playlist.copy(songIds = playlist.songIds.filterNot { it == songId })
            }
        }
        if (changed) {
            persist()
            revision++
        }
    }

    private fun persist() {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put(
                        "songs",
                        JSONArray().apply { playlist.songIds.forEach { put(it) } },
                    )
                    .put("sortField", playlist.sortField.storageValue)
                    .put("sortDirection", playlist.sortDirection.storageValue),
            )
        }
        prefs().edit().putString(KEY_PLAYLISTS_JSON, array.toString()).apply()
    }

    private fun loadPlaylists(): List<UserPlaylist> {
        val raw = prefs().getString(KEY_PLAYLISTS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val songsArray = obj.getJSONArray("songs")
                    val ids = buildList(songsArray.length()) {
                        for (j in 0 until songsArray.length()) {
                            add(songsArray.getString(j))
                        }
                    }
                    add(
                        UserPlaylist(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            songIds = ids,
                            sortField = if (obj.has("sortField")) {
                                SongSortField.fromStorage(obj.getString("sortField"))
                            } else {
                                SongSortField.CUSTOM
                            },
                            sortDirection = if (obj.has("sortDirection")) {
                                SortDirection.fromStorage(obj.getString("sortDirection"))
                            } else {
                                SortDirection.ASC
                            },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "mica_playlists"
        private const val KEY_PLAYLISTS_JSON = "playlists_json"
    }
}
