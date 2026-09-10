package com.mica.music.media

/** Playback-thread confined timestamp adapter shared by exactly one sink and its PCM tap. */
internal class SpectrumSinkSession(
    private val analyzer: SpectrumAnalysisEngine = MicaSpectrumAnalyzer.engine,
    private val processorDriven: Boolean = false,
) {
    private var token: Long? = null
    private var inputTimeUs: Long? = null
    private var baseUs: Long? = null
    private var frames = 0L
    private var rate = 0
    private var newFormatPending = false

    fun enterBuffer(timeUs: Long, hasRemaining: Boolean) {
        inputTimeUs = timeUs
        if (hasRemaining) token = analyzer.beginStream(this)
    }
    fun leaveBuffer() { inputTimeUs = null }
    fun configured() {
        if (processorDriven) newFormatPending = true else reset()
    }
    fun processorFlushed() {
        if (newFormatPending) {
            analyzer.resetStream(this)
            token = if (inputTimeUs != null) analyzer.beginStream(this) else null
            newFormatPending = false
        }
        baseUs = null
        frames = 0
        rate = 0
    }
    fun reset() {
        analyzer.resetStream(this)
        token = null
        inputTimeUs = null
        newFormatPending = false
        processorFlushed()
    }
    fun position(positionUs: Long) { token?.let { analyzer.updatePosition(it, positionUs) } }

    fun capture(bytes: ByteArray, offset: Int, length: Int, encoding: Int,
        sampleRate: Int, channels: Int, timestampEachBuffer: Boolean = false) {
        val capturedToken = token ?: return
        val input = inputTimeUs ?: baseUs ?: return
        if (sampleRate <= 0) return
        if (baseUs == null || rate != sampleRate || timestampEachBuffer) {
            baseUs = input
            frames = 0
            rate = sampleRate
        }
        val frameBytes = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> channels
            android.media.AudioFormat.ENCODING_PCM_16BIT -> channels * 2
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> channels * 3
            android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> channels * 4
            else -> return
        }
        if (frameBytes <= 0) return
        val timeUs = checkNotNull(baseUs) + frames * 1_000_000L / sampleRate
        frames += length / frameBytes
        if (!analyzer.isCaptureActive()) return
        val mono = MicaSpectrumAnalyzer.decodeMono(bytes, offset, length, encoding, channels,
            maxFrames = (sampleRate.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        // Conversion can race lifecycle invalidation; append validates the captured token.
        val skippedFrames = length / frameBytes - mono.size
        analyzer.append(capturedToken, mono, sampleRate, timeUs + skippedFrames * 1_000_000L / sampleRate)
    }
}
