package com.mica.music.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Downsamples ultra-high-rate PCM from FFmpeg `dsd_lsbf_planar` (~1.4 MHz on DSD256)
 * to a rate [AudioTrack] can play, using the same sample-rate ladder as [DsdOutputPolicy].
 *
 * Used on the Exo path only; software FFmpeg keeps its own output format selection.
 */
@UnstableApi
class DsdDecimationAudioProcessor(
    private val context: Context,
    private val decimationOutputMode: DsdDecimationOutputMode = DsdDecimationOutputMode.IntPcm,
) : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var downsampleFactor = 1
    private var outputEncoding = C.ENCODING_INVALID
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var pendingBytes = ByteArray(0)
    private var pendingSize = 0
    private var floatAccumulators = FloatArray(0)
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val shouldProcess =
            inputAudioFormat.sampleRate >= DSD_RATE_THRESHOLD &&
                isHighRatePcmEncoding(inputAudioFormat.encoding) &&
                inputAudioFormat.channelCount > 0

        if (!shouldProcess) {
            if (inputAudioFormat.sampleRate >= DSD_RATE_THRESHOLD) {
                DiagnosticLog.event(
                    "DsdProcessor",
                    "[DEBUG-dsd-output] passthrough rate=${inputAudioFormat.sampleRate}Hz " +
                        "encoding=${inputAudioFormat.encoding} channels=${inputAudioFormat.channelCount}",
                )
            }
            clearState()
            return inputAudioFormat
        }

        val target = resolveDsdDecimationTarget(
            context = context,
            inputRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
        )
        if (target == null) {
            clearState()
            return inputAudioFormat
        }

        val (format, factor) = target
        if (factor < 2) {
            clearState()
            return inputAudioFormat
        }

        inputFormat = inputAudioFormat
        downsampleFactor = factor
        outputEncoding = resolveOutputEncoding(format, decimationOutputMode)
        outputFormat = AudioProcessor.AudioFormat(
            format.sampleRateHz,
            inputAudioFormat.channelCount,
            outputEncoding,
        )
        DiagnosticLog.event(
            "DsdProcessor",
            "[DEBUG-dsd-output] input=${inputAudioFormat.sampleRate}Hz/${inputAudioFormat.encoding} " +
                "output=${format.sampleRateHz}Hz/${format.bitsPerSample}bit enc=$outputEncoding " +
                "decimationMode=$decimationOutputMode " +
                "channels=${inputAudioFormat.channelCount} factor=$factor",
        )
        pendingSize = 0
        ensurePendingCapacity(maxPendingBytes())
        ensureAccumulatorCapacity(inputAudioFormat.channelCount)
        return outputFormat
    }

    override fun isActive(): Boolean = outputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        when (inputFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer)
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer)
            else -> error("Unsupported DSD input encoding: ${inputFormat.encoding}")
        }
        inputBuffer.position(inputBuffer.limit())
    }

    override fun queueEndOfStream() {
        pendingSize = 0
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val pendingOutput = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pendingOutput
    }

    override fun isEnded(): Boolean =
        inputEnded && pendingSize == 0 && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        pendingSize = 0
        inputEnded = false
    }

    override fun reset() {
        clearState()
    }

    private fun clearState() {
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        downsampleFactor = 1
        outputEncoding = C.ENCODING_INVALID
        flush()
    }

    private fun processFloat(inputBuffer: ByteBuffer) {
        processPcm(
            inputBuffer = inputBuffer,
            inputBytesPerSample = Float.SIZE_BYTES,
            readPendingSample = ::readFloatFromPending,
            readInputSample = { buffer -> buffer.float },
        )
    }

    private fun processInt16(inputBuffer: ByteBuffer) {
        processPcm(
            inputBuffer = inputBuffer,
            inputBytesPerSample = Short.SIZE_BYTES,
            readPendingSample = ::readShortFromPending,
            readInputSample = { buffer -> buffer.short.toFloat() / Short.MAX_VALUE },
        )
    }

    private fun processPcm(
        inputBuffer: ByteBuffer,
        inputBytesPerSample: Int,
        readPendingSample: (Int) -> Float,
        readInputSample: (ByteBuffer) -> Float,
    ) {
        val bytesPerFrame = inputFormat.channelCount * inputBytesPerSample
        val processableFrameCount = processableFrameCount(bytesPerFrame, inputBuffer.remaining())
        if (processableFrameCount == 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            stashPendingBytes(inputBuffer)
            return
        }

        val outputBytesPerFrame = inputFormat.channelCount * outputBytesPerSample()
        outputBuffer = ensureOutputBuffer(processableFrameCount * outputBytesPerFrame)
        val pcmInput = inputBuffer.duplicate().order(NATIVE_ORDER)
        var pendingReadOffset = 0

        repeat(processableFrameCount) {
            java.util.Arrays.fill(floatAccumulators, 0f)
            repeat(downsampleFactor) {
                if (pendingReadOffset < pendingSize) {
                    for (ch in 0 until inputFormat.channelCount) {
                        floatAccumulators[ch] += readPendingSample(pendingReadOffset)
                        pendingReadOffset += inputBytesPerSample
                    }
                } else {
                    for (ch in 0 until inputFormat.channelCount) {
                        floatAccumulators[ch] += readInputSample(pcmInput)
                    }
                }
            }
            for (ch in 0 until inputFormat.channelCount) {
                writeOutputSample(
                    outputBuffer,
                    (floatAccumulators[ch] / downsampleFactor).coerceIn(-1f, 1f),
                )
            }
        }
        storePendingRemainder(pendingReadOffset, pcmInput)
        outputBuffer.flip()
    }

    private fun processableFrameCount(bytesPerFrame: Int, incomingBytes: Int): Int {
        val totalFrames = (pendingSize + incomingBytes) / bytesPerFrame
        return totalFrames / downsampleFactor
    }

    private fun maxPendingBytes(): Int =
        inputFormat.channelCount * Float.SIZE_BYTES * downsampleFactor

    private fun ensurePendingCapacity(requiredCapacity: Int) {
        if (pendingBytes.size >= requiredCapacity) return
        pendingBytes = pendingBytes.copyOf(requiredCapacity.coerceAtLeast(pendingBytes.size * 2).coerceAtLeast(1))
    }

    private fun ensureAccumulatorCapacity(channelCount: Int) {
        if (floatAccumulators.size < channelCount) {
            floatAccumulators = FloatArray(channelCount)
        }
    }

    private fun ensureOutputBuffer(requiredCapacity: Int): ByteBuffer =
        if (outputBuffer.capacity() < requiredCapacity) {
            ByteBuffer.allocateDirect(requiredCapacity).order(NATIVE_ORDER).also { outputBuffer = it }
        } else {
            outputBuffer.clear()
            outputBuffer
        }

    private fun stashPendingBytes(inputBuffer: ByteBuffer) {
        ensurePendingCapacity(pendingSize + inputBuffer.remaining())
        val source = inputBuffer.duplicate()
        source.get(pendingBytes, pendingSize, source.remaining())
        pendingSize += inputBuffer.remaining()
    }

    private fun storePendingRemainder(pendingReadOffset: Int, inputBuffer: ByteBuffer) {
        val unreadPendingBytes = pendingSize - pendingReadOffset
        val remainingInputBytes = inputBuffer.remaining()
        ensurePendingCapacity(unreadPendingBytes + remainingInputBytes)
        if (unreadPendingBytes > 0 && pendingReadOffset > 0) {
            pendingBytes.copyInto(
                destination = pendingBytes,
                destinationOffset = 0,
                startIndex = pendingReadOffset,
                endIndex = pendingSize,
            )
        }
        if (remainingInputBytes > 0) {
            inputBuffer.get(pendingBytes, unreadPendingBytes, remainingInputBytes)
        }
        pendingSize = unreadPendingBytes + remainingInputBytes
    }

    private fun readFloatFromPending(offset: Int): Float = Float.fromBits(readIntFromPending(offset))

    private fun readShortFromPending(offset: Int): Float {
        val low = pendingBytes[offset].toInt() and 0xFF
        val high = pendingBytes[offset + 1].toInt() and 0xFF
        val sample = if (NATIVE_ORDER_IS_BIG_ENDIAN) {
            (high shl 8) or low
        } else {
            low or (high shl 8)
        }.toShort()
        return sample.toFloat() / Short.MAX_VALUE
    }

    private fun outputBytesPerSample(): Int = when (outputEncoding) {
        C.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_16BIT -> 2
        else -> error("Unsupported DSD output encoding: $outputEncoding")
    }

    private fun writeOutputSample(buffer: ByteBuffer, sample: Float) {
        when (outputEncoding) {
            C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample.coerceIn(-1f, 1f))
            C.ENCODING_PCM_24BIT -> {
                val pcm = when {
                    sample <= -1f -> -8_388_608
                    sample >= 1f -> 8_388_607
                    else -> (sample * 8_388_608f).roundToInt().coerceIn(-8_388_608, 8_388_607)
                }
                buffer.put((pcm and 0xFF).toByte())
                buffer.put((pcm shr 8 and 0xFF).toByte())
                buffer.put((pcm shr 16 and 0xFF).toByte())
            }
            C.ENCODING_PCM_16BIT -> {
                val pcm = when {
                    sample <= -1f -> Short.MIN_VALUE.toInt()
                    sample >= 1f -> Short.MAX_VALUE.toInt()
                    else -> (sample * 32_768f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                }
                buffer.putShort(pcm.toShort())
            }
            else -> error("Unsupported DSD output encoding: $outputEncoding")
        }
    }

    private fun readIntFromPending(offset: Int): Int {
        val byte0 = pendingBytes[offset].toInt() and 0xFF
        val byte1 = pendingBytes[offset + 1].toInt() and 0xFF
        val byte2 = pendingBytes[offset + 2].toInt() and 0xFF
        val byte3 = pendingBytes[offset + 3].toInt() and 0xFF
        return if (NATIVE_ORDER_IS_BIG_ENDIAN) {
            (byte0 shl 24) or (byte1 shl 16) or (byte2 shl 8) or byte3
        } else {
            (byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0
        }
    }

    companion object {
        // FFmpeg emits one PCM sample per packed DSD byte, so DSD64 starts at 352.8 kHz.
        const val DSD_RATE_THRESHOLD = 352_800

        private fun isHighRatePcmEncoding(encoding: @C.PcmEncoding Int): Boolean =
            encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_16BIT

        private val NATIVE_ORDER = ByteOrder.nativeOrder()
        private val NATIVE_ORDER_IS_BIG_ENDIAN = NATIVE_ORDER == ByteOrder.BIG_ENDIAN

        internal fun resolveOutputEncoding(
            format: AlacPcmFormat,
            mode: DsdDecimationOutputMode,
        ): @C.PcmEncoding Int = when (mode) {
            DsdDecimationOutputMode.FloatPcm -> C.ENCODING_PCM_FLOAT
            DsdDecimationOutputMode.IntPcm -> if (format.bitsPerSample > 16) {
                C.ENCODING_PCM_24BIT
            } else {
                C.ENCODING_PCM_16BIT
            }
        }

        internal fun resolveDsdDecimationTarget(
            context: Context,
            inputRateHz: Int,
            channelCount: Int,
        ): Pair<AlacPcmFormat, Int>? {
            val candidates = DsdOutputPolicy.candidates(context, channelCount).ifEmpty {
                DsdOutputPolicy.candidates(
                    channelCount = channelCount,
                    bluetooth = false,
                    supportsPacked24 = true,
                )
            }
            candidates.firstOrNull { inputRateHz % it.sampleRateHz == 0 }?.let { format ->
                return format to (inputRateHz / format.sampleRateHz)
            }
            val fallback = candidates.firstOrNull() ?: return null
            val factor = ceil(inputRateHz.toDouble() / fallback.sampleRateHz).toInt().coerceAtLeast(2)
            return fallback to factor
        }
    }
}
