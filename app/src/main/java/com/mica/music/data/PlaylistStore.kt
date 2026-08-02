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
    val coverSongId: String? = null,
    val customCoverPath: String? = null,
)

data class PlaylistImportResult(
    val playlist: UserPlaylist,
    val importedSongCount: Int,
    val skippedSongCount: Int,
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
            id = newPlaylistId(),
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

    fun appendSongsAsCustomOrder(
        playlistId: String,
        currentDisplayedSongIds: List<String>,
        appendedSongIds: List<String>,
    ): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        val existingIds = target.songIds.toSet()
        val orderedExistingIds = currentDisplayedSongIds.filter { it in existingIds }.distinct()
        val missingExistingIds = target.songIds.filterNot { it in orderedExistingIds }
        val newIds = appendedSongIds.filterNot { it in existingIds }.distinct()
        val updated = target.copy(
            songIds = orderedExistingIds + missingExistingIds + newIds,
            sortField = SongSortField.CUSTOM,
            sortDirection = SortDirection.ASC,
        )
        if (updated == target) return true
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    fun playlistById(id: String): UserPlaylist? = playlists.find { it.id == id }

    fun renamePlaylist(playlistId: String, name: String): Boolean {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "歌单名不能为空" }
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        if (target.name == trimmed) return true
        playlists = playlists.toMutableList().also { it[index] = target.copy(name = trimmed) }
        persist()
        revision++
        return true
    }

    fun setCoverSong(playlistId: String, songId: String?): Boolean = updatePlaylist(playlistId) {
        it.copy(coverSongId = songId, customCoverPath = null)
    }

    fun setCustomCoverPath(playlistId: String, path: String?): Boolean = updatePlaylist(playlistId) {
        it.copy(coverSongId = null, customCoverPath = path?.takeIf(String::isNotBlank))
    }

    fun clearCover(playlistId: String): Boolean = updatePlaylist(playlistId) {
        it.copy(coverSongId = null, customCoverPath = null)
    }

    fun exportPlaylistJson(playlistId: String, resolveSong: (String) -> Song?): String? {
        val playlist = playlistById(playlistId) ?: return null
        val songs = JSONArray()
        playlist.songIds.forEach { songId ->
            val song = resolveSong(songId)
            songs.put(
                JSONObject()
                    .put("id", songId)
                    .apply {
                        if (song != null) {
                            put("title", song.title)
                            put("artist", song.artist)
                            put("album", song.album)
                            put("durationSec", song.durationSec)
                            put("sizeBytes", song.sizeBytes)
                            put("mediaUri", song.mediaUri)
                            put("filePath", song.filePath)
                        }
                    },
            )
        }
        return JSONObject()
            .put("format", PLAYLIST_JSON_FORMAT)
            .put("version", PLAYLIST_JSON_VERSION)
            .put("name", playlist.name)
            .put("sortField", playlist.sortField.storageValue)
            .put("sortDirection", playlist.sortDirection.storageValue)
            .apply {
                playlist.coverSongId?.let { put("coverSongId", it) }
            }
            .put("songs", songs)
            .toString(2)
    }

    fun importPlaylistJson(raw: String, availableSongs: List<Song>): PlaylistImportResult {
        val root = JSONObject(raw)
        val importedName = root.optString("name", "导入歌单").trim().ifBlank { "导入歌单" }
        val byId = availableSongs.associateBy { it.id }
        val uniqueByMediaUri = uniqueSongMap(availableSongs) { it.mediaUri }
        val uniqueByFilePath = uniqueSongMap(availableSongs) { it.filePath }
        val uniqueByMetadata = uniqueSongMap(availableSongs, ::songMetadataKey)
        val sourceToResolved = mutableMapOf<String, String>()
        val resolvedIds = ArrayList<String>()
        var skipped = 0
        val songs = root.optJSONArray("songs") ?: JSONArray()
        for (index in 0 until songs.length()) {
            val ref = songs.optJSONObject(index) ?: continue
            val sourceId = ref.optString("id").takeIf(String::isNotBlank)
            val resolved = sourceId?.let(byId::get)
                ?: ref.optString("mediaUri").takeIf(String::isNotBlank)?.let(uniqueByMediaUri::get)
                ?: ref.optString("filePath").takeIf(String::isNotBlank)?.let(uniqueByFilePath::get)
                ?: metadataKey(ref)?.let(uniqueByMetadata::get)
            if (resolved == null) {
                skipped++
            } else if (resolved.id !in resolvedIds) {
                resolvedIds += resolved.id
                sourceId?.let { sourceToResolved[it] = resolved.id }
            }
        }
        val importedCoverSongId = root.optString("coverSongId")
            .takeIf(String::isNotBlank)
            ?.let(sourceToResolved::get)
        val playlist = UserPlaylist(
            id = newPlaylistId(),
            name = uniquePlaylistName(importedName),
            songIds = resolvedIds,
            sortField = SongSortField.fromStorage(root.optString("sortField").takeIf(String::isNotBlank)),
            sortDirection = SortDirection.fromStorage(root.optString("sortDirection").takeIf(String::isNotBlank)),
            coverSongId = importedCoverSongId,
        )
        playlists = playlists + playlist
        persist()
        revision++
        return PlaylistImportResult(playlist, resolvedIds.size, skipped)
    }

    internal fun reloadFromStorage() {
        val loaded = loadPlaylists()
        if (loaded == playlists) return
        playlists = loaded
        revision++
    }

    fun deletePlaylist(id: String): Boolean {
        val before = playlists.size
        playlists = playlists.filterNot { it.id == id }
        if (playlists.size == before) return false
        PlaylistCoverImporter.clearCover(appContext, id)
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
        val updated = target.copy(
            songIds = target.songIds.filterNot { it == songId },
            coverSongId = target.coverSongId.takeUnless { it == songId },
        )
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
                playlist.copy(
                    songIds = playlist.songIds.filterNot { it == songId },
                    coverSongId = playlist.coverSongId.takeUnless { it == songId },
                )
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
                    .put("sortDirection", playlist.sortDirection.storageValue)
                    .apply {
                        playlist.coverSongId?.let { put("coverSongId", it) }
                        playlist.customCoverPath?.let { put("customCoverPath", it) }
                    },
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
                            coverSongId = obj.optString("coverSongId")
                                .takeIf(String::isNotBlank),
                            customCoverPath = obj.optString("customCoverPath")
                                .takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun newPlaylistId(): String {
        var id: String
        do {
            id = "pl_${System.currentTimeMillis()}_${(0..9999).random()}"
        } while (playlists.any { it.id == id })
        return id
    }

    private fun uniquePlaylistName(base: String): String {
        if (playlists.none { it.name == base }) return base
        var suffix = 2
        while (playlists.any { it.name == "$base ($suffix)" }) suffix++
        return "$base ($suffix)"
    }

    private fun updatePlaylist(playlistId: String, transform: (UserPlaylist) -> UserPlaylist): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val target = playlists[index]
        val updated = transform(target)
        if (updated == target) return true
        playlists = playlists.toMutableList().also { it[index] = updated }
        persist()
        revision++
        return true
    }

    private fun uniqueSongMap(songs: List<Song>, key: (Song) -> String): Map<String, Song> =
        songs.groupBy(key)
            .filterKeys { it.isNotBlank() }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

    private fun songMetadataKey(song: Song): String =
        metadataKey(song.title, song.artist, song.album, song.durationSec, song.sizeBytes)

    private fun metadataKey(ref: JSONObject): String? {
        val title = ref.optString("title")
        val artist = ref.optString("artist")
        val album = ref.optString("album")
        val durationSec = ref.optInt("durationSec", 0)
        val sizeBytes = ref.optLong("sizeBytes", 0L)
        if (title.isBlank() && artist.isBlank() && album.isBlank() && durationSec <= 0 && sizeBytes <= 0L) {
            return null
        }
        return metadataKey(title, artist, album, durationSec, sizeBytes)
    }

    private fun metadataKey(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        sizeBytes: Long,
    ): String = listOf(title, artist, album, durationSec, sizeBytes).joinToString("\u0001")

    companion object {
        private const val PREFS_NAME = "mica_playlists"
        private const val KEY_PLAYLISTS_JSON = "playlists_json"
        private const val PLAYLIST_JSON_FORMAT = "mica-playlist"
        private const val PLAYLIST_JSON_VERSION = 1

        internal fun migrateSongIds(context: Context, mapping: Map<String, String>) {
            if (mapping.isEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PLAYLISTS_JSON, null) ?: return
            val rewritten = runCatching {
                val array = JSONArray(raw)
                var changed = false
                for (i in 0 until array.length()) {
                    val playlist = array.getJSONObject(i)
                    val songs = playlist.getJSONArray("songs")
                    for (j in 0 until songs.length()) {
                        val newId = mapping[songs.getString(j)] ?: continue
                        songs.put(j, newId)
                        changed = true
                    }
                    val oldCoverSongId = playlist.optString("coverSongId")
                    val newCoverSongId = mapping[oldCoverSongId]
                    if (!newCoverSongId.isNullOrBlank()) {
                        playlist.put("coverSongId", newCoverSongId)
                        changed = true
                    }
                }
                changed to array.toString()
            }.getOrNull() ?: return
            if (rewritten.first) {
                prefs.edit().putString(KEY_PLAYLISTS_JSON, rewritten.second).commit()
            }
        }
    }
}
