package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbRecoveryActivationPolicyTest {
    @Test
    fun playbackStackPublicationAloneDoesNotAckUsbRecovery() {
        val expectation = expectation(requireFrameProgress = true)
        val facts = PlaybackOutputFacts(
            generation = 11L,
            phase = UsbOutputPhase.IDLE,
        )

        assertEquals(
            UsbRecoveryActivationState.WAITING,
            UsbRecoveryActivationPolicy.evaluate(expectation, facts, elapsedRealtimeMs = 1_500L),
        )
    }

    @Test
    fun activePausedReplacementCanAckWithoutFrameProgress() {
        val expectation = expectation(requireFrameProgress = false)

        assertEquals(
            UsbRecoveryActivationState.SUCCEEDED,
            UsbRecoveryActivationPolicy.evaluate(
                expectation,
                activeFacts(request = expectation.expectedRequest, completedFrames = null),
                elapsedRealtimeMs = 1_500L,
            ),
        )
    }

    @Test
    fun activePlayingReplacementWaitsForRealUsbCompletion() {
        val expectation = expectation(requireFrameProgress = true)

        assertEquals(
            UsbRecoveryActivationState.WAITING,
            UsbRecoveryActivationPolicy.evaluate(
                expectation,
                activeFacts(request = expectation.expectedRequest, completedFrames = 0L),
                elapsedRealtimeMs = 1_500L,
            ),
        )
        assertEquals(
            UsbRecoveryActivationState.SUCCEEDED,
            UsbRecoveryActivationPolicy.evaluate(
                expectation,
                activeFacts(request = expectation.expectedRequest, completedFrames = 512L),
                elapsedRealtimeMs = 2_000L,
            ),
        )
    }

    @Test
    fun failedUsbOpenFailsPendingAck() {
        val expectation = expectation(requireFrameProgress = false)
        val facts = PlaybackOutputFacts(
            generation = 12L,
            phase = UsbOutputPhase.FAILED,
            request = expectation.expectedRequest,
            failure = UsbOutputFailure("open", "failed"),
        )

        assertEquals(
            UsbRecoveryActivationState.FAILED,
            UsbRecoveryActivationPolicy.evaluate(expectation, facts, elapsedRealtimeMs = 1_500L),
        )
    }

    @Test
    fun replacementForDifferentRequestMakesOldRecoveryStale() {
        val expectation = expectation(requireFrameProgress = false)

        assertEquals(
            UsbRecoveryActivationState.STALE,
            UsbRecoveryActivationPolicy.evaluate(
                expectation,
                activeFacts(request = request("replacement"), completedFrames = 512L),
                elapsedRealtimeMs = 1_500L,
            ),
        )
    }

    @Test
    fun activationTimeoutFailsInsteadOfResolvingEpoch() {
        val expectation = expectation(requireFrameProgress = true)
        val facts = PlaybackOutputFacts(
            generation = 11L,
            phase = UsbOutputPhase.OPENING,
            request = expectation.expectedRequest,
        )

        assertEquals(
            UsbRecoveryActivationState.FAILED,
            UsbRecoveryActivationPolicy.evaluate(
                expectation,
                facts,
                elapsedRealtimeMs = expectation.deadlineElapsedRealtimeMs,
            ),
        )
    }

    private fun expectation(requireFrameProgress: Boolean): UsbRecoveryActivationExpectation {
        val epoch = UsbRecoveryEpoch(id = 1L, sessionGeneration = 10L)
        return UsbRecoveryActivationExpectation(
            action = UsbRecoveryAction(
                epoch = epoch,
                generation = 1L,
                actionId = 1L,
                kind = UsbRecoveryActionKind.FRESH_OPEN,
                trigger = UsbRecoveryTrigger.STALLED_PROGRESS,
                attempt = 1,
            ),
            expectedRequest = request("expected"),
            requireFrameProgress = requireFrameProgress,
            deadlineElapsedRealtimeMs = 5_000L,
        )
    }

    private fun activeFacts(
        request: UsbOutputRequest?,
        completedFrames: Long?,
    ) = PlaybackOutputFacts(
        generation = 12L,
        phase = UsbOutputPhase.ACTIVE,
        request = request,
        runtimeHandle = UsbAudioRuntimeHandle(88),
        attached = true,
        permission = UsbPermissionState.GRANTED,
        claimed = true,
        exclusive = true,
        signalExact = true,
        runtimeHealth = completedFrames?.let {
            UsbRuntimeHealth(
                sampledAtElapsedRealtimeMs = 2_000L,
                completedFrames = it,
                bufferedFrames = 256L,
                underrunBytes = 0L,
                transportErrorCode = 0,
                playbackRequested = true,
                sourceConsumptionActive = true,
            )
        },
    )

    private fun request(name: String) = UsbOutputRequest(
        device = UsbAudioDeviceIdentity(
            vendorId = 0x262a,
            productId = 0x0001,
            descriptorFingerprint = name,
        ),
    )
}
