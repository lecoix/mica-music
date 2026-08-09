package com.mica.music.media.usbprototype

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.decoder.ffmpeg.UsbExclusiveFfmpegPrototype
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.mica.music.media.MicaMediaService
import com.mica.music.media.UsbHostPrototypeOutput
import com.mica.music.media.UsbOutputRebuildSessionCommand
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbOutputDeviceLifecycle
import com.mica.music.media.usb.UsbOutputRequest
import com.mica.music.media.usb.UsbOutputRequestLease
import com.mica.music.media.usb.UsbOutputRequestToken
import com.mica.music.media.usb.UsbOutputRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/**
 * THROWAWAY PROTOTYPE.
 *
 * Question: can the single target DAC (Fosi SK02, 262a:0001) be driven through an exclusive
 * native USBFS transport, including a sustained feedback-controlled PCM queue?
 *
 * Some actions are read-only; the transport actions force-claim the audio interfaces and write
 * silence or a low-level generated PCM buffer. This debug-only receiver is intentionally not
 * wired into production playback.
 */
class UsbSk02DescriptorPrototypeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val probeAction = "${context.packageName}.debug.USB_SK02_PROBE"
        val claimProbeAction = "${context.packageName}.debug.USB_SK02_CLAIM_PROBE"
        val nativeFdProbeAction = "${context.packageName}.debug.USB_SK02_NATIVE_FD_PROBE"
        val reconnectAction = "${context.packageName}.debug.USB_SK02_RECONNECT"
        val forceClaimProbeAction = "${context.packageName}.debug.USB_SK02_FORCE_CLAIM_PROBE"
        val isoFeedbackProbeAction = "${context.packageName}.debug.USB_SK02_ISO_FEEDBACK_PROBE"
        val silentPcm16ProbeAction = "${context.packageName}.debug.USB_SK02_SILENT_PCM16_PROBE"
        val sustainedPcm16ProbeAction = "${context.packageName}.debug.USB_SK02_SUSTAINED_PCM16_PROBE"
        val generationPcm16ProbeAction = "${context.packageName}.debug.USB_SK02_GENERATION_PCM16_PROBE"
        val bufferedPcm16ProbeAction = "${context.packageName}.debug.USB_SK02_BUFFERED_PCM16_PROBE"
        val mediaFormatProbeAction = "${context.packageName}.debug.USB_SK02_MEDIA_FORMAT_PROBE"
        val mediaDecodeProbeAction = "${context.packageName}.debug.USB_SK02_MEDIA_DECODE_PROBE"
        val mediaUsbProbeAction = "${context.packageName}.debug.USB_SK02_MEDIA_USB_PROBE"
        val highResDecodeProbeAction = "${context.packageName}.debug.USB_SK02_HIGH_RES_DECODE_PROBE"
        val ffmpeg24ProbeAction = "${context.packageName}.debug.USB_SK02_FFMPEG24_PROBE"
        val ffmpeg24UsbProbeAction = "${context.packageName}.debug.USB_SK02_FFMPEG24_USB_PROBE"
        val media3EnableAction = "${context.packageName}.debug.USB_SK02_MEDIA3_ENABLE"
        val media3DisableAction = "${context.packageName}.debug.USB_SK02_MEDIA3_DISABLE"
        val media3PlayAction = "${context.packageName}.debug.USB_SK02_MEDIA3_PLAY"
        val media3PauseAction = "${context.packageName}.debug.USB_SK02_MEDIA3_PAUSE"
        val media3NextAction = "${context.packageName}.debug.USB_SK02_MEDIA3_NEXT"
        val media3SelectIndexAction = "${context.packageName}.debug.USB_SK02_MEDIA3_SELECT_INDEX"
        val media3SeekNearEndAction = "${context.packageName}.debug.USB_SK02_MEDIA3_SEEK_NEAR_END"
        val media3RepeatOneAction = "${context.packageName}.debug.USB_SK02_MEDIA3_REPEAT_ONE"
        val media3RepeatOffAction = "${context.packageName}.debug.USB_SK02_MEDIA3_REPEAT_OFF"
        val permissionAction = "${context.packageName}.debug.USB_SK02_PERMISSION"
        val retiredRawTransportActions = setOf(
            claimProbeAction,
            forceClaimProbeAction,
            isoFeedbackProbeAction,
            silentPcm16ProbeAction,
            sustainedPcm16ProbeAction,
            generationPcm16ProbeAction,
            bufferedPcm16ProbeAction,
            mediaUsbProbeAction,
            ffmpeg24UsbProbeAction,
        )
        if (intent.action in retiredRawTransportActions) {
            state("rawTransportProbe=retired useMedia3ProductionContract=true")
            return
        }
        when (intent.action) {
            probeAction -> beginProbe(context, permissionAction)
            claimProbeAction -> beginClaimProbe(context)
            nativeFdProbeAction -> beginNativeFdProbe(context)
            reconnectAction -> beginReconnect(context)
            forceClaimProbeAction -> beginForceClaimProbe(context)
            isoFeedbackProbeAction -> beginIsoFeedbackProbe(context)
            silentPcm16ProbeAction -> beginSilentPcm16Probe(context)
            sustainedPcm16ProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val durationMs = intent.getIntExtra("durationMs", 5_000).coerceIn(1_000, 60_000)
                Thread(
                    {
                        try {
                            beginSustainedPcm16Probe(appContext, durationMs)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02SustainedPrototype",
                ).start()
            }
            generationPcm16ProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val durationMs = intent.getIntExtra("durationMs", 5_000).coerceIn(1_000, 60_000)
                val supersedeAfterMs = intent.getIntExtra("supersedeAfterMs", -1)
                val token = UsbPrototypeGenerationOwner.gate.beginHarnessRequest()
                if (token == null) {
                    state("generationPcm16Probe=busy activeProductionSession=true")
                    pendingResult.finish()
                    return
                }
                UsbSk02NativePrototype.publishGeneration(token.value)
                state("generationPcm16Probe=queued generation=${token.value} durationMs=$durationMs")
                Thread(
                    {
                        try {
                            if (supersedeAfterMs >= 0) {
                                val oldThread = Thread(
                                    {
                                        runGenerationPcm16Probe(appContext, durationMs, token)
                                    },
                                    "UsbSk02Generation${token.value}",
                                )
                                oldThread.start()
                                Thread.sleep(supersedeAfterMs.toLong())
                                val newer = UsbPrototypeGenerationOwner.gate.beginHarnessRequest()
                                    ?: error("Production session became active during harness probe")
                                UsbSk02NativePrototype.publishGeneration(newer.value)
                                state(
                                    "generationPcm16Probe=queued generation=${newer.value} " +
                                        "durationMs=3000 supersedes=${token.value}",
                                )
                                runGenerationPcm16Probe(appContext, 3_000, newer)
                                oldThread.join()
                            } else {
                                runGenerationPcm16Probe(appContext, durationMs, token)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02Generation${token.value}",
                ).start()
            }
            bufferedPcm16ProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                Thread(
                    {
                        try {
                            beginBufferedPcm16Probe(appContext)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02BufferedPrototype",
                ).start()
            }
            mediaFormatProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                Thread(
                    {
                        try {
                            probeMediaFormats(appContext)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02MediaFormatPrototype",
                ).start()
            }
            mediaDecodeProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val mediaId = intent.getLongExtra("mediaId", DEFAULT_DECODE_PROBE_MEDIA_ID)
                Thread(
                    {
                        try {
                            decodeMediaProbe(appContext, mediaId)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02MediaDecodePrototype",
                ).start()
            }
            mediaUsbProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val mediaId = intent.getLongExtra("mediaId", DEFAULT_DECODE_PROBE_MEDIA_ID)
                Thread(
                    {
                        try {
                            decodeMediaProbe(appContext, mediaId, sendToUsb = true)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02MediaUsbPrototype",
                ).start()
            }
            highResDecodeProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val mediaId = intent.getLongExtra("mediaId", DEFAULT_HIGH_RES_PROBE_MEDIA_ID)
                Thread(
                    {
                        try {
                            probeHighResDecodeFormat(appContext, mediaId)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02HighResDecodePrototype",
                ).start()
            }
            ffmpeg24ProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val mediaId = intent.getLongExtra("mediaId", DEFAULT_HIGH_RES_PROBE_MEDIA_ID)
                Thread(
                    {
                        try {
                            probeFfmpeg24(appContext, mediaId)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02Ffmpeg24Prototype",
                ).start()
            }
            ffmpeg24UsbProbeAction -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                val mediaId = intent.getLongExtra("mediaId", DEFAULT_HIGH_RES_PROBE_MEDIA_ID)
                Thread(
                    {
                        try {
                            probeFfmpeg24(appContext, mediaId, sendToUsb = true)
                        } finally {
                            pendingResult.finish()
                        }
                    },
                    "UsbSk02Ffmpeg24UsbPrototype",
                ).start()
            }
            media3EnableAction ->
                beginSetMedia3PrototypeEnabled(context, goAsync(), enabled = true)
            media3DisableAction ->
                beginSetMedia3PrototypeEnabled(context, goAsync(), enabled = false)
            media3PlayAction -> beginMedia3Control(context, goAsync(), Media3Control.PLAY)
            media3PauseAction -> beginMedia3Control(context, goAsync(), Media3Control.PAUSE)
            media3NextAction -> beginMedia3Control(context, goAsync(), Media3Control.NEXT)
            media3SelectIndexAction -> beginMedia3Control(
                context,
                goAsync(),
                Media3Control.SELECT_INDEX,
                intent.getIntExtra("mediaIndex", -1),
            )
            media3SeekNearEndAction ->
                beginMedia3Control(context, goAsync(), Media3Control.SEEK_NEAR_END)
            media3RepeatOneAction ->
                beginMedia3Control(context, goAsync(), Media3Control.REPEAT_ONE)
            media3RepeatOffAction ->
                beginMedia3Control(context, goAsync(), Media3Control.REPEAT_OFF)
            permissionAction -> {
                val device = intent.usbDeviceExtra()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                state("permissionResult=$granted device=${device?.identity()}")
                if (granted && device != null) probe(context, device)
                else state("probe=permission_denied claimed=false audioWrittenBytes=0")
            }
        }
    }

    private fun beginSetMedia3PrototypeEnabled(
        context: Context,
        pendingResult: PendingResult,
        enabled: Boolean,
    ) {
        if (enabled) {
            val manager = context.getSystemService(UsbManager::class.java)
            val target = manager.deviceList.values.firstOrNull {
                it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
            }
            if (target == null) {
                state(
                    "media3Prototype=enable_rejected targetFound=false permission=false",
                )
                pendingResult.finish()
                return
            }
            if (!manager.hasPermission(target)) {
                val token = UsbOutputDeviceLifecycle.requestPermission(
                    context,
                    UsbOutputRequest(device = Sk02UsbContract.identity),
                )
                state(
                    "media3Prototype=permission_requested generation=${token.value} " +
                        "restartRequired=true repeatEnableAfterGrant=true",
                )
                pendingResult.finish()
                return
            }
        }
        val previousEnabled = UsbHostPrototypeOutput.isEnabled(context)
        UsbHostPrototypeOutput.setEnabled(context, enabled)
        val future = MediaController.Builder(
            context.applicationContext,
            SessionToken(context, ComponentName(context, MicaMediaService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                try {
                    val controller = future.get()
                    val rebuildFuture = controller.sendCustomCommand(
                        UsbOutputRebuildSessionCommand.command,
                        Bundle.EMPTY,
                    )
                    rebuildFuture.addListener(
                        {
                            try {
                                val result = rebuildFuture.get()
                                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                                    UsbHostPrototypeOutput.setEnabled(context, previousEnabled)
                                }
                                state(
                                    "media3Prototype=${if (enabled) "enabled" else "disabled"} " +
                                        "fullModeRebuild=" +
                                        "${result.resultCode == SessionResult.RESULT_SUCCESS} " +
                                        "resultCode=${result.resultCode}",
                                )
                            } catch (error: Throwable) {
                                UsbHostPrototypeOutput.setEnabled(context, previousEnabled)
                                state(
                                    "media3Prototype=rebuild_failed enabled=$enabled " +
                                        "error=${error.javaClass.simpleName}:${error.message}",
                                )
                            } finally {
                                MediaController.releaseFuture(future)
                                pendingResult.finish()
                            }
                        },
                        ContextCompat.getMainExecutor(context),
                    )
                } catch (error: Throwable) {
                    UsbHostPrototypeOutput.setEnabled(context, previousEnabled)
                    state(
                        "media3Prototype=rebuild_failed enabled=$enabled " +
                            "error=${error.javaClass.simpleName}:${error.message}",
                    )
                    MediaController.releaseFuture(future)
                    pendingResult.finish()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun beginMedia3Control(
        context: Context,
        pendingResult: PendingResult,
        command: Media3Control,
        mediaIndex: Int = -1,
    ) {
        val future = MediaController.Builder(
            context.applicationContext,
            SessionToken(context, ComponentName(context, MicaMediaService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                try {
                    val controller = future.get()
                    when (command) {
                        Media3Control.PLAY -> {
                            controller.prepare()
                            controller.play()
                        }
                        Media3Control.PAUSE -> controller.pause()
                        Media3Control.NEXT -> {
                            controller.seekToNextMediaItem()
                            controller.play()
                        }
                        Media3Control.SELECT_INDEX -> {
                            check(mediaIndex in 0 until controller.mediaItemCount) {
                                "mediaIndex=$mediaIndex itemCount=${controller.mediaItemCount}"
                            }
                            controller.seekToDefaultPosition(mediaIndex)
                            controller.play()
                        }
                        Media3Control.SEEK_NEAR_END -> {
                            val duration = controller.duration
                            check(duration != C.TIME_UNSET && duration > 0) {
                                "Current media duration is unavailable"
                            }
                            controller.seekTo((duration - 5_000L).coerceAtLeast(0L))
                            controller.play()
                        }
                        Media3Control.REPEAT_ONE ->
                            controller.repeatMode = Player.REPEAT_MODE_ONE
                        Media3Control.REPEAT_OFF ->
                            controller.repeatMode = Player.REPEAT_MODE_OFF
                    }
                    state(
                        "media3Control=${command.name.lowercase()} complete=true " +
                            "index=${controller.currentMediaItemIndex} " +
                            "positionMs=${controller.currentPosition} durationMs=${controller.duration}",
                    )
                } catch (error: Throwable) {
                    state(
                        "media3Control=${command.name.lowercase()} complete=false " +
                            "error=${error.javaClass.simpleName}:${error.message}",
                    )
                } finally {
                    MediaController.releaseFuture(future)
                    pendingResult.finish()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private enum class Media3Control {
        PLAY,
        PAUSE,
        NEXT,
        SELECT_INDEX,
        SEEK_NEAR_END,
        REPEAT_ONE,
        REPEAT_OFF,
    }

    private fun probeFfmpeg24(context: Context, mediaId: Long, sendToUsb: Boolean = false) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .appendPath(mediaId.toString())
            .build()
        try {
            state("ffmpeg24Probe=running id:$mediaId")
            val result = UsbExclusiveFfmpegPrototype.decodeFlac24(
                context,
                uri,
                HIGH_RES_PROBE_SAMPLE_RATE_HZ,
                HIGH_RES_PROBE_SAMPLE_RATE_HZ * FFMPEG24_PROBE_SECONDS,
            )
            val crc = CRC32().apply { update(result.packedPcm24) }.value
            val exact = result.sampleRate == HIGH_RES_PROBE_SAMPLE_RATE_HZ &&
                result.channelCount == 2 &&
                result.nonIntegralSamples == 0L &&
                result.packedPcm24.isNotEmpty()
            state(
                "ffmpeg24Probe=complete id:$mediaId decoder:${result.decoderName} " +
                    "rate:${result.sampleRate} channels:${result.channelCount} " +
                    "encoding:${result.encoding} samples:${result.sampleCount} " +
                    "packedBytes:${result.packedPcm24.size} crc32:$crc " +
                    "nonIntegralSamples:${result.nonIntegralSamples} " +
                    "maxResidual:${result.maxResidual} exact24:$exact",
            )
            if (sendToUsb) {
                if (!exact) {
                    state("ffmpeg24UsbProbe=rejected id:$mediaId reason:not_exact_24bit")
                    return
                }
                state("ffmpeg24UsbProbe=handoff id:$mediaId bytes:${result.packedPcm24.size} gain:unity")
                beginPcmUsbProbe(
                    context = context,
                    pcm = result.packedPcm24,
                    durationMs = FFMPEG24_USB_PROBE_DURATION_MS,
                    probeName = "ffmpeg24UsbProbe",
                    sampleRateHz = HIGH_RES_PROBE_SAMPLE_RATE_HZ,
                    alternateSetting = 2,
                    pcm24 = true,
                )
            }
        } catch (error: Exception) {
            state(
                "ffmpeg24Probe=failed id:$mediaId error:${error.javaClass.simpleName} " +
                    "message:${error.message}",
            )
        }
    }

    private fun probeHighResDecodeFormat(context: Context, mediaId: Long) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .appendPath(mediaId.toString())
            .build()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (trackIndex == null) {
                state("highResDecodeProbe=rejected id:$mediaId reason:no_audio_track")
                return
            }
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            val inputRate = inputFormat.intValueOrNull(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.intValueOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            val inputBits = inputFormat.intValueOrNull("bits-per-sample")
            state(
                "highResDecodeProbe=input id:$mediaId mime:$mime rate:$inputRate " +
                    "channels:$inputChannels bits:$inputBits",
            )
            if (mime != "audio/flac" || inputRate != HIGH_RES_PROBE_SAMPLE_RATE_HZ ||
                inputChannels != 2 || inputBits != 24
            ) {
                state("highResDecodeProbe=rejected id:$mediaId reason:input_format_mismatch")
                return
            }
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true
            state("highResDecodeProbe=running id:$mediaId decoder:${codec.name}")
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            val deadlineNanos = System.nanoTime() + HIGH_RES_PROBE_TIMEOUT_NANOS
            while (System.nanoTime() < deadlineNanos) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(MEDIA_CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, MEDIA_CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        val rate = output.intValueOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = output.intValueOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = output.intValueOrNull(MediaFormat.KEY_PCM_ENCODING)
                        val exactPacked24 = rate == HIGH_RES_PROBE_SAMPLE_RATE_HZ &&
                            channels == 2 &&
                            encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                        state(
                            "highResDecodeProbe=complete id:$mediaId rate:$rate channels:$channels " +
                                "pcmEncoding:$encoding exactPacked24:$exactPacked24",
                        )
                        return
                    }
                    else -> if (outputIndex >= 0) codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            state("highResDecodeProbe=failed id:$mediaId reason:output_format_timeout")
        } catch (error: Exception) {
            state(
                "highResDecodeProbe=failed id:$mediaId error:${error.javaClass.simpleName} " +
                    "message:${error.message}",
            )
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodeMediaProbe(context: Context, mediaId: Long, sendToUsb: Boolean = false) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .appendPath(mediaId.toString())
            .build()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var stage = "setDataSource"
        try {
            extractor.setDataSource(context, uri, null)
            stage = "inspectTrack"
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (trackIndex == null) {
                state("mediaDecodeProbe=rejected id:$mediaId reason:no_audio_track")
                return
            }
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            val inputRate = inputFormat.intValueOrNull(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.intValueOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            val inputBits = inputFormat.intValueOrNull("bits-per-sample")
            if (mime != "audio/flac" || inputRate != PCM16_PROBE_SAMPLE_RATE_HZ ||
                inputChannels != 2 || inputBits != 16
            ) {
                state(
                    "mediaDecodeProbe=rejected id:$mediaId mime:$mime rate:$inputRate " +
                        "channels:$inputChannels bits:$inputBits",
                )
                return
            }
            extractor.selectTrack(trackIndex)
            stage = "createDecoder"
            codec = MediaCodec.createDecoderByType(mime)
            state("mediaDecodeProbe=running id:$mediaId decoder:${codec.name}")
            stage = "configureDecoder"
            codec.configure(inputFormat, null, null, 0)
            stage = "startDecoder"
            codec.start()
            codecStarted = true
            stage = "decode"

            val output = ByteArrayOutputStream(MEDIA_DECODE_PROBE_MAX_BYTES)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputAccepted = false
            var outputRate: Int? = null
            var outputChannels: Int? = null
            var outputEncoding: Int? = null
            val deadlineNanos = System.nanoTime() + MEDIA_DECODE_PROBE_TIMEOUT_NANOS
            while (!outputEnded && output.size() < MEDIA_DECODE_PROBE_MAX_BYTES &&
                System.nanoTime() < deadlineNanos
            ) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(MEDIA_CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, MEDIA_CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.intValueOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.intValueOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = format.intValueOrNull(MediaFormat.KEY_PCM_ENCODING)
                        outputAccepted = outputRate == PCM16_PROBE_SAMPLE_RATE_HZ &&
                            outputChannels == 2 &&
                            outputEncoding == AudioFormat.ENCODING_PCM_16BIT
                        state(
                            "mediaDecodeProbe=outputFormat rate:$outputRate channels:$outputChannels " +
                                "pcmEncoding:$outputEncoding accepted:$outputAccepted",
                        )
                        if (!outputAccepted) return
                    }
                    else -> if (outputIndex >= 0) {
                        if (!outputAccepted) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            state("mediaDecodeProbe=rejected id:$mediaId reason:pcm_format_not_confirmed")
                            return
                        }
                        val buffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val copyBytes = minOf(
                            info.size,
                            MEDIA_DECODE_PROBE_MAX_BYTES - output.size(),
                        ) and -4
                        if (copyBytes > 0) {
                            val chunk = ByteArray(copyBytes)
                            buffer.get(chunk)
                            output.write(chunk)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            val pcm = output.toByteArray()
            if (!outputAccepted || pcm.isEmpty()) {
                state("mediaDecodeProbe=failed id:$mediaId reason:no_accepted_pcm")
                return
            }
            val nonZeroBytes = pcm.count { it != 0.toByte() }
            var peak = 0
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            while (samples.remaining() >= 2) {
                val sample = samples.short.toInt()
                peak = maxOf(peak, kotlin.math.abs(sample))
            }
            val crc = CRC32().apply { update(pcm) }.value
            state(
                "mediaDecodeProbe=complete id:$mediaId bytes:${pcm.size} " +
                    "frames:${pcm.size / 4} nonZeroBytes:$nonZeroBytes peak:$peak crc32:$crc " +
                    "inputEnded:$inputEnded outputEnded:$outputEnded",
            )
            if (sendToUsb) {
                state("mediaUsbProbe=handoff id:$mediaId bytes:${pcm.size} gain:unity")
                beginPcmUsbProbe(
                    context = context,
                    pcm = pcm,
                    durationMs = DECODED_USB_PROBE_DURATION_MS,
                    probeName = "mediaUsbProbe",
                )
            }
        } catch (error: Exception) {
            state(
                "mediaDecodeProbe=failed id:$mediaId stage:$stage " +
                    "error:${error.javaClass.simpleName} message:${error.message}",
            )
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun probeMediaFormats(context: Context) {
        var inspected = 0
        var compatible = 0
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.MIME_TYPE),
                "${MediaStore.Audio.Media.MIME_TYPE}=?",
                arrayOf("audio/flac"),
                "${MediaStore.Audio.Media._ID} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                while (cursor.moveToNext() && inspected < MEDIA_FORMAT_PROBE_LIMIT) {
                    val id = cursor.getLong(idColumn)
                    val mime = cursor.getString(mimeColumn)
                    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(id.toString())
                        .build()
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, uri, null)
                        val format = (0 until extractor.trackCount)
                            .map(extractor::getTrackFormat)
                            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        val rate = format?.intValueOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format?.intValueOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                        val bits = format?.intValueOrNull("bits-per-sample")
                        val pcmEncoding = format?.intValueOrNull(MediaFormat.KEY_PCM_ENCODING)
                        val matches = rate == PCM16_PROBE_SAMPLE_RATE_HZ &&
                            channels == 2 && bits == 16
                        if (matches) ++compatible
                        state(
                            "mediaFormatProbe=id:$id mime:$mime rate:$rate channels:$channels " +
                                "bits:$bits pcmEncoding:$pcmEncoding compatible:$matches",
                        )
                    } catch (error: Exception) {
                        state("mediaFormatProbe=id:$id error:${error.javaClass.simpleName}")
                    } finally {
                        extractor.release()
                    }
                    ++inspected
                }
            }
            state("mediaFormatProbe=complete inspected=$inspected compatible=$compatible")
        } catch (error: Exception) {
            state("mediaFormatProbe=failed error=${error.javaClass.simpleName} inspected=$inspected")
        }
    }

    private fun MediaFormat.intValueOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun beginBufferedPcm16Probe(context: Context) {
        beginPcmUsbProbe(
            context = context,
            pcm = createBufferedProbePcm16(),
            durationMs = BUFFERED_PROBE_DURATION_MS,
            probeName = "bufferedPcm16Probe",
        )
    }

    private fun beginPcmUsbProbe(
        context: Context,
        pcm: ByteArray,
        durationMs: Int,
        probeName: String,
        sampleRateHz: Int = PCM16_PROBE_SAMPLE_RATE_HZ,
        alternateSetting: Int = 1,
        pcm24: Boolean = false,
    ) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("$probeName=unavailable targetFound=${target != null}")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt0 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingTarget = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == alternateSetting
        }
        if (audioControl == null || audioStreamingAlt0 == null || audioStreamingTarget == null) {
            state("$probeName=interfaces_missing")
            return
        }
        val crc = CRC32().apply { update(pcm) }.value
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("$probeName=open_failed")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        var alt1Selected = false
        var originalClockHz: Int? = null
        try {
            controlClaimed = connection.claimInterface(audioControl, true)
            streamingClaimed = connection.claimInterface(audioStreamingAlt0, true)
            if (!controlClaimed || !streamingClaimed) {
                state("$probeName=claim_failed")
                return
            }
            originalClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            val setClockBytes = setClockFrequency(
                connection,
                AUDIO_CONTROL_INTERFACE_ID,
                1,
                sampleRateHz,
            )
            val activeClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            if (setClockBytes != 4 || activeClockHz != sampleRateHz) {
                state("$probeName=set_clock_failed activeHz=$activeClockHz")
                return
            }
            alt1Selected = connection.setInterface(audioStreamingTarget)
            if (!alt1Selected) {
                state("$probeName=set_alt_failed alternateSetting=$alternateSetting")
                return
            }
            state(
                "$probeName=running durationMs=$durationMs gain:unity " +
                    "sourceBytes=${pcm.size} sourceCrc32=$crc originalClockHz=$originalClockHz",
            )
            val result = if (pcm24) {
                UsbSk02NativePrototype.runPcm24Queue(
                    connection.fileDescriptor,
                    durationMs,
                    pcm,
                    sampleRateHz,
                )
            } else {
                UsbSk02NativePrototype.runPcm16Queue(
                    connection.fileDescriptor,
                    durationMs,
                    pcm,
                )
            }
            state("$probeName=transport result={$result}")
        } finally {
            val alt0Restored = alt1Selected && connection.setInterface(audioStreamingAlt0)
            val clockRestoreBytes = originalClockHz?.let {
                setClockFrequency(connection, AUDIO_CONTROL_INTERFACE_ID, 1, it)
            }
            val restoredClockHz = originalClockHz?.let {
                readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            }
            val streamingReleased = streamingClaimed && connection.releaseInterface(audioStreamingAlt0)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            connection.close()
            state(
                "$probeName=complete alt0Restored=$alt0Restored " +
                    "clockRestoreBytes=$clockRestoreBytes restoredClockHz=$restoredClockHz " +
                    "streamingReleased=$streamingReleased controlReleased=$controlReleased",
            )
        }
    }

    private fun createBufferedProbePcm16(): ByteArray {
        val frames = PCM16_PROBE_SAMPLE_RATE_HZ * BUFFERED_PROBE_SOURCE_SECONDS
        val output = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame ->
            val seconds = frame.toDouble() / PCM16_PROBE_SAMPLE_RATE_HZ
            val envelope = when {
                seconds < 0.05 -> seconds / 0.05
                seconds < 2.70 -> 1.0
                seconds < 3.00 -> (3.00 - seconds) / 0.30
                else -> 0.0
            }
            val left = (BUFFERED_PROBE_AMPLITUDE * envelope * sin(2.0 * PI * 997.0 * seconds)).toInt()
            val right = (BUFFERED_PROBE_AMPLITUDE * envelope * sin(2.0 * PI * 1499.0 * seconds)).toInt()
            output.putShort(left.toShort())
            output.putShort(right.toShort())
        }
        return output.array()
    }

    private fun runGenerationPcm16Probe(
        context: Context,
        durationMs: Int,
        token: UsbOutputRequestToken,
    ) {
        val ran = UsbPrototypeGenerationOwner.gate.withTransport(token) { lease ->
            beginGenerationPcm16Probe(context, durationMs, token.value, lease)
        }
        if (ran == null) {
            state("generationPcm16Probe=superseded_before_transport generation=${token.value}")
        }
    }

    private fun beginGenerationPcm16Probe(
        context: Context,
        durationMs: Int,
        generation: Long,
        lease: UsbOutputRequestLease,
    ) {
        val probeName = "generationPcm16Probe"
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target) || !lease.isCurrent()) {
            state("$probeName=unavailable generation=$generation current=${lease.isCurrent()}")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val streamingAlt0 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        val streamingAlt1 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 1
        }
        if (audioControl == null || streamingAlt0 == null || streamingAlt1 == null) {
            state("$probeName=interfaces_missing generation=$generation")
            return
        }
        if (!lease.isCurrent()) return
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("$probeName=open_failed generation=$generation")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        var alt1Selected = false
        var originalClockHz: Int? = null
        try {
            if (!lease.isCurrent()) return
            controlClaimed = connection.claimInterface(audioControl, true)
            if (!lease.isCurrent()) return
            streamingClaimed = connection.claimInterface(streamingAlt0, true)
            if (!controlClaimed || !streamingClaimed || !lease.isCurrent()) {
                state("$probeName=claim_aborted generation=$generation current=${lease.isCurrent()}")
                return
            }
            originalClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            if (!lease.isCurrent()) return
            val setClockBytes = setClockFrequency(
                connection,
                AUDIO_CONTROL_INTERFACE_ID,
                1,
                PCM16_PROBE_SAMPLE_RATE_HZ,
            )
            val activeClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            if (setClockBytes != 4 || activeClockHz != PCM16_PROBE_SAMPLE_RATE_HZ ||
                !lease.isCurrent()
            ) {
                state("$probeName=set_clock_aborted generation=$generation activeHz=$activeClockHz")
                return
            }
            alt1Selected = connection.setInterface(streamingAlt1)
            if (!alt1Selected || !lease.isCurrent()) {
                state("$probeName=set_alt_aborted generation=$generation current=${lease.isCurrent()}")
                return
            }
            state(
                "$probeName=running generation=$generation durationMs=$durationMs " +
                    "originalClockHz=$originalClockHz",
            )
            val result = UsbSk02NativePrototype.runSilentPcm16QueueGeneration(
                connection.fileDescriptor,
                durationMs,
                generation,
            )
            state("$probeName=transport generation=$generation result={$result}")
        } finally {
            val alt0Restored = alt1Selected && connection.setInterface(streamingAlt0)
            val clockRestoreBytes = originalClockHz?.let {
                setClockFrequency(connection, AUDIO_CONTROL_INTERFACE_ID, 1, it)
            }
            val restoredClockHz = originalClockHz?.let {
                readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            }
            val streamingReleased = streamingClaimed && connection.releaseInterface(streamingAlt0)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            connection.close()
            state(
                "$probeName=complete generation=$generation current=${lease.isCurrent()} " +
                    "alt0Restored=$alt0Restored clockRestoreBytes=$clockRestoreBytes " +
                    "restoredClockHz=$restoredClockHz streamingReleased=$streamingReleased " +
                    "controlReleased=$controlReleased",
            )
        }
    }

    private fun beginSustainedPcm16Probe(context: Context, durationMs: Int) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("sustainedPcm16Probe=unavailable targetFound=${target != null}")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt0 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt1 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 1
        }
        if (audioControl == null || audioStreamingAlt0 == null || audioStreamingAlt1 == null) {
            state("sustainedPcm16Probe=interfaces_missing")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("sustainedPcm16Probe=open_failed")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        var alt1Selected = false
        var originalClockHz: Int? = null
        try {
            controlClaimed = connection.claimInterface(audioControl, true)
            streamingClaimed = connection.claimInterface(audioStreamingAlt0, true)
            if (!controlClaimed || !streamingClaimed) {
                state("sustainedPcm16Probe=claim_failed")
                return
            }
            originalClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            val setClockBytes = setClockFrequency(
                connection,
                AUDIO_CONTROL_INTERFACE_ID,
                1,
                PCM16_PROBE_SAMPLE_RATE_HZ,
            )
            val activeClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            if (setClockBytes != 4 || activeClockHz != PCM16_PROBE_SAMPLE_RATE_HZ) {
                state("sustainedPcm16Probe=set_clock_failed activeHz=$activeClockHz")
                return
            }
            alt1Selected = connection.setInterface(audioStreamingAlt1)
            if (!alt1Selected) {
                state("sustainedPcm16Probe=set_alt1_failed")
                return
            }
            state("sustainedPcm16Probe=running durationMs=$durationMs originalClockHz=$originalClockHz")
            val result = UsbSk02NativePrototype.runSilentPcm16Queue(
                connection.fileDescriptor,
                durationMs,
            )
            state("sustainedPcm16Probe=transport result={$result}")
        } finally {
            val alt0Restored = alt1Selected && connection.setInterface(audioStreamingAlt0)
            val clockRestoreBytes = originalClockHz?.let {
                setClockFrequency(connection, AUDIO_CONTROL_INTERFACE_ID, 1, it)
            }
            val restoredClockHz = originalClockHz?.let {
                readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            }
            val streamingReleased = streamingClaimed && connection.releaseInterface(audioStreamingAlt0)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            connection.close()
            state(
                "sustainedPcm16Probe=complete alt0Restored=$alt0Restored " +
                    "clockRestoreBytes=$clockRestoreBytes restoredClockHz=$restoredClockHz " +
                    "streamingReleased=$streamingReleased controlReleased=$controlReleased",
            )
        }
    }

    private fun beginSilentPcm16Probe(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("silentPcm16Probe=unavailable targetFound=${target != null} audioWrittenBytes=0")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt0 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt1 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 1
        }
        if (audioControl == null || audioStreamingAlt0 == null || audioStreamingAlt1 == null) {
            state("silentPcm16Probe=interfaces_missing audioWrittenBytes=0")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("silentPcm16Probe=open_failed audioWrittenBytes=0")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        var alt1Selected = false
        var originalClockHz: Int? = null
        try {
            controlClaimed = connection.claimInterface(audioControl, true)
            streamingClaimed = connection.claimInterface(audioStreamingAlt0, true)
            if (!controlClaimed || !streamingClaimed) {
                state("silentPcm16Probe=claim_failed audioWrittenBytes=0")
                return
            }
            originalClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            val setClockBytes = setClockFrequency(
                connection,
                AUDIO_CONTROL_INTERFACE_ID,
                1,
                PCM16_PROBE_SAMPLE_RATE_HZ,
            )
            val activeClockHz = readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            state(
                "silentPcm16Probe=clock originalHz=$originalClockHz setBytes=$setClockBytes " +
                    "activeHz=$activeClockHz",
            )
            if (setClockBytes != 4 || activeClockHz != PCM16_PROBE_SAMPLE_RATE_HZ) {
                state("silentPcm16Probe=set_clock_failed audioWrittenBytes=0")
                return
            }
            alt1Selected = connection.setInterface(audioStreamingAlt1)
            if (!alt1Selected) {
                state("silentPcm16Probe=set_alt1_failed audioWrittenBytes=0")
                return
            }
            val feedback = UsbSk02NativePrototype.readFeedbackOnce(connection.fileDescriptor)
            state("silentPcm16Probe=feedback result={$feedback}")
            val transfer = UsbSk02NativePrototype.writeSilentPcm16Once(connection.fileDescriptor)
            state("silentPcm16Probe=transfer result={$transfer}")
        } finally {
            val alt0Restored = alt1Selected && connection.setInterface(audioStreamingAlt0)
            val clockRestoreBytes = originalClockHz?.let {
                setClockFrequency(connection, AUDIO_CONTROL_INTERFACE_ID, 1, it)
            }
            val restoredClockHz = originalClockHz?.let {
                readClockCurrentHz(connection, AUDIO_CONTROL_INTERFACE_ID, 1)
            }
            val streamingReleased = streamingClaimed && connection.releaseInterface(audioStreamingAlt0)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            connection.close()
            state(
                "silentPcm16Probe=complete alt0Restored=$alt0Restored " +
                    "clockRestoreBytes=$clockRestoreBytes restoredClockHz=$restoredClockHz " +
                    "streamingReleased=$streamingReleased controlReleased=$controlReleased",
            )
        }
    }

    private fun readClockCurrentHz(
        connection: UsbDeviceConnection,
        audioControlInterface: Int,
        clockId: Int,
    ): Int? {
        val bytes = ByteArray(4)
        val transferred = connection.controlTransfer(
            USB_CLASS_INTERFACE_IN,
            UAC2_REQUEST_CUR,
            UAC2_SAMPLING_FREQUENCY_CONTROL,
            (clockId shl 8) or audioControlInterface,
            bytes,
            bytes.size,
            CONTROL_TIMEOUT_MS,
        )
        return if (transferred == 4) bytes.u32le(0).toInt() else null
    }

    private fun setClockFrequency(
        connection: UsbDeviceConnection,
        audioControlInterface: Int,
        clockId: Int,
        sampleRateHz: Int,
    ): Int {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRateHz).array()
        return connection.controlTransfer(
            USB_CLASS_INTERFACE_OUT,
            UAC2_REQUEST_CUR,
            UAC2_SAMPLING_FREQUENCY_CONTROL,
            (clockId shl 8) or audioControlInterface,
            bytes,
            bytes.size,
            CONTROL_TIMEOUT_MS,
        )
    }

    private fun beginIsoFeedbackProbe(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("isoFeedbackProbe=unavailable targetFound=${target != null} audioWrittenBytes=0")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt0 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreamingAlt1 = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 1
        }
        if (audioControl == null || audioStreamingAlt0 == null || audioStreamingAlt1 == null) {
            state("isoFeedbackProbe=interfaces_missing audioWrittenBytes=0")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("isoFeedbackProbe=open_failed audioWrittenBytes=0")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        var alt1Selected = false
        try {
            controlClaimed = connection.claimInterface(audioControl, true)
            streamingClaimed = connection.claimInterface(audioStreamingAlt0, true)
            if (!controlClaimed || !streamingClaimed) {
                state(
                    "isoFeedbackProbe=claim_failed controlClaimed=$controlClaimed " +
                        "streamingClaimed=$streamingClaimed audioWrittenBytes=0",
                )
                return
            }
            state(readClockState(connection, AUDIO_CONTROL_INTERFACE_ID, 1))
            alt1Selected = connection.setInterface(audioStreamingAlt1)
            if (!alt1Selected) {
                state("isoFeedbackProbe=set_alt1_failed audioWrittenBytes=0")
                return
            }
            val feedback = UsbSk02NativePrototype.readFeedbackOnce(connection.fileDescriptor)
            state("isoFeedbackProbe=feedback result={$feedback} audioWrittenBytes=0")
        } finally {
            val alt0Restored = alt1Selected && connection.setInterface(audioStreamingAlt0)
            val streamingReleased = streamingClaimed && connection.releaseInterface(audioStreamingAlt0)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            connection.close()
            state(
                "isoFeedbackProbe=complete alt0Restored=$alt0Restored " +
                    "streamingReleased=$streamingReleased controlReleased=$controlReleased " +
                    "audioWrittenBytes=0",
            )
        }
    }

    private fun beginForceClaimProbe(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("forceClaimProbe=unavailable targetFound=${target != null} audioWrittenBytes=0")
            return
        }
        val interfaces = (0 until target.interfaceCount).map(target::getInterface)
        val audioControl = interfaces.firstOrNull {
            it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
        }
        val audioStreaming = interfaces.firstOrNull {
            it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
        }
        if (audioControl == null || audioStreaming == null) {
            state("forceClaimProbe=interfaces_missing audioWrittenBytes=0")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("forceClaimProbe=open_failed audioWrittenBytes=0")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        try {
            val beforeControl = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_CONTROL_INTERFACE_ID,
            )
            val beforeStreaming = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_STREAMING_INTERFACE_ID,
            )
            state("forceClaimProbe=before control={$beforeControl} streaming={$beforeStreaming}")

            controlClaimed = connection.claimInterface(audioControl, true)
            streamingClaimed = connection.claimInterface(audioStreaming, true)
            val duringControl = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_CONTROL_INTERFACE_ID,
            )
            val duringStreaming = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_STREAMING_INTERFACE_ID,
            )
            state(
                "forceClaimProbe=claimed force=true controlClaimed=$controlClaimed " +
                    "streamingClaimed=$streamingClaimed controlDriver={$duringControl} " +
                    "streamingDriver={$duringStreaming} audioWrittenBytes=0",
            )
        } finally {
            val streamingReleased = streamingClaimed && connection.releaseInterface(audioStreaming)
            val controlReleased = controlClaimed && connection.releaseInterface(audioControl)
            val afterControl = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_CONTROL_INTERFACE_ID,
            )
            val afterStreaming = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_STREAMING_INTERFACE_ID,
            )
            state(
                "forceClaimProbe=complete streamingReleased=$streamingReleased " +
                    "controlReleased=$controlReleased controlDriver={$afterControl} " +
                    "streamingDriver={$afterStreaming} audioWrittenBytes=0",
            )
            connection.close()
        }
    }

    private fun beginNativeFdProbe(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null || !manager.hasPermission(target)) {
            state("nativeFdProbe=unavailable targetFound=${target != null} audioWrittenBytes=0")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("nativeFdProbe=open_failed audioWrittenBytes=0")
            return
        }
        try {
            val controlDriver = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_CONTROL_INTERFACE_ID,
            )
            val streamingDriver = UsbSk02NativePrototype.queryInterfaceDriver(
                connection.fileDescriptor,
                AUDIO_STREAMING_INTERFACE_ID,
            )
            state(
                "nativeFdProbe=complete control={$controlDriver} streaming={$streamingDriver} " +
                    "detached=false claimed=false audioWrittenBytes=0",
            )
        } finally {
            connection.close()
        }
    }

    private fun beginReconnect(context: Context) {
        UsbKernelDriverReconnectOwner.submit(context, ::state)
    }

    private fun beginClaimProbe(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val target = manager.deviceList.values.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null) {
            state("claimProbe=target_not_found force=false audioWrittenBytes=0")
            return
        }
        if (!manager.hasPermission(target)) {
            state("claimProbe=permission_missing force=false audioWrittenBytes=0")
            return
        }
        val audioControl = (0 until target.interfaceCount)
            .map(target::getInterface)
            .firstOrNull {
                it.id == AUDIO_CONTROL_INTERFACE_ID && it.alternateSetting == 0
            }
        val audioStreaming = (0 until target.interfaceCount)
            .map(target::getInterface)
            .firstOrNull {
                it.id == AUDIO_STREAMING_INTERFACE_ID && it.alternateSetting == 0
            }
        if (audioControl == null || audioStreaming == null) {
            state("claimProbe=interfaces_missing force=false audioWrittenBytes=0")
            return
        }
        val connection = manager.openDevice(target)
        if (connection == null) {
            state("claimProbe=open_failed force=false audioWrittenBytes=0")
            return
        }
        var controlClaimed = false
        var streamingClaimed = false
        try {
            controlClaimed = connection.claimInterface(audioControl, false)
            streamingClaimed = connection.claimInterface(audioStreaming, false)
            state(
                "claimProbe=attempted force=false controlClaimed=$controlClaimed " +
                    "streamingClaimed=$streamingClaimed audioWrittenBytes=0",
            )
        } finally {
            if (streamingClaimed) connection.releaseInterface(audioStreaming)
            if (controlClaimed) connection.releaseInterface(audioControl)
            connection.close()
            state(
                "claimProbe=complete force=false controlReleased=$controlClaimed " +
                    "streamingReleased=$streamingClaimed audioWrittenBytes=0",
            )
        }
    }

    private fun beginProbe(context: Context, permissionAction: String) {
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = manager.deviceList.values.sortedBy { it.deviceName }
        state("devices=${devices.joinToString(prefix = "[", postfix = "]") { it.identity() }}")
        val target = devices.firstOrNull {
            it.vendorId == TARGET_VENDOR_ID && it.productId == TARGET_PRODUCT_ID
        }
        if (target == null) {
            state("probe=target_not_found claimed=false audioWrittenBytes=0")
            return
        }
        if (manager.hasPermission(target)) {
            state("permission=already_granted")
            probe(context, target)
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, UsbSk02DescriptorPrototypeReceiver::class.java)
                .setAction(permissionAction),
            flags,
        )
        state("permission=requested target=${target.identity()}")
        manager.requestPermission(target, permissionIntent)
    }

    private fun probe(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(UsbManager::class.java)
        val connection = manager.openDevice(device)
        if (connection == null) {
            state("probe=open_failed claimed=false audioWrittenBytes=0")
            return
        }
        try {
            val raw = connection.rawDescriptors ?: ByteArray(0)
            state("target=${device.identity()} interfaces=${device.interfaceCount}")
            state("rawDescriptorBytes=${raw.size} rawDescriptorHex=${raw.toHex()}")
            val parsed = DescriptorProbe.parse(raw)
            parsed.lines.forEach(::state)
            parsed.clockSourceIds.forEach { clockId ->
                state(readClockState(connection, parsed.audioControlInterface, clockId))
            }
            state("claimed=false audioWrittenBytes=0 probe=complete")
        } finally {
            connection.close()
        }
    }

    private fun readClockState(
        connection: UsbDeviceConnection,
        audioControlInterface: Int,
        clockId: Int,
    ): String {
        if (audioControlInterface < 0) return "clock[$clockId]=no_audio_control_interface"
        val index = (clockId shl 8) or audioControlInterface
        val current = ByteArray(4)
        val currentBytes = connection.controlTransfer(
            USB_CLASS_INTERFACE_IN,
            UAC2_REQUEST_CUR,
            UAC2_SAMPLING_FREQUENCY_CONTROL,
            index,
            current,
            current.size,
            CONTROL_TIMEOUT_MS,
        )
        val range = ByteArray(2 + MAX_CLOCK_RANGES * 12)
        val rangeBytes = connection.controlTransfer(
            USB_CLASS_INTERFACE_IN,
            UAC2_REQUEST_RANGE,
            UAC2_SAMPLING_FREQUENCY_CONTROL,
            index,
            range,
            range.size,
            CONTROL_TIMEOUT_MS,
        )
        val currentHz = if (currentBytes == 4) current.u32le(0) else null
        val ranges = if (rangeBytes >= 2) parseClockRanges(range, rangeBytes) else emptyList()
        return "clock[$clockId] currentHz=$currentHz curBytes=$currentBytes " +
            "rangeBytes=$rangeBytes ranges=${ranges.joinToString(prefix = "[", postfix = "]")}"
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun UsbDevice.identity(): String =
        "%04x:%04x name=%s product=%s manufacturer=%s version=%s serial=%s".format(
            vendorId,
            productId,
            deviceName,
            productName,
            manufacturerName,
            version,
            runCatching { serialNumber }.getOrNull(),
        )

    private fun state(message: String) {
        message.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.i(TAG, if (message.length > LOG_CHUNK_SIZE) "chunk=$index $chunk" else chunk)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private fun ByteArray.u32le(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private fun parseClockRanges(bytes: ByteArray, validBytes: Int): List<String> {
        val count = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        return (0 until minOf(count, MAX_CLOCK_RANGES)).mapNotNull { index ->
            val offset = 2 + index * 12
            if (offset + 12 > validBytes) null
            else "${bytes.u32le(offset)}..${bytes.u32le(offset + 4)}/${bytes.u32le(offset + 8)}"
        }
    }

    private companion object {
        const val TAG = "MicaUsbPrototype"
        const val TARGET_VENDOR_ID = 0x262a
        const val TARGET_PRODUCT_ID = 0x0001
        const val AUDIO_CONTROL_INTERFACE_ID = 1
        const val AUDIO_STREAMING_INTERFACE_ID = 2
        const val USB_CLASS_INTERFACE_IN = 0xa1
        const val USB_CLASS_INTERFACE_OUT = 0x21
        const val UAC2_REQUEST_CUR = 0x01
        const val UAC2_REQUEST_RANGE = 0x02
        const val UAC2_SAMPLING_FREQUENCY_CONTROL = 0x0100
        const val CONTROL_TIMEOUT_MS = 1_000
        const val MAX_CLOCK_RANGES = 32
        const val LOG_CHUNK_SIZE = 3_000
        const val PCM16_PROBE_SAMPLE_RATE_HZ = 44_100
        const val BUFFERED_PROBE_DURATION_MS = 3_000
        const val BUFFERED_PROBE_SOURCE_SECONDS = 4
        const val BUFFERED_PROBE_AMPLITUDE = 512.0
        const val MEDIA_FORMAT_PROBE_LIMIT = 64
        const val DEFAULT_DECODE_PROBE_MEDIA_ID = 4_729L
        const val DEFAULT_HIGH_RES_PROBE_MEDIA_ID = 4_688L
        const val HIGH_RES_PROBE_SAMPLE_RATE_HZ = 96_000
        const val HIGH_RES_PROBE_TIMEOUT_NANOS = 5_000_000_000L
        const val FFMPEG24_PROBE_SECONDS = 2
        const val FFMPEG24_USB_PROBE_DURATION_MS = 1_900
        const val MEDIA_DECODE_PROBE_MAX_BYTES = 705_600
        const val DECODED_USB_PROBE_DURATION_MS = 3_000
        const val MEDIA_CODEC_TIMEOUT_US = 10_000L
        const val MEDIA_DECODE_PROBE_TIMEOUT_NANOS = 10_000_000_000L
    }
}

internal object UsbSk02NativePrototype {
    init {
        System.loadLibrary("usb_sk02_prototype")
    }

    external fun queryInterfaceDriver(fd: Int, interfaceNumber: Int): String
    external fun connectKernelDriver(fd: Int, interfaceNumber: Int): Int
    external fun reconnectKernelDrivers(fd: Int): Int
    external fun readFeedbackOnce(fd: Int): String
    external fun writeSilentPcm16Once(fd: Int): String
    external fun runSilentPcm16Queue(fd: Int, durationMs: Int): String
    external fun publishGeneration(generation: Long)
    external fun runSilentPcm16QueueGeneration(fd: Int, durationMs: Int, generation: Long): String
    external fun runPcm16Queue(fd: Int, durationMs: Int, pcm: ByteArray): String
    external fun runPcm24Queue(fd: Int, durationMs: Int, pcm: ByteArray, sampleRateHz: Int): String
    external fun createMedia3Stream(
        fd: Int,
        sampleRateHz: Int,
        bytesPerFrame: Int,
        maxPacketBytes: Int,
        generation: Long,
    ): Long
    external fun writeMedia3Stream(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
    ): Int
    external fun setMedia3StreamPlaying(handle: Long, playing: Boolean)
    external fun getMedia3CompletedFrames(handle: Long): Long
    external fun getMedia3BufferedFrames(handle: Long): Long
    external fun getMedia3UnderrunBytes(handle: Long): Long
    external fun getMedia3ErrorCode(handle: Long): Int
    external fun destroyMedia3Stream(handle: Long)
}

internal object UsbPrototypeGenerationOwner {
    val gate = UsbOutputRuntime.owner
}

private object DescriptorProbe {
    data class Result(
        val lines: List<String>,
        val clockSourceIds: Set<Int>,
        val audioControlInterface: Int,
    )

    fun parse(raw: ByteArray): Result {
        val lines = mutableListOf<String>()
        val clocks = linkedSetOf<Int>()
        var offset = 0
        var interfaceNumber = -1
        var alternateSetting = -1
        var interfaceClass = -1
        var interfaceSubclass = -1
        var audioControlInterface = -1
        while (offset + 2 <= raw.size) {
            val length = raw.u8(offset)
            val type = raw.u8(offset + 1)
            if (length < 2 || offset + length > raw.size) {
                lines += "descriptorError offset=$offset length=$length remaining=${raw.size - offset}"
                break
            }
            when (type) {
                USB_DT_DEVICE -> if (length >= 18) {
                    lines += "device usb=${raw.bcd(offset + 2)} class=${raw.u8(offset + 4)} " +
                        "subclass=${raw.u8(offset + 5)} protocol=${raw.u8(offset + 6)} " +
                        "maxPacket0=${raw.u8(offset + 7)} configurations=${raw.u8(offset + 17)}"
                }
                USB_DT_CONFIG -> if (length >= 9) {
                    lines += "config totalLength=${raw.u16le(offset + 2)} interfaces=${raw.u8(offset + 4)} " +
                        "attributes=0x${raw.u8(offset + 7).toString(16)} maxPowerMa=${raw.u8(offset + 8) * 2}"
                }
                USB_DT_INTERFACE -> if (length >= 9) {
                    interfaceNumber = raw.u8(offset + 2)
                    alternateSetting = raw.u8(offset + 3)
                    interfaceClass = raw.u8(offset + 5)
                    interfaceSubclass = raw.u8(offset + 6)
                    if (interfaceClass == UsbConstants.USB_CLASS_AUDIO && interfaceSubclass == 1) {
                        audioControlInterface = interfaceNumber
                    }
                    lines += "interface id=$interfaceNumber alt=$alternateSetting endpoints=${raw.u8(offset + 4)} " +
                        "class=$interfaceClass subclass=$interfaceSubclass protocol=${raw.u8(offset + 7)}"
                }
                USB_DT_ENDPOINT -> if (length >= 7) {
                    val attributes = raw.u8(offset + 3)
                    lines += "endpoint interface=$interfaceNumber alt=$alternateSetting " +
                        "address=0x${raw.u8(offset + 2).toString(16)} type=${attributes and 0x3} " +
                        "syncType=${(attributes shr 2) and 0x3} usageType=${(attributes shr 4) and 0x3} " +
                        "maxPacket=${raw.u16le(offset + 4)} interval=${raw.u8(offset + 6)}"
                }
                CS_INTERFACE -> if (length >= 3) {
                    val subtype = raw.u8(offset + 2)
                    if (interfaceSubclass == AUDIO_SUBCLASS_CONTROL && subtype == UAC2_CLOCK_SOURCE && length >= 8) {
                        val clockId = raw.u8(offset + 3)
                        clocks += clockId
                        lines += "clockSource id=$clockId attributes=0x${raw.u8(offset + 4).toString(16)} " +
                            "controls=0x${raw.u8(offset + 5).toString(16)}"
                    } else if (
                        interfaceSubclass == AUDIO_SUBCLASS_STREAMING &&
                        subtype == UAC2_AS_GENERAL && length >= 16
                    ) {
                        lines += "asGeneral interface=$interfaceNumber alt=$alternateSetting " +
                            "terminalLink=${raw.u8(offset + 3)} formatType=${raw.u8(offset + 5)} " +
                            "formats=0x${raw.u32le(offset + 6).toString(16)} channels=${raw.u8(offset + 10)} " +
                            "channelConfig=0x${raw.u32le(offset + 11).toString(16)}"
                    } else if (
                        interfaceSubclass == AUDIO_SUBCLASS_STREAMING &&
                        subtype == UAC2_FORMAT_TYPE && length >= 6
                    ) {
                        lines += "formatType interface=$interfaceNumber alt=$alternateSetting " +
                            "type=${raw.u8(offset + 3)} subslotBytes=${raw.u8(offset + 4)} " +
                            "bitResolution=${raw.u8(offset + 5)}"
                    }
                }
                CS_ENDPOINT -> {
                    lines += "classEndpoint interface=$interfaceNumber alt=$alternateSetting " +
                        "hex=${raw.copyOfRange(offset, offset + length).toHex()}"
                }
            }
            offset += length
        }
        return Result(lines, clocks, audioControlInterface)
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
    private fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u32le(offset: Int): Long =
        u16le(offset).toLong() or (u16le(offset + 2).toLong() shl 16)
    private fun ByteArray.bcd(offset: Int): String =
        "%x.%02x".format(u8(offset + 1), u8(offset))
    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private const val CS_INTERFACE = 0x24
    private const val CS_ENDPOINT = 0x25
    private const val USB_DT_DEVICE = 0x01
    private const val USB_DT_CONFIG = 0x02
    private const val USB_DT_INTERFACE = 0x04
    private const val USB_DT_ENDPOINT = 0x05
    private const val AUDIO_SUBCLASS_CONTROL = 1
    private const val AUDIO_SUBCLASS_STREAMING = 2
    private const val UAC2_AS_GENERAL = 1
    private const val UAC2_FORMAT_TYPE = 2
    private const val UAC2_CLOCK_SOURCE = 0x0a
}
