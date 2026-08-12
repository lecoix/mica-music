package com.mica.music.media.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHealthRecoveryControllerTest {
    @Test
    fun transportErrorTriggersImmediateRecovery() {
        val current = AtomicReference(facts(generation = 7L, health = health(errorCode = 5)))
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()

        UsbHealthRecoveryController().poll(current::get, recovered::add)

        assertEquals(UsbRecoveryTrigger.TRANSPORT_ERROR, recovered.single().trigger)
        assertEquals(7L, recovered.single().sessionGeneration)
    }

    @Test
    fun activeConsumptionMustStallForThreeIntervals() {
        val current = AtomicReference(facts(health = health(sampledAtMs = 1_000L)))
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()
        val controller = UsbHealthRecoveryController()

        controller.poll(current::get, recovered::add)
        repeat(2) { index ->
            current.set(facts(health = health(sampledAtMs = 2_000L + index * 1_000L)))
            controller.poll(current::get, recovered::add)
        }
        assertTrue(recovered.isEmpty())

        current.set(facts(health = health(sampledAtMs = 4_000L)))
        controller.poll(current::get, recovered::add)

        assertEquals(UsbRecoveryTrigger.STALLED_PROGRESS, recovered.single().trigger)
    }

    @Test
    fun progressAndPauseResetStallEvidence() {
        val current = AtomicReference(facts(health = health(sampledAtMs = 1_000L)))
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()
        val controller = UsbHealthRecoveryController()

        controller.poll(current::get, recovered::add)
        current.set(facts(health = health(sampledAtMs = 2_000L)))
        controller.poll(current::get, recovered::add)
        current.set(facts(health = health(sampledAtMs = 3_000L, completedFrames = 2_000L)))
        controller.poll(current::get, recovered::add)
        current.set(facts(health = health(sampledAtMs = 4_000L, playbackRequested = false)))
        controller.poll(current::get, recovered::add)
        current.set(facts(health = health(sampledAtMs = 5_000L, completedFrames = 2_000L)))
        controller.poll(current::get, recovered::add)

        assertTrue(recovered.isEmpty())
    }

    @Test
    fun correlatedTransportDegradationTriggersRecoveryWhileFramesStillProgress() {
        val current = AtomicReference(
            facts(
                health = health(
                    sampledAtMs = 1_000L,
                    completedFrames = 1_000L,
                    dataPacketErrorCount = 0L,
                    previousDataCompletionGapUs = 1_000L,
                ),
            ),
        )
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()
        val controller = UsbHealthRecoveryController()

        controller.poll(current::get, recovered::add)
        current.set(
            facts(
                health = health(
                    sampledAtMs = 2_000L,
                    completedFrames = 2_000L,
                    dataPacketErrorCount = 1L,
                    previousDataCompletionGapUs = 60_000L,
                ),
            ),
        )
        controller.poll(current::get, recovered::add)
        assertTrue(recovered.isEmpty())

        current.set(
            facts(
                health = health(
                    sampledAtMs = 3_000L,
                    completedFrames = 3_000L,
                    dataPacketErrorCount = 2L,
                    previousDataCompletionGapUs = 70_000L,
                ),
            ),
        )
        controller.poll(current::get, recovered::add)

        assertEquals(UsbRecoveryTrigger.DEGRADED_TRANSPORT, recovered.single().trigger)
    }

    @Test
    fun isolatedPacketErrorAndSourceStarvationTelemetryDoNotReopenUsb() {
        val current = AtomicReference(
            facts(
                health = health(
                    sampledAtMs = 1_000L,
                    completedFrames = 1_000L,
                    dataPacketErrorCount = 0L,
                    zeroPcmFrameCount = 0L,
                ),
            ),
        )
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()
        val controller = UsbHealthRecoveryController()

        controller.poll(current::get, recovered::add)
        current.set(
            facts(
                health = health(
                    sampledAtMs = 2_000L,
                    completedFrames = 2_000L,
                    dataPacketErrorCount = 1L,
                    previousDataCompletionGapUs = 2_000L,
                    zeroPcmFrameCount = 2_048L,
                    maximumConsecutiveZeroPcmFrames = 2_048L,
                    underrunBytes = 8_192L,
                ),
            ),
        )
        controller.poll(current::get, recovered::add)
        current.set(
            facts(
                health = health(
                    sampledAtMs = 3_000L,
                    completedFrames = 3_000L,
                    dataPacketErrorCount = 1L,
                    previousDataCompletionGapUs = 1_500L,
                    zeroPcmFrameCount = 8_192L,
                    maximumConsecutiveZeroPcmFrames = 8_192L,
                    underrunBytes = 32_768L,
                ),
            ),
        )
        controller.poll(current::get, recovered::add)

        assertTrue(recovered.isEmpty())
    }

    @Test
    fun staleDecisionPausedAtRecoveryBoundaryCannotRebuildReplacement() {
        val atBoundary = CountDownLatch(1)
        val releaseBoundary = CountDownLatch(1)
        val current = AtomicReference(facts(generation = 10L, health = health(errorCode = 5)))
        val recovered = mutableListOf<UsbHealthRecoveryDecision>()
        val controller = UsbHealthRecoveryController(
            beforeRecoverySideEffect = {
                atBoundary.countDown()
                assertTrue(releaseBoundary.await(5, TimeUnit.SECONDS))
            },
        )

        val oldPoll = thread(name = "old-health-recovery") {
            controller.poll(current::get, recovered::add)
        }
        assertTrue(atBoundary.await(5, TimeUnit.SECONDS))
        current.set(facts(generation = 11L, health = health(sampledAtMs = 2_000L)))
        releaseBoundary.countDown()
        oldPoll.join(5_000L)

        assertTrue(recovered.isEmpty())
    }

    private fun facts(
        generation: Long = 1L,
        health: UsbRuntimeHealth,
    ) = PlaybackOutputFacts(
        generation = generation,
        phase = UsbOutputPhase.ACTIVE,
        runtimeHealth = health,
    )

    private fun health(
        sampledAtMs: Long = 1_000L,
        completedFrames: Long = 1_000L,
        errorCode: Int = 0,
        playbackRequested: Boolean = true,
        sourceConsumptionActive: Boolean = playbackRequested,
        underrunBytes: Long = 0L,
        dataPacketErrorCount: Long? = null,
        invalidFeedbackPacketCount: Long? = null,
        totalPollTimeouts: Long? = null,
        maximumConsecutivePollTimeouts: Long? = null,
        previousDataCompletionGapUs: Long? = null,
        previousFeedbackCompletionGapUs: Long? = null,
        zeroPcmFrameCount: Long? = null,
        maximumConsecutiveZeroPcmFrames: Long? = null,
    ) = UsbRuntimeHealth(
        sampledAtElapsedRealtimeMs = sampledAtMs,
        completedFrames = completedFrames,
        bufferedFrames = 256L,
        underrunBytes = underrunBytes,
        transportErrorCode = errorCode,
        playbackRequested = playbackRequested,
        sourceConsumptionActive = sourceConsumptionActive,
        previousDataCompletionGapUs = previousDataCompletionGapUs,
        previousFeedbackCompletionGapUs = previousFeedbackCompletionGapUs,
        totalPollTimeouts = totalPollTimeouts,
        maximumConsecutivePollTimeouts = maximumConsecutivePollTimeouts,
        invalidFeedbackPacketCount = invalidFeedbackPacketCount,
        dataPacketErrorCount = dataPacketErrorCount,
        zeroPcmFrameCount = zeroPcmFrameCount,
        maximumConsecutiveZeroPcmFrames = maximumConsecutiveZeroPcmFrames,
    )
}
