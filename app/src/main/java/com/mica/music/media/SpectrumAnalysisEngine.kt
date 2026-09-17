package com.mica.music.media

import com.mica.music.audio.spectrum.SPECTRUM_BAND_COUNT
import kotlin.math.exp

/** One owner for stream generations, PCM history, clock snapshots and observable publication. */
internal class SpectrumAnalysisEngine(
    private val nowNanos: () -> Long = System::nanoTime,
    private val publish: (List<Float>, Float) -> Unit,
    private val beforePublish: () -> Unit = {},
    private val onHealth: (String) -> Unit = {},
) {
    private val lock = Any()
    private val workerLock = Any()
    private val timeline = SpectrumPcmTimeline()
    private var owner: Any? = null
    private var generation = 0L
    private var visibilityRevision = 0L
    private var enabled = false
    private var visible = false
    private var advancing = false
    private var positionUs: Long? = null
    private var clockNanos = 0L
    private var lastPublishNanos: Long? = null
    private var lastAnalyzedPositionUs: Long? = null
    private var levels = List(SPECTRUM_BAND_COUNT) { 0f }
    private var envelope = 0f
    private var probeStartNanos = nowNanos()
    private var probeFrames = 0
    private var probeMissing = 0
    // Only tick's worker may touch these, including reset/replacement of FFT history.
    private var fftGeneration = -1L
    private var fftRevision = -1L
    private var fft = SpectrumFft()
    private var transientFreshMisses = 0

    fun isEnabled() = synchronized(lock) { enabled }
    fun isAnalysisActive() = synchronized(lock) { enabled && visible }
    fun isCaptureActive() = synchronized(lock) { enabled && (visible || advancing) }
    fun isAdvancing() = synchronized(lock) { advancing }
    fun bufferedSamples() = synchronized(lock) { timeline.size }

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled != value) { enabled = value; resetLocked() }
    }
    fun setAnalysisActive(value: Boolean) = synchronized(lock) {
        if (visible != value) {
            visible = value
            visibilityRevision++
            // Keep PCM and clock across even the shortest background round trip.
            if (!value) publishSilenceLocked()
        }
    }
    fun setPlaybackAdvancing(value: Boolean) = synchronized(lock) {
        if (advancing != value) {
            advancing = value
            visibilityRevision++
            if (!value) publishSilenceLocked()
        }
    }
    fun beginStream(source: Any): Long = synchronized(lock) {
        if (owner !== source) { resetLocked(); owner = source }
        generation
    }
    fun resetStream(source: Any) = synchronized(lock) {
        if (owner === source) resetLocked()
    }
    fun reset() = synchronized(lock) { resetLocked() }
    private fun resetLocked() {
        generation++
        owner = null
        timeline.clear()
        positionUs = null
        publishSilenceLocked()
    }
    private fun publishSilenceLocked() {
        levels = List(SPECTRUM_BAND_COUNT) { 0f }
        envelope = 0f
        lastPublishNanos = null
        lastAnalyzedPositionUs = null
        transientFreshMisses = 0
        probeStartNanos = nowNanos()
        probeFrames = 0
        probeMissing = 0
        publish(levels, envelope)
    }

    fun append(token: Long, samples: FloatArray, sampleRate: Int, startUs: Long) = synchronized(lock) {
        if (token != generation || !enabled || (!visible && !advancing) || sampleRate <= 0) return
        if (timeline.sampleRate != 0 && timeline.sampleRate != sampleRate) visibilityRevision++
        timeline.append(samples, sampleRate, startUs)
    }

    fun updatePosition(token: Long, mediaPositionUs: Long) = synchronized(lock) {
        if (token != generation || mediaPositionUs < 0) return
        if (positionUs != mediaPositionUs) clockNanos = nowNanos()
        positionUs = mediaPositionUs
        timeline.protect(mediaPositionUs)
    }

    private data class Work(
        val generation: Long,
        val revision: Long,
        val rate: Int,
        val positionUs: Long?,
        val clockFresh: Boolean,
        val samples: FloatArray?,
    )

    fun tick(): Unit = synchronized(workerLock) {
        val now = nowNanos()
        val work = synchronized(lock) {
            if (!enabled || !visible || !advancing) return
            val position = positionUs
            val clockFresh = position != null && now - clockNanos <= 250_000_000L
            if (clockFresh && position == lastAnalyzedPositionUs) return
            Work(
                generation,
                visibilityRevision,
                timeline.sampleRate,
                position,
                clockFresh,
                if (clockFresh) timeline.windowAt(checkNotNull(position)) else null,
            )
        }
        if (fftGeneration != work.generation || fftRevision != work.revision) {
            fft = SpectrumFft()
            fftGeneration = work.generation
            fftRevision = work.revision
        }
        val result = work.samples?.let { fft.analyze(it, work.rate) }
        beforePublish()
        val health = synchronized(lock) {
            // FFT and the test barrier are non-cancellable. Revalidate under the publication lock.
            if (generation != work.generation || visibilityRevision != work.revision || !enabled || !visible || !advancing) return
            if (result != null) {
                levels = result.first
                envelope = result.second
                lastAnalyzedPositionUs = work.positionUs
                transientFreshMisses = 0
            } else {
                val transientMiss = work.clockFresh && lastAnalyzedPositionUs != null && transientFreshMisses < FreshMissHoldTicks
                if (transientMiss) {
                    transientFreshMisses++
                } else {
                    transientFreshMisses = 0
                    val seconds = ((now - (lastPublishNanos ?: now)).coerceAtLeast(0L) / 1e9)
                    val decay = exp(-seconds / 0.12).toFloat()
                    levels = levels.map { (it * decay).let { v -> if (v < 0.001f) 0f else v } }
                    envelope = (envelope * decay).let { if (it < 0.001f) 0f else it }
                    // A sustained gap must not make recovery inherit stale attack/release history.
                    fftGeneration = -1L
                }
            }
            lastPublishNanos = now
            publish(levels, envelope)
            probeFrames++
            if (result == null) probeMissing++
            if (now - probeStartNanos >= 1_000_000_000L) {
                val seconds = (now - probeStartNanos) / 1e9
                val message = "analysis fps=${(probeFrames - probeMissing) / seconds} tickFps=${probeFrames / seconds} missingTicks=$probeMissing " +
                    "retainedSamples=${timeline.size} sr=${timeline.sampleRate} " +
                    "positionUs=$positionUs clockAgeMs=${(now - clockNanos) / 1_000_000} generation=$generation"
                probeStartNanos = now
                probeFrames = 0
                probeMissing = 0
                message
            } else null
        }
        // Diagnostic IO is not on the PCM/publication lock.
        health?.let(onHealth)
        Unit
    }

    private companion object {
        const val FreshMissHoldTicks = 2
    }
}
