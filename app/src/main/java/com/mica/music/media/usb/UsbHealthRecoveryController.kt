package com.mica.music.media.usb

internal data class UsbHealthRecoveryDecision(
    val sessionGeneration: Long,
    val sampledAtElapsedRealtimeMs: Long,
    val trigger: UsbRecoveryTrigger,
) {
    fun matches(facts: PlaybackOutputFacts): Boolean =
        facts.phase == UsbOutputPhase.ACTIVE &&
            facts.generation == sessionGeneration &&
            facts.runtimeHealth?.sampledAtElapsedRealtimeMs == sampledAtElapsedRealtimeMs
}

/** Main-thread policy seam. It classifies health; the caller owns recovery execution. */
internal class UsbHealthRecoveryController(
    private val requiredStalledIntervals: Int = 3,
    private val requiredDegradedIntervals: Int = 2,
    private val minimumSampleIntervalMs: Long = 750L,
    private val dataCompletionGapThresholdUs: Long = 50_000L,
    private val feedbackCompletionGapThresholdUs: Long = 100_000L,
    private val minimumConsecutivePollTimeouts: Long = 3L,
    private val beforeRecoverySideEffect: (UsbHealthRecoveryDecision) -> Unit = {},
) {
    private var previousGeneration = Long.MIN_VALUE
    private var previousHealth: UsbRuntimeHealth? = null
    private var stalledIntervals = 0
    private var degradedIntervals = 0

    init {
        require(requiredStalledIntervals > 0)
        require(requiredDegradedIntervals > 0)
        require(minimumSampleIntervalMs > 0L)
        require(dataCompletionGapThresholdUs > 0L)
        require(feedbackCompletionGapThresholdUs > 0L)
        require(minimumConsecutivePollTimeouts > 0L)
    }

    fun poll(
        facts: () -> PlaybackOutputFacts,
        recover: (UsbHealthRecoveryDecision) -> Unit,
    ) {
        val decision = observe(facts()) ?: return
        beforeRecoverySideEffect(decision)
        if (decision.matches(facts())) recover(decision)
    }

    private fun observe(facts: PlaybackOutputFacts): UsbHealthRecoveryDecision? {
        val health = facts.runtimeHealth
        if (facts.phase != UsbOutputPhase.ACTIVE || health == null) {
            reset()
            return null
        }
        if (facts.generation != previousGeneration) {
            previousGeneration = facts.generation
            previousHealth = null
            stalledIntervals = 0
            degradedIntervals = 0
        }
        val previous = previousHealth
        if (previous != null &&
            health.sampledAtElapsedRealtimeMs <= previous.sampledAtElapsedRealtimeMs
        ) {
            return null
        }
        previousHealth = health

        if (health.transportErrorCode != 0) {
            return decision(facts, health, UsbRecoveryTrigger.TRANSPORT_ERROR)
        }
        if (!health.playbackRequested || !health.sourceConsumptionActive) {
            stalledIntervals = 0
            degradedIntervals = 0
            return null
        }
        if (previous == null ||
            health.sampledAtElapsedRealtimeMs - previous.sampledAtElapsedRealtimeMs <
            minimumSampleIntervalMs
        ) {
            stalledIntervals = 0
            degradedIntervals = 0
            return null
        }

        if (isTransportDegraded(previous, health)) {
            degradedIntervals++
            if (degradedIntervals >= requiredDegradedIntervals) {
                return decision(facts, health, UsbRecoveryTrigger.DEGRADED_TRANSPORT)
            }
        } else {
            degradedIntervals = 0
        }

        if (health.completedFrames != previous.completedFrames) {
            stalledIntervals = 0
            return null
        }
        stalledIntervals++
        return if (stalledIntervals >= requiredStalledIntervals) {
            decision(facts, health, UsbRecoveryTrigger.STALLED_PROGRESS)
        } else {
            null
        }
    }

    private fun decision(
        facts: PlaybackOutputFacts,
        health: UsbRuntimeHealth,
        trigger: UsbRecoveryTrigger,
    ) = UsbHealthRecoveryDecision(
        sessionGeneration = facts.generation,
        sampledAtElapsedRealtimeMs = health.sampledAtElapsedRealtimeMs,
        trigger = trigger,
    )

    /**
     * Reopen only when multiple transport-side symptoms agree. Source starvation, underrun
     * growth, or an isolated data/feedback error are deliberately insufficient evidence.
     */
    private fun isTransportDegraded(
        previous: UsbRuntimeHealth,
        current: UsbRuntimeHealth,
    ): Boolean {
        val dataErrorsAdvanced = counterAdvanced(
            previous.dataPacketErrorCount,
            current.dataPacketErrorCount,
        )
        val invalidFeedbackAdvanced = counterAdvanced(
            previous.invalidFeedbackPacketCount,
            current.invalidFeedbackPacketCount,
        )
        val pollTimeoutsAdvanced = counterAdvanced(
            previous.totalPollTimeouts,
            current.totalPollTimeouts,
        )
        val dataCompletionGapHigh =
            (current.previousDataCompletionGapUs ?: 0L) >= dataCompletionGapThresholdUs
        val feedbackCompletionGapHigh =
            (current.previousFeedbackCompletionGapUs ?: 0L) >= feedbackCompletionGapThresholdUs
        val pollTimeoutBurst = pollTimeoutsAdvanced &&
            (current.maximumConsecutivePollTimeouts ?: 0L) >= minimumConsecutivePollTimeouts

        return (dataErrorsAdvanced && dataCompletionGapHigh) ||
            (dataErrorsAdvanced && pollTimeoutBurst) ||
            (dataErrorsAdvanced && invalidFeedbackAdvanced) ||
            (invalidFeedbackAdvanced && feedbackCompletionGapHigh) ||
            (pollTimeoutBurst && (dataCompletionGapHigh || feedbackCompletionGapHigh))
    }

    private fun counterAdvanced(previous: Long?, current: Long?): Boolean =
        previous != null && current != null && current > previous

    private fun reset() {
        previousGeneration = Long.MIN_VALUE
        previousHealth = null
        stalledIntervals = 0
        degradedIntervals = 0
    }
}
