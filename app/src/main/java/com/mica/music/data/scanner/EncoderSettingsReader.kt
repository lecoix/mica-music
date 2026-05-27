package com.mica.music.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * 读取编码器/转码信息：ENCODERSETTINGS、ID3 TSSE/TENC、FLAC vendor、MP4 ©too 等。
 */
internal object EncoderSettingsReader {

    private val retrieverKeys = listOf(
        "ENCODERSETTINGS",
        "encoder_settings",
        "encodersettings",
        "encoder",
        "ENCODER",
        "tool",
    )

    private val rawPrefixes = listOf(
        "ENCODERSETTINGS=",
        "encoder_settings=",
        "ENCODER=",
    )

    private val mp4ToolMarkers = listOf(
        byteArrayOf(0xA9.toByte(), 't'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte()),
        "tool".toByteArray(Charsets.US_ASCII),
    )

    fun read(context: Context, uri: Uri, retriever: MediaMetadataRetriever? = null): String {
        retriever?.let { fromRetriever(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = AudioProbeBytes.read(context, uri) ?: return ""
        return fromBytes(bytes).orEmpty()
    }

    fun fromRetriever(retriever: MediaMetadataRetriever): String {
        for (key in retrieverKeys) {
            extractMetadataString(retriever, key)
                ?.let { MetadataTextFix.normalize(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    fun fromBytes(bytes: ByteArray): String? {
        for (prefix in rawPrefixes) {
            scanRawValue(bytes, prefix)?.let { return MetadataTextFix.normalize(it) }
        }
        parseId3(bytes)?.let { return MetadataTextFix.normalize(it) }
        parseVorbis(bytes)?.let { return MetadataTextFix.normalize(it) }
        parseMp4Tool(bytes)?.let { return MetadataTextFix.normalize(it) }
        return null
    }

    private fun extractMetadataString(retriever: MediaMetadataRetriever, key: String): String? =
        runCatching {
            val method = MediaMetadataRetriever::class.java.getMethod(
                "extractMetadata",
                String::class.java,
            )
            method.invoke(retriever, key) as? String
        }.getOrNull()

    private fun scanRawValue(bytes: ByteArray, prefix: String): String? {
        val patterns = listOf(
            prefix.toByteArray(Charsets.UTF_8),
            prefix.lowercase().toByteArray(Charsets.UTF_8),
        )
        for (pattern in patterns) {
            val idx = Id3Binary.indexOf(bytes, pattern, 0)
            if (idx >= 0) {
                val value = Id3Binary.extractTextAfterMarker(bytes, idx + pattern.size)
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun parseId3(bytes: ByteArray): String? {
        var searchFrom = 0
        while (searchFrom < bytes.size - 10) {
            val idx = Id3Binary.indexOf(bytes, "ID3".toByteArray(), searchFrom)
            if (idx < 0) break
            parseId3TagAt(bytes, idx)?.let { return it }
            searchFrom = idx + 3
        }
        return null
    }

    private fun parseId3TagAt(bytes: ByteArray, start: Int): String? {
        if (start + 10 > bytes.size || !Id3Binary.isId3Header(bytes, start)) return null
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
        var encSettings: String? = null
        var tsse: String? = null
        var tenc: String? = null

        while (offset + frameIdLen + 6 <= end) {
            val frameId = String(bytes, offset, frameIdLen, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            val sizeOffset = offset + frameIdLen
            val frameSize = if (versionMajor == 4) {
                Id3Binary.synchsafeSize(bytes, sizeOffset)
            } else {
                Id3Binary.readUInt32Be(bytes, sizeOffset).toInt()
            }
            val frameStart = sizeOffset + 4 + 2
            val frameEnd = (frameStart + frameSize).coerceAtMost(end)
            if (frameEnd <= frameStart) break
            when (frameId) {
                "TSSE" -> {
                    var payload = bytes.copyOfRange(frameStart, frameEnd)
                    if (tagUnsync) payload = Id3Binary.deunsynchronize(payload)
                    parseId3TextFrame(payload)?.let { tsse = it }
                }
                "TENC" -> {
                    var payload = bytes.copyOfRange(frameStart, frameEnd)
                    if (tagUnsync) payload = Id3Binary.deunsynchronize(payload)
                    parseId3TextFrame(payload)?.let { tenc = it }
                }
                "TXXX" -> {
                    var payload = bytes.copyOfRange(frameStart, frameEnd)
                    if (tagUnsync) payload = Id3Binary.deunsynchronize(payload)
                    parseTxxxEncoderSettings(payload)?.let { encSettings = it }
                }
            }
            offset = frameEnd
        }
        return encSettings?.takeIf { it.isNotBlank() }
            ?: tsse?.takeIf { it.isNotBlank() }
            ?: tenc?.takeIf { it.isNotBlank() }
    }

    /** ID3 文本帧：encoding(1) + 正文（无语言码），如 TSSE / TENC。 */
    private fun parseId3TextFrame(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        return Id3Binary.decodeTextSlice(payload, 1, encoding)
    }

    private fun parseTxxxEncoderSettings(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xFF
        val descEnd = Id3Binary.textFieldEnd(payload, 1, encoding)
        if (descEnd <= 1) return null
        val desc = Id3Binary.decodeTextSlice(payload, 1, encoding)?.uppercase().orEmpty()
            .replace(" ", "")
        if (!desc.contains("ENCODERSETTINGS") && !desc.contains("ENCODER")) return null
        val valueStart = if (encoding == 1 || encoding == 2) descEnd + 2 else descEnd + 1
        return Id3Binary.decodeTextSlice(payload, valueStart, encoding)
    }

    private fun parseVorbis(bytes: ByteArray): String? {
        val start = Id3Binary.indexOf(bytes, "fLaC".toByteArray(), 0)
        if (start < 0) return null
        var offset = start + 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val blockLen = Id3Binary.readUInt24(bytes, offset + 1)
            val blockStart = offset + 4
            val blockEnd = (blockStart + blockLen).coerceAtMost(bytes.size)
            if (blockType == 4 && blockEnd > blockStart) {
                readVorbisEncoderInfo(bytes, blockStart, blockEnd)?.let { return it }
            }
            offset = blockEnd
            if (isLast) break
        }
        return null
    }

    private fun readVorbisEncoderInfo(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 8 > end) return null
        val vendorLen = Id3Binary.readUInt32Le(bytes, start).toInt()
        if (vendorLen < 0 || start + 4 + vendorLen > end) return null
        val vendor = LyricsEncoding.decodeBytes(
            bytes.copyOfRange(start + 4, start + 4 + vendorLen),
        ).trim()
        var pos = start + 4 + vendorLen
        if (pos + 4 > end) return vendor.takeIf { it.isNotBlank() }
        val count = Id3Binary.readUInt32Le(bytes, pos).toInt()
        pos += 4
        var encSettings: String? = null
        var encoder: String? = null
        for (i in 0 until count) {
            if (pos + 4 > end) break
            val len = Id3Binary.readUInt32Le(bytes, pos).toInt()
            pos += 4
            if (pos + len > end) break
            val entry = LyricsEncoding.decodeBytes(bytes.copyOfRange(pos, pos + len))
            pos += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase().replace(" ", "")
            val value = entry.substring(eq + 1).trim()
            when (key) {
                "ENCODERSETTINGS" -> encSettings = value
                "ENCODER" -> encoder = value
            }
        }
        return encSettings?.takeIf { it.isNotBlank() }
            ?: encoder?.takeIf { it.isNotBlank() }
            ?: vendor.takeIf { it.isNotBlank() }
    }

    private fun parseMp4Tool(bytes: ByteArray): String? =
        Mp4AtomTextReader.read(bytes, mp4ToolMarkers)
}

/** ID3/文件探测共用二进制工具 */
internal object AudioProbeBytes {
    fun read(context: Context, uri: Uri): ByteArray? = readBytes(context, uri, headBytes = 2 * 1024 * 1024, tailBytes = 8 * 1024 * 1024)

    /** 歌词扫描：大文件加大头尾窗口，避免 ID3/标签落在中间被截断。 */
    fun readForLyrics(context: Context, uri: Uri): ByteArray? =
        readBytes(context, uri, headBytes = 8 * 1024 * 1024, tailBytes = 16 * 1024 * 1024)

    fun readFastForLyrics(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String?,
    ): ByteArray? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.lowercase()
        return when {
            ext == "mp3" || mime.contains("mpeg") -> readId3Tag(context, uri)
            ext == "flac" || mime.contains("flac") -> readHead(context, uri, 4 * 1024 * 1024)
            ext == "ape" -> readTail(context, uri, 2 * 1024 * 1024)
            ext in setOf("m4a", "m4b", "mp4", "aac", "alac") || mime.contains("mp4") -> {
                readMp4Moov(context, uri)
                    ?: readHeadAndTail(context, uri, headBytes = 2 * 1024 * 1024, tailBytes = 4 * 1024 * 1024)
            }
            else -> readId3Tag(context, uri)
                ?: readHeadAndTail(context, uri, headBytes = 512 * 1024, tailBytes = 512 * 1024)
        }
    }

    private fun readBytes(
        context: Context,
        uri: Uri,
        headBytes: Int,
        tailBytes: Int,
    ): ByteArray? {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        return when {
            size in 0..25_000_000 -> readAll(context, uri)
            else -> readHeadAndTail(context, uri, headBytes = headBytes, tailBytes = tailBytes)
        }
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

    private fun readId3Tag(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = input.readNBytes(10)
                if (header.size < 10 || !Id3Binary.isId3Header(header, 0)) return@use null
                val tagSize = Id3Binary.synchsafeSize(header, 6)
                val totalSize = (tagSize + 10).coerceAtMost(4 * 1024 * 1024)
                header + input.readNBytes(totalSize - header.size)
            }
        }.getOrNull()

    private fun readHead(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(maxBytes) }
        }.getOrNull()

    private fun readTail(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size <= 0L) return@use null
                val tailLen = maxBytes.toLong().coerceAtMost(size).toInt()
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    if (size > tailLen) fis.skipFully(size - tailLen)
                    fis.readNBytes(tailLen)
                }
            }
        }.getOrNull()

    private fun readMp4Moov(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val fileSize = pfd.statSize.takeIf { it > 0L } ?: channel.size()
                    if (fileSize <= 0L) return@use null
                    var offset = 0L
                    while (offset + 8 <= fileSize) {
                        val header = channel.readAt(offset, 16) ?: return@use null
                        if (header.size < 8) return@use null
                        val boxSize32 = header.readUInt32Be(0)
                        val type = String(header, 4, 4, Charsets.US_ASCII)
                        val headerSize: Long
                        val boxSize: Long
                        when (boxSize32) {
                            0L -> {
                                headerSize = 8L
                                boxSize = fileSize - offset
                            }
                            1L -> {
                                if (header.size < 16) return@use null
                                headerSize = 16L
                                boxSize = header.readUInt64Be(8)
                            }
                            else -> {
                                headerSize = 8L
                                boxSize = boxSize32
                            }
                        }
                        if (boxSize < headerSize) return@use null
                        if (type == "moov") {
                            val safeSize = boxSize.coerceAtMost(16L * 1024L * 1024L).toInt()
                            return@use channel.readAt(offset, safeSize)
                        }
                        offset += boxSize
                    }
                    null
                }
            }
        }.getOrNull()

    private fun readHeadAndTail(context: Context, uri: Uri, headBytes: Int, tailBytes: Int): ByteArray? =
        runCatching {
            val head = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(headBytes) }
                ?: return@runCatching null
            val tail = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                val tailLen = tailBytes.toLong().coerceAtMost(size).toInt()
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    if (size > tailLen) fis.skipFully(size - tailLen)
                    fis.readNBytes(tailLen)
                }
            } ?: return@runCatching null
            head + tail
        }.getOrNull()

    private fun java.io.InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                if (read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun java.nio.channels.FileChannel.readAt(offset: Long, byteCount: Int): ByteArray? {
        val buffer = java.nio.ByteBuffer.allocate(byteCount)
        position(offset)
        while (buffer.hasRemaining()) {
            val read = read(buffer)
            if (read < 0) break
        }
        val size = buffer.position()
        if (size <= 0) return null
        return buffer.array().copyOf(size)
    }

    private fun ByteArray.readUInt32Be(offset: Int): Long {
        if (offset + 4 > size) return 0L
        return ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)
    }

    private fun ByteArray.readUInt64Be(offset: Int): Long {
        if (offset + 8 > size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

internal object Id3Binary {
    fun isId3Header(bytes: ByteArray, start: Int): Boolean =
        start + 3 <= bytes.size &&
            bytes[start] == 'I'.code.toByte() &&
            bytes[start + 1] == 'D'.code.toByte() &&
            bytes[start + 2] == '3'.code.toByte()

    fun extractTextAfterMarker(bytes: ByteArray, start: Int): String {
        val maxEnd = (start + 64 * 1024).coerceAtMost(bytes.size)
        var i = start
        while (i < maxEnd && bytes[i] == 0.toByte()) i++
        var end = i
        while (end < maxEnd) {
            if (bytes[end] == 0.toByte()) break
            end++
        }
        if (end <= i) return ""
        return LyricsEncoding.decodeBytes(bytes.copyOfRange(i, end))
    }

    fun decodeTextSlice(bytes: ByteArray, offset: Int, encoding: Int): String? {
        if (offset >= bytes.size) return null
        val end = when (encoding) {
            1, 2 -> bytes.size
            else -> {
                val fieldEnd = textFieldEnd(bytes, offset, encoding)
                if (fieldEnd <= offset) bytes.size else fieldEnd
            }
        }
        if (end <= offset) return null
        return LyricsEncoding.decodeId3Bytes(bytes.copyOfRange(offset, end), encoding)
            .takeIf { it.isNotEmpty() }
    }

    fun textFieldEnd(bytes: ByteArray, offset: Int, encoding: Int): Int {
        return when (encoding) {
            1, 2 -> {
                var i = offset
                while (i + 1 < bytes.size) {
                    if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                    i++
                }
                bytes.size
            }
            else -> indexOfByte(bytes, 0.toByte(), offset).let { if (it < 0) bytes.size else it }
        }
    }

    fun deunsynchronize(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                out.add(0.toByte())
                i += 2
            } else {
                out.add(data[i])
                i++
            }
        }
        return out.toByteArray()
    }

    fun synchsafeSize(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    fun readUInt32Be(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun readUInt24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from >= bytes.size) return -1
        outer@ for (i in from..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOfByte(bytes: ByteArray, byte: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == byte) return i
        return -1
    }
}
