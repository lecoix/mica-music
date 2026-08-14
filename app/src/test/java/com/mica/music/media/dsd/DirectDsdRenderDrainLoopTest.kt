package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdRenderDrainLoopTest {
    @Test
    fun dsd128BudgetHasMarginAtThirtyFiveThirtyEightAndFortyCallbacksPerSecond() {
        val reads = DirectDsdRenderDrainCapacityPolicy.sourceReadsPerCallback(
            sourceSampleRateHz = 5_644_800,
            channelCount = 2,
        )
        assertEquals(6, reads)
        assertEquals(reads, DirectDsdMedia3Renderer.MAX_SOURCE_READS_PER_RENDER)

        val requiredPacketsPerSecond = 172.266
        listOf(35, 38, 40).forEach { callbacksPerSecond ->
            val capacity = DirectDsdRenderDrainCapacityPolicy.packetCapacityPerSecond(
                sourceSampleRateHz = 5_644_800,
                channelCount = 2,
                callbacksPerSecond = callbacksPerSecond,
            )
            assertTrue("capacity=$capacity at callbacks=$callbacksPerSecond", capacity > requiredPacketsPerSecond)
        }
    }

    @Test
    fun dsd64UsesSmallerBoundedBudget() {
        assertEquals(
            3,
            DirectDsdRenderDrainCapacityPolicy.sourceReadsPerCallback(
                sourceSampleRateHz = 2_822_400,
                channelCount = 2,
            ),
        )
    }

    @Test
    fun rendererDemandBudgetStillCapsIndefinitelyReadySource() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(
            maxSourceReads = DirectDsdMedia3Renderer.MAX_SOURCE_READS_PER_RENDER,
        ).drain {
            calls++
            step(read = true, packet = true, DirectDsdDrainAction.CONTINUE)
        }

        assertEquals(6, calls)
        assertEquals(6, result.sourceReadCount)
        assertEquals(6, result.packetReadCount)
        assertTrue(result.budgetExhausted)
    }

    @Test
    fun formatCanContinueIntoMultiplePacketsWithinOneRenderOpportunity() {
        val steps = ArrayDeque(
            listOf(
                step(read = true, packet = false, DirectDsdDrainAction.CONTINUE),
                step(read = true, packet = true, DirectDsdDrainAction.CONTINUE),
                step(read = true, packet = true, DirectDsdDrainAction.CONTINUE),
                step(read = true, packet = true, DirectDsdDrainAction.CONTINUE),
            ),
        )
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain { steps.removeFirst() }

        assertEquals(4, result.sourceReadCount)
        assertEquals(3, result.packetReadCount)
        assertTrue(result.budgetExhausted)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun nothingReadYieldsImmediately() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain {
            calls++
            step(read = true, packet = false, DirectDsdDrainAction.YIELD)
        }

        assertEquals(1, calls)
        assertEquals(1, result.sourceReadCount)
        assertEquals(0, result.packetReadCount)
        assertFalse(result.budgetExhausted)
    }

    @Test
    fun packetThatRetainsBackpressureTailCannotBeOvertaken() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain {
            calls++
            check(calls == 1) { "later extractor packet was read after pending tail" }
            step(read = true, packet = true, DirectDsdDrainAction.YIELD)
        }

        assertEquals(1, calls)
        assertEquals(1, result.sourceReadCount)
        assertEquals(1, result.packetReadCount)
        assertFalse(result.budgetExhausted)
    }

    @Test
    fun unresolvedPendingTailYieldsWithoutConsumingSourceReadBudget() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain {
            calls++
            check(calls == 1)
            step(read = false, packet = false, DirectDsdDrainAction.YIELD)
        }

        assertEquals(1, calls)
        assertEquals(0, result.sourceReadCount)
        assertEquals(0, result.packetReadCount)
        assertFalse(result.budgetExhausted)
    }

    @Test
    fun indefinitelyReadySourceIsCappedByPerCallbackBudget() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain {
            calls++
            step(read = true, packet = true, DirectDsdDrainAction.CONTINUE)
        }

        assertEquals(4, calls)
        assertEquals(4, result.sourceReadCount)
        assertEquals(4, result.packetReadCount)
        assertTrue(result.budgetExhausted)
    }

    @Test
    fun eosTerminalStopsAfterPriorFormatWithoutExtraRead() {
        var calls = 0
        val result = DirectDsdRenderDrainLoop(maxSourceReads = 4).drain {
            calls++
            when (calls) {
                1 -> step(read = true, packet = false, DirectDsdDrainAction.CONTINUE)
                2 -> step(read = true, packet = false, DirectDsdDrainAction.TERMINAL)
                else -> error("read after EOS")
            }
        }

        assertEquals(2, calls)
        assertEquals(2, result.sourceReadCount)
        assertEquals(0, result.packetReadCount)
        assertFalse(result.budgetExhausted)
    }

    private fun step(
        read: Boolean,
        packet: Boolean,
        action: DirectDsdDrainAction,
    ) = DirectDsdDrainStepResult(
        sourceReadPerformed = read,
        packetRead = packet,
        action = action,
    )
}
