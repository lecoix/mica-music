package com.mica.music.media.dsf

import androidx.media3.common.util.ParsableByteArray
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

object DsfHeaderReader {

    fun sniffHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == DsfFormat.CHUNK_DSD

    @Throws(IOException::class)
    fun read(input: InputStream): DsfFormat {
        val dsd = readFully(input, DsfFormat.DSD_CHUNK_SIZE.toInt())
        val fmt = readFully(input, DsfFormat.FMT_CHUNK_SIZE.toInt())
        val dataHeader = readFully(input, DsfFormat.DATA_HEADER_SIZE.toInt())
        return parse(
            dsd,
            fmt,
            dataHeader,
            payloadOffset = DsfFormat.DSD_CHUNK_SIZE + DsfFormat.FMT_CHUNK_SIZE + DsfFormat.DATA_HEADER_SIZE,
        )
    }

    @Throws(IOException::class)
    fun parse(
        dsdChunk: ByteArray,
        fmtChunk: ByteArray,
        dataHeader: ByteArray,
        payloadOffset: Long = DsfFormat.DSD_CHUNK_SIZE + DsfFormat.FMT_CHUNK_SIZE + DsfFormat.DATA_HEADER_SIZE,
    ): DsfFormat {
        val dsd = ParsableByteArray(dsdChunk)
        val fmt = ParsableByteArray(fmtChunk)
        val data = ParsableByteArray(dataHeader)

        expectAscii(dsd, DsfFormat.CHUNK_DSD, "DSD")
        val dsdChunkSize = readUInt64(dsd)
        if (dsdChunkSize != DsfFormat.DSD_CHUNK_SIZE) {
            throw IOException("Unexpected DSD chunk size: $dsdChunkSize")
        }
        val totalFileSize = readUInt64(dsd)
        val metadataPointer = readUInt64(dsd)

        expectAscii(fmt, DsfFormat.CHUNK_FMT, "fmt")
        val fmtChunkSize = readUInt64(fmt)
        if (fmtChunkSize != DsfFormat.FMT_CHUNK_SIZE) {
            throw IOException("Unexpected fmt chunk size: $fmtChunkSize")
        }
        val formatVersion = readUInt32(fmt)
        val formatId = readUInt32(fmt)
        if (formatId != DsfFormat.FORMAT_ID_DSD_RAW) {
            throw IOException("Unsupported DSF format id: $formatId")
        }
        val channelType = readUInt32(fmt)
        val channelCount = readUInt32(fmt)
        val sampleRateHz = readUInt32(fmt)
        val bitsPerSample = readUInt32(fmt)
        val sampleCount = readUInt64(fmt)
        val blockSizePerChannel = readUInt32(fmt)

        expectAscii(data, DsfFormat.CHUNK_DATA, "data")
        val dataChunkSize = readUInt64(data)

        if (channelCount <= 0 || sampleRateHz <= 0 || sampleCount <= 0L) {
            throw IOException("Invalid DSF audio parameters")
        }
        if (bitsPerSample != 1) {
            throw IOException("Only 1-bit DSF streams are supported")
        }

        return DsfFormat(
            totalFileSize = totalFileSize,
            metadataPointer = metadataPointer,
            formatVersion = formatVersion,
            formatId = formatId,
            channelType = channelType,
            channelCount = channelCount,
            sampleRateHz = sampleRateHz,
            bitsPerSample = bitsPerSample,
            sampleCount = sampleCount,
            blockSizePerChannel = blockSizePerChannel,
            dataChunkSize = dataChunkSize,
            dataPayloadOffset = payloadOffset,
        )
    }

    private fun expectAscii(buffer: ParsableByteArray, expected: String, label: String) {
        val actual = buffer.readString(expected.length)
        if (actual != expected) {
            throw IOException("Invalid $label chunk header: $actual")
        }
    }

    private fun readUInt32(buffer: ParsableByteArray): Int =
        buffer.readLittleEndianUnsignedInt().toInt()

    private fun readUInt64(buffer: ParsableByteArray): Long {
        val low = buffer.readLittleEndianUnsignedInt()
        val high = buffer.readLittleEndianUnsignedInt()
        return low or (high shl 32)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                throw IOException("DSF header truncated")
            }
            offset += read
        }
        return buffer
    }
}
