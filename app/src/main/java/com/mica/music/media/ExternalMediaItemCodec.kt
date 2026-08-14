package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.scanner.AlbumArtCache

/**
 * The MediaItem representation returned to a non-local controller.
 *
 * It intentionally carries only the transport fields needed by the service and a small metadata
 * allowlist. The caller's URI and metadata are never copied into this representation.
 */
internal object ExternalMediaItemCodec {
    private const val VERSION_KEY = "mica.external.version"
    private const val SOURCE_KEY = "mica.external.source"
    private const val COVER_COLOR_KEY = "mica.external.coverColorArgb"
    private const val VERSION = 1

    fun encode(
        context: Context,
        song: Song,
        device: SystemMediaArtworkResolver.DeviceProfile =
            SystemMediaArtworkResolver.DeviceProfile.current(),
    ): MediaItem {
        val metadataExtras = Bundle().apply {
            putInt(VERSION_KEY, VERSION)
            putString(SOURCE_KEY, song.source.name)
            putInt(COVER_COLOR_KEY, song.coverColorArgb)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setDurationMs(song.durationSec.coerceAtLeast(0) * 1000L)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(metadataExtras)
            .apply {
                externalArtworkUri(context, song.albumArtUri, device)?.let(::setArtworkUri)
            }
            .build()
        val mime = PlaybackMimeResolver.resolve(
            storeMime = song.metadata.playbackMimeType,
            probeMime = song.metadata.playbackMimeType,
            displayName = song.fileName,
            mediaUri = song.effectivePlaybackUri,
            containerName = song.metadata.containerName,
        )
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(song.effectivePlaybackUri)
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .build()
    }

    fun isExternal(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getInt(VERSION_KEY, 0) == VERSION

    fun decode(item: MediaItem): Song? {
        if (!isExternal(item)) return null
        val metadata = item.mediaMetadata
        val extras = metadata.extras ?: return null
        val mediaUri = item.localConfiguration?.uri?.toString().orEmpty()
        if (item.mediaId.isBlank() || mediaUri.isBlank()) return null
        val durationMs = metadata.durationMs?.takeIf { it >= 0L } ?: 0L
        val source = extras.getString(SOURCE_KEY)?.let { encoded ->
            runCatching { SongSource.valueOf(encoded) }.getOrNull()
        } ?: SongSource.LIBRARY
        return Song(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumArtist = metadata.albumArtist?.toString().orEmpty(),
            durationSec = (durationMs / 1000L).toInt().coerceAtLeast(0),
            metadata = TrackMetadata(
                containerName = "",
                sampleRateHz = 0,
                bitsPerSample = null,
                bitrateKbps = 0,
                channelCount = 0,
                playbackMimeType = item.localConfiguration?.mimeType.orEmpty(),
            ),
            albumArtUri = metadata.artworkUri?.toString(),
            coverColorArgb = extras.getInt(COVER_COLOR_KEY, 0),
            mediaUri = mediaUri,
            source = source,
        )
    }

    /** Returns only artwork schemes that an external controller can reasonably resolve. */
    fun externalArtworkUri(
        context: Context,
        rawArtworkUri: String?,
        device: SystemMediaArtworkResolver.DeviceProfile =
            SystemMediaArtworkResolver.DeviceProfile.current(),
    ): Uri? {
        if (rawArtworkUri.isNullOrBlank()) return null
        if (AlbumArtCache.parseManagedArtworkUri(context, rawArtworkUri) != null) {
            return SystemMediaArtworkResolver.resolve(context, rawArtworkUri, device)
        }
        val uri = runCatching { Uri.parse(rawArtworkUri) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme.equals("http", ignoreCase = true) ||
            it.scheme.equals("https", ignoreCase = true) }
    }
}
