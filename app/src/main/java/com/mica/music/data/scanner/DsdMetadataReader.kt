package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.mica.music.data.TrackMetadata
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal object DsdMetadataReader {

    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val copyright: String = "",
        val year: Int = 0,
    )

    data class Result(
        val metadata: TrackMetadata,
        val durationSec: Int,
        val tags: Tags = Tags(),
        val albumArtBytes: ByteArray? = null,
    )

    private data class Id3Data(
        val tags: Tags,
        val albumArtBytes: ByteArray?,
    )

    fun read(context: Context, uri: Uri, draft: TrackDraft): Result? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use pfdUse@{ pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val header = channel.readAt(0, 4) ?: return@pfdUse null
                    when {
                        header.asAscii() == "DSD " -> readDsf(channel, pfd.statSize, draft)
                        header.asAscii() == "FRM8" || header.asAscii() == "FORM" -> readDff(channel, pfd.statSize, draft)
                        else -> null
                    }
                }
            }
        }.getOrNull()

    private fun readDsf(channel: FileChannel, statSize: Long, draft: TrackDraft): Result? {
        val header = channel.readAt(0, 256) ?: return null
        if (header.size < 92 || header.asAscii(0, 4) != "DSD ") return null
        val fileSize = header.u64Le(12).takeIf { it > 0L } ?: statSize.coerceAtLeast(0L)
        val metadataOffset = header.u64Le(20)
        val fmtOffset = findAscii(header, "fmt ", 0).takeIf { it >= 0 } ?: 28
        if (fmtOffset + 52 > header.size) return null
        val channelCount = header.u32Le(fmtOffset + 24).coerceAtLeast(1)
        val sampleRate = header.u32Le(fmtOffset + 28)
        val bitsPerSample = header.u32Le(fmtOffset + 32).takeIf { it > 0 } ?: 1
        val sampleCount = header.u64Le(fmtOffset + 36)
        val durationSec = if (sampleRate > 0 && sampleCount > 0L) {
            (sampleCount / sampleRate).toInt()
        } else {
            draft.durationSec
        }
        val bitrateKbps = bitrateKbps(
            fileSize = fileSize,
            durationSec = durationSec,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            fallbackBps = draft.bitrateBpsFromStore,
        )
        val id3 = readDsfId3(channel, metadataOffset)
        return Result(
            metadata = TrackMetadata(
                containerName = "DSD",
                sampleRateHz = sampleRate,
                bitsPerSample = 1,
                bitrateKbps = bitrateKbps,
                channelCount = channelCount.coerceAtLeast(1),
                playbackMimeType = "audio/x-dsf",
            ),
            durationSec = durationSec,
            tags = id3.tags,
            albumArtBytes = id3.albumArtBytes,
        )
    }

    private fun readDff(channel: FileChannel, statSize: Long, draft: TrackDraft): Result? {
        val bytes = channel.readAt(0, 4 * 1024 * 1024) ?: return null
        if (bytes.size < 16 || (bytes.asAscii(0, 4) != "FRM8" && bytes.asAscii(0, 4) != "FORM")) return null
        var sampleRate = 0
        var channelCount = 0
        var dataBytes = 0L
        var pos = 12
        while (pos + 12 <= bytes.size) {
            val id = bytes.asAscii(pos, 4)
            val size = bytes.u64Be(pos + 4)
            val dataStart = pos + 12
            val dataEnd = (dataStart + size).coerceAtMost(bytes.size.toLong()).toInt()
            when (id) {
                "FVER" -> Unit
                "PROP" -> {
                    val propEnd = dataEnd
                    var sub = dataStart + 4
                    while (sub + 12 <= propEnd) {
                        val subId = bytes.asAscii(sub, 4)
                        val subSize = bytes.u64Be(sub + 4)
                        val subData = sub + 12
                        val subEnd = (subData + subSize).coerceAtMost(propEnd.toLong()).toInt()
                        when (subId) {
                            "FS  " -> if (subData + 4 <= subEnd) sampleRate = bytes.u32Be(subData)
                            "CHNL" -> if (subData + 2 <= subEnd) channelCount = bytes.u16Be(subData)
                        }
                        sub = paddedEnd(subData, subSize)
                    }
                }
                "DSD " -> dataBytes = size
            }
            pos = paddedEnd(dataStart, size)
        }
        if (sampleRate <= 0) return null
        val channels = channelCount.coerceAtLeast(1)
        val durationSec = if (dataBytes > 0L) {
            ((dataBytes * 8L) / (sampleRate.toLong() * channels)).toInt()
        } else {
            draft.durationSec
        }
        val fileSize = statSize.takeIf { it > 0L } ?: dataBytes
        return Result(
            metadata = TrackMetadata(
                containerName = "DSD",
                sampleRateHz = sampleRate,
                bitsPerSample = 1,
                bitrateKbps = bitrateKbps(
                    fileSize = fileSize,
                    durationSec = durationSec,
                    sampleRate = sampleRate,
                    channelCount = channels,
                    bitsPerSample = 1,
                    fallbackBps = draft.bitrateBpsFromStore,
                ),
                channelCount = channels,
                playbackMimeType = "audio/x-dsdiff",
            ),
            durationSec = durationSec,
        )
    }

    private fun readDsfId3(channel: FileChannel, metadataOffset: Long): Id3Data {
        if (metadataOffset <= 0L) return Id3Data(Tags(), null)
        val tagHeader = channel.readAt(metadataOffset, 10) ?: return Id3Data(Tags(), null)
        val totalBytes = if (Id3Binary.isId3Header(tagHeader, 0)) {
            (Id3Binary.synchsafeSize(tagHeader, 6) + 10).coerceIn(10, 16 * 1024 * 1024)
        } else {
            4 * 1024 * 1024
        }
        val bytes = channel.readAt(metadataOffset, totalBytes) ?: return Id3Data(Tags(), null)
        val frames = Id3FrameLister.listAll(bytes)
        fun text(id: String): String = frames.firstOrNull { it.frameId == id }?.preview.orEmpty()
        val year = text("TDRC").takeIf { it.isNotBlank() }
            ?: text("TYER")
        return Id3Data(
            tags = Tags(
                title = text("TIT2"),
                artist = text("TPE1"),
                album = text("TALB"),
                albumArtist = text("TPE2"),
                copyright = text("TCOP"),
                year = year.take(4).toIntOrNull() ?: 0,
            ),
            albumArtBytes = readId3Picture(bytes),
        )
    }

    private fun readId3Picture(bytes: ByteArray): ByteArray? {
        var searchFrom = 0
        while (searchFrom < bytes.size - 10) {
            val start = Id3Binary.indexOf(bytes, "ID3".toByteArray(Charsets.US_ASCII), searchFrom)
            if (start < 0) return null
            readId3PictureAt(bytes, start)?.let { return it }
            searchFrom = start + 3
        }
        return null
    }

    private fun readId3PictureAt(bytes: ByteArray, start: Int): ByteArray? {
        if (!Id3Binary.isId3Header(bytes, start)) return null
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
        val frameHeaderSize = if (versionMajor == 2) 6 else 10
        while (offset + frameHeaderSize <= end) {
            val frameId = String(bytes, offset, frameIdLen, Charsets.US_ASCII).trim('\u0000')
            if (frameId.isEmpty()) break
            val frameSize = when (versionMajor) {
                2 -> readUInt24Be(bytes, offset + 3)
                4 -> Id3Binary.synchsafeSize(bytes, offset + 4)
                else -> Id3Binary.readUInt32Be(bytes, offset + 4).toInt()
            }
            val frameStart = offset + frameHeaderSize
            val frameEnd = (frameStart + frameSize).coerceAtMost(end)
            if (frameEnd <= frameStart) break
            if (frameId == "APIC" || frameId == "PIC") {
                var payload = bytes.copyOfRange(frameStart, frameEnd)
                if (tagUnsync) payload = deunsynchronizeId3(payload)
                parsePicturePayload(frameId, payload)?.let { return it }
            }
            offset = frameEnd
        }
        return null
    }

    private fun parsePicturePayload(frameId: String, payload: ByteArray): ByteArray? {
        if (payload.size < 8) return null
        val encoding = payload[0].toInt() and 0xFF
        var pos = 1
        if (frameId == "PIC") {
            pos += 3
        } else {
            val mimeEnd = payload.indexOfZero(pos)
            if (mimeEnd < 0) return null
            pos = mimeEnd + 1
        }
        if (pos >= payload.size) return null
        pos += 1 // picture type
        val descEnd = textTerminatorEnd(payload, pos, encoding)
        if (descEnd < 0) return null
        pos = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        return payload.copyOfRange(pos, payload.size)
            .takeIf { it.size >= 256 && looksLikeImage(it) }
    }

    private fun readUInt24Be(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun ByteArray.indexOfZero(start: Int): Int {
        var i = start
        while (i < size) {
            if (this[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun textTerminatorEnd(bytes: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < bytes.size) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                i++
            }
            return -1
        } else {
            return bytes.indexOfZero(start)
        }
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ||
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
            )

    private fun deunsynchronizeId3(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            out.add(data[i])
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun bitrateKbps(
        fileSize: Long,
        durationSec: Int,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        fallbackBps: Int,
    ): Int = when {
        durationSec > 0 && fileSize > 0L -> ((fileSize * 8L) / durationSec / 1000L).toInt().coerceAtLeast(0)
        sampleRate > 0 && channelCount > 0 -> (sampleRate.toLong() * channelCount * bitsPerSample / 1000L)
            .toInt()
            .coerceAtLeast(0)
        fallbackBps > 0 -> fallbackBps / 1000
        else -> 0
    }

    private fun FileChannel.readAt(offset: Long, byteCount: Int): ByteArray? {
        val buffer = ByteBuffer.allocate(byteCount)
        position(offset)
        while (buffer.hasRemaining()) {
            val read = read(buffer)
            if (read < 0) break
        }
        val size = buffer.position()
        if (size <= 0) return null
        return buffer.array().copyOf(size)
    }

    private fun ByteArray.asAscii(offset: Int = 0, length: Int = size - offset): String =
        if (offset + length <= size) String(this, offset, length, Charsets.US_ASCII) else ""

    private fun ByteArray.u16Be(offset: Int): Int {
        if (offset + 2 > size) return 0
        return ((this[offset].toInt() and 0xFF) shl 8) or
            (this[offset + 1].toInt() and 0xFF)
    }

    private fun ByteArray.u32Le(offset: Int): Int {
        if (offset + 4 > size) return 0
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun ByteArray.u32Be(offset: Int): Int {
        if (offset + 4 > size) return 0
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun ByteArray.u64Le(offset: Int): Long {
        if (offset + 8 > size) return 0L
        return ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun ByteArray.u64Be(offset: Int): Long {
        if (offset + 8 > size) return 0L
        return ByteBuffer.wrap(this, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun findAscii(bytes: ByteArray, text: String, start: Int): Int =
        Id3Binary.indexOf(bytes, text.toByteArray(Charsets.US_ASCII), start)

    private fun paddedEnd(dataStart: Int, size: Long): Int =
        (dataStart + size + (size and 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
