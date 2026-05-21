package com.mica.music.data.scanner

/**
 * 从 M4A/MP4 的 ilst 原子（如 `©ly`、`©too`）读取 UTF-8 文本。
 */
internal object Mp4AtomTextReader {

    fun read(bytes: ByteArray, markers: List<ByteArray>): String? {
        var best = ""
        var bestLen = 0
        for (marker in markers) {
            var from = 0
            while (from < bytes.size) {
                val idx = Id3Binary.indexOf(bytes, marker, from)
                if (idx < 0) break
                readNearMarker(bytes, idx, marker.size)?.let { text ->
                    if (text.length > bestLen) {
                        best = text
                        bestLen = text.length
                    }
                }
                from = idx + 1
            }
        }
        return best.takeIf { it.isNotBlank() }
    }

    private fun readNearMarker(bytes: ByteArray, markerIndex: Int, markerLen: Int): String? {
        val windowEnd = (markerIndex + markerLen + 256).coerceAtMost(bytes.size)
        var i = markerIndex + markerLen
        while (i + 8 <= windowEnd) {
            if (bytes[i] == 'd'.code.toByte() &&
                i + 3 < bytes.size &&
                bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() &&
                bytes[i + 3] == 'a'.code.toByte()
            ) {
                readDataAtomText(bytes, i)?.let { return it }
            }
            i++
        }
        return null
    }

    private fun readDataAtomText(bytes: ByteArray, dataTypeOffset: Int): String? {
        val sizeOffset = dataTypeOffset - 4
        if (sizeOffset < 0) return null
        val atomSize = Id3Binary.readUInt32Be(bytes, sizeOffset).toInt()
        if (atomSize < 12) return null
        val payloadStart = dataTypeOffset + 4
        val payloadLen = atomSize - 8
        if (payloadLen <= 4 || payloadStart + payloadLen > bytes.size) return null
        val textStart = payloadStart + 4
        val textLen = payloadLen - 4
        if (textLen <= 0 || textStart + textLen > bytes.size) return null
        return LyricsEncoding.decodeBytes(bytes.copyOfRange(textStart, textStart + textLen))
    }
}
