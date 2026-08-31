package com.mica.music.data.remote

import android.os.Bundle
import com.mica.music.data.Song
import com.mica.music.data.SongSource

/**
 * Non-sensitive remote track metadata that is safe to cross the MediaSession boundary.
 *
 * Endpoint/authentication state is intentionally not representable here.
 */
object RemoteMediaMetadataExtras {
    private const val PREFIX = "mica.remote.metadata."
    private const val VERSION = "${PREFIX}version"
    private const val CURRENT_VERSION = 1
    private const val TITLE = "${PREFIX}title"
    private const val ARTIST = "${PREFIX}artist"
    private const val ALBUM = "${PREFIX}album"
    private const val ALBUM_ARTIST = "${PREFIX}albumArtist"
    private const val MIME = "${PREFIX}mime"
    private const val FILE_NAME = "${PREFIX}fileName"
    private const val SUFFIX = "${PREFIX}suffix"
    private const val SIZE_BYTES = "${PREFIX}sizeBytes"
    private const val SAMPLE_RATE_HZ = "${PREFIX}sampleRateHz"
    private const val BITS_PER_SAMPLE = "${PREFIX}bitsPerSample"
    private const val BITRATE_KBPS = "${PREFIX}bitrateKbps"
    private const val CHANNEL_COUNT = "${PREFIX}channelCount"
    private const val YEAR = "${PREFIX}year"
    private const val TRACK_NUMBER = "${PREFIX}trackNumber"
    private const val DISC_NUMBER = "${PREFIX}discNumber"

    fun encode(track: RemoteTrackSummary): Bundle = Bundle().apply {
        putInt(VERSION, CURRENT_VERSION)
        putString(TITLE, track.title)
        putString(ARTIST, track.artist)
        putString(ALBUM, track.album)
        putString(ALBUM_ARTIST, track.albumArtist)
        putString(MIME, track.mimeTypeHint)
        putString(FILE_NAME, track.fileName)
        putString(SUFFIX, track.suffix)
        putLong(SIZE_BYTES, track.sizeBytes.coerceAtLeast(0L))
        putInt(SAMPLE_RATE_HZ, track.sampleRateHz.coerceAtLeast(0))
        track.bitsPerSample?.takeIf { it > 0 }?.let { putInt(BITS_PER_SAMPLE, it) }
        putInt(BITRATE_KBPS, track.bitrateKbps.coerceAtLeast(0))
        putInt(CHANNEL_COUNT, track.channelCount.coerceAtLeast(0))
        putInt(YEAR, track.year.coerceAtLeast(0))
        putInt(TRACK_NUMBER, track.trackNumber.coerceAtLeast(0))
        putInt(DISC_NUMBER, track.discNumber.coerceAtLeast(0))
    }

    fun encode(song: Song): Bundle {
        require(song.source == SongSource.REMOTE) { "Remote metadata extras require a remote song" }
        return Bundle().apply {
            putInt(VERSION, CURRENT_VERSION)
            putString(TITLE, song.title)
            putString(ARTIST, song.artist)
            putString(ALBUM, song.album)
            putString(ALBUM_ARTIST, song.albumArtist)
            putString(MIME, song.metadata.playbackMimeType)
            putString(FILE_NAME, song.fileName)
            putString(SUFFIX, song.metadata.containerName.lowercase())
            putLong(SIZE_BYTES, song.sizeBytes.coerceAtLeast(0L))
            putInt(SAMPLE_RATE_HZ, song.metadata.sampleRateHz.coerceAtLeast(0))
            song.metadata.bitsPerSample?.takeIf { it > 0 }?.let { putInt(BITS_PER_SAMPLE, it) }
            putInt(BITRATE_KBPS, song.metadata.bitrateKbps.coerceAtLeast(0))
            putInt(CHANNEL_COUNT, song.metadata.channelCount.coerceAtLeast(0))
            putInt(YEAR, song.year.coerceAtLeast(0))
            putInt(TRACK_NUMBER, song.trackNumber.coerceAtLeast(0))
            putInt(DISC_NUMBER, song.discNumber.coerceAtLeast(0))
        }
    }

    fun isTrustedProjection(extras: Bundle?): Boolean = extras?.getInt(VERSION, 0) == CURRENT_VERSION
    fun title(extras: Bundle?): String = extras?.getString(TITLE).orEmpty()
    fun artist(extras: Bundle?): String = extras?.getString(ARTIST).orEmpty()
    fun album(extras: Bundle?): String = extras?.getString(ALBUM).orEmpty()
    fun albumArtist(extras: Bundle?): String = extras?.getString(ALBUM_ARTIST).orEmpty()
    fun mimeType(extras: Bundle?): String = extras?.getString(MIME).orEmpty()
    fun fileName(extras: Bundle?): String = extras?.getString(FILE_NAME).orEmpty()
    fun suffix(extras: Bundle?): String = extras?.getString(SUFFIX).orEmpty()
    fun sizeBytes(extras: Bundle?): Long = extras?.getLong(SIZE_BYTES, 0L)?.coerceAtLeast(0L) ?: 0L
    fun sampleRateHz(extras: Bundle?): Int = extras?.getInt(SAMPLE_RATE_HZ, 0)?.coerceAtLeast(0) ?: 0
    fun bitsPerSample(extras: Bundle?): Int? = extras?.takeIf { it.containsKey(BITS_PER_SAMPLE) }
        ?.getInt(BITS_PER_SAMPLE, 0)?.takeIf { it > 0 }
    fun bitrateKbps(extras: Bundle?): Int = extras?.getInt(BITRATE_KBPS, 0)?.coerceAtLeast(0) ?: 0
    fun channelCount(extras: Bundle?): Int = extras?.getInt(CHANNEL_COUNT, 0)?.coerceAtLeast(0) ?: 0
    fun year(extras: Bundle?): Int = extras?.getInt(YEAR, 0)?.coerceAtLeast(0) ?: 0
    fun trackNumber(extras: Bundle?): Int = extras?.getInt(TRACK_NUMBER, 0)?.coerceAtLeast(0) ?: 0
    fun discNumber(extras: Bundle?): Int = extras?.getInt(DISC_NUMBER, 0)?.coerceAtLeast(0) ?: 0
}
