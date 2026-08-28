package com.mica.music.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mica.music.data.Song
import com.mica.music.data.SongSource
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
        require(RemoteMediaIdCodec.isRemoteId(song.id)) { "Remote song media id is invalid" }
        val stableUri = RemotePlaybackUriCodec.encode(song.id)
        require(song.mediaUri == stableUri && song.playbackUri == null) {
            "Remote song must use only the stable Mica playback URI"
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setDurationMs(song.durationSec.coerceAtLeast(0) * 1000L)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
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
