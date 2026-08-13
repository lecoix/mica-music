package com.mica.music.media.dsf

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/**
 * Media3 progressive extractor for Sony DSF (`.dsf`) files.
 *
 * Outputs channel-planar DSF payload packets for a downstream renderer. Full packets preserve the
 * on-disk per-channel block layout; the final packet compacts each channel's valid tail while
 * preserving that planar shape. Header parsing is validated against [DsfHeaderReader].
 */
@UnstableApi
class DsfExtractor : Extractor {

  private var format: DsfFormat? = null
  private var extractorOutput: ExtractorOutput? = null
  private var trackOutput: TrackOutput? = null
  private var sampleIndex: Long = 0L
  private var headerLoaded: Boolean = false

  override fun sniff(input: ExtractorInput): Boolean {
    val scratch = ParsableByteArray(SNIFF_BYTES)
    if (input.peekFully(scratch.data, 0, SNIFF_BYTES, true)) {
      return DsfHeaderReader.sniffHeader(scratch.data)
    }
    return false
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
    val dsf = format ?: return RESULT_END_OF_INPUT
    val remaining = DsfSeekMath.payloadBytesRemaining(dsf, sampleIndex)
    if (remaining <= 0L) {
      return RESULT_END_OF_INPUT
    }
    val bytesRead = readDsfPacket(input, dsf, remaining)
    val timeUs = sampleIndex * 1_000_000L / dsf.sampleRateHz
    checkNotNull(trackOutput).sampleData(scratch, bytesRead)
    checkNotNull(trackOutput).sampleMetadata(
        timeUs,
        C.BUFFER_FLAG_KEY_FRAME,
        bytesRead,
        0,
        null,
    )
    sampleIndex += bytesRead * 8L / dsf.channelCount
    return RESULT_CONTINUE
  }

  override fun seek(position: Long, timeUs: Long) {
    val dsf = format ?: return
    sampleIndex = DsfSeekMath.sampleIndexForPositionMs(dsf, timeUs / 1_000L)
  }

  override fun release() = Unit

  @Throws(IOException::class)
  private fun loadHeader(input: ExtractorInput) {
    val dsd = readFully(input, DsfFormat.DSD_CHUNK_SIZE.toInt())
    val fmt = readFully(input, DsfFormat.FMT_CHUNK_SIZE.toInt())
    val dataHeader = readFully(input, DsfFormat.DATA_HEADER_SIZE.toInt())
    val parsed = DsfHeaderReader.parse(dsd, fmt, dataHeader)
    format = parsed
    sampleIndex = 0L
    val mediaFormat = Format.Builder()
        .setSampleMimeType(DsfFormat.MIME_DSF)
        .setContainerMimeType(DsfFormat.MIME_CONTAINER_DSF)
        .setChannelCount(parsed.channelCount)
        // FFmpeg's DSD decoder emits one float PCM sample per packed DSD byte.
        .setSampleRate(parsed.decoderSampleRateHz)
        .setCustomData(DsfExtractorPacketFacts.fromFormat(parsed))
        .setAverageBitrate(
            (parsed.sampleRateHz.toLong() * parsed.channelCount * parsed.bitsPerSample).toInt(),
        )
        .build()
    checkNotNull(trackOutput).format(mediaFormat)
    extractorOutput?.seekMap(createSeekMap(parsed))
  }

  @Throws(IOException::class)
  private fun readFully(input: ExtractorInput, length: Int): ByteArray {
    val buffer = ByteArray(length)
    input.readFully(buffer, 0, length)
    return buffer
  }

  @Throws(IOException::class)
  private fun readDsfPacket(input: ExtractorInput, dsf: DsfFormat, remaining: Long): Int {
    val blockSizePerChannel = dsf.blockSizePerChannel
    val blockAlign = blockSizePerChannel * dsf.channelCount
    ensureScratchCapacity(blockAlign)

    val validBytes = minOf(remaining, blockAlign.toLong()).toInt()
    if (validBytes == blockAlign) {
      input.readFully(scratch.data, 0, blockAlign)
    } else {
      // DSF pads the final block independently for each channel. Compact the valid
      // bytes into the planar packet shape expected by FFmpeg's dsd_lsbf_planar.
      check(validBytes % dsf.channelCount == 0) { "Unaligned final DSF packet" }
      val validBytesPerChannel = validBytes / dsf.channelCount
      repeat(dsf.channelCount) { channel ->
        input.readFully(
            scratch.data,
            channel * validBytesPerChannel,
            validBytesPerChannel,
        )
        input.skipFully(blockSizePerChannel - validBytesPerChannel)
      }
    }
    scratch.setPosition(0)
    scratch.setLimit(validBytes)
    return validBytes
  }

  private fun ensureScratchCapacity(requiredCapacity: Int) {
    if (scratch.capacity() >= requiredCapacity) return
    scratch = ParsableByteArray(requiredCapacity)
  }

  companion object {
    private const val SNIFF_BYTES = 4
    const val RESULT_CONTINUE = Extractor.RESULT_CONTINUE
    const val RESULT_END_OF_INPUT = Extractor.RESULT_END_OF_INPUT

    val FACTORY: ExtractorsFactory = ExtractorsFactory { arrayOf(DsfExtractor()) }

    fun createSeekMap(dsf: DsfFormat): SeekMap =
        object : SeekMap {
          override fun isSeekable(): Boolean = true

          override fun getDurationUs(): Long = dsf.durationUs

          override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val sample = DsfSeekMath.sampleIndexForPositionMs(dsf, timeUs / 1_000L)
            val position = DsfSeekMath.fileOffsetForSampleIndex(dsf, sample)
            val alignedTimeUs = sample * 1_000_000L / dsf.sampleRateHz
            val seekPoint = SeekPoint(alignedTimeUs, position)
            return SeekMap.SeekPoints(seekPoint)
          }
        }
  }

  private var scratch = ParsableByteArray(0)
}
