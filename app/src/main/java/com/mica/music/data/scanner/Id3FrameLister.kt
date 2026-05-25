package com.mica.music.data.scanner

internal data class Id3FrameInfo(
    val tagIndex: Int,
    val frameId: String,
    val size: Int,
    val encoding: Int?,
    val preview: String,
)

internal object Id3FrameLister {

    fun listAll(bytes: ByteArray): List<Id3FrameInfo> {
        val result = mutableListOf<Id3FrameInfo>()
        var searchFrom = 0
        var tagIndex = 0
        while (searchFrom < bytes.size - 10) {
            val idx = Id3Binary.indexOf(bytes, "ID3".toByteArray(), searchFrom)
            if (idx < 0) break
            listTagAt(bytes, idx, tagIndex, result)
            tagIndex++
            searchFrom = idx + 3
        }
        return result
    }

    private fun listTagAt(
        bytes: ByteArray,
        start: Int,
        tagIndex: Int,
        out: MutableList<Id3FrameInfo>,
    ) {
        if (!Id3Binary.isId3Header(bytes, start)) return
        val versionMajor = bytes[start + 3].toInt()
        val flags = bytes[start + 5].toInt()
        val tagUnsync = flags and 0x80 != 0
        val tagSize = Id3Binary.synchsafeSize(bytes, start + 6)
        var offset = start + 10
        when {
            versionMajor == 4 && flags and 0x40 != 0 -> {
                val extSize = Id3Binary.synchsafeSize(bytes, offset)
                offset += 4 + extSize
            }
            versionMajor == 3 && flags and 0x40 != 0 -> {
                val extSize = Id3Binary.readUInt32Be(bytes, offset).toInt()
                if (extSize >= 4) offset += extSize
            }
        }
        val end = (start + 10 + tagSize).coerceAtMost(bytes.size)
        val frameIdLen = if (versionMajor == 2) 3 else 4
        out += Id3FrameInfo(
            tagIndex = tagIndex,
            frameId = "HEADER",
            size = tagSize,
            encoding = null,
            preview = "v2.$versionMajor flags=0x${flags.toString(16)}",
        )
        while (offset + frameIdLen + 6 <= end) {
            val frameId = String(bytes, offset, frameIdLen, Charsets.US_ASCII).trim('\u0000')
            if (frameId.isEmpty() || frameId.all { it == '\u0000' }) break
            val sizeOffset = offset + frameIdLen
            val frameSize = if (versionMajor == 4) {
                Id3Binary.synchsafeSize(bytes, sizeOffset)
            } else {
                Id3Binary.readUInt32Be(bytes, sizeOffset).toInt()
            }
            val frameStart = sizeOffset + 4 + 2
            val frameEnd = (frameStart + frameSize).coerceAtMost(end)
            if (frameEnd <= frameStart) break
            var payload = bytes.copyOfRange(frameStart, frameEnd)
            if (tagUnsync) payload = Id3Binary.deunsynchronize(payload)
            val encoding = payload.firstOrNull()?.toInt()?.and(0xFF)
            val preview = frameTextPreview(frameId, payload)
            out += Id3FrameInfo(tagIndex, frameId, frameSize, encoding, preview)
            offset = frameEnd
        }
    }

    private fun frameTextPreview(frameId: String, payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        if (frameId == "APIC" || frameId == "PIC") return "[binary ${payload.size} bytes]"
        val text = when (frameId) {
            "USLT", "ULT", "LYR" -> previewUslt(payload)
            "COMM" -> previewComm(payload)
            "TXXX" -> previewTxxx(payload)
            else -> previewGeneric(payload)
        }
        return text?.replace('\n', ' ')?.take(400).orEmpty()
    }

    private fun previewUslt(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        var i = skipId3Field(payload, 4, encoding)
        return Id3Binary.decodeTextSlice(payload, i, encoding)
    }

    private fun previewComm(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        var i = skipId3Field(payload, 4, encoding)
        i = skipId3Field(payload, i, encoding)
        return Id3Binary.decodeTextSlice(payload, i, encoding)
    }

    private fun previewTxxx(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xFF
        val descEnd = Id3Binary.textFieldEnd(payload, 1, encoding)
        val desc = Id3Binary.decodeTextSlice(payload, 1, encoding).orEmpty()
        val valueStart = if (encoding == 1 || encoding == 2) descEnd + 2 else descEnd + 1
        val value = Id3Binary.decodeTextSlice(payload, valueStart, encoding).orEmpty()
        return "$desc = $value"
    }

    private fun previewGeneric(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        return if (encoding in 0..3 && payload.size > 1) {
            Id3Binary.decodeTextSlice(payload, 1, encoding)
        } else {
            LyricsEncoding.decodeBytes(payload)
        }
    }

    private fun skipId3Field(payload: ByteArray, start: Int, encoding: Int): Int {
        if (start >= payload.size) return start
        return when (encoding) {
            1, 2 -> {
                var i = start
                while (i + 1 < payload.size) {
                    if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte()) return i + 2
                    i++
                }
                payload.size
            }
            else -> {
                var i = start
                while (i < payload.size && payload[i] != 0.toByte()) i++
                if (i < payload.size) i + 1 else i
            }
        }
    }
}

internal object VorbisCommentLister {

    fun listAll(bytes: ByteArray): List<Pair<String, String>> {
        val start = Id3Binary.indexOf(bytes, "fLaC".toByteArray(), 0)
        if (start < 0) return emptyList()
        var offset = start + 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val blockLen = Id3Binary.readUInt24(bytes, offset + 1)
            val blockStart = offset + 4
            val blockEnd = (blockStart + blockLen).coerceAtMost(bytes.size)
            if (blockType == 4 && blockEnd > blockStart) {
                return readComments(bytes, blockStart, blockEnd)
            }
            offset = blockEnd
            if (isLast) break
        }
        return emptyList()
    }

    private fun readComments(bytes: ByteArray, start: Int, end: Int): List<Pair<String, String>> {
        if (start + 8 > end) return emptyList()
        val vendorLen = Id3Binary.readUInt32Le(bytes, start).toInt()
        var pos = start + 4 + vendorLen
        if (pos + 4 > end) return emptyList()
        val count = Id3Binary.readUInt32Le(bytes, pos).toInt()
        pos += 4
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until count) {
            if (pos + 4 > end) break
            val len = Id3Binary.readUInt32Le(bytes, pos).toInt()
            pos += 4
            if (pos + len > end) break
            val entry = LyricsEncoding.decodeBytes(bytes.copyOfRange(pos, pos + len))
            pos += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq)
            val value = entry.substring(eq + 1)
            out += key to value
        }
        return out
    }
}
