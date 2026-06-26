package com.mica.music.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata

object SongMediaItemCodec {
    private const val PREFIX = "mica.song."

    fun encode(song: Song, includeUri: Boolean = true): MediaItem {
        val extras = Bundle().apply {
            putString("${PREFIX}artist", song.artist)
            putString("${PREFIX}album", song.album)
            putString("${PREFIX}albumArtist", song.albumArtist)
            putInt("${PREFIX}durationSec", song.durationSec)
            putString("${PREFIX}container", song.metadata.containerName)
            putInt("${PREFIX}sampleRateHz", song.metadata.sampleRateHz)
            putInt("${PREFIX}bitsPerSample", song.metadata.bitsPerSample ?: -1)
            putInt("${PREFIX}bitrateKbps", song.metadata.bitrateKbps)
            putInt("${PREFIX}channelCount", song.metadata.channelCount)
            putString("${PREFIX}mime", song.metadata.playbackMimeType)
            putString("${PREFIX}albumArtUri", song.albumArtUri)
            putInt("${PREFIX}coverColorArgb", song.coverColorArgb)
            putString("${PREFIX}mediaUri", song.mediaUri)
            putString("${PREFIX}playbackUri", song.playbackUri)
            putString("${PREFIX}fileName", song.fileName)
            putLong("${PREFIX}sizeBytes", song.sizeBytes)
            putInt("${PREFIX}year", song.year)
            putString("${PREFIX}folderPath", song.folderPath)
            putString("${PREFIX}filePath", song.filePath)
            putString("${PREFIX}copyright", song.copyright)
            putString("${PREFIX}codecLabel", song.codecLabel)
            putLong("${PREFIX}dateAddedMs", song.dateAddedMs)
            putLong("${PREFIX}dateModifiedMs", song.dateModifiedMs)
            putInt("${PREFIX}playCount", song.playCount)
            putLong("${PREFIX}totalListenSeconds", song.totalListenSeconds)
            putLong("${PREFIX}lastPlayedAtMs", song.lastPlayedAtMs)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setDurationMs(song.durationSec.coerceAtLeast(0) * 1000L)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .apply {
                song.albumArtUri?.let { runCatching { setArtworkUri(Uri.parse(it)) } }
            }
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(metadata)
        if (includeUri) {
            val mime = PlaybackMimeResolver.resolve(
                storeMime = song.metadata.playbackMimeType,
                probeMime = song.metadata.playbackMimeType,
                displayName = song.fileName,
                mediaUri = song.mediaUri,
                containerName = song.metadata.containerName,
            )
            builder.setUri(song.mediaUri).setMimeType(mime)
        }
        return builder.build()
    }

    fun decode(item: MediaItem): Song? {
        val metadata = item.mediaMetadata ?: return null
        val extras = metadata.extras ?: return null
        val mediaUri = extras.getString("${PREFIX}mediaUri").orEmpty()
        if (item.mediaId.isBlank() || mediaUri.isBlank()) return null
        val bits = extras.getInt("${PREFIX}bitsPerSample", -1).takeIf { it > 0 }
        return Song(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = extras.getString("${PREFIX}artist").orEmpty(),
            album = extras.getString("${PREFIX}album").orEmpty(),
            albumArtist = extras.getString("${PREFIX}albumArtist").orEmpty(),
            durationSec = extras.getInt("${PREFIX}durationSec", 0).coerceAtLeast(0),
            metadata = TrackMetadata(
                containerName = extras.getString("${PREFIX}container").orEmpty(),
                sampleRateHz = extras.getInt("${PREFIX}sampleRateHz", 0),
                bitsPerSample = bits,
                bitrateKbps = extras.getInt("${PREFIX}bitrateKbps", 0),
                channelCount = extras.getInt("${PREFIX}channelCount", 0),
                playbackMimeType = extras.getString("${PREFIX}mime").orEmpty(),
            ),
            albumArtUri = extras.getString("${PREFIX}albumArtUri"),
            coverColorArgb = extras.getInt("${PREFIX}coverColorArgb", 0),
            mediaUri = mediaUri,
            playbackUri = extras.getString("${PREFIX}playbackUri"),
            fileName = extras.getString("${PREFIX}fileName").orEmpty(),
            sizeBytes = extras.getLong("${PREFIX}sizeBytes", 0L),
            year = extras.getInt("${PREFIX}year", 0),
            folderPath = extras.getString("${PREFIX}folderPath").orEmpty(),
            filePath = extras.getString("${PREFIX}filePath").orEmpty(),
            copyright = extras.getString("${PREFIX}copyright").orEmpty(),
            codecLabel = extras.getString("${PREFIX}codecLabel").orEmpty(),
            dateAddedMs = extras.getLong("${PREFIX}dateAddedMs", 0L),
            dateModifiedMs = extras.getLong("${PREFIX}dateModifiedMs", 0L),
            playCount = extras.getInt("${PREFIX}playCount", 0),
            totalListenSeconds = extras.getLong("${PREFIX}totalListenSeconds", 0L).coerceAtLeast(0L),
            lastPlayedAtMs = extras.getLong("${PREFIX}lastPlayedAtMs", 0L),
        )
    }
}
