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
    private const val MIME = "${PREFIX}mime"
    private const val FILE_NAME = "${PREFIX}fileName"
    private const val SUFFIX = "${PREFIX}suffix"
    private const val SIZE_BYTES = "${PREFIX}sizeBytes"
    private const val YEAR = "${PREFIX}year"
    private const val TRACK_NUMBER = "${PREFIX}trackNumber"
    private const val DISC_NUMBER = "${PREFIX}discNumber"

    fun encode(track: RemoteTrackSummary): Bundle = Bundle().apply {
        putInt(VERSION, CURRENT_VERSION)
        putString(MIME, track.mimeTypeHint)
        putString(FILE_NAME, track.fileName)
        putString(SUFFIX, track.suffix)
        putLong(SIZE_BYTES, track.sizeBytes.coerceAtLeast(0L))
        putInt(YEAR, track.year.coerceAtLeast(0))
        putInt(TRACK_NUMBER, track.trackNumber.coerceAtLeast(0))
        putInt(DISC_NUMBER, track.discNumber.coerceAtLeast(0))
    }

    fun encode(song: Song): Bundle {
        require(song.source == SongSource.REMOTE) { "Remote metadata extras require a remote song" }
        return Bundle().apply {
            putInt(VERSION, CURRENT_VERSION)
            putString(MIME, song.metadata.playbackMimeType)
            putString(FILE_NAME, song.fileName)
            putString(SUFFIX, song.metadata.containerName.lowercase())
            putLong(SIZE_BYTES, song.sizeBytes.coerceAtLeast(0L))
            putInt(YEAR, song.year.coerceAtLeast(0))
            putInt(TRACK_NUMBER, song.trackNumber.coerceAtLeast(0))
            putInt(DISC_NUMBER, song.discNumber.coerceAtLeast(0))
        }
    }

    fun isTrustedProjection(extras: Bundle?): Boolean = extras?.getInt(VERSION, 0) == CURRENT_VERSION
    fun mimeType(extras: Bundle?): String = extras?.getString(MIME).orEmpty()
    fun fileName(extras: Bundle?): String = extras?.getString(FILE_NAME).orEmpty()
    fun suffix(extras: Bundle?): String = extras?.getString(SUFFIX).orEmpty()
    fun sizeBytes(extras: Bundle?): Long = extras?.getLong(SIZE_BYTES, 0L)?.coerceAtLeast(0L) ?: 0L
    fun year(extras: Bundle?): Int = extras?.getInt(YEAR, 0)?.coerceAtLeast(0) ?: 0
    fun trackNumber(extras: Bundle?): Int = extras?.getInt(TRACK_NUMBER, 0)?.coerceAtLeast(0) ?: 0
    fun discNumber(extras: Bundle?): Int = extras?.getInt(DISC_NUMBER, 0)?.coerceAtLeast(0) ?: 0
}
