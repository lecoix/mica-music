package com.mica.music.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteArtworkUriCodec
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteMediaMetadataExtras
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.RemoteTrackSummaryLookup

internal object RemoteMediaItemCodec {
    fun encode(track: RemoteTrackSummary): MediaItem {
        val mediaId = track.mediaId
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist)
            .setDurationMs(track.durationSec.coerceAtLeast(0) * 1000L)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                track.artworkOpaqueId.takeIf(String::isNotBlank)?.let { artworkId ->
                    setArtworkUri(
                        Uri.parse(
                            RemoteArtworkUriCodec.encode(
                                RemoteArtworkRef(track.ref.sourceInstanceId, artworkId),
                            ),
                        ),
                    )
                }
            }
            .setExtras(RemoteMediaMetadataExtras.encode(track))
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.parse(RemotePlaybackUriCodec.encode(mediaId)))
            .setMediaMetadata(metadata)
            .apply {
                track.mimeTypeHint.takeIf(String::isNotBlank)?.let(::setMimeType)
            }
            .build()
    }

    fun encode(song: Song): MediaItem {
        require(song.source == SongSource.REMOTE) { "Remote media item requires a remote song" }
        val trackRef = RemoteMediaIdCodec.decode(song.id)
            ?: throw IllegalArgumentException("Remote song media id is invalid")
        val stableUri = RemotePlaybackUriCodec.encode(song.id)
        require(song.mediaUri == stableUri && song.playbackUri == null) {
            "Remote song must use only the stable Mica playback URI"
        }
        val stableArtworkUri = song.albumArtUri?.let { value ->
            require(RemoteArtworkUriCodec.decodeForSource(value, trackRef.sourceInstanceId) != null) {
                "Remote song artwork must use the stable Mica artwork URI for its source"
            }
            Uri.parse(value)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setDurationMs(song.durationSec.coerceAtLeast(0) * 1000L)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { stableArtworkUri?.let(::setArtworkUri) }
            .setExtras(RemoteMediaMetadataExtras.encode(song))
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(stableUri))
            .setMediaMetadata(metadata)
            .apply {
                song.metadata.playbackMimeType.takeIf(String::isNotBlank)?.let(::setMimeType)
            }
            .build()
    }

    fun decode(item: MediaItem): Song? {
        val trackRef = RemoteMediaIdCodec.decode(item.mediaId) ?: return null
        val extras = item.mediaMetadata.extras
        if (!RemoteMediaMetadataExtras.isTrustedProjection(extras)) return null

        val stableUri = RemotePlaybackUriCodec.encode(item.mediaId)
        item.localConfiguration?.uri?.toString()?.let { actualUri ->
            if (actualUri != stableUri) return null
        }
        val mimeType = RemoteMediaMetadataExtras.mimeType(extras)
            .ifBlank { item.localConfiguration?.mimeType.orEmpty() }
        val suffix = RemoteMediaMetadataExtras.suffix(extras)
        val stableArtworkUri = item.mediaMetadata.artworkUri?.toString()?.let { artworkUri ->
            RemoteArtworkUriCodec.decodeForSource(artworkUri, trackRef.sourceInstanceId)
                ?.let { artworkUri }
                ?: return null
        }
        val durationSec = ((item.mediaMetadata.durationMs ?: 0L).coerceAtLeast(0L) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        return Song(
            id = item.mediaId,
            title = RemoteMediaMetadataExtras.title(extras).ifBlank { item.mediaMetadata.title?.toString().orEmpty() },
            artist = RemoteMediaMetadataExtras.artist(extras).ifBlank { item.mediaMetadata.artist?.toString().orEmpty() },
            album = RemoteMediaMetadataExtras.album(extras).ifBlank { item.mediaMetadata.albumTitle?.toString().orEmpty() },
            albumArtist = RemoteMediaMetadataExtras.albumArtist(extras)
                .ifBlank { item.mediaMetadata.albumArtist?.toString().orEmpty() },
            durationSec = durationSec,
            metadata = TrackMetadata(
                containerName = suffix.ifBlank { mimeType.substringAfter('/', "") }.uppercase(),
                sampleRateHz = 0,
                bitsPerSample = null,
                bitrateKbps = 0,
                channelCount = 0,
                playbackMimeType = mimeType,
            ),
            albumArtUri = stableArtworkUri,
            coverColorArgb = 0,
            mediaUri = stableUri,
            playbackUri = null,
            fileName = RemoteMediaMetadataExtras.fileName(extras),
            sizeBytes = RemoteMediaMetadataExtras.sizeBytes(extras),
            year = RemoteMediaMetadataExtras.year(extras),
            trackNumber = RemoteMediaMetadataExtras.trackNumber(extras),
            discNumber = RemoteMediaMetadataExtras.discNumber(extras),
            lyricsLoaded = false,
            source = SongSource.REMOTE,
        )
    }
}

internal class TrustedRemoteMediaItemProvider(
    private val trackLookup: RemoteTrackSummaryLookup,
) {
    suspend fun resolve(mediaIds: List<String>): Map<String, MediaItem> {
        val refsByMediaId = mediaIds
            .distinct()
            .mapNotNull { mediaId ->
                RemoteMediaIdCodec.decode(mediaId)?.let { ref -> mediaId to ref }
            }
        if (refsByMediaId.isEmpty()) return emptyMap()
        val summaries = trackLookup.find(refsByMediaId.map { it.second }.distinct())
        return buildMap {
            refsByMediaId.forEach { (mediaId, ref) ->
                val summary = summaries[ref] ?: return@forEach
                put(mediaId, RemoteMediaItemCodec.encode(summary))
            }
        }
    }
}
