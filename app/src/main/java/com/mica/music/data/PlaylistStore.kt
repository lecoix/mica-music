package com.mica.music.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.local.PlaylistRepository
import com.mica.music.data.library.LibraryConfirmedMissingFollowup
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

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

private data class PlaylistPublication(
    val playlists: List<UserPlaylist>,
    val expectedRevision: Long,
)

private data class PlaylistMutationResult<T>(
    val value: T,
    val publication: PlaylistPublication? = null,
)

/** Process-scoped playlist facade backed by ordered Room rows. */
class PlaylistStore(
    context: Context,
    ownerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext = context.applicationContext
    private val repository = PlaylistRepository(MicaDatabase.get(appContext))

    private val initialized = CompletableDeferred<Unit>()

    var playlists by mutableStateOf(emptyList<UserPlaylist>())
        private set

    var revision by mutableIntStateOf(0)
        private set

    /** Test seam for the durable-commit -> memory-adopt cancellation boundary. */
    internal var beforeMutationAdoptForTest: suspend () -> Unit = {}

    init {
        ownerScope.launch(Dispatchers.IO) {
            runCatching { initializeFromStorage() }
                .onSuccess { initialized.complete(Unit) }
                .onFailure { initialized.completeExceptionally(it) }
        }
    }

    internal suspend fun awaitReady() {
        initialized.await()
    }

    suspend fun createPlaylist(name: String): UserPlaylist = mutate {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "歌单名不能为空" }
        val playlist = UserPlaylist(
            id = newPlaylistId(it),
            name = trimmed,
            songIds = emptyList(),
        )
        val storageRevision = checkStorage("create") { insertPlaylist(playlist, it.size) }
        PlaylistMutationResult(
            value = playlist,
            publication = PlaylistPublication(it + playlist, storageRevision),
        )
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean = mutate { current ->
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        if (songId in target.songIds) return@mutate PlaylistMutationResult(true)
        val updated = target.copy(songIds = target.songIds + songId)
        val storageRevision = writeStorage("add-song") { addSong(playlistId, songId, target.songIds.size) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
    }

    suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Boolean = mutate { current ->
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        val existing = target.songIds.toHashSet()
        val additions = songIds.asSequence()
            .filter(String::isNotBlank)
            .filter { it !in existing }
            .distinct()
            .toList()
        if (additions.isEmpty()) return@mutate PlaylistMutationResult(true)
        val updated = target.copy(songIds = target.songIds + additions)
        val storageRevision = writeStorage("add-songs") { replacePlaylist(updated, index) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
    }

    suspend fun appendSongsAsCustomOrder(
        playlistId: String,
        currentDisplayedSongIds: List<String>,
        appendedSongIds: List<String>,
    ): Boolean = mutate { current ->
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        val existingIds = target.songIds.toSet()
        val orderedExistingIds = currentDisplayedSongIds.filter { it in existingIds }.distinct()
        val missingExistingIds = target.songIds.filterNot { it in orderedExistingIds }
        val newIds = appendedSongIds.filterNot { it in existingIds }.distinct()
        val updated = target.copy(
            songIds = orderedExistingIds + missingExistingIds + newIds,
            sortField = SongSortField.CUSTOM,
            sortDirection = SortDirection.ASC,
        )
        if (updated == target) return@mutate PlaylistMutationResult(true)
        val storageRevision = writeStorage("append-custom-order") { replacePlaylist(updated, index) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
    }

    fun playlistById(id: String): UserPlaylist? = playlists.find { it.id == id }

    suspend fun renamePlaylist(playlistId: String, name: String): Boolean = mutate { current ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "歌单名不能为空" }
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        if (target.name == trimmed) return@mutate PlaylistMutationResult(true)
        val updated = target.copy(name = trimmed)
        val storageRevision = writeStorage("rename") { updatePlaylistMetadata(updated, index) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
    }

    suspend fun setCoverSong(playlistId: String, songId: String?): Boolean = updatePlaylist(playlistId) {
        it.copy(coverSongId = songId, customCoverPath = null)
    }

    suspend fun setCustomCoverPath(playlistId: String, path: String?): Boolean = updatePlaylist(playlistId) {
        it.copy(coverSongId = null, customCoverPath = path?.takeIf(String::isNotBlank))
    }

    suspend fun clearCover(playlistId: String): Boolean = updatePlaylist(playlistId) {
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

    suspend fun importPlaylistJson(raw: String, availableSongs: List<Song>): PlaylistImportResult = mutate { current ->
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
            id = newPlaylistId(current),
            name = uniquePlaylistName(importedName, current),
            songIds = resolvedIds,
            sortField = SongSortField.fromStorage(root.optString("sortField").takeIf(String::isNotBlank)),
            sortDirection = SortDirection.fromStorage(root.optString("sortDirection").takeIf(String::isNotBlank)),
            coverSongId = importedCoverSongId,
        )
        val storageRevision = checkStorage("import") { insertPlaylist(playlist, current.size) }
        PlaylistMutationResult(
            value = PlaylistImportResult(playlist, resolvedIds.size, skipped),
            publication = PlaylistPublication(current + playlist, storageRevision),
        )
    }

    internal suspend fun reloadFromStorage(beforePublish: suspend () -> Unit = {}) {
        awaitReady()
        val snapshot = mutationMutex.withLock {
            mutationGeneration.incrementAndGet()
            withContext(Dispatchers.IO) { repository.loadSnapshot() }
        }
        beforePublish()
        publishIfCurrent(PlaylistPublication(snapshot.playlists, snapshot.revision))
    }

    suspend fun deletePlaylist(id: String): Boolean {
        val deleted = mutate { current ->
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) return@mutate PlaylistMutationResult(false)
            val storageRevision = writeStorage("delete") { deletePlaylist(id) }
                ?: return@mutate PlaylistMutationResult(false)
            PlaylistMutationResult(
                value = true,
                publication = PlaylistPublication(updated, storageRevision),
            )
        }
        if (deleted) {
            withContext(Dispatchers.IO) { PlaylistCoverImporter.clearCover(appContext, id) }
        }
        return deleted
    }

    suspend fun updateSort(playlistId: String, field: SongSortField, direction: SortDirection): Boolean =
        mutate { current ->
            val index = current.indexOfFirst { it.id == playlistId }
            if (index < 0) return@mutate PlaylistMutationResult(false)
            val target = current[index]
            if (target.sortField == field && target.sortDirection == direction) {
                return@mutate PlaylistMutationResult(true)
            }
            val updated = target.copy(sortField = field, sortDirection = direction)
            val storageRevision = writeStorage("sort") { updatePlaylistMetadata(updated, index) }
                ?: return@mutate PlaylistMutationResult(false)
            PlaylistMutationResult(
                value = true,
                publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
            )
        }

    suspend fun moveSongInPlaylist(playlistId: String, fromIndex: Int, toIndex: Int): Boolean =
        mutate { current ->
            val index = current.indexOfFirst { it.id == playlistId }
            if (index < 0) return@mutate PlaylistMutationResult(false)
            val ids = current[index].songIds.toMutableList()
            if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) {
                return@mutate PlaylistMutationResult(false)
            }
            val moved = ids.removeAt(fromIndex)
            ids.add(toIndex, moved)
            val target = current[index]
            val updated = target.copy(songIds = ids, sortField = SongSortField.CUSTOM)
            val storageRevision = writeStorage("move-song") {
                moveSong(updated, index, moved, fromIndex, toIndex)
            } ?: return@mutate PlaylistMutationResult(false)
            PlaylistMutationResult(
                value = true,
                publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
            )
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

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String): Boolean = mutate { current ->
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        if (songId !in target.songIds) return@mutate PlaylistMutationResult(false)
        val updated = target.copy(
            songIds = target.songIds.filterNot { it == songId },
            coverSongId = target.coverSongId.takeUnless { it == songId },
        )
        val removedIndex = target.songIds.indexOf(songId)
        val storageRevision = writeStorage("remove-song") {
            removeSong(updated, index, songId, removedIndex)
        } ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
    }

    suspend fun removeSongFromAllPlaylists(songId: String): Boolean = mutate { current ->
        var changed = false
        val updated = current.map { playlist ->
            if (songId !in playlist.songIds) playlist
            else {
                changed = true
                playlist.copy(
                    songIds = playlist.songIds.filterNot { it == songId },
                    coverSongId = playlist.coverSongId.takeUnless { it == songId },
                )
            }
        }
        if (!changed) return@mutate PlaylistMutationResult(true)
        val storageRevision = writeStorage("remove-song-everywhere") { removeSongEverywhere(songId) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(updated, storageRevision),
        )
    }

    internal suspend fun consumeConfirmedMissingFollowups(
        requests: List<LibraryConfirmedMissingFollowup>,
    ): Int {
        if (requests.isEmpty()) return 0
        awaitReady()
        return mutationMutex.withLock {
            mutationGeneration.incrementAndGet()
            withContext(NonCancellable) {
                val outcome = withContext(Dispatchers.IO) {
                    repository.consumeConfirmedMissingFollowups(requests)
                }
                val expectedRevision = outcome.playlistRevision
                val committedPlaylists = outcome.playlists
                if (expectedRevision != null && committedPlaylists != null) {
                    beforeMutationAdoptForTest()
                    adoptCommittedPublicationLocked(
                        PlaylistPublication(committedPlaylists, expectedRevision),
                    )
                }
                outcome.acknowledgedCount
            }
        }
    }

    private suspend fun initializeFromStorage() {
        val publication = mutationMutex.withLock {
            mutationGeneration.incrementAndGet()
            withContext(Dispatchers.IO) {
                val preferences = prefs()
                if (!preferences.getBoolean(KEY_ROOM_MIGRATION_COMPLETE, false)) {
                    val raw = preferences.getString(KEY_PLAYLISTS_JSON, null)
                    if (raw != null) {
                        val legacy = runCatching { parseLegacyPlaylists(raw) }
                        if (legacy.isSuccess) {
                            repository.replaceAll(legacy.getOrThrow())
                            preferences.edit().putBoolean(KEY_ROOM_MIGRATION_COMPLETE, true).commit()
                        } else {
                            DiagnosticLog.important(
                                "PlaylistStore",
                                "legacy-migration-failed error=${legacy.exceptionOrNull()?.javaClass?.simpleName}",
                            )
                        }
                    } else {
                        preferences.edit().putBoolean(KEY_ROOM_MIGRATION_COMPLETE, true).commit()
                    }
                }
                val snapshot = repository.loadSnapshot()
                PlaylistPublication(snapshot.playlists, snapshot.revision)
            }
        }
        publishIfCurrent(publication)
    }

    private suspend fun <T> mutate(
        block: suspend (List<UserPlaylist>) -> PlaylistMutationResult<T>,
    ): T {
        awaitReady()
        return mutationMutex.withLock {
            mutationGeneration.incrementAndGet()
            withContext(NonCancellable) {
                val current = withContext(Dispatchers.IO) { repository.loadSnapshot() }
                val result = block(current.playlists)
                result.publication?.let { publication ->
                    beforeMutationAdoptForTest()
                    adoptCommittedPublicationLocked(publication)
                }
                result.value
            }
        }
    }

    private suspend fun publishIfCurrent(publication: PlaylistPublication) {
        mutationMutex.withLock {
            val durableRevision = withContext(Dispatchers.IO) { repository.currentRevision() }
            if (durableRevision != publication.expectedRevision) return@withLock
            adoptCommittedPublicationLocked(publication)
        }
    }

    /**
     * Adopt a snapshot returned by a durable write performed while [mutationMutex] is held.
     * No post-commit I/O is allowed here: once Room commits successfully, memory adoption is the
     * remaining half of the same owner operation and cannot fail because of another DB round-trip.
     */
    private fun adoptCommittedPublicationLocked(publication: PlaylistPublication) {
        if (playlists != publication.playlists) {
            playlists = publication.playlists
            revision++
        }
    }

    private suspend fun writeStorage(
        operation: String,
        block: suspend PlaylistRepository.() -> Long,
    ): Long? = runCatching {
        withContext(Dispatchers.IO) { repository.block() }
    }.onFailure { error ->
        DiagnosticLog.important(
            "PlaylistStore",
            "write-failed operation=$operation error=${error.javaClass.simpleName}",
        )
    }.getOrNull()

    private suspend fun checkStorage(
        operation: String,
        block: suspend PlaylistRepository.() -> Long,
    ): Long = checkNotNull(writeStorage(operation, block)) { "歌单保存失败" }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun newPlaylistId(current: List<UserPlaylist>): String {
        var id: String
        do {
            id = "pl_${System.currentTimeMillis()}_${(0..9999).random()}"
        } while (current.any { it.id == id })
        return id
    }

    private fun uniquePlaylistName(base: String, current: List<UserPlaylist>): String {
        if (current.none { it.name == base }) return base
        var suffix = 2
        while (current.any { it.name == "$base ($suffix)" }) suffix++
        return "$base ($suffix)"
    }

    private suspend fun updatePlaylist(
        playlistId: String,
        transform: (UserPlaylist) -> UserPlaylist,
    ): Boolean = mutate { current ->
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@mutate PlaylistMutationResult(false)
        val target = current[index]
        val updated = transform(target)
        if (updated == target) return@mutate PlaylistMutationResult(true)
        val storageRevision = writeStorage("update") { updatePlaylistMetadata(updated, index) }
            ?: return@mutate PlaylistMutationResult(false)
        PlaylistMutationResult(
            value = true,
            publication = PlaylistPublication(current.toMutableList().also { it[index] = updated }, storageRevision),
        )
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
        private const val KEY_ROOM_MIGRATION_COMPLETE = "room_migration_complete_v1"
        private const val PLAYLIST_JSON_FORMAT = "mica-playlist"
        private const val PLAYLIST_JSON_VERSION = 1
        private val mutationMutex = Mutex()
        private val mutationGeneration = AtomicLong()

        internal suspend fun migrateSongIds(
            context: Context,
            database: MicaDatabase,
            mapping: Map<String, String>,
        ) {
            if (mapping.isEmpty()) return
            mutationMutex.withLock {
                mutationGeneration.incrementAndGet()
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY_PLAYLISTS_JSON, null)
                val rewritten = raw?.let { legacyRaw -> runCatching {
                    val array = JSONArray(legacyRaw)
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
                }.getOrNull() }
                if (rewritten?.first == true) {
                    prefs.edit().putString(KEY_PLAYLISTS_JSON, rewritten.second).commit()
                }
                withContext(Dispatchers.IO) {
                    PlaylistRepository(database).migrateSongIds(mapping)
                }
            }
        }

        private fun parseLegacyPlaylists(raw: String): List<UserPlaylist> {
            val array = JSONArray(raw)
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val songsArray = obj.getJSONArray("songs")
                    val ids = buildList(songsArray.length()) {
                        for (j in 0 until songsArray.length()) add(songsArray.getString(j))
                    }
                    add(
                        UserPlaylist(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            songIds = ids.distinct(),
                            sortField = obj.optString("sortField")
                                .takeIf(String::isNotBlank)
                                ?.let { SongSortField.fromStorage(it) }
                                ?: SongSortField.CUSTOM,
                            sortDirection = obj.optString("sortDirection")
                                .takeIf(String::isNotBlank)
                                ?.let { SortDirection.fromStorage(it) }
                                ?: SortDirection.ASC,
                            coverSongId = obj.optString("coverSongId").takeIf(String::isNotBlank),
                            customCoverPath = obj.optString("customCoverPath").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }
    }
}
