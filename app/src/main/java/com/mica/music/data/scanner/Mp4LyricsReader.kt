package com.mica.music.data.scanner

/** M4A/MP4 内嵌歌词：iTunes `©ly` 原子及 `lyrics` 自由格式。 */
internal object Mp4LyricsReader {

    private val lyricMarkers = listOf(
        byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte()),
        "lyrics".toByteArray(Charsets.US_ASCII),
    )

    fun read(bytes: ByteArray): String? = Mp4AtomTextReader.read(bytes, lyricMarkers)
}
