package com.mica.music.media.ape

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * APE 3.81–3.99 header/frame-table reader.
 *
 * The packet layout mirrors FFmpeg's APE demuxer contract, implemented against Media3's
 * [ExtractorInput].
 */
object ApeHeaderReader {
    private const val MAGIC = "MAC "
    private const val PREFIX_BYTES = 6
    private const val MODERN_DESCRIPTOR_BYTES = 52
    private const val MODERN_HEADER_BYTES = 24
    private const val LEGACY_REMAINDER_BYTES = 26
    private const val SEEK_ENTRY_BYTES = 4
    private const val ID3_HEADER_BYTES = 10
    private const val ID3_FOOTER_BYTES = 10
    private const val ID3_FOOTER_FLAG = 0x10
    private const val MAX_LEADING_ID3_BYTES = 16 * 1024 * 1024
    // At the smallest supported blocks-per-frame this still permits more than five hours at
    // 44.1 kHz, while bounding the one-active-track frame table to single-digit megabytes.
    internal const val MAX_FRAMES = 100_000

    private const val FLAG_8_BIT = 1
    private const val FLAG_HAS_PEAK_LEVEL = 4
    private const val FLAG_24_BIT = 8
    private const val FLAG_HAS_SEEK_ELEMENTS = 16
    private const val FLAG_CREATE_WAV_HEADER = 32

    fun sniffHeader(bytes: ByteArray): Boolean {
        if (bytes.size < PREFIX_BYTES) return false
        val magic = String(bytes, 0, MAGIC.length, StandardCharsets.US_ASCII)
        val version = littleEndianUnsignedShort(bytes, MAGIC.length)
        return magic == MAGIC &&
            version in ApeFormat.MIN_FILE_VERSION..ApeFormat.MAX_FILE_VERSION
    }

    fun sniff(input: ExtractorInput): Boolean {
        val first = ByteArray(PREFIX_BYTES)
        if (!input.peekFully(first, 0, first.size, true)) return false
        if (sniffHeader(first)) return true
        if (!hasId3Magic(first)) return false

        val id3Header = ByteArray(ID3_HEADER_BYTES)
        first.copyInto(id3Header)
        if (!input.peekFully(id3Header, PREFIX_BYTES, ID3_HEADER_BYTES - PREFIX_BYTES, true)) {
            return false
        }
        val id3Length = id3TagLength(id3Header) ?: return false
        if (!input.advancePeekPosition(id3Length - ID3_HEADER_BYTES, true)) return false
        return input.peekFully(first, 0, first.size, true) && sniffHeader(first)
    }

    @Throws(IOException::class)
    fun read(input: ExtractorInput): ApeFormat {
        val first = readFully(input, PREFIX_BYTES)
        val (prefix, junkLength) = if (hasId3Magic(first)) {
            val id3Header = ByteArray(ID3_HEADER_BYTES)
            first.copyInto(id3Header)
            input.readFully(
                id3Header,
                PREFIX_BYTES,
                ID3_HEADER_BYTES - PREFIX_BYTES,
            )
            val id3Length = id3TagLength(id3Header)
                ?: throw IOException("Invalid leading ID3v2 tag")
            skipFully(input, (id3Length - ID3_HEADER_BYTES).toLong())
            readFully(input, PREFIX_BYTES) to id3Length.toLong()
        } else {
            first to 0L
        }
        if (!sniffHeader(prefix)) {
            throw IOException("Not a supported Monkey's Audio stream")
        }
        val version = littleEndianUnsignedShort(prefix, 4)
        val parsedWithoutJunk = if (version >= ApeFormat.MODERN_FILE_VERSION) {
            readModernHeader(input, version)
        } else {
            readLegacyHeader(input, version)
        }
        val parsed = parsedWithoutJunk.copy(junkLength = junkLength)
        validateAudioParameters(parsed)

        val firstFrame =
            parsed.junkLength +
                parsed.descriptorLength +
                parsed.headerLength +
                parsed.seekTableLength +
                parsed.wavHeaderLength

        val rawPositions = LongArray(parsed.totalFrames)
        rawPositions[0] = firstFrame
        readLittleEndianUnsignedInt(input) // seektable[0] is redundant with firstFrame.
        for (index in 1 until parsed.totalFrames) {
            rawPositions[index] = readLittleEndianUnsignedInt(input) + parsed.junkLength
        }
        val unusedSeekBytes = parsed.seekTableLength - parsed.totalFrames.toLong() * SEEK_ENTRY_BYTES
        if (unusedSeekBytes > 0L) {
            skipFully(input, unusedSeekBytes)
        }

        val skips = IntArray(parsed.totalFrames)
        for (index in 1 until parsed.totalFrames) {
            skips[index] = ((rawPositions[index] - rawPositions[0]) and 3L).toInt()
        }
        val rawSizes = LongArray(parsed.totalFrames)
        for (index in 0 until parsed.totalFrames - 1) {
            rawSizes[index] = rawPositions[index + 1] - rawPositions[index]
        }
        rawSizes[parsed.totalFrames - 1] = finalFrameSize(
            inputLength = input.length,
            firstFrame = firstFrame,
            lastFramePosition = rawPositions.last(),
            wavTailLength = parsed.wavTailLength,
            audioDataLength = parsed.audioDataLength,
            finalFrameBlocks = parsed.finalFrameBlocks,
        )

        val frames = ArrayList<ApeFrame>(parsed.totalFrames)
        var ptsBlocks = 0L
        for (index in 0 until parsed.totalFrames) {
            var position = rawPositions[index]
            var size = rawSizes[index]
            val byteSkip = skips[index]
            if (byteSkip > 0) {
                position -= byteSkip
                size += byteSkip
            }
            size = alignToFour(size)
            if (position < 0L || size <= 0L || size > Int.MAX_VALUE - ApeFormat.PACKET_PREFIX_BYTES) {
                throw IOException("Invalid APE frame $index: position=$position size=$size")
            }
            if (input.length != C.LENGTH_UNSET.toLong() && position >= input.length) {
                throw EOFException("APE frame $index starts beyond input length")
            }
            val blocks = if (index == parsed.totalFrames - 1) {
                parsed.finalFrameBlocks
            } else {
                parsed.blocksPerFrame
            }
            frames += ApeFrame(
                position = position,
                sizeBytes = size.toInt(),
                blocks = blocks,
                skip = skips[index],
                timeUs = scaleToUs(ptsBlocks, parsed.sampleRateHz),
            )
            ptsBlocks += parsed.blocksPerFrame
        }
        val totalBlocks =
            (parsed.totalFrames - 1L) * parsed.blocksPerFrame + parsed.finalFrameBlocks
        return ApeFormat(
            fileVersion = version,
            compressionType = parsed.compressionType,
            formatFlags = parsed.formatFlags,
            bitsPerSample = parsed.bitsPerSample,
            channelCount = parsed.channelCount,
            sampleRateHz = parsed.sampleRateHz,
            blocksPerFrame = parsed.blocksPerFrame,
            finalFrameBlocks = parsed.finalFrameBlocks,
            frames = frames,
            durationUs = scaleToUs(totalBlocks, parsed.sampleRateHz),
        )
    }

    private fun readModernHeader(input: ExtractorInput, version: Int): ParsedHeader {
        val rest = littleEndian(readFully(input, MODERN_DESCRIPTOR_BYTES - PREFIX_BYTES))
        rest.short // descriptor padding
        val descriptorLength = rest.unsignedInt()
        val headerLength = rest.unsignedInt()
        val seekTableLength = rest.unsignedInt()
        val wavHeaderLength = rest.unsignedInt()
        val audioDataLengthLow = rest.unsignedInt()
        val audioDataLengthHigh = rest.unsignedInt()
        val wavTailLength = rest.unsignedInt()
        rest.position(rest.position() + 16) // MD5
        if (descriptorLength < MODERN_DESCRIPTOR_BYTES || headerLength < MODERN_HEADER_BYTES) {
            throw IOException("Invalid APE descriptor/header length")
        }
        skipFully(input, descriptorLength - MODERN_DESCRIPTOR_BYTES)
        val header = littleEndian(readFully(input, MODERN_HEADER_BYTES))
        val compressionType = header.unsignedShort()
        val formatFlags = header.unsignedShort()
        val blocksPerFrame = header.unsignedInt()
        val finalFrameBlocks = header.unsignedInt()
        val totalFrames = header.unsignedIntToCount()
        val bitsPerSample = header.unsignedShort()
        val channelCount = header.unsignedShort()
        val sampleRateHz = header.unsignedIntToInt("sample rate")
        skipFully(input, headerLength - MODERN_HEADER_BYTES)
        return ParsedHeader(
            junkLength = 0L,
            fileVersion = version,
            descriptorLength = descriptorLength,
            headerLength = headerLength,
            seekTableLength = seekTableLength,
            wavHeaderLength = wavHeaderLength,
            wavTailLength = wavTailLength,
            audioDataLength = audioDataLengthLow or (audioDataLengthHigh shl 32),
            compressionType = compressionType,
            formatFlags = formatFlags,
            blocksPerFrame = blocksPerFrame,
            finalFrameBlocks = finalFrameBlocks,
            totalFrames = totalFrames,
            bitsPerSample = bitsPerSample,
            channelCount = channelCount,
            sampleRateHz = sampleRateHz,
        )
    }

    private fun readLegacyHeader(input: ExtractorInput, version: Int): ParsedHeader {
        val header = littleEndian(readFully(input, LEGACY_REMAINDER_BYTES))
        val compressionType = header.unsignedShort()
        val formatFlags = header.unsignedShort()
        val channelCount = header.unsignedShort()
        val sampleRateHz = header.unsignedIntToInt("sample rate")
        val wavHeaderLength = header.unsignedInt()
        val wavTailLength = header.unsignedInt()
        val totalFrames = header.unsignedIntToCount()
        val finalFrameBlocks = header.unsignedInt()
        var headerLength = 32L
        if (formatFlags and FLAG_HAS_PEAK_LEVEL != 0) {
            skipFully(input, 4L)
            headerLength += 4L
        }
        val seekTableLength = if (formatFlags and FLAG_HAS_SEEK_ELEMENTS != 0) {
            headerLength += 4L
            readLittleEndianUnsignedInt(input) * SEEK_ENTRY_BYTES
        } else {
            totalFrames.toLong() * SEEK_ENTRY_BYTES
        }
        val bitsPerSample = when {
            formatFlags and FLAG_8_BIT != 0 -> 8
            formatFlags and FLAG_24_BIT != 0 -> 24
            else -> 16
        }
        val blocksPerFrame = when {
            version >= 3_950 -> 73_728L * 4L
            version >= 3_900 || version >= 3_800 && compressionType >= 4_000 -> 73_728L
            else -> 9_216L
        }
        if (formatFlags and FLAG_CREATE_WAV_HEADER == 0) {
            skipFully(input, wavHeaderLength)
        }
        return ParsedHeader(
            junkLength = 0L,
            fileVersion = version,
            descriptorLength = 0L,
            headerLength = headerLength,
            seekTableLength = seekTableLength,
            wavHeaderLength = wavHeaderLength,
            wavTailLength = wavTailLength,
            audioDataLength = 0L,
            compressionType = compressionType,
            formatFlags = formatFlags,
            blocksPerFrame = blocksPerFrame,
            finalFrameBlocks = finalFrameBlocks,
            totalFrames = totalFrames,
            bitsPerSample = bitsPerSample,
            channelCount = channelCount,
            sampleRateHz = sampleRateHz,
        )
    }

    private fun validateAudioParameters(header: ParsedHeader) {
        if (header.totalFrames !in 1..MAX_FRAMES) {
            throw IOException("Unsupported APE frame count: ${header.totalFrames}")
        }
        if (header.seekTableLength < header.totalFrames.toLong() * SEEK_ENTRY_BYTES) {
            throw IOException("APE seek table has fewer entries than frames")
        }
        if (header.channelCount !in 1..2) {
            throw IOException("APE playback supports mono/stereo only")
        }
        if (header.bitsPerSample !in setOf(8, 16, 24)) {
            throw IOException("Unsupported APE bit depth: ${header.bitsPerSample}")
        }
        if (header.compressionType !in setOf(1_000, 2_000, 3_000, 4_000, 5_000) ||
            header.compressionType == 5_000 && header.fileVersion < 3_930
        ) {
            throw IOException("Unsupported APE compression level: ${header.compressionType}")
        }
        if (header.sampleRateHz !in 1..768_000 ||
            header.blocksPerFrame !in 1L..1_000_000L ||
            header.finalFrameBlocks !in 1L..header.blocksPerFrame
        ) {
            throw IOException("Invalid APE audio parameters")
        }
    }

    private fun finalFrameSize(
        inputLength: Long,
        firstFrame: Long,
        lastFramePosition: Long,
        wavTailLength: Long,
        audioDataLength: Long,
        finalFrameBlocks: Long,
    ): Long {
        val fileBound = if (inputLength != C.LENGTH_UNSET.toLong()) {
            inputLength - lastFramePosition - wavTailLength
        } else {
            0L
        }
        val descriptorBound = if (audioDataLength > 0L) {
            firstFrame + audioDataLength - lastFramePosition
        } else {
            0L
        }
        val candidate = when {
            fileBound > 0L -> fileBound
            descriptorBound > 0L -> descriptorBound
            else -> finalFrameBlocks * 8L
        }
        return candidate - (candidate and 3L)
    }

    private fun readFully(input: ExtractorInput, length: Int): ByteArray {
        val bytes = ByteArray(length)
        input.readFully(bytes, 0, length)
        return bytes
    }

    private fun skipFully(input: ExtractorInput, length: Long) {
        if (length < 0L || length > Int.MAX_VALUE) {
            throw IOException("Invalid APE skip length: $length")
        }
        if (length > 0L) input.skipFully(length.toInt())
    }

    private fun readLittleEndianUnsignedInt(input: ExtractorInput): Long =
        littleEndian(readFully(input, 4)).unsignedInt()

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.unsignedShort(): Int = short.toInt() and 0xFFFF

    private fun ByteBuffer.unsignedInt(): Long = int.toLong() and 0xFFFF_FFFFL

    private fun ByteBuffer.unsignedIntToInt(label: String): Int {
        val value = unsignedInt()
        if (value > Int.MAX_VALUE) throw IOException("APE $label is too large: $value")
        return value.toInt()
    }

    private fun ByteBuffer.unsignedIntToCount(): Int {
        val value = unsignedInt()
        if (value > Int.MAX_VALUE) throw IOException("APE frame count is too large: $value")
        return value.toInt()
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun hasId3Magic(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()

    private fun id3TagLength(header: ByteArray): Int? {
        if (header.size < ID3_HEADER_BYTES || !hasId3Magic(header)) return null
        val sizeBytes = header.copyOfRange(6, 10)
        if (sizeBytes.any { it.toInt() and 0x80 != 0 }) return null
        val payloadLength = sizeBytes.fold(0) { value, byte ->
            (value shl 7) or (byte.toInt() and 0x7F)
        }
        val footerLength = if (header[5].toInt() and ID3_FOOTER_FLAG != 0) ID3_FOOTER_BYTES else 0
        val totalLength = ID3_HEADER_BYTES + payloadLength + footerLength
        return totalLength.takeIf { it in ID3_HEADER_BYTES..MAX_LEADING_ID3_BYTES }
    }

    private fun alignToFour(value: Long): Long = (value + 3L) and 3L.inv()

    private fun scaleToUs(blocks: Long, sampleRateHz: Int): Long =
        blocks * 1_000_000L / sampleRateHz

    private data class ParsedHeader(
        val junkLength: Long,
        val fileVersion: Int,
        val descriptorLength: Long,
        val headerLength: Long,
        val seekTableLength: Long,
        val wavHeaderLength: Long,
        val wavTailLength: Long,
        val audioDataLength: Long,
        val compressionType: Int,
        val formatFlags: Int,
        val blocksPerFrame: Long,
        val finalFrameBlocks: Long,
        val totalFrames: Int,
        val bitsPerSample: Int,
        val channelCount: Int,
        val sampleRateHz: Int,
    )
}
