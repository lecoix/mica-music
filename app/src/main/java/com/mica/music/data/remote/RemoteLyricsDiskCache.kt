package com.mica.music.data.remote

import com.mica.music.data.LyricsDocument
import com.mica.music.data.local.LyricsDocumentCodec
import java.io.File

internal class RemoteLyricsDiskCache(
    directory: File,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val storage = RevisionedDiskCache(directory, maxBytes, maxEntries)

    fun get(songId: String, revision: String, lyricsDataVersion: Int): LyricsDocument? {
        val bytes = storage.get(key(songId, revision, lyricsDataVersion)) ?: return null
        val document = LyricsDocumentCodec.decode(bytes.toString(Charsets.UTF_8))
        return document.takeIf { it.lines.isNotEmpty() }
    }

    fun put(songId: String, revision: String, lyricsDataVersion: Int, document: LyricsDocument) {
        if (document.lines.isEmpty()) return
        storage.put(
            key(songId, revision, lyricsDataVersion),
            LyricsDocumentCodec.encode(document).toByteArray(Charsets.UTF_8),
        )
    }

    private fun key(songId: String, revision: String, lyricsDataVersion: Int): String =
        "remote-lyrics-disk-v1|$lyricsDataVersion|$songId|$revision"

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 2_048
    }
}
