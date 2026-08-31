package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary

internal fun NavidromeTrack.toRemoteTrackSummary(sourceInstanceId: String): RemoteTrackSummary =
    RemoteTrackSummary(
        ref = RemoteTrackRef(sourceInstanceId, remoteId),
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationSec = durationSec,
        mimeTypeHint = contentType,
        fileName = when {
            serverPath.substringAfterLast('/').isNotBlank() -> serverPath.substringAfterLast('/')
            suffix.isNotBlank() -> "$title.$suffix"
            else -> title
        },
        suffix = suffix,
        sizeBytes = sizeBytes,
        sampleRateHz = samplingRateHz,
        bitsPerSample = bitDepth.takeIf { it > 0 },
        bitrateKbps = bitRateKbps,
        channelCount = channelCount,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumOpaqueId = albumId,
        artistOpaqueId = artistId,
        artworkOpaqueId = coverArtId,
    )
