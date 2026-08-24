package com.mica.music.media.usbhybrid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import com.afalphy.sylvakru.DsfPlanarBlockConverter
import com.mica.music.media.dsf.DsfFormat
import java.util.concurrent.TimeUnit

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
    private var transportUnavailable = false
    private var requestedVolumeGainQ16 = 65_536

    override fun getName(): String = "UsbHybridDsdRenderer"

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VOLUME) {
            val volume = (message as? Float)?.coerceIn(0f, 1f) ?: return
            requestedVolumeGainQ16 = (volume * 65_536f).toInt()
            session?.let {
                runSessionAction("set-volume") { active ->
                    binding.realtime.setVolume(active, requestedVolumeGainQ16)
                }
            }
            return
        }
        super.handleMessage(messageType, message)
    }

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        if (format.sampleMimeType == DsfFormat.MIME_DSF) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
    )

    override fun isReady(): Boolean = ended || session != null || isSourceReady
    override fun isEnded(): Boolean = ended

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (ended || transportUnavailable) return
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
                            runSessionAction("finish") { binding.realtime.finishDsd(it) }
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
                    if (!write(interleaved)) return
                }
            }
        }
    }

    override fun onStarted() {
        if (transportUnavailable) return
        if (currentSession() != null && !runSessionAction("resume") { binding.realtime.resumeDsd(it) }) return
        preroll.arm()?.let { if (!write(it)) return }
    }

    override fun onStopped() {
        preroll.stop()
        if (transportUnavailable) return
        if (currentSession() != null) runSessionAction("pause") { binding.realtime.pauseDsd(it) }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isPlaying: Boolean) {
        ended = false
        // Media3's callback isPlaying hint is not a renderer lifecycle authority. On-device, a
        // paused seek can report it true and would otherwise drain real DSD while playWhenReady=false.
        // Only the renderer's STARTED state may arm payload delivery.
        val rendererStarted = state == STATE_STARTED
        preroll.reset(started = rendererStarted)
        if (transportUnavailable) return
        if (currentSession() != null) {
            if (!runSessionAction("seek-reset") { binding.realtime.prepareDsdSeek(it) }) return
            // prepareDsdSeek stops the old filler while draining the encoder tail. A paused seek must
            // immediately resume valid 0x69 silence so the DAC stays locked until onStarted() drains
            // the single staged buffer from the new position.
            if (!rendererStarted) {
                runSessionAction("seek-paused-idle") { binding.realtime.pauseDsd(it) }
            }
        }
    }

    override fun onDisabled() {
        ended = false
        transportUnavailable = false
        preroll.clear()
        converter = null
        activeFormat = null
        session = null
    }

    override fun onRelease() {
        transportUnavailable = false
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
        val facts = runCatching {
            binding.owner.requestOpen(
                binding.epoch,
                UsbStreamFormat.Dsd(dsdRate, format.channelCount, native),
            ).get(10L, TimeUnit.SECONDS)
        }.getOrElse { error ->
            fail(error.message ?: "USB DSD open timed out or failed.", format)
        }
        val nativeId = facts.sessionId
            ?: fail(facts.failure?.message ?: "USB DSD session did not become active.", format)
        if (facts.requestEpoch != binding.epoch.value ||
            facts.activeMode != expectedMode() || !facts.exclusive || !facts.transportExact
        ) {
            fail(facts.failure?.message ?: "USB DSD facts did not match the explicit mode.", format)
        }
        session = UsbTransportSessionId(binding.epoch, nativeId)
        runSessionAction("set-volume") { active -> binding.realtime.setVolume(active, requestedVolumeGainQ16) }
        converter = DsfPlanarBlockConverter(format.channelCount, lsbFirst = bitsPerSample == 1)
        activeFormat = format
        val rendererStarted = state == STATE_STARTED
        preroll.reset(started = rendererStarted)
        // A fresh/reconfigured DSD session can be opened while Media3 is already STOPPED (for
        // example after a USB replug while paused). In that case there is no later onStopped()
        // edge to start the 0x69 carrier, so arm it immediately for the newly bound session.
        if (!rendererStarted && !transportUnavailable) {
            runSessionAction("configure-paused-idle") { binding.realtime.pauseDsd(it) }
        }
        ended = false
    }

    private fun expectedMode(): UsbExclusiveMode = if (native) {
        UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL
    } else {
        UsbExclusiveMode.USB_DOP
    }

    private fun currentSession(): UsbTransportSessionId? = session

    private fun write(payload: ByteArray): Boolean =
        runSessionAction("write") { binding.realtime.writeDsd(it, payload) }

    /**
     * A same-epoch retired session is a technical transport reopen, not a Media3 lifecycle event.
     * Rebind to the owner's fresh session and retry exactly once without resetting queue/timeline.
     */
    private fun runSessionAction(
        operation: String,
        action: (UsbTransportSessionId) -> UsbRealtimeResult,
    ): Boolean {
        val current = currentSession() ?: fail("USB DSD session is missing.", activeFormat)
        return when (val result = action(current)) {
            UsbRealtimeResult.Success -> true
            UsbRealtimeResult.Retired -> {
                if (binding.owner.currentEpoch() != binding.epoch) return false
                val replacement = reopenCurrentDsdSession()
                when (val retry = action(replacement)) {
                    UsbRealtimeResult.Success -> true
                    UsbRealtimeResult.Retired -> {
                        if (binding.owner.currentEpoch() != binding.epoch) false
                        else fail("USB DSD session retired again during same-epoch $operation reopen.", activeFormat)
                    }
                    is UsbRealtimeResult.Failed -> {
                        if (isUsbRealtimeTransportUnavailableError(retry.message)) {
                            transportUnavailable = true
                            false
                        } else {
                            fail(retry.message, activeFormat)
                        }
                    }
                }
            }
            is UsbRealtimeResult.Failed -> {
                if (isUsbRealtimeTransportUnavailableError(result.message)) {
                    transportUnavailable = true
                    false
                } else {
                    fail(result.message, activeFormat)
                }
            }
        }
    }

    private fun reopenCurrentDsdSession(): UsbTransportSessionId {
        val format = activeFormat ?: fail("USB DSD format is missing during technical reopen.", null)
        val facts = runCatching {
            binding.owner.requestOpen(
                binding.epoch,
                UsbStreamFormat.Dsd(format.sampleRate * 8, format.channelCount, native),
            ).get(10L, TimeUnit.SECONDS)
        }.getOrElse { error ->
            fail(error.message ?: "USB DSD same-epoch reopen failed.", format)
        }
        val nativeId = facts.sessionId
            ?: fail(facts.failure?.message ?: "USB DSD same-epoch reopen returned no session.", format)
        if (facts.requestEpoch != binding.epoch.value ||
            facts.activeMode != expectedMode() || !facts.exclusive || !facts.transportExact
        ) {
            fail(facts.failure?.message ?: "USB DSD same-epoch reopen facts did not match the active mode.", format)
        }
        return UsbTransportSessionId(binding.epoch, nativeId).also { session = it }
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
