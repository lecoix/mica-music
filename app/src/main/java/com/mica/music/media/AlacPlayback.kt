package com.mica.music.media

import com.mica.music.data.Song

/**
 * ALAC detection for Media3-first routing.
 */
object AlacPlayback {

    fun isAlac(song: Song): Boolean =
        song.metadata.containerName == "ALAC" ||
            song.metadata.playbackMimeType.contains("alac", ignoreCase = true)

}
