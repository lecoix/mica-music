package com.mica.music.data.scanner

/**
 * Local-library sink for playback-time cover-color repair.
 *
 * Remote songs stay in the process cache only; they are not library rows.
 */
internal object CoverColorPersistence {
    fun interface Sink {
        fun persistLibraryColor(songId: String, albumArtUri: String?, argb: Int)
    }

    @Volatile
    private var sink: Sink? = null

    fun attach(sink: Sink) {
        this.sink = sink
    }

    fun detach(sink: Sink) {
        if (this.sink === sink) this.sink = null
    }

    fun persistLibraryColor(songId: String, albumArtUri: String?, argb: Int) {
        sink?.persistLibraryColor(songId, albumArtUri, argb)
    }
}
