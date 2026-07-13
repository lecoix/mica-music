package com.mica.music.testutil

import com.mica.music.data.LyricLine
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.toLyricsDocumentCompat

object SongFixtures {
    fun song(
        id: String = "song-1",
        title: String = id,
        container: String = "FLAC",
        mime: String = "audio/flac",
        durationSec: Int = 240,
        queueOrder: Int = 0,
        fileExtension: String? = null,
        totalListenSeconds: Long = 0L,
    ): Song = Song(
        id = id,
        title = title,
        artist = "Artist ${queueOrder % 2}",
        album = "Album",
        albumArtist = "Album Artist",
        durationSec = durationSec,
        metadata = TrackMetadata(
            containerName = container,
            sampleRateHz = if (container == "DSD") 2_822_400 else 96_000,
            bitsPerSample = if (container == "DSD") 1 else 24,
            bitrateKbps = 1_411,
            channelCount = 2,
            playbackMimeType = mime,
        ),
        albumArtUri = "file:///cover-$id.jpg",
        coverColorArgb = 0xFF334455.toInt(),
        mediaUri = "content://media/$id",
        fileName = "$id.${fileExtension ?: extension(container)}",
        sizeBytes = 1_000_000L + queueOrder,
        year = 2020 + queueOrder,
        folderPath = "Music/Album",
        filePath = "Music/Album/$id.${fileExtension ?: extension(container)}",
        copyright = "Test",
        codecLabel = "fixture",
        dateAddedMs = 1_000L + queueOrder,
        dateModifiedMs = 2_000L + queueOrder,
        playCount = queueOrder,
        totalListenSeconds = totalListenSeconds,
        lyricsDocument = listOf(
            LyricLine(0, "intro"),
            LyricLine(1_000, "line"),
        ).toLyricsDocumentCompat(),
    )

    fun queue(size: Int): List<Song> =
        List(size) { index -> song(id = "song-$index", queueOrder = index) }

    private fun extension(container: String): String = when (container) {
        "MP3" -> "mp3"
        "ALAC" -> "m4a"
        "DSD" -> "dsf"
        else -> container.lowercase()
    }
}
