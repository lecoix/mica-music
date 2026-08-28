package com.mica.music.media.loudness

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.ffmpeg.OfflineFfmpegPcmDecoder
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/**
 * Runs Mica-owned Media3 extractors (currently DSF / APE) off the playback clock, then sends the
 * resulting compressed packets through the shared Media3 FFmpeg decoder.
 */
@UnstableApi
internal object OfflineMicaExtractorPcmDecoder {
    fun decode(
        context: Context,
        uri: Uri,
        extractor: Extractor,
        consumer: OfflineFfmpegPcmDecoder.PcmConsumer,
    ): OfflineFfmpegPcmDecoder.Result {
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        var extractorInput: ExtractorInput? = null
        var packetDecoder: OfflineFfmpegPcmDecoder.PacketDecoder? = null
        val packetTrack = PacketTrackOutput(
            onFormat = { format ->
                check(packetDecoder == null) { "Offline extractor changed audio format mid-stream" }
                packetDecoder = OfflineFfmpegPcmDecoder.createPacketDecoder(format, consumer)
            },
            onPacket = { packet, timeUs ->
                val decoder = packetDecoder ?: error("Compressed sample arrived before format")
                decoder.queue(packet, 0, packet.size, timeUs)
            },
        )
        val extractorOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = packetTrack
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        val positionHolder = PositionHolder()

        fun reopen(position: Long): ExtractorInput {
            runCatching { dataSource.close() }
            val length = dataSource.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(position)
                    .build(),
            )
            val streamLength = if (length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                position + length
            }
            return DefaultExtractorInput(dataSource, position, streamLength).also {
                extractorInput = it
            }
        }

        try {
            extractor.init(extractorOutput)
            var input = reopen(0L)
            while (true) {
                when (extractor.read(input, positionHolder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> input = reopen(positionHolder.position)
                    Extractor.RESULT_END_OF_INPUT -> break
                    else -> error("Unexpected extractor result")
                }
            }
            return (packetDecoder ?: error("Extractor produced no audio format")).finish()
        } finally {
            runCatching { packetDecoder?.close() }
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
            extractorInput = null
        }
    }

    private class PacketTrackOutput(
        private val onFormat: (Format) -> Unit,
        private val onPacket: (ByteArray, Long) -> Unit,
    ) : TrackOutput {
        private var pending = ByteArray(0)
        private var pendingSize = 0

        override fun format(format: Format) {
            onFormat(format)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            ensureCapacity(pendingSize + length)
            val read = input.read(pending, pendingSize, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw IOException("Unexpected end of compressed sample")
            }
            pendingSize += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensureCapacity(pendingSize + length)
            data.readBytes(pending, pendingSize, length)
            pendingSize += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val start = pendingSize - offset - size
            check(start >= 0) { "Invalid compressed sample metadata" }
            val packet = pending.copyOfRange(start, start + size)
            onPacket(packet, timeUs)

            val trailingStart = start + size
            val trailingBytes = pendingSize - trailingStart
            if (trailingBytes > 0) {
                pending.copyInto(pending, 0, trailingStart, pendingSize)
            }
            pendingSize = trailingBytes
        }

        private fun ensureCapacity(required: Int) {
            if (pending.size >= required) return
            var next = pending.size.coerceAtLeast(4096)
            while (next < required) next *= 2
            pending = pending.copyOf(next)
        }
    }
}
