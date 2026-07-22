package com.mica.music.perf

import com.mica.music.BuildConfig
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata

internal object CapacityQueueFactory {
    const val DefaultSize = 10_000

    fun create(size: Int = DefaultSize): List<Song> = List(size) { index ->
        Song(
            id = "capacity-song-$index",
            title = "Capacity Track ${index.toString().padStart(5, '0')}",
            artist = "Capacity Artist ${index % 127}",
            album = "Capacity Album ${index % 521}",
            durationSec = 180 + index % 120,
            metadata = TrackMetadata(
                containerName = "FLAC",
                sampleRateHz = 96_000,
                bitsPerSample = 24,
                bitrateKbps = 2_800,
                channelCount = 2,
                playbackMimeType = "audio/flac",
            ),
            albumArtUri = "content://${BuildConfig.APPLICATION_ID}.capacity-artwork/$index",
            coverColorArgb = colorFor(index),
            mediaUri = "content://${BuildConfig.APPLICATION_ID}.capacity-audio/fixture",
            fileName = "capacity-$index.flac",
            sizeBytes = 80L * 1024L * 1024L,
            dateModifiedMs = index.toLong(),
            externalLyricsSignature = "capacity-word-synced-$index",
            // The capacity harness deliberately models the production lazy boundary: the
            // 10,000 persisted lyric payloads are not copied into queue rows.
            lyricsLoaded = false,
        )
    }

    private fun colorFor(index: Int): Int {
        val red = 72 + (index * 37 % 160)
        val green = 72 + (index * 67 % 160)
        val blue = 72 + (index * 97 % 160)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
