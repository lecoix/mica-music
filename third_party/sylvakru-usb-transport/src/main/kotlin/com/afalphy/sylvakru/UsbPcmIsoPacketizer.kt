package com.afalphy.sylvakru

import java.io.ByteArrayOutputStream
import java.util.Locale

private const val UNITY_GAIN_Q16 = 65536

/**
 * Adapted from sylvakru-usb UsbExclusiveAudioEngine.PcmIsoPacketizer.
 * Packet cadence, feedback handling and PCM slot conversion intentionally stay aligned with the
 * reference implementation.
 */
class UsbPcmIsoPacketizer(
    private val sampleRate: Int,
    private val packetsPerSecond: Int,
    channels: Int,
    private val inputBytesPerSample: Int,
    private val inputBitDepth: Int,
    private val usbBytesPerSample: Int,
    private val usbBitResolution: Int,
    private val feedbackOutputPacketDivisor: Int = 1,
    private val feedbackFramesPerPacketQ16: (() -> Int)? = null,
    private val volumeGainQ16: (() -> Int)? = null,
    private val writePackets: (ByteArray, IntArray, Int) -> Unit,
) {
    private val pending = ByteArrayOutputStream()
    private val transfer = ByteArrayOutputStream()
    private val transferPacketLengths = IntArray(16)
    private val bytesPerFrame = channels * usbBytesPerSample
    private val inputBytesPerFrame = channels * inputBytesPerSample
    private val nominalCadence = UsbPacketCadence(sampleRate, packetsPerSecond)
    private var feedbackRemainderQ16 = 0L
    private var transferPacketCount = 0
    private var packetLogCount = 0
    private var feedbackRejectLogCount = 0
    private var pcmPreviewLogged = false
    private var pcmPreviewAttempts = 0

    fun write(data: ByteArray) {
        val converted = convertPcmToUsbSlots(data)
        if (!pcmPreviewLogged) {
            pcmPreviewAttempts++
            val forcePreview = pcmPreviewAttempts >= 64
            if (hasAudibleSamples(data) || forcePreview) {
                pcmPreviewLogged = true
                logPcmPreview(
                    data,
                    converted,
                    if (forcePreview) "forced-after-silence" else "first-nonzero",
                )
            }
        }
        pending.write(converted)
        drain(fullPacketsOnly = true)
    }

    fun flush() {
        drain(fullPacketsOnly = false)
    }

    fun reset() {
        pending.reset()
        transfer.reset()
        transferPacketCount = 0
        nominalCadence.reset()
        feedbackRemainderQ16 = 0L
        packetLogCount = 0
        feedbackRejectLogCount = 0
        pcmPreviewLogged = false
        pcmPreviewAttempts = 0
    }

    private fun drain(fullPacketsOnly: Boolean) {
        while (pending.size() > 0) {
            val packetBytes = nextPacketBytes()
            if (fullPacketsOnly && pending.size() < packetBytes) {
                return
            }
            val source = pending.toByteArray()
            val length = minOf(packetBytes, source.size)
            val packet = ByteArray(packetBytes)
            System.arraycopy(source, 0, packet, 0, length)
            pending.reset()
            if (source.size > length) {
                pending.write(source, length, source.size - length)
            }
            if (packetLogCount < 5) {
                ++packetLogCount
                UsbDiagnostics.d(
                    "UsbExclusivePcmTransport",
                    "USB PCM packet bytes=${packet.size}, filled=$length",
                )
            }
            transfer.write(packet)
            transferPacketLengths[transferPacketCount] = packet.size
            transferPacketCount++
            if (transferPacketCount >= transferPacketLengths.size) {
                flushTransfer()
            }
        }

        if (!fullPacketsOnly) {
            flushTransfer()
        }
    }

    private fun flushTransfer() {
        if (transferPacketCount == 0) {
            return
        }
        writePackets(
            transfer.toByteArray(),
            transferPacketLengths.copyOf(transferPacketCount),
            transferPacketCount,
        )
        transfer.reset()
        transferPacketCount = 0
    }

    private fun nextPacketBytes(): Int {
        val feedbackQ16 = feedbackFramesPerPacketQ16?.invoke() ?: 0
        if (feedbackQ16 > 0) {
            val outputFeedbackQ16 = feedbackQ16 / feedbackOutputPacketDivisor
            val nominalFramesQ16 = ((sampleRate.toLong() shl 16) / packetsPerSecond).toInt()
            val minFeedbackQ16 = nominalFramesQ16 - (nominalFramesQ16 / 8)
            val maxFeedbackQ16 = nominalFramesQ16 + (nominalFramesQ16 / 2)
            if (outputFeedbackQ16 in minFeedbackQ16..maxFeedbackQ16) {
                feedbackRemainderQ16 += outputFeedbackQ16.toLong()
                val frames = (feedbackRemainderQ16 ushr 16).toInt()
                feedbackRemainderQ16 = feedbackRemainderQ16 and 0xffff
                if (frames > 0) {
                    return maxOf(bytesPerFrame, frames * bytesPerFrame)
                }
            } else if (feedbackRejectLogCount < 8) {
                ++feedbackRejectLogCount
                UsbDiagnostics.w(
                    "UsbExclusivePcmTransport",
                    "USB feedback ignored outputFrames=${q16ToFrames(outputFeedbackQ16)}, " +
                        "nominalFrames=${q16ToFrames(nominalFramesQ16)}, " +
                        "sampleRate=$sampleRate, packetsPerSecond=$packetsPerSecond",
                )
            }
        }

        val frames = nominalCadence.nextNominalFrames()
        return maxOf(bytesPerFrame, frames * bytesPerFrame)
    }

    private fun q16ToFrames(value: Int): String =
        String.format(Locale.US, "%.6f", value.toDouble() / 65536.0)

    private fun convertPcmToUsbSlots(data: ByteArray): ByteArray {
        val gainQ16 = volumeGainQ16?.invoke() ?: UNITY_GAIN_Q16
        val applyGain = gainQ16 < UNITY_GAIN_Q16
        if (!applyGain && inputBytesPerSample == usbBytesPerSample && inputBitDepth == usbBitResolution) {
            return data
        }

        val frames = data.size / inputBytesPerFrame
        val output = ByteArray(frames * bytesPerFrame)
        var inputOffset = 0
        var outputOffset = 0
        repeat(frames) {
            repeat(inputBytesPerFrame / inputBytesPerSample) {
                var sample = readSignedLittleEndian(data, inputOffset, inputBytesPerSample, inputBitDepth)
                if (applyGain) {
                    sample = ((sample.toLong() * gainQ16) shr 16).toInt()
                }
                val shifted = if (usbBitResolution >= inputBitDepth) {
                    sample shl (usbBitResolution - inputBitDepth)
                } else {
                    sample shr (inputBitDepth - usbBitResolution)
                }
                writeLittleEndian(output, outputOffset, usbBytesPerSample, shifted)
                inputOffset += inputBytesPerSample
                outputOffset += usbBytesPerSample
            }
        }
        return output
    }

    private fun hasAudibleSamples(input: ByteArray): Boolean {
        val frames = input.size / inputBytesPerFrame
        val samplesPerFrame = inputBytesPerFrame / inputBytesPerSample
        val samplesToInspect = minOf(4096, frames * samplesPerFrame)
        var sumAbs = 0L
        for (index in 0 until samplesToInspect) {
            val offset = index * inputBytesPerSample
            val sample = readSignedLittleEndian(input, offset, inputBytesPerSample, inputBitDepth)
            val abs = kotlin.math.abs(sample.toLong())
            sumAbs += abs
            if (abs > 512) {
                return true
            }
        }
        return samplesToInspect > 0 && (sumAbs / samplesToInspect) > 64
    }

    private fun logPcmPreview(input: ByteArray, converted: ByteArray, reason: String) {
        val frames = input.size / inputBytesPerFrame
        val samplesPerFrame = inputBytesPerFrame / inputBytesPerSample
        val samplesToInspect = minOf(4096, frames * samplesPerFrame)
        var minSample = 0
        var maxSample = 0
        var sumAbs = 0L
        for (index in 0 until samplesToInspect) {
            val offset = index * inputBytesPerSample
            val sample = readSignedLittleEndian(input, offset, inputBytesPerSample, inputBitDepth)
            if (index == 0 || sample < minSample) minSample = sample
            if (index == 0 || sample > maxSample) maxSample = sample
            sumAbs += kotlin.math.abs(sample.toLong())
        }
        val averageAbs = if (samplesToInspect > 0) sumAbs / samplesToInspect else 0
        UsbDiagnostics.i(
            "UsbExclusivePcmTransport",
            "USB PCM preview reason=$reason, inputBytes=${input.size}, convertedBytes=${converted.size}, " +
                "frames=$frames, inputBitDepth=$inputBitDepth, usbBytesPerSample=$usbBytesPerSample, " +
                "usbBitResolution=$usbBitResolution, min=$minSample, max=$maxSample, avgAbs=$averageAbs, " +
                "inputHead=${input.toHexPreview()}, usbHead=${converted.toHexPreview()}",
        )
    }

    private fun ByteArray.toHexPreview(limit: Int = 64): String =
        take(minOf(size, limit)).joinToString(" ") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun readSignedLittleEndian(
        data: ByteArray,
        offset: Int,
        bytes: Int,
        bitDepth: Int,
    ): Int {
        var value = 0
        for (index in 0 until bytes) {
            value = value or ((data[offset + index].toInt() and 0xff) shl (index * 8))
        }
        val shift = (32 - bitDepth).coerceIn(0, 31)
        return (value shl shift) shr shift
    }

    private fun writeLittleEndian(
        data: ByteArray,
        offset: Int,
        bytes: Int,
        value: Int,
    ) {
        for (index in 0 until bytes) {
            data[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
        }
    }
}
