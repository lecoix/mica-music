package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdRenderTimingTest {
    @Test
    fun deadlineLoopYieldsWhenQuantumExpiresWithoutCallbackRateAssumption() {
        val clock = FakeClock()
        val loop = DirectDsdDeadlineDrainLoop(clock, quantumNs = 10, hardIterationFuse = 100)
        var steps = 0
        val result = loop.drain {
            steps++
            clock.advance(4)
            DirectDsdDrainAction.CONTINUE
        }
        assertEquals(3, steps)
        assertTrue(result.deadlineExhausted)
        assertFalse(result.fused)
    }

    @Test
    fun irregularArrivalDoesNotAffectDeadlineWorkAccounting() {
        val clock = FakeClock(1_000)
        val loop = DirectDsdDeadlineDrainLoop(clock, quantumNs = 9, hardIterationFuse = 100)
        val result = loop.drain {
            clock.advance(3)
            DirectDsdDrainAction.CONTINUE
        }
        assertEquals(3, result.iterations)
        assertTrue(result.deadlineExhausted)
    }

    @Test
    fun pendingTailAndNothingReadYieldImmediately() {
        val clock = FakeClock()
        val loop = DirectDsdDeadlineDrainLoop(clock, quantumNs = 100, hardIterationFuse = 100)
        assertEquals(1, loop.drain { DirectDsdDrainAction.YIELD }.iterations)
        assertEquals(1, loop.drain { DirectDsdDrainAction.YIELD }.iterations)
    }

    @Test
    fun terminalStopsImmediately() {
        val clock = FakeClock()
        val result = DirectDsdDeadlineDrainLoop(clock, 100, 100).drain {
            DirectDsdDrainAction.TERMINAL
        }
        assertEquals(1, result.iterations)
        assertFalse(result.deadlineExhausted)
    }

    @Test
    fun deadlineLoopPropagatesStepErrorImmediately() {
        val clock = FakeClock()
        var calls = 0
        val failure = runCatching {
            DirectDsdDeadlineDrainLoop(clock, 100, 100).drain {
                calls++
                error("boom")
            }
        }.exceptionOrNull()
        assertEquals(1, calls)
        assertEquals("boom", failure?.message)
    }

    @Test
    fun zeroCostClockBugHitsHardFuse() {
        val clock = FakeClock()
        val result = DirectDsdDeadlineDrainLoop(clock, 100, 7).drain {
            DirectDsdDrainAction.CONTINUE
        }
        assertEquals(7, result.iterations)
        assertTrue(result.fused)
    }

    @Test
    fun accumulatorSeparatesBusyStagesAndCallbackGap() {
        val clock = FakeClock()
        val timing = DirectDsdRenderTimingAccumulator(clock)
        val start1 = timing.onCallbackStart()
        timing.measureReadSource { clock.advance(2); 1 }
        timing.measurePacketStage { clock.advance(3) }
        timing.measurePump { clock.advance(5) }
        timing.onDrainComplete(start1, DirectDsdDrainResult(1, 1, true))
        clock.advance(90)
        val start2 = timing.onCallbackStart()
        timing.measureReadSource { clock.advance(1); 1 }
        timing.onTermination(DirectDsdDrainTermination.NOTHING_READ)
        timing.onDrainComplete(start2, DirectDsdDrainResult(1, 0, false))
        val s = timing.snapshotAndReset()

        assertEquals(2, s.callbacks)
        assertEquals(100, s.interArrivalMinNs)
        assertEquals(100, s.interArrivalMaxNs)
        assertEquals(11, s.drainBusyTotalNs)
        assertEquals(3, s.readSourceTotalNs)
        assertEquals(3, s.packetStageTotalNs)
        assertEquals(5, s.pumpTotalNs)
        assertEquals(1, s.nothingReadYields)
        assertEquals(1, s.budgetExhausted)
    }

    private class FakeClock(private var now: Long = 0L) : DirectDsdMonotonicClock {
        override fun nanoTime(): Long = now
        fun advance(delta: Long) { now += delta }
    }
}
