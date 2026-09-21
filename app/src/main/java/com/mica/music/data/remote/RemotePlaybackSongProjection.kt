package com.mica.music.data.remote

import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata

/** Hydration updates descriptive fields without replacing lyrics, statistics or playback policy. */
internal fun RemoteTrackSummary.mergePlaybackMetadata(current: Song): Song {
    require(current.id == mediaId)
    return current.copy(
        title = title.ifBlank { current.title },
        artist = artist.ifBlank { current.artist },
        album = album.ifBlank { current.album },
        albumArtist = albumArtist.ifBlank { current.albumArtist },
        durationSec = durationSec.takeIf { it > 0 } ?: current.durationSec,
        metadata = current.metadata.copy(
            sampleRateHz = sampleRateHz.takeIf { it > 0 } ?: current.metadata.sampleRateHz,
            bitsPerSample = bitsPerSample?.takeIf { it > 0 } ?: current.metadata.bitsPerSample,
            bitrateKbps = bitrateKbps.takeIf { it > 0 } ?: current.metadata.bitrateKbps,
            channelCount = channelCount.takeIf { it > 0 } ?: current.metadata.channelCount,
        ),
        albumArtUri = artworkOpaqueId.takeIf(String::isNotBlank)?.let {
            RemoteArtworkUriCodec.encode(RemoteArtworkRef(ref.sourceInstanceId, it))
        } ?: current.albumArtUri,
        year = year.takeIf { it > 0 } ?: current.year,
        trackNumber = trackNumber.takeIf { it > 0 } ?: current.trackNumber,
        discNumber = discNumber.takeIf { it > 0 } ?: current.discNumber,
    )
}

/** Missing descriptions must not silently hide persistent playlist entries. */
fun unavailableRemoteSong(mediaId: String): Song? = RemoteMediaIdCodec.decode(mediaId)?.let { ref ->
    RemoteTrackSummary(ref, ref.opaqueTrackId.substringAfterLast('/').ifBlank { "远程歌曲" },
        artist = "来源不可用或曲目信息未加载", fileName = ref.opaqueTrackId.substringAfterLast('/'))
        .toPlaybackSong()
}

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
            sampleRateHz = sampleRateHz.coerceAtLeast(0),
            bitsPerSample = bitsPerSample?.takeIf { it > 0 },
            bitrateKbps = bitrateKbps.coerceAtLeast(0),
            channelCount = channelCount.coerceAtLeast(0),
            playbackMimeType = mimeTypeHint,
        ),
        albumArtUri = artworkOpaqueId.takeIf(String::isNotBlank)?.let { artworkId ->
            RemoteArtworkUriCodec.encode(RemoteArtworkRef(ref.sourceInstanceId, artworkId))
        },
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
