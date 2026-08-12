package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportConfigTest {
    @Test
    fun sk02Pcm16GoldenDecisionPreservesExistingGeometryWithFactualTiming() {
        val decision = Sk02UsbContract.negotiate(
            source = UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            capability = Sk02UsbContract.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        ) as UsbFormatDecision.Accepted

        val result = UsbTransportConfigBuilder.build(decision, Sk02UsbContract.capability.busSpeed)

        assertTrue(result is UsbTransportConfigResult.Ready)
        val config = (result as UsbTransportConfigResult.Ready).config
        assertEquals(48_000L, config.nominalRuntimeFrameRateHz)
        assertEquals(0x03, config.dataEndpointAddress)
        assertEquals(200, config.dataMaxBytesPerServiceInterval)
        assertEquals(4, config.bytesPerRuntimeFrame)
        assertEquals(UsbBusSpeed.HIGH, config.busSpeed)
        assertEquals(ratio(1, 8_000), config.dataServicePeriodSeconds)
        assertEquals(125L, config.dataServicePeriodMicros)
        assertEquals(UsbEndpointSyncMode.ASYNCHRONOUS, config.syncMode)
        assertEquals(8, config.packetsPerTransfer)
        assertEquals(16, config.dataQueueDepth)
        assertEquals(ratio(2, 125), config.aheadWindowTargetSeconds)
        assertEquals(ratio(2, 125), config.aheadWindowCoverageSeconds)
        assertEquals(16_000L, config.aheadWindowCoverageMicros)

        val feedback = checkNotNull(config.feedback)
        assertEquals(0x84, feedback.endpointAddress)
        assertEquals(4, feedback.endpointCapacityBytesPerServiceInterval)
        assertEquals(4, feedback.expectedPayloadBytes)
        assertEquals(16, feedback.fractionalBits)
        assertEquals(UsbFeedbackRawTimeUnit.FRAMES_PER_USB_MICROFRAME, feedback.rawTimeUnit)
        assertEquals(ratio(1, 1), feedback.rawToDataServiceIntervalScale)
        assertEquals(ratio(1, 1_000), feedback.pollPeriodSeconds)
        assertEquals(1_000L, feedback.pollPeriodMicros)
        assertEquals(0L, feedback.requiredZeroMask)
    }

    @Test
    fun sk02Pcm32GoldenDecisionChangesOnlyRuntimeFormatDependentFacts() {
        val decision = Sk02UsbContract.negotiate(
            source = UsbPcmFormat(192_000, 2, UsbPcmEncoding.PCM_32),
            capability = Sk02UsbContract.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        ) as UsbFormatDecision.Accepted

        val config = (UsbTransportConfigBuilder.build(
            decision,
            Sk02UsbContract.capability.busSpeed,
        ) as UsbTransportConfigResult.Ready).config

        assertEquals(192_000L, config.nominalRuntimeFrameRateHz)
        assertEquals(400, config.dataMaxBytesPerServiceInterval)
        assertEquals(8, config.bytesPerRuntimeFrame)
        assertEquals(0x03, config.dataEndpointAddress)
        assertEquals(ratio(1, 8_000), config.dataServicePeriodSeconds)
        assertEquals(16_000L, config.aheadWindowCoverageMicros)
    }

    @Test
    fun noFeedback44100UsesExactRationalServiceTiming() {
        val decision = acceptedDecision(
            sampleRateHz = 44_100,
            profile = profile(
                sampleRateHz = 44_100,
                syncMode = UsbEndpointSyncMode.ADAPTIVE,
                feedbackPlan = UsbFeedbackPlan(UsbFeedbackMode.NONE),
            ),
        )

        val config = (UsbTransportConfigBuilder.build(
            decision,
            UsbBusSpeed.HIGH,
        ) as UsbTransportConfigResult.Ready).config

        assertEquals(44_100L, config.nominalRuntimeFrameRateHz)
        assertEquals(ratio(1, 8_000), config.dataServicePeriodSeconds)
        assertEquals(125L, config.dataServicePeriodMicros)
        assertEquals(44_100L, exactRuntimeFramesOverIntervals(config, 8_000))
        assertNull(config.feedback)
    }

    @Test
    fun sixteenMillisecondDataIntervalRemainsExactWithFractionalIntervalsPerSecond() {
        val config = readyConfig(
            profile = profile(
                sampleRateHz = 44_100,
                interval = 8,
                syncMode = UsbEndpointSyncMode.SYNCHRONOUS,
            ),
            busSpeed = UsbBusSpeed.HIGH,
            sampleRateHz = 44_100,
        )

        assertEquals(ratio(2, 125), config.dataServicePeriodSeconds)
        assertEquals(16_000L, config.dataServicePeriodMicros)
        assertEquals(88_200L, exactRuntimeFramesOverIntervals(config, 125))
    }

    @Test
    fun multiUnitDataIntervalProducesExactFeedbackNormalizationScale() {
        val decision = acceptedDecision(
            profile = profile(
                interval = 3,
                syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                feedbackPlan = explicitFeedback(interval = 4),
            ),
        )

        val config = (UsbTransportConfigBuilder.build(
            decision,
            UsbBusSpeed.HIGH,
        ) as UsbTransportConfigResult.Ready).config

        assertEquals(ratio(1, 2_000), config.dataServicePeriodSeconds)
        assertEquals(500L, config.dataServicePeriodMicros)
        assertEquals(4, config.dataQueueDepth)
        val feedback = checkNotNull(config.feedback)
        assertEquals(ratio(4, 1), feedback.rawToDataServiceIntervalScale)
        assertEquals(UsbFeedbackRawTimeUnit.FRAMES_PER_USB_MICROFRAME, feedback.rawTimeUnit)
    }

    @Test
    fun feedbackPollCadenceDoesNotChangeRawRateNormalization() {
        val pollEveryEightMicroframes = readyConfig(
            profile = profile(
                syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                feedbackPlan = explicitFeedback(interval = 4),
            ),
            busSpeed = UsbBusSpeed.HIGH,
        ).feedback!!
        val pollEverySixteenMicroframes = readyConfig(
            profile = profile(
                syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                feedbackPlan = explicitFeedback(interval = 5),
            ),
            busSpeed = UsbBusSpeed.HIGH,
        ).feedback!!

        assertEquals(
            pollEveryEightMicroframes.rawToDataServiceIntervalScale,
            pollEverySixteenMicroframes.rawToDataServiceIntervalScale,
        )
        assertEquals(ratio(1, 1), pollEveryEightMicroframes.rawToDataServiceIntervalScale)
        assertEquals(ratio(1, 1_000), pollEveryEightMicroframes.pollPeriodSeconds)
        assertEquals(ratio(1, 500), pollEverySixteenMicroframes.pollPeriodSeconds)
    }

    @Test
    fun fullSpeedFeedbackUsesBusFrameAsRawTimeUnit() {
        val config = readyConfig(
            profile = profile(
                syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                feedbackPlan = UsbFeedbackPlan(
                    mode = UsbFeedbackMode.EXPLICIT,
                    endpointAddress = 0x82,
                    maxPacketBytes = 3,
                    interval = 1,
                    encoding = UsbFeedbackEncoding.UAC1_10_14,
                ),
            ),
            busSpeed = UsbBusSpeed.FULL,
        )

        val feedback = checkNotNull(config.feedback)
        assertEquals(3, feedback.expectedPayloadBytes)
        assertEquals(14, feedback.fractionalBits)
        assertEquals(UsbFeedbackRawTimeUnit.FRAMES_PER_USB_FRAME, feedback.rawTimeUnit)
        assertEquals(ratio(1, 1), feedback.rawToDataServiceIntervalScale)
        assertEquals(ratio(1, 1_000), feedback.pollPeriodSeconds)
    }

    @Test
    fun malformedFeedbackPayloadCapacityFailsClosed() {
        val result = UsbTransportConfigBuilder.build(
            acceptedDecision(
                profile = profile(
                    syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                    feedbackPlan = UsbFeedbackPlan(
                        mode = UsbFeedbackMode.EXPLICIT,
                        endpointAddress = 0x84,
                        maxPacketBytes = 3,
                        interval = 4,
                        encoding = UsbFeedbackEncoding.UAC2_16_16,
                    ),
                ),
            ),
            UsbBusSpeed.HIGH,
        )

        assertTrue(result is UsbTransportConfigResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
            (result as UsbTransportConfigResult.Rejected).rejection.code,
        )
    }

    @Test
    fun invalidEndpointIntervalFailsClosed() {
        val result = UsbTransportConfigBuilder.build(
            acceptedDecision(profile = profile(interval = 0)),
            UsbBusSpeed.HIGH,
        )

        assertTrue(result is UsbTransportConfigResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
            (result as UsbTransportConfigResult.Rejected).rejection.code,
        )
    }

    @Test
    fun asynchronousTransportWithoutExplicitFeedbackFailsBeforeNative() {
        val decision = acceptedDecision(
            profile = profile(
                syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
                feedbackPlan = UsbFeedbackPlan(UsbFeedbackMode.NONE),
            ),
        )

        val result = UsbTransportConfigBuilder.build(decision, UsbBusSpeed.HIGH)

        assertTrue(result is UsbTransportConfigResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
            (result as UsbTransportConfigResult.Rejected).rejection.code,
        )
    }

    @Test
    fun unknownBusSpeedNeverLeaksIntoTransportConfig() {
        val result = UsbTransportConfigBuilder.build(
            acceptedDecision(profile = profile()),
            UsbBusSpeed.UNKNOWN,
        )

        assertTrue(result is UsbTransportConfigResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
            (result as UsbTransportConfigResult.Rejected).rejection.code,
        )
    }

    private fun readyConfig(
        profile: UsbAudioStreamingProfile,
        busSpeed: UsbBusSpeed,
        sampleRateHz: Int = 48_000,
    ): UsbTransportConfig = (UsbTransportConfigBuilder.build(
        acceptedDecision(sampleRateHz = sampleRateHz, profile = profile),
        busSpeed,
    ) as UsbTransportConfigResult.Ready).config

    private fun profile(
        sampleRateHz: Int = 48_000,
        interval: Int = 1,
        syncMode: UsbEndpointSyncMode = UsbEndpointSyncMode.SYNCHRONOUS,
        feedbackPlan: UsbFeedbackPlan = UsbFeedbackPlan(UsbFeedbackMode.NONE),
    ) = UsbAudioStreamingProfile(
        interfaceNumber = 2,
        alternateSetting = 1,
        endpointAddress = 0x03,
        feedbackEndpointAddress = feedbackPlan.endpointAddress,
        feedbackMaxPacketBytes = feedbackPlan.maxPacketBytes,
        feedbackInterval = feedbackPlan.interval,
        channelCount = 2,
        encoding = UsbPcmEncoding.PCM_16,
        subslotBytes = 2,
        bitResolution = 16,
        sampleRates = UsbSampleRateSupport.Fixed(sampleRateHz),
        maxPacketBytes = 200,
        interval = interval,
        syncMode = syncMode,
        feedbackPlan = feedbackPlan,
        clockPlan = UsbClockPlan.Uac1Endpoint(0x03, samplingFrequencyControl = false),
        capacityEvidence = UsbEndpointCapacityEvidence(
            maxPacketBytes = 200,
            bytesPerAudioFrame = 4,
            maxFramesPerServiceInterval = 50,
        ),
        claimPlan = UsbInterfaceClaimPlan(0, 2, 1),
    )

    private fun explicitFeedback(interval: Int) = UsbFeedbackPlan(
        mode = UsbFeedbackMode.EXPLICIT,
        endpointAddress = 0x84,
        maxPacketBytes = 4,
        interval = interval,
        encoding = UsbFeedbackEncoding.UAC2_16_16,
    )

    private fun acceptedDecision(
        sampleRateHz: Int = 48_000,
        profile: UsbAudioStreamingProfile,
    ) = UsbFormatDecision.Accepted(
        requestedFormat = UsbPcmFormat(sampleRateHz, 2, UsbPcmEncoding.PCM_16),
        deviceFormat = UsbPcmFormat(sampleRateHz, 2, UsbPcmEncoding.PCM_16),
        streamingProfile = profile,
        signalExact = true,
    )

    private fun ratio(numerator: Long, denominator: Long): UsbExactRatio =
        checkNotNull(UsbExactRatio.of(numerator, denominator))

    private fun exactRuntimeFramesOverIntervals(
        config: UsbTransportConfig,
        intervalCount: Long,
    ): Long {
        val numerator = config.nominalRuntimeFrameRateHz *
            config.dataServicePeriodSeconds.numerator *
            intervalCount
        check(numerator % config.dataServicePeriodSeconds.denominator == 0L)
        return numerator / config.dataServicePeriodSeconds.denominator
    }
}
