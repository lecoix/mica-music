package com.mica.music.media.usbprototype

import com.mica.music.media.dsd.DirectDsdMonotonicClock

internal data class DirectDsdWriteTimingSnapshot(
    val windowNs: Long,
    val writeCanonicalCalls: Long,
    val sourceBytes: Long,
    val carrierBytesEmitted: Long,
    val ownershipGuardTotalNs: Long,
    val ownershipGuardMaxNs: Long,
    val feederTotalNs: Long,
    val feederMaxNs: Long,
    val postFeedTotalNs: Long,
    val postFeedMaxNs: Long,
    val sinkTotalNs: Long,
    val sinkMaxNs: Long,
    val sinkCalls: Long,
    val sinkOfferedBytes: Long,
    val sinkAcceptedBytes: Long,
    val sinkPartialAccepts: Long,
    val sinkZeroAccepts: Long,
    val directBufferTotalNs: Long,
    val directBufferMaxNs: Long,
    val nativeWriteTotalNs: Long,
    val nativeWriteMaxNs: Long,
    val nativeWriteCalls: Long,
    val bufferedFramesJniTotalNs: Long,
    val bufferedFramesJniMaxNs: Long,
    val streamWriteJniTotalNs: Long,
    val streamWriteJniMaxNs: Long,
)

/** Behavior-neutral aggregate timing/count recorder for the Direct DSD synchronous write path. */
internal class DirectDsdWriteTimingRecorder(
    private val clock: DirectDsdMonotonicClock,
) {
    private var windowStartNs = clock.nanoTime()
    private var writeCanonicalCalls = 0L
    private var sourceBytes = 0L
    private var carrierBytesEmitted = 0L
    private var ownershipGuardTotalNs = 0L
    private var ownershipGuardMaxNs = 0L
    private var feederTotalNs = 0L
    private var feederMaxNs = 0L
    private var postFeedTotalNs = 0L
    private var postFeedMaxNs = 0L
    private var sinkTotalNs = 0L
    private var sinkMaxNs = 0L
    private var sinkCalls = 0L
    private var sinkOfferedBytes = 0L
    private var sinkAcceptedBytes = 0L
    private var sinkPartialAccepts = 0L
    private var sinkZeroAccepts = 0L
    private var directBufferTotalNs = 0L
    private var directBufferMaxNs = 0L
    private var nativeWriteTotalNs = 0L
    private var nativeWriteMaxNs = 0L
    private var nativeWriteCalls = 0L
    private var bufferedFramesJniTotalNs = 0L
    private var bufferedFramesJniMaxNs = 0L
    private var streamWriteJniTotalNs = 0L
    private var streamWriteJniMaxNs = 0L

    fun nowNs(): Long = clock.nanoTime()

    @Synchronized
    fun recordOwnershipGuardElapsed(elapsedNs: Long) {
        val elapsed = elapsedNs.coerceAtLeast(0L)
        ownershipGuardTotalNs += elapsed
        ownershipGuardMaxNs = maxOf(ownershipGuardMaxNs, elapsed)
    }

    @Synchronized
    fun recordWriteCanonical(byteCount: Int) {
        writeCanonicalCalls++
        sourceBytes += byteCount
    }

    @Synchronized
    fun recordCarrierBytesEmitted(byteCount: Int) {
        carrierBytesEmitted += byteCount
    }

    fun <T> measureOwnershipGuard(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            ownershipGuardTotalNs += elapsed
            ownershipGuardMaxNs = maxOf(ownershipGuardMaxNs, elapsed)
        }
    }

    fun <T> measureFeeder(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            feederTotalNs += elapsed
            feederMaxNs = maxOf(feederMaxNs, elapsed)
        }
    }

    fun <T> measurePostFeed(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            postFeedTotalNs += elapsed
            postFeedMaxNs = maxOf(postFeedMaxNs, elapsed)
        }
    }

    fun <T> measureSink(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            sinkTotalNs += elapsed
            sinkMaxNs = maxOf(sinkMaxNs, elapsed)
        }
    }

    fun <T> measureDirectBuffer(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            directBufferTotalNs += elapsed
            directBufferMaxNs = maxOf(directBufferMaxNs, elapsed)
        }
    }

    fun <T> measureNativeWrite(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            nativeWriteTotalNs += elapsed
            nativeWriteMaxNs = maxOf(nativeWriteMaxNs, elapsed)
            nativeWriteCalls++
        }
    }

    fun <T> measureBufferedFramesJni(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            bufferedFramesJniTotalNs += elapsed
            bufferedFramesJniMaxNs = maxOf(bufferedFramesJniMaxNs, elapsed)
        }
    }

    fun <T> measureStreamWriteJni(block: () -> T): T = measure(block) { elapsed ->
        synchronized(this) {
            streamWriteJniTotalNs += elapsed
            streamWriteJniMaxNs = maxOf(streamWriteJniMaxNs, elapsed)
        }
    }

    @Synchronized
    fun recordSinkResult(offeredBytes: Int, acceptedBytes: Int) {
        sinkCalls++
        sinkOfferedBytes += offeredBytes
        sinkAcceptedBytes += acceptedBytes
        if (acceptedBytes == 0) sinkZeroAccepts++
        else if (acceptedBytes < offeredBytes) sinkPartialAccepts++
    }

    @Synchronized
    fun snapshotAndReset(): DirectDsdWriteTimingSnapshot {
        val now = clock.nanoTime()
        return DirectDsdWriteTimingSnapshot(
            windowNs = (now - windowStartNs).coerceAtLeast(0L),
            writeCanonicalCalls = writeCanonicalCalls,
            sourceBytes = sourceBytes,
            carrierBytesEmitted = carrierBytesEmitted,
            ownershipGuardTotalNs = ownershipGuardTotalNs,
            ownershipGuardMaxNs = ownershipGuardMaxNs,
            feederTotalNs = feederTotalNs,
            feederMaxNs = feederMaxNs,
            postFeedTotalNs = postFeedTotalNs,
            postFeedMaxNs = postFeedMaxNs,
            sinkTotalNs = sinkTotalNs,
            sinkMaxNs = sinkMaxNs,
            sinkCalls = sinkCalls,
            sinkOfferedBytes = sinkOfferedBytes,
            sinkAcceptedBytes = sinkAcceptedBytes,
            sinkPartialAccepts = sinkPartialAccepts,
            sinkZeroAccepts = sinkZeroAccepts,
            directBufferTotalNs = directBufferTotalNs,
            directBufferMaxNs = directBufferMaxNs,
            nativeWriteTotalNs = nativeWriteTotalNs,
            nativeWriteMaxNs = nativeWriteMaxNs,
            nativeWriteCalls = nativeWriteCalls,
            bufferedFramesJniTotalNs = bufferedFramesJniTotalNs,
            bufferedFramesJniMaxNs = bufferedFramesJniMaxNs,
            streamWriteJniTotalNs = streamWriteJniTotalNs,
            streamWriteJniMaxNs = streamWriteJniMaxNs,
        ).also {
            windowStartNs = now
            writeCanonicalCalls = 0L
            sourceBytes = 0L
            carrierBytesEmitted = 0L
            ownershipGuardTotalNs = 0L
            ownershipGuardMaxNs = 0L
            feederTotalNs = 0L
            feederMaxNs = 0L
            postFeedTotalNs = 0L
            postFeedMaxNs = 0L
            sinkTotalNs = 0L
            sinkMaxNs = 0L
            sinkCalls = 0L
            sinkOfferedBytes = 0L
            sinkAcceptedBytes = 0L
            sinkPartialAccepts = 0L
            sinkZeroAccepts = 0L
            directBufferTotalNs = 0L
            directBufferMaxNs = 0L
            nativeWriteTotalNs = 0L
            nativeWriteMaxNs = 0L
            nativeWriteCalls = 0L
            bufferedFramesJniTotalNs = 0L
            bufferedFramesJniMaxNs = 0L
            streamWriteJniTotalNs = 0L
            streamWriteJniMaxNs = 0L
        }
    }

    private inline fun <T> measure(block: () -> T, record: (Long) -> Unit): T {
        val start = clock.nanoTime()
        return try {
            block()
        } finally {
            record((clock.nanoTime() - start).coerceAtLeast(0L))
        }
    }
}
