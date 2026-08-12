package com.mica.music.media.usb

internal data class UsbRecoveryActivationExpectation(
    val action: UsbRecoveryAction,
    val expectedRequest: UsbOutputRequest?,
    val requireFrameProgress: Boolean,
    val deadlineElapsedRealtimeMs: Long,
)

internal enum class UsbRecoveryActivationState {
    WAITING,
    SUCCEEDED,
    FAILED,
    STALE,
}

/**
 * A playback-stack publication is not a USB recovery ACK. This policy waits for the replacement
 * transport to prove that it actually became the requested exclusive/exact session. While active
 * playback is being restored, one real USB completion sample is required as the final proof.
 */
internal object UsbRecoveryActivationPolicy {
    fun evaluate(
        expectation: UsbRecoveryActivationExpectation,
        facts: PlaybackOutputFacts,
        elapsedRealtimeMs: Long,
    ): UsbRecoveryActivationState {
        if (facts.generation <= expectation.action.epoch.sessionGeneration) {
            return if (elapsedRealtimeMs >= expectation.deadlineElapsedRealtimeMs) {
                UsbRecoveryActivationState.FAILED
            } else {
                UsbRecoveryActivationState.WAITING
            }
        }

        val expectedRequest = expectation.expectedRequest
        if (facts.request != null && expectedRequest != null && facts.request != expectedRequest) {
            return UsbRecoveryActivationState.STALE
        }

        when (facts.phase) {
            UsbOutputPhase.ACTIVE -> {
                if (facts.request != expectedRequest ||
                    !facts.attached ||
                    facts.permission != UsbPermissionState.GRANTED ||
                    !facts.claimed ||
                    !facts.exclusive ||
                    !facts.signalExact
                ) {
                    return UsbRecoveryActivationState.FAILED
                }
                if (expectation.requireFrameProgress &&
                    (facts.runtimeHealth?.completedFrames ?: 0L) <= 0L
                ) {
                    return if (elapsedRealtimeMs >= expectation.deadlineElapsedRealtimeMs) {
                        UsbRecoveryActivationState.FAILED
                    } else {
                        UsbRecoveryActivationState.WAITING
                    }
                }
                return UsbRecoveryActivationState.SUCCEEDED
            }

            UsbOutputPhase.FAILED -> return UsbRecoveryActivationState.FAILED
            UsbOutputPhase.IDLE,
            UsbOutputPhase.REQUESTED,
            UsbOutputPhase.OPENING,
            UsbOutputPhase.RELEASING,
            -> Unit
        }

        return if (elapsedRealtimeMs >= expectation.deadlineElapsedRealtimeMs) {
            UsbRecoveryActivationState.FAILED
        } else {
            UsbRecoveryActivationState.WAITING
        }
    }
}
