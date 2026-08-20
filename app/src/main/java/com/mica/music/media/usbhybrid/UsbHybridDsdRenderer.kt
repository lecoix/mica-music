package com.mica.music.media.usbhybrid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import com.afalphy.sylvakru.DsfPlanarBlockConverter
import com.mica.music.media.dsf.DsfFormat

/** Raw DSF renderer. USB is opened during readiness, but payload writing arms only at STARTED. */
@UnstableApi
class UsbHybridDsdRenderer(
    private val binding: UsbHybridPlaybackBinding,
    private val native: Boolean,
) : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val formatHolder = FormatHolder()
    private var activeFormat: Format? = null
    private var converter: DsfPlanarBlockConverter? = null
    private var session: UsbTransportSessionId? = null
    private val preroll = DsdPrerollGate()
    private var ended = false

    override fun getName(): String = "UsbHybridDsdRenderer"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        if (format.sampleMimeType == DsfFormat.MIME_DSF) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
    )

    override fun isReady(): Boolean = ended || session != null || isSourceReady
    override fun isEnded(): Boolean = ended

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (ended) return
        if (!preroll.isStarted() && preroll.hasStaged()) {
            return
        }

        repeat(MAX_READS_PER_RENDER) {
            inputBuffer.clear()
            formatHolder.clear()
            when (readSource(formatHolder, inputBuffer, 0)) {
                C.RESULT_NOTHING_READ -> return
                C.RESULT_FORMAT_READ -> configure(checkNotNull(formatHolder.format))
                C.RESULT_BUFFER_READ -> {
                    if (inputBuffer.isEndOfStream) {
                        if (!preroll.isStarted() && preroll.hasStaged()) return
                        currentSession()?.let { token ->
                            handleRealtime(binding.realtime.finishDsd(token))
                        }
                        ended = true
                        return
                    }
                    val activeConverter = converter ?: run {
                        inputBuffer.format?.let(::configure)
                        converter ?: return
                    }
                    inputBuffer.flip()
                    val data = inputBuffer.data ?: return@repeat
                    if (!data.hasRemaining()) return@repeat
                    val planar = ByteArray(data.remaining())
                    data.get(planar)
                    val interleaved = try {
                        activeConverter.convert(planar)
                    } catch (error: IllegalArgumentException) {
                        throw createRendererException(
                            error,
                            activeFormat,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        )
                    }
                    if (!preroll.isStarted()) {
                        check(preroll.stage(interleaved)) { "DSD preroll gate accepted more than one buffer." }
                        return
                    }
                    write(interleaved)
                }
            }
        }
    }

    override fun onStarted() {
        currentSession()?.let { token ->
            if (!handleRealtime(binding.realtime.resumeDsd(token))) return
        }
        preroll.arm()?.let(::write)
    }

    override fun onStopped() {
        preroll.stop()
        currentSession()?.let { token ->
            handleRealtime(binding.realtime.pauseDsd(token))
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isPlaying: Boolean) {
        ended = false
        preroll.reset(started = isPlaying)
        currentSession()?.let { token ->
            handleRealtime(binding.realtime.prepareDsdSeek(token))
        }
    }

    override fun onDisabled() {
        ended = false
        preroll.clear()
        converter = null
        activeFormat = null
        session = null
    }

    override fun onRelease() {
        preroll.clear()
        session = null
    }

    private fun configure(format: Format) {
        if (format.sampleMimeType != DsfFormat.MIME_DSF || format.sampleRate <= 0 || format.channelCount <= 0) {
            fail("Only raw DSF is supported by USB DSD mode.", format)
        }
        val bitsPerSample = format.initializationData.firstOrNull()?.firstOrNull()?.toInt()?.and(0xff) ?: 1
        if (bitsPerSample !in setOf(1, 8)) fail("Unsupported DSF bitsPerSample=$bitsPerSample", format)
        val dsdRate = format.sampleRate * 8
        val facts = binding.owner.requestOpen(
            binding.epoch,
            UsbStreamFormat.Dsd(dsdRate, format.channelCount, native),
        ).get()
        val nativeId = facts.sessionId
            ?: fail(facts.failure?.message ?: "USB DSD session did not become active.", format)
        if (facts.requestEpoch != binding.epoch.value ||
            facts.activeMode != expectedMode() || !facts.exclusive || !facts.transportExact
        ) {
            fail(facts.failure?.message ?: "USB DSD facts did not match the explicit mode.", format)
        }
        session = UsbTransportSessionId(binding.epoch, nativeId)
        converter = DsfPlanarBlockConverter(format.channelCount, lsbFirst = bitsPerSample == 1)
        activeFormat = format
        preroll.reset(started = state == STATE_STARTED)
        ended = false
    }

    private fun expectedMode(): UsbExclusiveMode = if (native) {
        UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL
    } else {
        UsbExclusiveMode.USB_DOP
    }

    private fun currentSession(): UsbTransportSessionId? = session

    private fun write(payload: ByteArray) {
        val token = currentSession() ?: fail("USB DSD session is missing.", activeFormat)
        handleRealtime(binding.realtime.writeDsd(token, payload))
    }

    private fun handleRealtime(result: UsbRealtimeResult): Boolean = when (result) {
        UsbRealtimeResult.Success -> true
        UsbRealtimeResult.Retired -> false
        is UsbRealtimeResult.Failed -> fail(result.message, activeFormat)
    }

    private fun fail(message: String, format: Format?): Nothing {
        throw createRendererException(
            IllegalStateException(message),
            format,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }

    private companion object {
        const val MAX_READS_PER_RENDER = 4
    }
}
