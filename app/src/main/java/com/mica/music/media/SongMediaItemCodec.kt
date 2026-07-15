package com.mica.music.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mica.music.data.LyricsDocument
import com.mica.music.data.PlaybackMimeResolver
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import java.security.MessageDigest

object SongMediaItemCodec {
    private const val PREFIX = "mica.song."
    private const val METADATA_REVISION = "${PREFIX}metadataRevision"
    private const val LYRICS_REVISION = "${PREFIX}lyricsRevision"

    internal fun canonicalTitleExtraKey(): String = "${PREFIX}title"

    fun metadataRevision(song: Song): String =
        metadataRevision(song, lyricsRevision(song))

    private fun metadataRevision(song: Song, lyricsRevision: String): String = sha256(
        song.copy(
            playCount = 0,
            totalListenSeconds = 0L,
            lastPlayedAtMs = 0L,
            lyricsDocument = LyricsDocument(),
            lyricsLoaded = false,
        ).toString() + lyricsRevision,
    )

    fun metadataRevision(item: MediaItem): String? = item.mediaMetadata.extras?.getString(METADATA_REVISION)

    fun lyricsRevision(item: MediaItem): String =
        item.mediaMetadata.extras?.getString(LYRICS_REVISION).orEmpty()

    fun encode(song: Song, includeUri: Boolean = true): MediaItem {
        val extras = buildExtras(song)
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

    private fun buildExtras(song: Song): Bundle {
        val lyricsRevision = lyricsRevision(song)
        return Bundle().apply {
            putString(canonicalTitleExtraKey(), song.title)
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
            putInt("${PREFIX}trackNumber", song.trackNumber)
            putInt("${PREFIX}discNumber", song.discNumber)
            putString("${PREFIX}folderPath", song.folderPath)
            putString("${PREFIX}filePath", song.filePath)
            putString("${PREFIX}copyright", song.copyright)
            putString("${PREFIX}codecLabel", song.codecLabel)
            putLong("${PREFIX}dateAddedMs", song.dateAddedMs)
            putLong("${PREFIX}dateModifiedMs", song.dateModifiedMs)
            putInt("${PREFIX}playCount", song.playCount)
            putLong("${PREFIX}totalListenSeconds", song.totalListenSeconds)
            putLong("${PREFIX}lastPlayedAtMs", song.lastPlayedAtMs)
            song.replayGain.trackGainDb?.let { putFloat("${PREFIX}replayGainTrackDb", it) }
            song.replayGain.trackPeak?.let { putFloat("${PREFIX}replayGainTrackPeak", it) }
            song.replayGain.albumGainDb?.let { putFloat("${PREFIX}replayGainAlbumDb", it) }
            song.replayGain.albumPeak?.let { putFloat("${PREFIX}replayGainAlbumPeak", it) }
            putString(LYRICS_REVISION, lyricsRevision)
            putString(METADATA_REVISION, metadataRevision(song, lyricsRevision))
        }
    }

    private fun lyricsRevision(song: Song): String =
        if (song.lyricsLoaded) sha256(song.lyricsDocument.toString()) else song.lyricsCacheRevision

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"

    fun decode(item: MediaItem): Song? {
        val metadata = item.mediaMetadata ?: return null
        val extras = metadata.extras ?: return null
        val mediaUri = extras.getString("${PREFIX}mediaUri").orEmpty()
        if (item.mediaId.isBlank() || mediaUri.isBlank()) return null
        val bits = extras.getInt("${PREFIX}bitsPerSample", -1).takeIf { it > 0 }
        return Song(
            id = item.mediaId,
            title = extras.getString(canonicalTitleExtraKey())
                ?: metadata.title?.toString().orEmpty(),
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
            trackNumber = extras.getInt("${PREFIX}trackNumber", 0).coerceAtLeast(0),
            discNumber = extras.getInt("${PREFIX}discNumber", 0).coerceAtLeast(0),
            folderPath = extras.getString("${PREFIX}folderPath").orEmpty(),
            filePath = extras.getString("${PREFIX}filePath").orEmpty(),
            copyright = extras.getString("${PREFIX}copyright").orEmpty(),
            codecLabel = extras.getString("${PREFIX}codecLabel").orEmpty(),
            dateAddedMs = extras.getLong("${PREFIX}dateAddedMs", 0L),
            dateModifiedMs = extras.getLong("${PREFIX}dateModifiedMs", 0L),
            playCount = extras.getInt("${PREFIX}playCount", 0),
            totalListenSeconds = extras.getLong("${PREFIX}totalListenSeconds", 0L).coerceAtLeast(0L),
            lastPlayedAtMs = extras.getLong("${PREFIX}lastPlayedAtMs", 0L),
            replayGain = com.mica.music.data.ReplayGainTags(
                trackGainDb = extras.getFloat("${PREFIX}replayGainTrackDb").takeIf {
                    extras.containsKey("${PREFIX}replayGainTrackDb")
                },
                trackPeak = extras.getFloat("${PREFIX}replayGainTrackPeak").takeIf {
                    extras.containsKey("${PREFIX}replayGainTrackPeak")
                },
                albumGainDb = extras.getFloat("${PREFIX}replayGainAlbumDb").takeIf {
                    extras.containsKey("${PREFIX}replayGainAlbumDb")
                },
                albumPeak = extras.getFloat("${PREFIX}replayGainAlbumPeak").takeIf {
                    extras.containsKey("${PREFIX}replayGainAlbumPeak")
                },
            ),
            lyricsLoaded = false,
        )
    }
}
