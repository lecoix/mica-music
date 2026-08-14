package com.mica.music.media.usbprototype

import com.mica.music.media.usb.ExactCarrierFrameSink
import java.nio.ByteBuffer

internal object UsbDoPIdleProbePolicy {
    const val DSD64_BIT_RATE_HZ = 2_822_400L
    const val CARRIER_RATE_HZ = 176_400
    const val CHANNEL_COUNT = 2
    const val ACTIVE_DURATION_MS = 4_000L
    const val SAMPLE_INTERVAL_MS = 500L
    const val FEED_LOOP_SLEEP_MS = 4L

    fun refillTargetFrames(bufferCapacityFrames: Long, requiredPrefillFrames: Long): Long {
        require(bufferCapacityFrames > 0)
        require(requiredPrefillFrames > 0)
        return minOf(
            bufferCapacityFrames,
            maxOf(requiredPrefillFrames * 2L, CARRIER_RATE_HZ.toLong()),
        )
    }
}

internal fun interface UsbExactCarrierNativeWrite {
    fun write(buffer: ByteBuffer, length: Int): Int
}

internal class UsbDoPIdleNativeSink(
    override val bytesPerRuntimeFrame: Int,
    private val timing: DirectDsdWriteTimingRecorder? = null,
    private val nativeWrite: UsbExactCarrierNativeWrite,
) : ExactCarrierFrameSink {
    override fun writeCarrierFrames(source: ByteArray, offset: Int, byteCount: Int): Int {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= source.size)
        require(byteCount % bytesPerRuntimeFrame == 0)
        if (byteCount == 0) return 0
        val operation = {
            val direct = timing?.measureDirectBuffer {
                ByteBuffer.allocateDirect(byteCount).also { buffer ->
                    buffer.put(source, offset, byteCount)
                    buffer.flip()
                }
            } ?: ByteBuffer.allocateDirect(byteCount).also { buffer ->
                buffer.put(source, offset, byteCount)
                buffer.flip()
            }
            val accepted = timing?.measureNativeWrite { nativeWrite.write(direct, byteCount) }
                ?: nativeWrite.write(direct, byteCount)
            timing?.recordSinkResult(byteCount, accepted)
            accepted
        }
        return timing?.measureSink(operation) ?: operation()
    }
}
