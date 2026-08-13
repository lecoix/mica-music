package com.mica.music.media.usbprototype

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

internal data class UsbDirectDsdBufferPolicy(
    val highWatermarkFrames: Long,
    val lowWatermarkFrames: Long,
    val capacityFrames: Long,
) {
    init {
        require(lowWatermarkFrames > 0)
        require(lowWatermarkFrames <= highWatermarkFrames)
        require(highWatermarkFrames < capacityFrames)
    }

    fun allowedSinkBytes(
        bufferedFrames: Long,
        requestedBytes: Int,
        bytesPerRuntimeFrame: Int,
    ): Int {
        require(bufferedFrames >= 0)
        require(requestedBytes >= 0)
        require(bytesPerRuntimeFrame > 0)
        require(requestedBytes % bytesPerRuntimeFrame == 0)
        if (requestedBytes == 0 || bufferedFrames >= highWatermarkFrames) return 0
        val headroomFrames = highWatermarkFrames - bufferedFrames
        val requestedFrames = requestedBytes / bytesPerRuntimeFrame
        val acceptedFrames = min(headroomFrames, requestedFrames.toLong()).toInt()
        return acceptedFrames * bytesPerRuntimeFrame
    }

    fun shouldBeginRefill(bufferedFrames: Long): Boolean = bufferedFrames < lowWatermarkFrames

    fun refillRequestFrames(bufferedFrames: Long, maxRequestFrames: Int): Int {
        require(bufferedFrames >= 0)
        require(maxRequestFrames > 0)
        if (bufferedFrames >= highWatermarkFrames) return 0
        return min(highWatermarkFrames - bufferedFrames, maxRequestFrames.toLong()).toInt()
    }

    companion object {
        private const val HIGH_WATERMARK_MS = 250L
        private const val HYSTERESIS_MS = 50L

        fun create(
            carrierRateHz: Long,
            requiredPrefillFrames: Long,
            capacityFrames: Long,
        ): UsbDirectDsdBufferPolicy {
            require(carrierRateHz > 0)
            require(requiredPrefillFrames > 0)
            require(capacityFrames > requiredPrefillFrames) {
                "Direct DSD QA watermark needs capacity above Native startup minimum"
            }
            val targetHigh = Math.multiplyExact(carrierRateHz, HIGH_WATERMARK_MS) / 1_000L
            val hysteresis = Math.multiplyExact(carrierRateHz, HYSTERESIS_MS) / 1_000L
            val high = max(requiredPrefillFrames, targetHigh).coerceAtMost(capacityFrames - 1L)
            check(high >= requiredPrefillFrames && high < capacityFrames)
            val low = max(requiredPrefillFrames, high - hysteresis)
            return UsbDirectDsdBufferPolicy(high, low, capacityFrames)
        }
    }
}

internal enum class UsbDirectDsdWriterPhase {
    CONTENT,
    GAP,
    FAILED,
    CLOSED,
}

internal data class UsbDirectDsdPauseLivenessSnapshot(
    val phase: UsbDirectDsdWriterPhase,
    val workerAlive: Boolean,
    val workerFailure: Throwable?,
)

/**
 * Serializes access to the non-thread-safe P5 session/feeder pair.
 * CONTENT is called by the Media3 playback thread; GAP is called by exactly one pause worker.
 */
internal class UsbDirectDsdPauseLivenessController(
    private val workerName: String = "MicaDirectDsdGap",
    private val joinTimeoutMs: Long = 2_000L,
) {
    private val writerLock = ReentrantLock()
    private val lifecycleLock = Any()

    @Volatile
    private var phase = UsbDirectDsdWriterPhase.CONTENT

    @Volatile
    private var stopRequested = false

    @Volatile
    private var workerFailure: Throwable? = null

    @Volatile
    private var worker: Thread? = null

    fun <T> withContentWriter(block: () -> T): T = writerLock.withLock {
        check(phase == UsbDirectDsdWriterPhase.CONTENT) { "Direct DSD CONTENT writer while phase=$phase" }
        workerFailure?.let { throw IllegalStateException("Direct DSD GAP worker failed", it) }
        block()
    }

    fun startGap(workerStep: () -> Long) {
        val thread = writerLock.withLock {
            synchronized(lifecycleLock) {
                check(phase == UsbDirectDsdWriterPhase.CONTENT) { "Direct DSD GAP start while phase=$phase" }
                check(worker == null) { "Direct DSD GAP worker already exists" }
                workerFailure?.let { throw IllegalStateException("Direct DSD GAP worker failed", it) }
                stopRequested = false
                phase = UsbDirectDsdWriterPhase.GAP
                Thread({ runGapLoop(workerStep) }, workerName).also {
                    it.isDaemon = true
                    worker = it
                }
            }
        }
        thread.start()
    }

    fun stopGapAndJoin() {
        val thread = synchronized(lifecycleLock) {
            when (phase) {
                UsbDirectDsdWriterPhase.CONTENT -> return
                UsbDirectDsdWriterPhase.CLOSED -> error("Direct DSD GAP stop after close")
                UsbDirectDsdWriterPhase.GAP,
                UsbDirectDsdWriterPhase.FAILED,
                -> {
                    stopRequested = true
                    worker
                }
            }
        }
        thread?.interrupt()
        thread?.join(joinTimeoutMs)
        check(thread?.isAlive != true) { "Direct DSD GAP worker did not join" }
        synchronized(lifecycleLock) {
            worker = null
            workerFailure?.let {
                phase = UsbDirectDsdWriterPhase.FAILED
                throw IllegalStateException("Direct DSD GAP worker failed", it)
            }
            check(phase != UsbDirectDsdWriterPhase.CLOSED)
            phase = UsbDirectDsdWriterPhase.CONTENT
            stopRequested = false
        }
    }

    /** Normal renderer teardown: join first, then prevent either writer from re-entering. */
    fun closeAndJoin() {
        synchronized(lifecycleLock) {
            if (phase == UsbDirectDsdWriterPhase.CLOSED) return
        }
        val failure = runCatching { stopGapAndJoin() }.exceptionOrNull()
        synchronized(lifecycleLock) {
            stopRequested = true
            phase = UsbDirectDsdWriterPhase.CLOSED
            worker = null
        }
        if (failure != null) throw failure
    }

    /** Owner-driven release may already hold the P2 transport lock, so it must never join here. */
    fun markReleasedWithoutJoin() {
        synchronized(lifecycleLock) {
            stopRequested = true
            phase = UsbDirectDsdWriterPhase.CLOSED
            worker?.interrupt()
        }
    }

    fun snapshot(): UsbDirectDsdPauseLivenessSnapshot = UsbDirectDsdPauseLivenessSnapshot(
        phase = phase,
        workerAlive = worker?.isAlive == true,
        workerFailure = workerFailure,
    )

    private fun runGapLoop(workerStep: () -> Long) {
        try {
            while (!stopRequested && phase == UsbDirectDsdWriterPhase.GAP) {
                val sleepMs = writerLock.withLock {
                    if (stopRequested || phase != UsbDirectDsdWriterPhase.GAP) return
                    workerStep().coerceAtLeast(0L)
                }
                if (sleepMs > 0L) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        // Stop/join uses interrupt only to wake bounded sleeps.
                    }
                }
            }
        } catch (error: Throwable) {
            workerFailure = error
            stopRequested = true
            phase = UsbDirectDsdWriterPhase.FAILED
        }
    }
}
