/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
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
    private val channels: Int,
    private val inputBytesPerSample: Int,
    private val inputBitDepth: Int,
    private val usbBytesPerSample: Int,
    private val usbBitResolution: Int,
    private val packetsPerTransfer: Int = 16,
    private val feedbackOutputPacketDivisor: Int = 1,
    private val feedbackFramesPerPacketQ16: (() -> Int)? = null,
    private val reportFeedback: ((Int, Int, Boolean) -> Unit)? = null,
    private val volumeGainQ16: (() -> Int)? = null,
    private val writeFrames: ((ByteArray) -> Unit)? = null,
    private val writeSyntheticFrames: ((ByteArray) -> Unit)? = writeFrames,
    private val writePackets: (ByteArray, IntArray, Int) -> Unit = { _, _, _ -> },
) {
    private val pending = ByteArrayOutputStream()
    private val transfer = ByteArrayOutputStream()
    private val transferPacketLengths = IntArray(packetsPerTransfer.coerceIn(1, 16))
    private val bytesPerFrame = channels * usbBytesPerSample
    private val inputBytesPerFrame = channels * inputBytesPerSample
    private val nominalCadence = UsbPacketCadence(sampleRate, packetsPerSecond)
    private var feedbackRemainderQ16 = 0L
    private var transferPacketCount = 0
    private var packetLogCount = 0
    private var feedbackRejectLogCount = 0
    private var pcmPreviewLogged = false
    private var pcmPreviewAttempts = 0
    private val lastUsbSamples = IntArray(channels)
    private var hasLastUsbFrame = false
    private var fadeInTotalFrames = 0
    private var fadeInFramesDone = 0

    fun beginFadeIn(durationMs: Int) {
        fadeInTotalFrames = usbSilenceFrames(sampleRate, durationMs)
        fadeInFramesDone = 0
    }

    fun write(data: ByteArray) {
        val converted = applyFadeInIfNeeded(convertPcmToUsbSlots(data))
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
        if (writeFrames != null) {
            writeFrames.invoke(converted)
        } else {
            pending.write(converted)
            drain(fullPacketsOnly = true)
        }
    }

    fun flush() {
        if (writeFrames != null) return
        drain(fullPacketsOnly = false)
    }

    fun writeTransitionTail(fadeMs: Int, silenceMs: Int) {
        val fadeFrames = usbSilenceFrames(sampleRate, fadeMs)
        val silenceFrames = usbSilenceFrames(sampleRate, silenceMs)
        if (!hasLastUsbFrame) {
            writeUsbSilence(fadeFrames + silenceFrames)
            return
        }
        val samples = pcmFadeToSilence(lastUsbSamples, fadeFrames, silenceFrames)
        val bytes = ByteArray(samples.size * usbBytesPerSample)
        samples.forEachIndexed { index, sample ->
            writeLittleEndian(bytes, index * usbBytesPerSample, usbBytesPerSample, sample)
        }
        if (writeSyntheticFrames != null) {
            writeSyntheticFrames.invoke(bytes)
        } else {
            pending.write(bytes)
            drain(fullPacketsOnly = false)
        }
    }

    fun writeUsbSilence(frames: Int) {
        if (frames <= 0) return
        val silence = ByteArray(frames * bytesPerFrame)
        if (writeSyntheticFrames != null) {
            writeSyntheticFrames.invoke(silence)
        } else {
            pending.write(silence)
            drain(fullPacketsOnly = false)
        }
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
        hasLastUsbFrame = false
        lastUsbSamples.fill(0)
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
        val data = transfer.toByteArray()
        val packetCount = transferPacketCount
        val packetLengths = transferPacketLengths.copyOf(packetCount)
        // Clear before the transport side effect so a terminal callback failure cannot leave
        // a full batch behind for a later playback session.
        transfer.reset()
        transferPacketCount = 0
        writePackets(data, packetLengths, packetCount)
    }

    private fun nextPacketBytes(): Int {
        val feedbackQ16 = feedbackFramesPerPacketQ16?.invoke() ?: 0
        if (feedbackQ16 > 0) {
            val outputFeedbackQ16 = feedbackQ16 / feedbackOutputPacketDivisor
            val nominalFramesQ16 = ((sampleRate.toLong() shl 16) / packetsPerSecond).toInt()
            val minFeedbackQ16 = nominalFramesQ16 - (nominalFramesQ16 / 8)
            val maxFeedbackQ16 = nominalFramesQ16 + (nominalFramesQ16 / 2)
            if (outputFeedbackQ16 in minFeedbackQ16..maxFeedbackQ16) {
                reportFeedback?.invoke(outputFeedbackQ16, nominalFramesQ16, false)
                feedbackRemainderQ16 += outputFeedbackQ16.toLong()
                val frames = (feedbackRemainderQ16 ushr 16).toInt()
                feedbackRemainderQ16 = feedbackRemainderQ16 and 0xffff
                if (frames > 0) {
                    return maxOf(bytesPerFrame, frames * bytesPerFrame)
                }
            } else {
                reportFeedback?.invoke(outputFeedbackQ16, nominalFramesQ16, true)
                if (feedbackRejectLogCount < 8) {
                    ++feedbackRejectLogCount
                    UsbDiagnostics.w(
                        "UsbExclusivePcmTransport",
                        "USB feedback ignored outputFrames=${q16ToFrames(outputFeedbackQ16)}, " +
                            "nominalFrames=${q16ToFrames(nominalFramesQ16)}, " +
                            "sampleRate=$sampleRate, packetsPerSecond=$packetsPerSecond",
                    )
                }
            }
        }

        val frames = nominalCadence.nextNominalFrames()
        return maxOf(bytesPerFrame, frames * bytesPerFrame)
    }

    private fun q16ToFrames(value: Int): String =
        String.format(Locale.US, "%.6f", value.toDouble() / 65536.0)

    private fun applyFadeInIfNeeded(data: ByteArray): ByteArray {
        if (fadeInTotalFrames == 0 || fadeInFramesDone >= fadeInTotalFrames) {
            return data
        }
        val frames = data.size / bytesPerFrame
        var offset = 0
        var frame = 0
        while (frame < frames && fadeInFramesDone < fadeInTotalFrames) {
            val gainQ16 = pcmFadeInGainQ16(fadeInFramesDone, fadeInTotalFrames)
            repeat(channels) {
                val sample = readSignedLittleEndian(data, offset, usbBytesPerSample, usbBitResolution)
                val faded = ((sample.toLong() * gainQ16) shr 16).toInt()
                writeLittleEndian(data, offset, usbBytesPerSample, faded)
                offset += usbBytesPerSample
            }
            fadeInFramesDone++
            frame++
        }
        return data
    }

    private fun convertPcmToUsbSlots(data: ByteArray): ByteArray {
        val gainQ16 = volumeGainQ16?.invoke() ?: UNITY_GAIN_Q16
        val applyGain = gainQ16 < UNITY_GAIN_Q16
        val frames = data.size / inputBytesPerFrame
        if (frames > 0) {
            var inputOffset = (frames - 1) * inputBytesPerFrame
            repeat(channels) { channel ->
                val sample = readSignedLittleEndian(
                    data,
                    inputOffset,
                    inputBytesPerSample,
                    inputBitDepth,
                )
                lastUsbSamples[channel] = pcmSampleForUsbTransition(
                    sample,
                    inputBitDepth,
                    usbBitResolution,
                    gainQ16,
                )
                inputOffset += inputBytesPerSample
            }
            hasLastUsbFrame = true
        }
        if (!applyGain && inputBytesPerSample == usbBytesPerSample && inputBitDepth == usbBitResolution) {
            return data
        }

        val output = ByteArray(frames * bytesPerFrame)
        var inputOffset = 0
        var outputOffset = 0
        repeat(frames) {
            repeat(inputBytesPerFrame / inputBytesPerSample) {
                val sample = readSignedLittleEndian(data, inputOffset, inputBytesPerSample, inputBitDepth)
                val shifted = pcmSampleForUsbTransition(sample, inputBitDepth, usbBitResolution, gainQ16)
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
