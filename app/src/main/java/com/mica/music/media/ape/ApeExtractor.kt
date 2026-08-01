package com.mica.music.media.ape

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException

/** Media3 ingress for Monkey's Audio using FFmpeg's `nblocks + skip` packet contract. */
@UnstableApi
class ApeExtractor : Extractor {
    private var format: ApeFormat? = null
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerLoaded = false
    private var frameIndex = 0
    private var packet = ParsableByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        return ApeHeaderReader.sniff(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerLoaded) {
            loadHeader(input)
            headerLoaded = true
        }
        val ape = format ?: return Extractor.RESULT_END_OF_INPUT
        val frame = ape.frames.getOrNull(frameIndex) ?: return Extractor.RESULT_END_OF_INPUT
        if (input.position != frame.position) {
            seekPosition.position = frame.position
            return Extractor.RESULT_SEEK
        }

        val packetBytes = ApeFormat.PACKET_PREFIX_BYTES + frame.sizeBytes
        packet.reset(packetBytes)
        writeLittleEndianInt(packet.data, 0, frame.blocks)
        writeLittleEndianInt(packet.data, 4, frame.skip.toLong())
        val payloadBytes = readApeFramePayload(
            input = input,
            target = packet.data,
            offset = ApeFormat.PACKET_PREFIX_BYTES,
            length = frame.sizeBytes,
            isLastFrame = frameIndex == ape.frames.lastIndex,
        )
        val actualPacketBytes = ApeFormat.PACKET_PREFIX_BYTES + payloadBytes
        packet.setPosition(0)
        packet.setLimit(actualPacketBytes)
        checkNotNull(trackOutput).sampleData(packet, actualPacketBytes)
        checkNotNull(trackOutput).sampleMetadata(
            frame.timeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            actualPacketBytes,
            0,
            null,
        )
        frameIndex++
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        val ape = format ?: return
        frameIndex = ape.frameIndexForTimeUs(timeUs)
    }

    override fun release() = Unit

    private fun loadHeader(input: ExtractorInput) {
        val ape = ApeHeaderReader.read(input)
        format = ape
        frameIndex = 0
        val durationSeconds = ape.durationUs / 1_000_000.0
        val averageBitrate = if (durationSeconds > 0.0 && input.length > 0L) {
            (input.length * 8.0 / durationSeconds).toInt()
        } else {
            Format.NO_VALUE
        }
        checkNotNull(trackOutput).format(
            Format.Builder()
                .setSampleMimeType(ApeFormat.MIME)
                .setContainerMimeType(ApeFormat.CONTAINER_MIME)
                .setCodecs("ape")
                .setChannelCount(ape.channelCount)
                .setSampleRate(ape.sampleRateHz)
                .setPcmEncoding(ape.pcmEncoding)
                .setAverageBitrate(averageBitrate)
                .setMaxInputSize(ape.maxPacketBytes)
                .setInitializationData(listOf(ape.decoderInitializationData))
                .build(),
        )
        extractorOutput?.seekMap(ApeSeekMap(ape))
    }

    private class ApeSeekMap(
        private val ape: ApeFormat,
    ) : SeekMap {
        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = ape.durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val index = ape.frameIndexForTimeUs(timeUs)
            val frame = ape.frames[index]
            val first = SeekPoint(frame.timeUs, frame.position)
            val next = ape.frames.getOrNull(index + 1)
            return if (next != null && frame.timeUs < timeUs) {
                SeekMap.SeekPoints(first, SeekPoint(next.timeUs, next.position))
            } else {
                SeekMap.SeekPoints(first)
            }
        }
    }

    private companion object {
        fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Long) {
            target[offset] = value.toByte()
            target[offset + 1] = (value ushr 8).toByte()
            target[offset + 2] = (value ushr 16).toByte()
            target[offset + 3] = (value ushr 24).toByte()
        }
    }
}

@Throws(IOException::class)
internal fun readApeFramePayload(
    input: ExtractorInput,
    target: ByteArray,
    offset: Int,
    length: Int,
    isLastFrame: Boolean,
): Int {
    if (!isLastFrame) {
        input.readFully(target, offset, length)
        return length
    }

    var totalRead = 0
    while (totalRead < length) {
        val read = input.read(target, offset + totalRead, length - totalRead)
        if (read == C.RESULT_END_OF_INPUT) {
            if (totalRead == 0) throw EOFException("APE last frame has no payload")
            break
        }
        if (read <= 0) throw IOException("APE last frame read made no progress")
        totalRead += read
    }
    return totalRead
}
