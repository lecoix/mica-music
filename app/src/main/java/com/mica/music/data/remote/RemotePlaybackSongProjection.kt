package com.mica.music.data.remote

import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata

/**
 * Safe Song-shaped projection used by the existing UI/queue surface.
 *
 * It is not a local-library entity. [Song.mediaUri] is the stable Mica-owned `mica-remote://`
 * address only; authenticated protocol URLs are still created exclusively by the playback
 * DataSource at open time.
 */
fun RemoteTrackSummary.toPlaybackSong(): Song {
    val stableUri = RemotePlaybackUriCodec.encode(mediaId)
    return Song(
        id = mediaId,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationSec = durationSec.coerceAtLeast(0),
        metadata = TrackMetadata(
            containerName = suffix.ifBlank { mimeTypeHint.substringAfter('/', "") }.uppercase(),
            sampleRateHz = 0,
            bitsPerSample = null,
            bitrateKbps = 0,
            channelCount = 0,
            playbackMimeType = mimeTypeHint,
        ),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = stableUri,
        playbackUri = null,
        fileName = fileName,
        sizeBytes = sizeBytes.coerceAtLeast(0L),
        year = year.coerceAtLeast(0),
        trackNumber = trackNumber.coerceAtLeast(0),
        discNumber = discNumber.coerceAtLeast(0),
        lyricsLoaded = false,
        source = SongSource.REMOTE,
    )
}
