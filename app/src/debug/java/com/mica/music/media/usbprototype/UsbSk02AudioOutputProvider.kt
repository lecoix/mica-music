package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.round

internal fun shouldConsumeUsbSource(
    requestedPlaying: Boolean,
    volume: Float,
    bufferedFrames: Long,
    minimumBufferedFrames: Long,
): Boolean = requestedPlaying && volume == 1f && bufferedFrames >= minimumBufferedFrames

internal fun usbStartPrefillFrames(sampleRate: Int): Long =
    ((sampleRate.toLong() * USB_START_PREFILL_MILLIS) + 999L) / 1_000L

private const val USB_START_PREFILL_MILLIS = 20L

/**
 * THROWAWAY PROTOTYPE: Media3 1.9 AudioOutputProvider backed by the proven SK02 USBFS queue.
 *
 * This is deliberately narrower than a USB audio engine: one VID/PID, stereo PCM16 plus float
 * buffers that are provably exact signed PCM32, and no AudioTrack fallback while selected.
 */
@UnstableApi
class UsbSk02AudioOutputProvider(context: Context) : AudioOutputProvider {
    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.channelCount != CHANNEL_COUNT ||
            format.sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE ||
            formatConfig.enableOffload ||
            formatConfig.enableTunneling
        ) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        val support = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            // Advertise direct acceptance so FFmpeg does not pre-emptively decode high-resolution
            // sources to PCM16. Exact S32/2^31 floats are losslessly packed for SK02 alt 3.
            C.ENCODING_PCM_FLOAT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            // The scoped FFmpeg prototype asks for S32 only for 24/32-bit sources. Sending the
            // decoder's S32 samples through SK02 alt 3 avoids any float-to-integer quantization.
            C.ENCODING_PCM_32BIT -> AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            else -> AudioOutputProvider.FORMAT_UNSUPPORTED
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(support)
            .build()
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        val support = getFormatSupport(formatConfig)
        if (support.supportLevel == AudioOutputProvider.FORMAT_UNSUPPORTED) {
            throw AudioOutputProvider.ConfigurationException(
                "SK02 prototype does not support ${formatConfig.format}",
            )
        }
        val format = formatConfig.format
        val inputBytesPerFrame = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 4
            C.ENCODING_PCM_FLOAT -> 8
            C.ENCODING_PCM_32BIT -> 8
            else -> throw AudioOutputProvider.ConfigurationException("Unsupported PCM encoding")
        }
        val preferred = formatConfig.preferredBufferSize.takeIf { it > 0 }
            ?: format.sampleRate * inputBytesPerFrame
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(preferred)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
            .setIsOffload(false)
            .setIsTunneling(false)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .build()
    }

    override fun getAudioOutput(
        config: AudioOutputProvider.OutputConfig,
    ): AudioOutput = try {
        UsbSk02Media3SessionOwner.open(appContext, config)
    } catch (error: Exception) {
        throw AudioOutputProvider.InitializationException(error)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners -= listener
    }

    override fun release() = Unit

    private companion object {
        const val CHANNEL_COUNT = 2
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 384_000
    }
}

@UnstableApi
private object UsbSk02Media3SessionOwner {
    private val lock = Any()
    private var active: UsbSk02AudioOutput? = null

    fun open(
        context: Context,
        config: AudioOutputProvider.OutputConfig,
    ): UsbSk02AudioOutput = synchronized(lock) {
        val token = nextGeneration()
        active?.releaseLocked("replaced")
        UsbSk02AudioOutput.open(context, config, token).also { active = it }
    }

    fun restart(output: UsbSk02AudioOutput) = synchronized(lock) {
        check(active === output) { "Cannot restart a stale SK02 output" }
        val token = nextGeneration()
        output.restartNativeLocked(token)
    }

    fun release(output: UsbSk02AudioOutput) = synchronized(lock) {
        if (active === output) {
            nextGeneration()
            active = null
        }
        output.releaseLocked("release")
    }

    private fun nextGeneration(): UsbPrototypeGenerationGate.Token =
        UsbPrototypeGenerationOwner.gate.beginRequest().also {
            UsbSk02NativePrototype.publishGeneration(it.value)
        }
}

@UnstableApi
private class UsbSk02AudioOutput private constructor(
    private val connection: UsbDeviceConnection,
    private val audioControl: UsbInterface,
    private val streamingAlt0: UsbInterface,
    private val streamingTarget: UsbInterface,
    private val sampleRate: Int,
    private val inputEncoding: Int,
    private val usbBytesPerFrame: Int,
    private val maxPacketBytes: Int,
    private val originalClockHz: Int?,
    generation: UsbPrototypeGenerationGate.Token,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var generation = generation
    private var nativeHandle = 0L
    private var requestedPlaying = false
    private var volume = 1f
    private var released = false
    private var positionNotified = false
    private var lastUnderrunBytes = 0L
    private var resumeSequence = 0L
    private var resumeRequestedAtNs = 0L
    private var firstWriteLoggedForResume = true
    private var lastAppliedConsuming = false
    private var floatScratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    init {
        nativeHandle = createNative(generation)
    }

    override fun play() {
        requestedPlaying = true
        resumeSequence++
        resumeRequestedAtNs = SystemClock.elapsedRealtimeNanos()
        firstWriteLoggedForResume = false
        DiagnosticLog.event(
            "UsbResumeTiming",
            "resume=$resumeSequence event=playRequested bufferedFrames=" +
                UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle),
        )
        applyPlayingState()
        if (!positionNotified) {
            positionNotified = true
            listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
        }
    }

    override fun pause() {
        requestedPlaying = false
        DiagnosticLog.event(
            "UsbResumeTiming",
            "resume=$resumeSequence event=pause bufferedFrames=" +
                UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle),
        )
        applyPlayingState()
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        checkNativeError()
        if (!buffer.hasRemaining()) return true
        val writtenInputBytes = when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> writePcm16(buffer)
            C.ENCODING_PCM_FLOAT -> writeExactFloatAsPcm32(buffer)
            C.ENCODING_PCM_32BIT -> writePcm32(buffer)
            else -> throw AudioOutput.WriteException(ERROR_UNSUPPORTED_ENCODING, false)
        }
        buffer.position(buffer.position() + writtenInputBytes)
        if (writtenInputBytes > 0 && !firstWriteLoggedForResume) {
            firstWriteLoggedForResume = true
            DiagnosticLog.event(
                "UsbResumeTiming",
                "resume=$resumeSequence event=firstWrite elapsedUs=${resumeElapsedUs()} " +
                    "acceptedInputBytes=$writtenInputBytes bufferedFrames=" +
                    UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle),
            )
        }
        if (writtenInputBytes > 0) applyPlayingState()
        reportUnderrunIfChanged()
        checkNativeError()
        return !buffer.hasRemaining()
    }

    override fun flush() {
        UsbSk02Media3SessionOwner.restart(this)
        positionNotified = false
        lastUnderrunBytes = 0
        applyPlayingState()
    }

    override fun stop() = Unit

    override fun release() {
        UsbSk02Media3SessionOwner.release(this)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        // The exclusive prototype never applies digital gain. Non-unity Media3 volume (including
        // audio-focus ducking) mutes transport source consumption instead of playing too loudly.
        applyPlayingState()
        if (volume != 1f) {
            DiagnosticLog.event("UsbExclusivePrototype", "nonUnityVolume=$volume action=mute")
        }
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET
    override fun getSampleRate(): Int = sampleRate
    override fun getBufferSizeInFrames(): Long = sampleRate.toLong() * 2L
    override fun getPositionUs(): Long =
        UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle) * C.MICROS_PER_SECOND /
            sampleRate

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled(): Boolean =
        nativeHandle == 0L || UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) != 0

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    fun restartNativeLocked(token: UsbPrototypeGenerationGate.Token) {
        if (nativeHandle != 0L) UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle)
        generation = token
        nativeHandle = createNative(token)
    }

    fun releaseLocked(reason: String) {
        if (released) return
        released = true
        requestedPlaying = false
        if (nativeHandle != 0L) {
            UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle)
            nativeHandle = 0L
        }
        val alt0Restored = runCatching { connection.setInterface(streamingAlt0) }.getOrDefault(false)
        val clockRestoreBytes = originalClockHz?.let { setClockFrequency(connection, it) }
        val restoredClockHz = originalClockHz?.let { readClockCurrentHz(connection) }
        val streamingReleased = runCatching { connection.releaseInterface(streamingAlt0) }
            .getOrDefault(false)
        val controlReleased = runCatching { connection.releaseInterface(audioControl) }
            .getOrDefault(false)
        val reconnectErrno = UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor)
        connection.close()
        DiagnosticLog.event(
            "UsbExclusivePrototype",
            "closed reason=$reason alt0=$alt0Restored clockBytes=$clockRestoreBytes " +
                "clockHz=$restoredClockHz streamingReleased=$streamingReleased " +
                "controlReleased=$controlReleased reconnectErrno=$reconnectErrno",
        )
        listeners.forEach(AudioOutput.Listener::onReleased)
        listeners.clear()
    }

    private fun createNative(token: UsbPrototypeGenerationGate.Token): Long {
        val handle = UsbSk02NativePrototype.createMedia3Stream(
            connection.fileDescriptor,
            sampleRate,
            usbBytesPerFrame,
            maxPacketBytes,
            token.value,
        )
        check(handle != 0L) { "Unable to create native SK02 Media3 stream" }
        return handle
    }

    private fun writePcm16(buffer: ByteBuffer): Int {
        val length = buffer.remaining() - buffer.remaining() % PCM16_INPUT_FRAME_BYTES
        if (length == 0) return 0
        return UsbSk02NativePrototype.writeMedia3Stream(
            nativeHandle,
            buffer,
            buffer.position(),
            length,
        )
    }

    private fun writePcm32(buffer: ByteBuffer): Int {
        val length = buffer.remaining() - buffer.remaining() % PCM32_INPUT_FRAME_BYTES
        if (length == 0) return 0
        return UsbSk02NativePrototype.writeMedia3Stream(
            nativeHandle,
            buffer,
            buffer.position(),
            length,
        )
    }

    private fun writeExactFloatAsPcm32(buffer: ByteBuffer): Int {
        val availableFrames = buffer.remaining() / FLOAT_INPUT_FRAME_BYTES
        if (availableFrames == 0) return 0
        val frames = minOf(availableFrames, FLOAT_CONVERSION_FRAMES)
        val outputBytes = frames * PCM32_OUTPUT_FRAME_BYTES
        if (floatScratch.capacity() < outputBytes) {
            floatScratch = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
        } else {
            floatScratch.clear()
        }
        try {
            ExactPcm32PrototypePacking.pack(buffer, frames, floatScratch)
        } catch (error: IllegalArgumentException) {
            DiagnosticLog.event(
                "UsbExclusivePrototype",
                "exactPcm32Rejected ${error.message ?: "unknown"}",
            )
            throw AudioOutput.WriteException(ERROR_NON_EXACT_PCM24, false)
        }
        floatScratch.flip()
        val acceptedOutput = UsbSk02NativePrototype.writeMedia3Stream(
            nativeHandle,
            floatScratch,
            0,
            outputBytes,
        )
        val acceptedFrames = acceptedOutput / PCM32_OUTPUT_FRAME_BYTES
        return acceptedFrames * FLOAT_INPUT_FRAME_BYTES
    }

    private fun applyPlayingState() {
        if (nativeHandle != 0L) {
            val bufferedFrames = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
            val consuming = shouldConsumeUsbSource(
                requestedPlaying = requestedPlaying,
                volume = volume,
                bufferedFrames = bufferedFrames,
                minimumBufferedFrames = usbStartPrefillFrames(sampleRate),
            )
            if (consuming != lastAppliedConsuming) {
                DiagnosticLog.event(
                    "UsbResumeTiming",
                    "resume=$resumeSequence event=consumeState consuming=$consuming " +
                        "elapsedUs=${resumeElapsedUs()} bufferedFrames=$bufferedFrames " +
                        "minimumFrames=${usbStartPrefillFrames(sampleRate)}",
                )
                lastAppliedConsuming = consuming
            }
            UsbSk02NativePrototype.setMedia3StreamPlaying(
                nativeHandle,
                consuming,
            )
        }
    }

    private fun resumeElapsedUs(): Long = if (resumeRequestedAtNs == 0L) {
        -1L
    } else {
        (SystemClock.elapsedRealtimeNanos() - resumeRequestedAtNs) / 1_000L
    }

    private fun reportUnderrunIfChanged() {
        val current = UsbSk02NativePrototype.getMedia3UnderrunBytes(nativeHandle)
        if (current > lastUnderrunBytes) {
            lastUnderrunBytes = current
            listeners.forEach(AudioOutput.Listener::onUnderrun)
            DiagnosticLog.event(
                "UsbExclusivePrototype",
                "underrunBytes=$current resume=$resumeSequence elapsedUs=${resumeElapsedUs()} " +
                    "bufferedFrames=${UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)}",
            )
        }
    }

    private fun checkNativeError() {
        val error = if (nativeHandle == 0L) ERROR_RELEASED else {
            UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
        }
        if (error != 0) throw AudioOutput.WriteException(error, true)
    }

    companion object {
        private const val TARGET_VENDOR_ID = 0x262a
        private const val TARGET_PRODUCT_ID = 0x0001
        private const val AUDIO_CONTROL_INTERFACE_ID = 1
        private const val AUDIO_STREAMING_INTERFACE_ID = 2
        private const val CLOCK_ID = 1
        private const val CONTROL_TIMEOUT_MS = 1_000
        private const val USB_CLASS_INTERFACE_IN = 0xa1
        private const val USB_CLASS_INTERFACE_OUT = 0x21
        private const val UAC2_REQUEST_CUR = 0x01
        private const val UAC2_SAMPLING_FREQUENCY_CONTROL = 0x0100
        private const val CHANNEL_COUNT = 2
        private const val PCM16_INPUT_FRAME_BYTES = 4
        private const val PCM32_INPUT_FRAME_BYTES = 8
        private const val FLOAT_INPUT_FRAME_BYTES = 8
        private const val PCM32_OUTPUT_FRAME_BYTES = 8
        private const val FLOAT_CONVERSION_FRAMES = 4_096
        private const val ERROR_RELEASED = 10_001
        private const val ERROR_UNSUPPORTED_ENCODING = 10_002
        private const val ERROR_NON_EXACT_PCM24 = 10_003

        fun open(
            context: Context,
            config: AudioOutputProvider.OutputConfig,
            token: UsbPrototypeGenerationGate.Token,
        ): UsbSk02AudioOutput {
            val profile = when (config.encoding) {
                C.ENCODING_PCM_16BIT -> Profile(1, 4, 200)
                C.ENCODING_PCM_FLOAT -> Profile(3, 8, 400)
                C.ENCODING_PCM_32BIT -> Profile(3, 8, 400)
                else -> error("Unsupported SK02 input encoding ${config.encoding}")
            }
            val manager = context.getSystemService(UsbManager::class.java)
            val target = manager.deviceList.values.firstOrNull {
                it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
            } ?: error("Fosi Audio SK02 is not attached")
            check(manager.hasPermission(target)) { "USB permission for SK02 is missing" }
            val interfaces = (0 until target.interfaceCount).map(target::getInterface)
            val audioControl = interfaces.firstOrNull {
                it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
            } ?: error("SK02 AudioControl interface is missing")
            val streamingAlt0 = interfaces.firstOrNull {
                it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
            } ?: error("SK02 AudioStreaming alt 0 is missing")
            val streamingTarget = interfaces.firstOrNull {
                it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == profile.alt
            } ?: error("SK02 AudioStreaming alt ${profile.alt} is missing")
            val connection = manager.openDevice(target) ?: error("Unable to open SK02")
            var controlClaimed = false
            var streamingClaimed = false
            var altSelected = false
            var originalClockHz: Int? = null
            try {
                check(UsbPrototypeGenerationOwner.gate.isCurrent(token)) { "Stale USB generation" }
                controlClaimed = connection.claimInterface(audioControl, true)
                check(UsbPrototypeGenerationOwner.gate.isCurrent(token)) { "Stale after control claim" }
                streamingClaimed = connection.claimInterface(streamingAlt0, true)
                check(controlClaimed && streamingClaimed) { "Unable to force-claim SK02 interfaces" }
                check(UsbPrototypeGenerationOwner.gate.isCurrent(token)) { "Stale after stream claim" }
                originalClockHz = readClockCurrentHz(connection)
                check(setClockFrequency(connection, config.sampleRate) == 4) {
                    "Unable to set SK02 clock to ${config.sampleRate} Hz"
                }
                check(readClockCurrentHz(connection) == config.sampleRate) {
                    "SK02 clock did not settle at ${config.sampleRate} Hz"
                }
                check(UsbPrototypeGenerationOwner.gate.isCurrent(token)) { "Stale after clock write" }
                altSelected = connection.setInterface(streamingTarget)
                check(altSelected) { "Unable to select SK02 alt ${profile.alt}" }
                check(UsbPrototypeGenerationOwner.gate.isCurrent(token)) { "Stale after alt select" }
                return UsbSk02AudioOutput(
                    connection = connection,
                    audioControl = audioControl,
                    streamingAlt0 = streamingAlt0,
                    streamingTarget = streamingTarget,
                    sampleRate = config.sampleRate,
                    inputEncoding = config.encoding,
                    usbBytesPerFrame = profile.usbBytesPerFrame,
                    maxPacketBytes = profile.maxPacketBytes,
                    originalClockHz = originalClockHz,
                    generation = token,
                ).also {
                    DiagnosticLog.event(
                        "UsbExclusivePrototype",
                        "opened sr=${config.sampleRate} inputEncoding=${config.encoding} " +
                            "alt=${profile.alt} generation=${token.value}",
                    )
                }
            } catch (error: Exception) {
                if (altSelected) runCatching { connection.setInterface(streamingAlt0) }
                originalClockHz?.let { runCatching { setClockFrequency(connection, it) } }
                if (streamingClaimed) runCatching { connection.releaseInterface(streamingAlt0) }
                if (controlClaimed) runCatching { connection.releaseInterface(audioControl) }
                connection.close()
                throw error
            }
        }

        private fun readClockCurrentHz(connection: UsbDeviceConnection): Int? {
            val bytes = ByteArray(4)
            val transferred = connection.controlTransfer(
                USB_CLASS_INTERFACE_IN,
                UAC2_REQUEST_CUR,
                UAC2_SAMPLING_FREQUENCY_CONTROL,
                (CLOCK_ID shl 8) or AUDIO_CONTROL_INTERFACE_ID,
                bytes,
                bytes.size,
                CONTROL_TIMEOUT_MS,
            )
            return if (transferred == 4) {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                null
            }
        }

        private fun setClockFrequency(connection: UsbDeviceConnection, sampleRate: Int): Int {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sampleRate)
                .array()
            return connection.controlTransfer(
                USB_CLASS_INTERFACE_OUT,
                UAC2_REQUEST_CUR,
                UAC2_SAMPLING_FREQUENCY_CONTROL,
                (CLOCK_ID shl 8) or AUDIO_CONTROL_INTERFACE_ID,
                bytes,
                bytes.size,
                CONTROL_TIMEOUT_MS,
            )
        }

        private data class Profile(
            val alt: Int,
            val usbBytesPerFrame: Int,
            val maxPacketBytes: Int,
        )
    }
}

/** Fail-closed conversion used only for exact signed PCM32 carried in Media3 float buffers. */
internal object ExactPcm32PrototypePacking {
    private const val CHANNEL_COUNT = 2
    private const val SCALE = 2_147_483_648.0
    private const val MIN = -2_147_483_648.0
    private const val MAX = 2_147_483_647.0

    fun pack(inputBuffer: ByteBuffer, frames: Int, output: ByteBuffer) {
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        repeat(frames * CHANNEL_COUNT) { sampleIndex ->
            val sample = input.float
            require(sample.isFinite()) { "sampleIndex=$sampleIndex value=$sample nonFinite=true" }
            val scaled = sample.toDouble() * SCALE
            val rounded = round(scaled)
            require(scaled == rounded && rounded in MIN..MAX) {
                "sampleIndex=$sampleIndex value=$sample scaled=$scaled rounded=$rounded " +
                    "residual=${scaled - rounded}"
            }
            val value = rounded.toLong().toInt()
            output.put((value and 0xff).toByte())
            output.put(((value ushr 8) and 0xff).toByte())
            output.put(((value ushr 16) and 0xff).toByte())
            output.put(((value ushr 24) and 0xff).toByte())
        }
    }
}
