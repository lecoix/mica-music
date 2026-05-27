package com.mica.music.data.scanner

/** M4A/MP4 内嵌歌词：仅解析 ilst 内规范的 iTunes `©ly` 项及其 `data` 子原子。 */
internal object Mp4LyricsReader {

    data class IlstItem(
        val key: String,
        val valuePreview: String,
    )

    private val cLyType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte())
    private val freeformType = "----".toByteArray(Charsets.US_ASCII)
    private val ilstType = "ilst".toByteArray(Charsets.US_ASCII)
    private val dataType = "data".toByteArray(Charsets.US_ASCII)
    private val meanType = "mean".toByteArray(Charsets.US_ASCII)
    private val nameType = "name".toByteArray(Charsets.US_ASCII)

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
        scanTextDataAtoms(bytes, maxPreviewChars = Int.MAX_VALUE)
            .map { it.valuePreview }
            .filter { looksLikeLyrics(it) }
            .forEach { text ->
                if (text.length > bestLen) {
                    best = text
                    bestLen = text.length
                }
            }
        return best?.takeIf { it.isNotBlank() }
    }

    fun listIlstItems(bytes: ByteArray): List<IlstItem> {
        val out = mutableListOf<IlstItem>()
        var from = 0
        while (from < bytes.size) {
            val idx = Id3Binary.indexOf(bytes, ilstType, from)
            if (idx < 0) break
            val ilstStart = idx - 4
            if (ilstStart >= 0) {
                collectIlstItems(bytes, ilstStart, out)
            }
            from = idx + 4
        }
        return out
    }

    fun scanTextDataAtoms(bytes: ByteArray, maxPreviewChars: Int = 500): List<IlstItem> {
        val out = mutableListOf<IlstItem>()
        var from = 0
        while (from < bytes.size) {
            val idx = Id3Binary.indexOf(bytes, dataType, from)
            if (idx < 4) break
            val atomStart = idx - 4
            val atomSize = Id3Binary.readUInt32Be(bytes, atomStart).toInt()
            val atomEnd = atomStart + atomSize
            if (atomSize >= 16 && atomEnd <= bytes.size) {
                readDataAtomText(bytes, atomStart, atomEnd)?.let { text ->
                    val cleaned = MetadataTextFix.normalize(text).trim()
                    if (
                        cleaned.isNotBlank() &&
                        cleaned.length >= 3 &&
                        LyricsEncoding.isRenderable(cleaned) &&
                        !LyricsEncoding.looksLikeMojibake(cleaned)
                    ) {
                        out += IlstItem("data@0x${atomStart.toString(16)}", cleaned.take(maxPreviewChars))
                    }
                }
            }
            from = idx + 4
        }
        return out
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
            when {
                bytesMatch(bytes, pos + 4, cLyType) -> {
                    readItemDataText(bytes, pos + 8, itemEnd)?.let { return it }
                }
                bytesMatch(bytes, pos + 4, freeformType) -> {
                    readFreeformLyrics(bytes, pos + 8, itemEnd)?.let { return it }
                }
            }
            pos = itemEnd
        }
        return null
    }

    private fun collectIlstItems(bytes: ByteArray, ilstBoxStart: Int, out: MutableList<IlstItem>) {
        val ilstSize = Id3Binary.readUInt32Be(bytes, ilstBoxStart).toInt()
        if (ilstSize < 8) return
        val ilstEnd = (ilstBoxStart + ilstSize).coerceAtMost(bytes.size)
        var pos = ilstBoxStart + 8
        while (pos + 8 <= ilstEnd) {
            val itemSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (itemSize < 8) break
            val itemEnd = (pos + itemSize).coerceAtMost(ilstEnd)
            if (itemEnd <= pos + 8) break
            val type = itemTypeName(bytes, pos + 4)
            if (bytesMatch(bytes, pos + 4, freeformType)) {
                readFreeformDebug(bytes, pos + 8, itemEnd)?.let { out += it }
            } else {
                readItemDataDebug(bytes, pos + 8, itemEnd)
                    ?.let { out += IlstItem(type, it) }
            }
            pos = itemEnd
        }
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

    private fun readItemDataDebug(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 8 <= end) {
            val atomSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (atomSize < 8) break
            val atomEnd = (pos + atomSize).coerceAtMost(end)
            if (bytesMatch(bytes, pos + 4, dataType)) {
                val typeCode = readDataTypeCode(bytes, pos)
                if (typeCode == 13 || typeCode == 14) return "<binary artwork ${atomEnd - pos} bytes>"
                return readDataAtomText(bytes, pos, atomEnd)
                    ?.takeIf { it.isNotBlank() }
                    ?.take(500)
            }
            pos = atomEnd
        }
        return null
    }

    private fun readFreeformLyrics(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        var mean = ""
        var name = ""
        var data: String? = null
        while (pos + 8 <= end) {
            val atomSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (atomSize < 8) break
            val atomEnd = (pos + atomSize).coerceAtMost(end)
            when {
                bytesMatch(bytes, pos + 4, meanType) -> {
                    mean = readFreeformTextAtom(bytes, pos, atomEnd).orEmpty()
                }
                bytesMatch(bytes, pos + 4, nameType) -> {
                    name = readFreeformTextAtom(bytes, pos, atomEnd).orEmpty()
                }
                bytesMatch(bytes, pos + 4, dataType) -> {
                    data = readDataAtomText(bytes, pos, atomEnd)
                }
            }
            pos = atomEnd
        }
        val key = "$mean $name".uppercase().replace(" ", "")
        val isLyrics = key.contains("LYRIC") || key.contains("UNSYNCED")
        return data?.takeIf { text ->
            text.isNotBlank() && (isLyrics || looksLikeLyrics(text))
        }
    }

    private fun readFreeformDebug(bytes: ByteArray, start: Int, end: Int): IlstItem? {
        var pos = start
        var mean = ""
        var name = ""
        var data: String? = null
        while (pos + 8 <= end) {
            val atomSize = Id3Binary.readUInt32Be(bytes, pos).toInt()
            if (atomSize < 8) break
            val atomEnd = (pos + atomSize).coerceAtMost(end)
            when {
                bytesMatch(bytes, pos + 4, meanType) -> mean = readFreeformTextAtom(bytes, pos, atomEnd).orEmpty()
                bytesMatch(bytes, pos + 4, nameType) -> name = readFreeformTextAtom(bytes, pos, atomEnd).orEmpty()
                bytesMatch(bytes, pos + 4, dataType) -> data = readDataAtomText(bytes, pos, atomEnd)
            }
            pos = atomEnd
        }
        val key = "---- mean=${mean.ifBlank { "?" }} name=${name.ifBlank { "?" }}"
        return IlstItem(key, data.orEmpty().take(500)).takeIf {
            it.key.isNotBlank() || it.valuePreview.isNotBlank()
        }
    }

    private fun readFreeformTextAtom(bytes: ByteArray, atomStart: Int, atomEnd: Int): String? {
        val textStart = atomStart + 12
        if (textStart >= atomEnd || atomEnd > bytes.size) return null
        return LyricsEncoding.decodeBytes(bytes.copyOfRange(textStart, atomEnd)).trim()
    }

    private fun readDataAtomText(bytes: ByteArray, atomStart: Int, atomEnd: Int): String? {
        val textStart = atomStart + 16
        if (textStart >= atomEnd || atomEnd > bytes.size) return null
        return LyricsEncoding.decodeBytes(bytes.copyOfRange(textStart, atomEnd)).trim()
    }

    private fun readDataTypeCode(bytes: ByteArray, atomStart: Int): Int {
        if (atomStart + 12 > bytes.size) return -1
        return Id3Binary.readUInt32Be(bytes, atomStart + 8).toInt()
    }

    private fun looksLikeLyrics(text: String): Boolean {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !LyricsSanitizer.isNoiseLine(it) }
        if (lines.size >= 8) return true
        return Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(text)
    }

    private fun itemTypeName(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return "????"
        val raw = bytes.copyOfRange(offset, offset + 4)
        return raw.joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else "\\x${c.toString(16).padStart(2, '0')}"
        }
    }

    private fun bytesMatch(bytes: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset + needle.size > bytes.size) return false
        for (i in needle.indices) {
            if (bytes[offset + i] != needle[i]) return false
        }
        return true
    }
}
