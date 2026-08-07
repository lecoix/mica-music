package com.mica.music.data

/**
 * Process-lifetime owner for songs opened from another app.
 *
 * The current external queue replaces the previous one, so a long-running playback process
 * cannot retain an unbounded number of transient metadata rows.
 */
class TransientPlaybackCatalog {
    companion object {
        const val TRANSIENT_ID_PREFIX = "external_"

        fun isTransientId(id: String): Boolean = id.startsWith(TRANSIENT_ID_PREFIX)
    }

    private val songs = LinkedHashMap<String, Song>()
    private val restorableIds = HashSet<String>()

    @Synchronized
    fun replace(song: Song): Song {
        val transientSong = song.copy(source = SongSource.TRANSIENT_EXTERNAL)
        replaceAll(listOf(transientSong))
        return transientSong
    }

    @Synchronized
    fun replaceAll(newSongs: List<Song>, restorable: Boolean = false): List<Song> {
        songs.clear()
        restorableIds.clear()
        newSongs
            .asSequence()
            .map { it.copy(source = SongSource.TRANSIENT_EXTERNAL) }
            .filter { it.id.isNotBlank() }
            .distinctBy(Song::id)
            .forEach { songs[it.id] = it }
        if (restorable) restorableIds += songs.keys
        return songs.values.toList()
    }

    @Synchronized
    fun markRestorable(id: String, restorable: Boolean = true) {
        if (restorable && songs.containsKey(id)) restorableIds += id else restorableIds -= id
    }

    @Synchronized
    fun songForPersistence(id: String): Song? =
        songs[id]?.takeIf { id in restorableIds }

    @Synchronized
    fun songById(id: String): Song? = songs[id]

    @Synchronized
    fun clear() {
        songs.clear()
        restorableIds.clear()
    }
}
