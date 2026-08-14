package com.mica.music.media.dsd

fun interface DirectDsdMonotonicClock {
    fun nanoTime(): Long
}

internal object DirectDsdSystemMonotonicClock : DirectDsdMonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

internal enum class DirectDsdDrainTermination {
    PENDING_TAIL,
    NOTHING_READ,
    TERMINAL,
    ERROR,
    COUNT_BUDGET,
}

internal data class DirectDsdTimingSnapshot(
    val wallNs: Long,
    val callbacks: Long,
    val interArrivalMinNs: Long,
    val interArrivalMaxNs: Long,
    val sourceReads: Long,
    val packets: Long,
    val budgetExhausted: Long,
    val pendingTailYields: Long,
    val nothingReadYields: Long,
    val terminalStops: Long,
    val errors: Long,
    val drainBusyTotalNs: Long,
    val drainBusyMaxNs: Long,
    val readSourceTotalNs: Long,
    val readSourceMaxNs: Long,
    val packetStageTotalNs: Long,
    val packetStageMaxNs: Long,
    val pumpTotalNs: Long,
    val pumpMaxNs: Long,
)

internal class DirectDsdRenderTimingAccumulator(
    private val clock: DirectDsdMonotonicClock,
) {
    private var windowStartNs = Long.MIN_VALUE
    private var previousCallbackStartNs = Long.MIN_VALUE
    private var callbacks = 0L
    private var interArrivalMinNs = Long.MAX_VALUE
    private var interArrivalMaxNs = 0L
    private var sourceReads = 0L
    private var packets = 0L
    private var budgetExhausted = 0L
    private var pendingTailYields = 0L
    private var nothingReadYields = 0L
    private var terminalStops = 0L
    private var errors = 0L
    private var drainBusyTotalNs = 0L
    private var drainBusyMaxNs = 0L
    private var readSourceTotalNs = 0L
    private var readSourceMaxNs = 0L
    private var packetStageTotalNs = 0L
    private var packetStageMaxNs = 0L
    private var pumpTotalNs = 0L
    private var pumpMaxNs = 0L

    fun onCallbackStart(): Long {
        val now = clock.nanoTime()
        if (windowStartNs == Long.MIN_VALUE) windowStartNs = now
        if (previousCallbackStartNs != Long.MIN_VALUE) {
            val interval = (now - previousCallbackStartNs).coerceAtLeast(0L)
            interArrivalMinNs = minOf(interArrivalMinNs, interval)
            interArrivalMaxNs = maxOf(interArrivalMaxNs, interval)
        }
        previousCallbackStartNs = now
        callbacks++
        return now
    }

    fun onDrainComplete(startNs: Long, result: DirectDsdDrainResult) {
        val busy = (clock.nanoTime() - startNs).coerceAtLeast(0L)
        drainBusyTotalNs += busy
        drainBusyMaxNs = maxOf(drainBusyMaxNs, busy)
        sourceReads += result.sourceReadCount
        packets += result.packetReadCount
        if (result.budgetExhausted) {
            budgetExhausted++
            onTermination(DirectDsdDrainTermination.COUNT_BUDGET)
        }
    }

    fun measureReadSource(block: () -> Int): Int = measure(
        add = { elapsed ->
            readSourceTotalNs += elapsed
            readSourceMaxNs = maxOf(readSourceMaxNs, elapsed)
        },
        block = block,
    )

    fun <T> measurePacketStage(block: () -> T): T = measure(
        add = { elapsed ->
            packetStageTotalNs += elapsed
            packetStageMaxNs = maxOf(packetStageMaxNs, elapsed)
        },
        block = block,
    )

    fun <T> measurePump(block: () -> T): T = measure(
        add = { elapsed ->
            pumpTotalNs += elapsed
            pumpMaxNs = maxOf(pumpMaxNs, elapsed)
        },
        block = block,
    )

    fun onTermination(reason: DirectDsdDrainTermination) {
        when (reason) {
            DirectDsdDrainTermination.PENDING_TAIL -> pendingTailYields++
            DirectDsdDrainTermination.NOTHING_READ -> nothingReadYields++
            DirectDsdDrainTermination.TERMINAL -> terminalStops++
            DirectDsdDrainTermination.ERROR -> errors++
            DirectDsdDrainTermination.COUNT_BUDGET -> Unit
        }
    }

    fun snapshotAndReset(): DirectDsdTimingSnapshot {
        val now = clock.nanoTime()
        val snapshot = DirectDsdTimingSnapshot(
            wallNs = if (windowStartNs == Long.MIN_VALUE) 0L else (now - windowStartNs).coerceAtLeast(0L),
            callbacks = callbacks,
            interArrivalMinNs = if (interArrivalMinNs == Long.MAX_VALUE) 0L else interArrivalMinNs,
            interArrivalMaxNs = interArrivalMaxNs,
            sourceReads = sourceReads,
            packets = packets,
            budgetExhausted = budgetExhausted,
            pendingTailYields = pendingTailYields,
            nothingReadYields = nothingReadYields,
            terminalStops = terminalStops,
            errors = errors,
            drainBusyTotalNs = drainBusyTotalNs,
            drainBusyMaxNs = drainBusyMaxNs,
            readSourceTotalNs = readSourceTotalNs,
            readSourceMaxNs = readSourceMaxNs,
            packetStageTotalNs = packetStageTotalNs,
            packetStageMaxNs = packetStageMaxNs,
            pumpTotalNs = pumpTotalNs,
            pumpMaxNs = pumpMaxNs,
        )
        reset(now)
        return snapshot
    }

    private fun reset(now: Long) {
        windowStartNs = now
        callbacks = 0L
        interArrivalMinNs = Long.MAX_VALUE
        interArrivalMaxNs = 0L
        sourceReads = 0L
        packets = 0L
        budgetExhausted = 0L
        pendingTailYields = 0L
        nothingReadYields = 0L
        terminalStops = 0L
        errors = 0L
        drainBusyTotalNs = 0L
        drainBusyMaxNs = 0L
        readSourceTotalNs = 0L
        readSourceMaxNs = 0L
        packetStageTotalNs = 0L
        packetStageMaxNs = 0L
        pumpTotalNs = 0L
        pumpMaxNs = 0L
    }

    private inline fun <T> measure(add: (Long) -> Unit, block: () -> T): T {
        val start = clock.nanoTime()
        return try {
            block()
        } finally {
            add((clock.nanoTime() - start).coerceAtLeast(0L))
        }
    }
}

internal data class DirectDsdDeadlineDrainResult(
    val iterations: Int,
    val deadlineExhausted: Boolean,
    val fused: Boolean,
)

/** Test/scaffolding only until a diagnostic capture supplies an evidence-backed production quantum. */
internal class DirectDsdDeadlineDrainLoop(
    private val clock: DirectDsdMonotonicClock,
    private val quantumNs: Long,
    private val hardIterationFuse: Int,
) {
    init {
        require(quantumNs > 0L)
        require(hardIterationFuse > 0)
    }

    fun drain(step: () -> DirectDsdDrainAction): DirectDsdDeadlineDrainResult {
        val start = clock.nanoTime()
        var iterations = 0
        while (iterations < hardIterationFuse) {
            if (iterations > 0 && clock.nanoTime() - start >= quantumNs) {
                return DirectDsdDeadlineDrainResult(iterations, deadlineExhausted = true, fused = false)
            }
            iterations++
            when (step()) {
                DirectDsdDrainAction.CONTINUE -> Unit
                DirectDsdDrainAction.YIELD,
                DirectDsdDrainAction.TERMINAL,
                -> return DirectDsdDeadlineDrainResult(iterations, deadlineExhausted = false, fused = false)
            }
        }
        return DirectDsdDeadlineDrainResult(iterations, deadlineExhausted = false, fused = true)
    }
}
