package com.mica.music.media

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** Observes the renderer's clock reads on its own thread; never polls an AudioSink from FFT/UI. */
@UnstableApi
internal class SpectrumClockAudioSink(sink: AudioSink, private val session: SpectrumSinkSession) : ForwardingAudioSink(sink) {
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        session.enterBuffer(presentationTimeUs, buffer.hasRemaining())
        return try { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
        finally { session.leaveBuffer() }
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also(session::position)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        session.configured()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
    override fun flush() { session.reset(); super.flush() }
    override fun reset() { session.reset(); super.reset() }
    override fun release() { session.reset(); super.release() }
    override fun handleDiscontinuity() { session.reset(); super.handleDiscontinuity() }
}
