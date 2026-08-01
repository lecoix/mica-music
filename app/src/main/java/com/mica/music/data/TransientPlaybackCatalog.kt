package com.mica.music.data

/**
 * Process-lifetime owner for songs opened from another app.
 *
 * Nothing in this catalog is persisted. The current external song replaces the previous one so
 * a long-running playback process cannot retain an unbounded number of transient metadata rows.
 */
class TransientPlaybackCatalog {
    companion object {
        const val TRANSIENT_ID_PREFIX = "external_"

        fun isTransientId(id: String): Boolean = id.startsWith(TRANSIENT_ID_PREFIX)
    }

    private val songs = LinkedHashMap<String, Song>()

    @Synchronized
    fun replace(song: Song): Song {
        val transientSong = song.copy(source = SongSource.TRANSIENT_EXTERNAL)
        songs.clear()
        songs[transientSong.id] = transientSong
        return transientSong
    }

    @Synchronized
    fun songById(id: String): Song? = songs[id]

    @Synchronized
    fun clear() {
        songs.clear()
    }
}
