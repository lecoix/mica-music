package com.mica.music.media

import android.media.AudioFormat
import com.mica.music.audio.spectrum.SpectrumUiProjection
import com.mica.music.diagnostics.AudioPipelineDebugDiagnostics
import com.mica.music.util.DiagnosticLog
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process facade. Stream time/PCM/publication ownership lives in [SpectrumAnalysisEngine]. */
object MicaSpectrumAnalyzer {
    internal val engine = SpectrumAnalysisEngine(publish = { levels, envelope ->
        SpectrumUiProjection.publishEnvelope(envelope)
        SpectrumUiProjection.publishLevels(levels)
    }, onHealth = { message ->
        if (AudioPipelineDebugDiagnostics.formatTraceEnabled) DiagnosticLog.event("SpectrumProbe", message)
    })
    private val directSource = Any()
    private var directFrames = 0L
    private val analysisOwnerLock = Any()
    private val analysisOwners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val legacyAnalysisOwner = Any()
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    init {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mica-spectrum-analyze").apply { isDaemon = true }
        }.scheduleAtFixedRate({
            try { engine.tick() } catch (failure: Exception) {
                // A malformed frame must not permanently cancel the scheduled task.
                engine.reset()
                DiagnosticLog.event("Spectrum", "analysis-failed", failure)
            }
        }, 0L, 1_000_000_000L / 60, TimeUnit.NANOSECONDS)
    }

    fun isEnabledForProcessing() = engine.isEnabled()
    fun isAnalysisActive() = engine.isAnalysisActive()
    internal fun isCaptureActive() = engine.isCaptureActive()
    internal fun isPlaybackAdvancing() = engine.isAdvancing()
    internal fun queuedPcmSampleCount() = engine.bufferedSamples()
    internal fun maxQueuedPcmSampleCount(sampleRateHz: Int) = sampleRateHz * 2
    internal fun analyzeTickForTest() = engine.tick()

    fun setEnabled(value: Boolean, notifyPipeline: Boolean = true) {
        if (engine.isEnabled() == value) return
        engine.setEnabled(value)
        if (notifyPipeline) onEnabledChanged?.invoke(value)
    }
    fun setAnalysisActive(value: Boolean) = setAnalysisActive(legacyAnalysisOwner, value)

    @androidx.annotation.VisibleForTesting
    internal fun resetAnalysisOwnersForTest() {
        synchronized(analysisOwnerLock) {
            analysisOwners.clear()
            engine.setAnalysisActive(false)
        }
    }

    fun setAnalysisActive(owner: Any, value: Boolean) {
        val state = synchronized(analysisOwnerLock) {
            if (value) analysisOwners.add(owner) else analysisOwners.remove(owner)
            val active = analysisOwners.isNotEmpty()
            engine.setAnalysisActive(active)
            active to analysisOwners.size
        }
        DiagnosticLog.event(
            "Spectrum",
            "analysis-active=${state.first} requested=$value owners=${state.second} enabled=${engine.isEnabled()}",
        )
    }
    fun setPlaybackAdvancing(value: Boolean) {
        engine.setPlaybackAdvancing(value)
        DiagnosticLog.event("Spectrum", "playback-advancing=$value")
    }
    fun resetBufferedPcm(reason: String) {
        engine.reset()
        directFrames = 0
        DiagnosticLog.event("Spectrum", "buffer-reset reason=$reason")
    }

    // Kept for standalone processor/tap clients; production passes a sink-owned token and time.
    fun processPcmBuffer(buffer: ByteArray, offset: Int, length: Int, encoding: Int,
        sampleRateHz: Int, channelCount: Int) {
        if (!isCaptureActive() || sampleRateHz <= 0) return
        val token = engine.beginStream(directSource)
        val positionUs = directFrames * 1_000_000L / sampleRateHz
        val mono = decodeMono(buffer, offset, length, encoding, channelCount)
        directFrames += mono.size
        engine.append(token, mono, sampleRateHz, positionUs)
    }

    internal fun decodeMono(buffer: ByteArray, offset: Int, length: Int, encoding: Int,
        channelCount: Int, maxFrames: Int = Int.MAX_VALUE): FloatArray {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return FloatArray(0)
        }
        if (channelCount <= 0 || offset < 0 || length <= 0 || offset > buffer.size - length) return FloatArray(0)
        val frameBytes = bytesPerSample * channelCount
        val frameCount = length / frameBytes
        val skipped = (frameCount - maxFrames).coerceAtLeast(0)
        return FloatArray(frameCount - skipped) { frame ->
            var sum = 0f
            repeat(channelCount) { ch ->
                val sample = readSample(buffer, offset + (frame + skipped) * frameBytes + ch * bytesPerSample, encoding)
                if (sample.isFinite()) sum += sample
            }
            sum / channelCount
        }
    }
    private fun readSample(buffer: ByteArray, pos: Int, encoding: Int): Float {
        if (pos !in buffer.indices) return 0f
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                ((buffer[pos].toInt() and 0xff) - 128) / 128f
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                if (pos + 2 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        ((buffer[pos + 1].toInt() and 0xff) shl 8) or
                        (buffer[pos + 2].toInt() shl 16)
                    raw / 8_388_608f
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                if (pos + 3 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        ((buffer[pos + 1].toInt() and 0xff) shl 8) or
                        ((buffer[pos + 2].toInt() and 0xff) shl 16) or
                        (buffer[pos + 3].toInt() shl 24)
                    raw / 2_147_483_648f
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                if (pos + 3 >= buffer.size) 0f else {
                    val bits = (buffer[pos].toInt() and 0xff) or
                        ((buffer[pos + 1].toInt() and 0xff) shl 8) or
                        ((buffer[pos + 2].toInt() and 0xff) shl 16) or
                        (buffer[pos + 3].toInt() shl 24)
                    // Media3 float PCM is native (little-endian) IEEE-754 already in [-1, 1].
                    Float.fromBits(bits).coerceIn(-1f, 1f)
                }
            }
            else -> {
                if (pos + 1 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        (buffer[pos + 1].toInt() shl 8)
                    raw / 32768f
                }
            }
        }
    }

}
