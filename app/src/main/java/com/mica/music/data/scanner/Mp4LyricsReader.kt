package com.mica.music.data.scanner

/** M4A/MP4 内嵌歌词：仅解析 ilst 内规范的 iTunes `©ly` 项及其 `data` 子原子。 */
internal object Mp4LyricsReader {

    private val cLyType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte())
    private val ilstType = "ilst".toByteArray(Charsets.US_ASCII)
    private val dataType = "data".toByteArray(Charsets.US_ASCII)

    fun read(bytes: ByteArray): String? {
        var best: String? = null
        var bestLen = 0
        var from = 0
        while (from < bytes.size) {
            val idx = Id3Binary.indexOf(bytes, ilstType, from)
            if (idx < 0) break
            val ilstStart = idx - 4
            if (ilstStart >= 0) {
                readIlstLyrics(bytes, ilstStart)?.let { text ->
                    if (text.length > bestLen) {
                        best = text
                        bestLen = text.length
                    }
                }
            }
            from = idx + 4
        }
        return best?.takeIf { it.isNotBlank() }
    }

    private fun readIlstLyrics(bytes: ByteArray, ilstBoxStart: Int): String? {
        val ilstSize = Id3Binary.readUInt32Be(bytes, ilstBoxStart).toInt()
        if (ilstSize < 8) return null
        val ilstEnd = (ilstBoxStart + ilstSize).coerceAtMost(bytes.size)
        var pos = ilstBoxStart + 8
        while (pos + 8 <= ilstEnd) {
            val itemSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (itemSize < 8) break
            val itemEnd = (pos + itemSize).coerceAtMost(ilstEnd)
            if (itemEnd <= pos + 8) break
            if (bytesMatch(bytes, pos + 4, cLyType)) {
                readItemDataText(bytes, pos + 8, itemEnd)?.let { return it }
            }
            pos = itemEnd
        }
        return null
    }

    private fun readItemDataText(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 8 <= end) {
            val atomSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (atomSize < 8) break
            val atomEnd = (pos + atomSize).coerceAtMost(end)
            if (bytesMatch(bytes, pos + 4, dataType)) {
                Mp4AtomTextReader.readDataAtom(bytes, pos)?.let { return it }
            }
            pos = atomEnd
        }
        return null
    }

    private fun bytesMatch(bytes: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset + needle.size > bytes.size) return false
        for (i in needle.indices) {
            if (bytes[offset + i] != needle[i]) return false
        }
        return true
    }
}
