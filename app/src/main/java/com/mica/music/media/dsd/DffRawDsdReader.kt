package com.mica.music.media.dsd

import kotlin.math.min

internal class DffRawDsdReader private constructor(
    private val source: SeekableByteSource,
    override val info: DsdStreamInfo,
    private val dataOffset: Long,
) : DsdContainerReader {
    override val sourceIdentity: ByteSourceIdentity = source.identity
    override var framePosition: Long = 0L
        private set

    override fun readFrames(destination: ByteArray, destinationOffset: Int, maxFrames: Int): Int {
        require(destinationOffset >= 0 && maxFrames >= 0)
        require(maxFrames <= (destination.size - destinationOffset) / info.channelCount) {
            "destination cannot hold $maxFrames DSD frames"
        }
        if (maxFrames == 0 || framePosition >= info.byteFrameCount) return 0

        val frames = min(maxFrames.toLong(), info.byteFrameCount - framePosition).toInt()
        val bytes = frames * info.channelCount
        val sourceOffset = checkedAdd(
            dataOffset,
            checkedMultiply(framePosition, info.channelCount.toLong(), "DFF frame offset"),
            "DFF read offset",
        )
        var total = 0
        while (total < bytes) {
            val read = source.readAt(sourceOffset + total, destination, destinationOffset + total, bytes - total)
            if (read <= 0) {
                throw DsdContainerException(
                    DsdContainerFailure.TRUNCATED,
                    "DFF DSD payload ended while reading frame $framePosition",
                )
            }
            total += read
        }
        framePosition += frames
        return frames
    }

    override fun seekToSample(sampleIndex: Long): Long {
        require(sampleIndex >= 0L) { "sampleIndex must be non-negative" }
        val clamped = min(sampleIndex, info.sampleCountPerChannel)
        val aligned = (clamped / 8L) * 8L
        framePosition = min(aligned / 8L, info.byteFrameCount)
        return aligned
    }

    override fun close() {
        source.close()
    }

    companion object {
        private const val CHUNK_HEADER_SIZE = 12L
        private const val FORM_HEADER_SIZE = 16L

        fun open(source: SeekableByteSource): DffRawDsdReader {
            val formHeader = source.readFullyAt(0L, FORM_HEADER_SIZE.toInt())
            requireAscii(formHeader, 0, "FRM8", "DSDIFF FRM8")
            val formSize = formHeader.u64Be(4)
            if (formSize < 4L) malformed("DSDIFF FRM8 chunk is too small")
            requireAscii(formHeader, 12, "DSD ", "DSDIFF form type")
            val formEnd = checkedAdd(CHUNK_HEADER_SIZE, formSize, "DSDIFF FRM8 end")
            source.length?.let { actual ->
                if (formEnd > actual) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF FRM8 ends at $formEnd but source has $actual bytes",
                    )
                }
            }

            var sampleRateHz: Int? = null
            var channelCount: Int? = null
            var compressionType: String? = null
            var dsdDataOffset: Long? = null
            var dsdDataBytes: Long? = null
            var sawDstData = false

            var position = FORM_HEADER_SIZE
            while (position < formEnd) {
                if (formEnd - position < CHUNK_HEADER_SIZE) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF child chunk header truncated at $position",
                    )
                }
                val chunkHeader = source.readFullyAt(position, CHUNK_HEADER_SIZE.toInt())
                val id = chunkHeader.decodeAscii(0, 4)
                val size = chunkHeader.u64Be(4)
                val dataStart = checkedAdd(position, CHUNK_HEADER_SIZE, "$id data start")
                val dataEnd = checkedAdd(dataStart, size, "$id data end")
                if (dataEnd > formEnd) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF $id chunk exceeds FRM8 boundary",
                    )
                }

                when (id) {
                    "PROP" -> {
                        val properties = parseSoundProperties(source, dataStart, dataEnd)
                        properties.sampleRateHz?.let { sampleRateHz = it }
                        properties.channelCount?.let { channelCount = it }
                        properties.compressionType?.let { compressionType = it }
                    }
                    "DSD " -> {
                        if (dsdDataOffset != null) malformed("Multiple DSD sound-data chunks are not supported")
                        dsdDataOffset = dataStart
                        dsdDataBytes = size
                    }
                    "DST " -> sawDstData = true
                }
                position = paddedEnd(dataEnd, size, formEnd, id)
            }

            if (sawDstData || compressionType == "DST ") {
                throw DsdContainerException(
                    DsdContainerFailure.DST_UNSUPPORTED,
                    "DST-compressed DSDIFF is not supported by P5 v1",
                )
            }
            if (compressionType != "DSD ") {
                throw DsdContainerException(
                    DsdContainerFailure.UNSUPPORTED,
                    "Unsupported DSDIFF compression type: ${compressionType ?: "missing"}",
                )
            }
            val rate = sampleRateHz ?: malformed("DSDIFF PROP is missing FS")
            val channels = channelCount ?: malformed("DSDIFF PROP is missing CHNL")
            val dataOffset = dsdDataOffset ?: malformed("DSDIFF is missing DSD sound-data chunk")
            val dataBytes = dsdDataBytes ?: malformed("DSDIFF is missing DSD data length")
            if (channels <= 0) malformed("Invalid DSDIFF channel count: $channels")
            if (dataBytes % channels != 0L) {
                malformed("DSDIFF DSD data size $dataBytes is not a whole clustered frame for $channels channels")
            }
            source.length?.let { actual ->
                val dataEnd = checkedAdd(dataOffset, dataBytes, "DSDIFF DSD payload end")
                if (dataEnd > actual) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF DSD payload ends at $dataEnd but source has $actual bytes",
                    )
                }
            }
            val frames = dataBytes / channels
            val sampleCount = checkedMultiply(frames, 8L, "DSDIFF sample count")

            return DffRawDsdReader(
                source = source,
                info = DsdStreamInfo(
                    container = DsdContainerType.DFF,
                    sampleRateHz = rate,
                    channelCount = channels,
                    sampleCountPerChannel = sampleCount,
                    sourceBitOrder = DsdSourceBitOrder.MSB_FIRST,
                ),
                dataOffset = dataOffset,
            )
        }

        private data class SoundProperties(
            val sampleRateHz: Int?,
            val channelCount: Int?,
            val compressionType: String?,
        )

        private fun parseSoundProperties(
            source: SeekableByteSource,
            dataStart: Long,
            dataEnd: Long,
        ): SoundProperties {
            if (dataEnd - dataStart < 4L) malformed("DSDIFF PROP chunk is too small")
            val propertyType = source.readFullyAt(dataStart, 4).decodeAscii()
            if (propertyType != "SND ") return SoundProperties(null, null, null)

            var sampleRateHz: Int? = null
            var channelCount: Int? = null
            var compressionType: String? = null
            var position = dataStart + 4L
            while (position < dataEnd) {
                if (dataEnd - position < CHUNK_HEADER_SIZE) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF PROP child header truncated at $position",
                    )
                }
                val header = source.readFullyAt(position, CHUNK_HEADER_SIZE.toInt())
                val id = header.decodeAscii(0, 4)
                val size = header.u64Be(4)
                val childData = checkedAdd(position, CHUNK_HEADER_SIZE, "$id property start")
                val childEnd = checkedAdd(childData, size, "$id property end")
                if (childEnd > dataEnd) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSDIFF PROP/$id exceeds PROP boundary",
                    )
                }
                when (id) {
                    "FS  " -> {
                        if (size < 4L) malformed("DSDIFF FS chunk is too small")
                        val raw = source.readFullyAt(childData, 4).u32Be(0)
                        if (raw <= 0L || raw > Int.MAX_VALUE) malformed("Invalid DSDIFF sample rate: $raw")
                        sampleRateHz = raw.toInt()
                    }
                    "CHNL" -> {
                        if (size < 2L) malformed("DSDIFF CHNL chunk is too small")
                        val parsedChannels = source.readFullyAt(childData, 2).u16Be(0)
                        if (parsedChannels <= 0) malformed("Invalid DSDIFF channel count: $parsedChannels")
                        val requiredBytes = checkedAdd(
                            2L,
                            checkedMultiply(parsedChannels.toLong(), 4L, "DSDIFF CHNL ids"),
                            "DSDIFF CHNL size",
                        )
                        if (size < requiredBytes) {
                            throw DsdContainerException(
                                DsdContainerFailure.TRUNCATED,
                                "DSDIFF CHNL declares $parsedChannels channels but only has $size bytes",
                            )
                        }
                        channelCount = parsedChannels
                    }
                    "CMPR" -> {
                        if (size < 4L) malformed("DSDIFF CMPR chunk is too small")
                        compressionType = source.readFullyAt(childData, 4).decodeAscii()
                    }
                }
                position = paddedEnd(childEnd, size, dataEnd, "PROP/$id")
            }
            return SoundProperties(sampleRateHz, channelCount, compressionType)
        }

        private fun paddedEnd(dataEnd: Long, chunkSize: Long, containerEnd: Long, label: String): Long {
            val next = if ((chunkSize and 1L) == 0L) dataEnd else checkedAdd(dataEnd, 1L, "$label pad")
            if (next > containerEnd) {
                throw DsdContainerException(
                    DsdContainerFailure.TRUNCATED,
                    "$label padding exceeds container boundary",
                )
            }
            return next
        }

        private fun requireAscii(bytes: ByteArray, offset: Int, expected: String, label: String) {
            if (bytes.decodeAscii(offset, expected.length) != expected) malformed("Invalid $label")
        }

        private fun malformed(message: String): Nothing =
            throw DsdContainerException(DsdContainerFailure.MALFORMED, message)
    }
}
