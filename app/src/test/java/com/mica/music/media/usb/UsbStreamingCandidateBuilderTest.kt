package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStreamingCandidateBuilderTest {
    private val identity = UsbAudioDeviceIdentity(
        vendorId = 0x1234,
        productId = 0x5678,
        descriptorFingerprint = "synthetic-p3-candidate-fixture",
    )

    @Test
    fun uac1AsyncExplicitFeedbackBuildsExactCandidate() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x05, maxPacket = 200, synchAddress = 0x82, rateControl = true),
                feedback = endpoint(address = 0x82, attributes = 0x11, maxPacket = 3),
                rates = UsbSampleRateSupport.Discrete(setOf(44_100, 48_000)),
            ),
        )

        assertEquals(1, report.capability.streamingProfiles.size)
        assertTrue(report.rejections.isEmpty())
        val profile = report.capability.streamingProfiles.single()
        assertEquals(UsbEndpointSyncMode.ASYNCHRONOUS, profile.syncMode)
        assertEquals(UsbFeedbackMode.EXPLICIT, profile.feedbackPlan.mode)
        assertEquals(UsbFeedbackEncoding.UAC1_10_14, profile.feedbackPlan.encoding)
        assertEquals(0x82, profile.feedbackPlan.endpointAddress)
        assertEquals(
            UsbClockPlan.Uac1Endpoint(endpointAddress = 0x01, samplingFrequencyControl = true),
            profile.clockPlan,
        )

        val decision = GenericExactUsbFormatNegotiator.negotiate(
            source = UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            capability = report.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        )
        assertTrue(decision is UsbFormatDecision.Accepted)
        assertTrue((decision as UsbFormatDecision.Accepted).signalExact)
    }

    @Test
    fun adaptiveEndpointNeedsNoFeedback() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x09, maxPacket = 200),
                feedback = null,
                rates = UsbSampleRateSupport.Fixed(48_000),
            ),
        )

        val profile = report.capability.streamingProfiles.single()
        assertEquals(UsbEndpointSyncMode.ADAPTIVE, profile.syncMode)
        assertEquals(UsbFeedbackMode.NONE, profile.feedbackPlan.mode)
    }

    @Test
    fun asyncWithoutExplicitFeedbackFailsClosed() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x05, maxPacket = 200, rateControl = true),
                feedback = null,
                rates = UsbSampleRateSupport.Discrete(setOf(44_100, 48_000)),
            ),
        )

        assertTrue(report.capability.streamingProfiles.isEmpty())
        assertEquals(
            UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
            report.rejections.single().rejection.code,
        )
    }

    @Test
    fun implicitFeedbackIsRecognizedAndRejectedExplicitly() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x25, maxPacket = 200, rateControl = true),
                feedback = null,
                rates = UsbSampleRateSupport.Fixed(48_000),
            ),
        )

        assertEquals(
            UsbAudioRejectionCode.IMPLICIT_FEEDBACK_UNSUPPORTED,
            report.rejections.single().rejection.code,
        )
    }

    @Test
    fun uac2NeedsRuntimeRateEvidenceAndPreserves24In32() {
        val facts = uac2Facts()
        val withoutRates = UsbStreamingCandidateBuilder.build(identity, facts)
        assertTrue(withoutRates.capability.streamingProfiles.isEmpty())
        assertEquals(
            UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE,
            withoutRates.rejections.single().rejection.code,
        )

        val withRates = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = facts,
            uac2ClockEvidence = Uac2RuntimeClockEvidence(
                sampleRatesByClockSourceId = mapOf(
                    4 to UsbSampleRateSupport.Discrete(setOf(48_000, 96_000, 192_000)),
                ),
            ),
        )
        val profile = withRates.capability.streamingProfiles.single()
        assertEquals(UsbPcmEncoding.PCM_24_IN_32, profile.encoding)
        assertEquals(UsbFeedbackEncoding.UAC2_16_16, profile.feedbackPlan.encoding)
        assertEquals(UsbClockPlan.Uac2Entity(sourceEntityId = 4), profile.clockPlan)
    }

    @Test
    fun rawFormatIdentityIsAdditiveAndDoesNotChangePcmCandidate() {
        val facts = uac1Facts(
            data = endpoint(address = 0x01, attributes = 0x09, maxPacket = 200),
            feedback = null,
            rates = UsbSampleRateSupport.Fixed(48_000),
        )
        val baseline = UsbStreamingCandidateBuilder.build(identity, facts)
        val alternate = facts.streamingAlternates.single()
        val withRawIdentity = UsbStreamingCandidateBuilder.build(
            identity,
            facts.copy(
                streamingAlternates = listOf(
                    alternate.copy(
                        rawFormatIdentity = UsbRawStreamingFormatIdentity.Uac1(
                            formatTag = 0x0001,
                            formatType = 0x01,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(baseline.capability.streamingProfiles, withRawIdentity.capability.streamingProfiles)
        assertEquals(baseline.rejections, withRawIdentity.rejections)
    }

    @Test
    fun nonUnityClockMultiplierFailsClosedUntilRateMappingIsProven() {
        val base = uac2Facts()
        val facts = base.copy(
            uac2ClockEntities = mapOf(
                10 to UsbUac2ClockEntity.Multiplier(id = 10, sourceId = 4, controls = 0x0f),
                4 to UsbUac2ClockEntity.Source(id = 4, attributes = 3, controls = 3),
            ),
            uac2TerminalClockLinks = mapOf(2 to 10),
        )
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = facts,
            uac2ClockEvidence = Uac2RuntimeClockEvidence(
                sampleRatesByClockSourceId = mapOf(4 to UsbSampleRateSupport.Fixed(48_000)),
                multiplierRatios = mapOf(10 to UsbClockMultiplierRatio(2, 1)),
            ),
        )

        assertEquals(
            UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
            report.rejections.single().rejection.code,
        )
    }

    @Test
    fun endpointCapacityFailureHasItsOwnTypedReason() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x09, maxPacket = 80),
                feedback = null,
                rates = UsbSampleRateSupport.Fixed(48_000),
            ),
        )
        val decision = GenericExactUsbFormatNegotiator.negotiate(
            source = UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            capability = report.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        )

        assertTrue(decision is UsbFormatDecision.Rejected)
        assertEquals(
            UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
            (decision as UsbFormatDecision.Rejected).rejection.code,
        )
    }

    @Test
    fun duplicateExactCandidatesAreRejectedAsAmbiguous() {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x09, maxPacket = 200),
                feedback = null,
                rates = UsbSampleRateSupport.Fixed(48_000),
                duplicateAlternate = true,
            ),
        )
        val decision = GenericExactUsbFormatNegotiator.negotiate(
            source = UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            capability = report.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        )

        assertTrue(decision is UsbFormatDecision.Rejected)
        assertEquals(
            UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
            (decision as UsbFormatDecision.Rejected).rejection.code,
        )
    }

    @Test
    fun reservedHighBandwidthMultiplierNeverBecomesEligible() {
        val reservedRawMaxPacket = 200 or (3 shl 11)
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = uac1Facts(
                data = endpoint(address = 0x01, attributes = 0x09, maxPacket = reservedRawMaxPacket),
                feedback = null,
                rates = UsbSampleRateSupport.Fixed(48_000),
            ),
        )

        assertEquals(
            UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
            report.rejections.single().rejection.code,
        )
    }

    private fun uac1Facts(
        data: UsbParsedEndpoint,
        feedback: UsbParsedEndpoint?,
        rates: UsbSampleRateSupport,
        duplicateAlternate: Boolean = false,
    ): UsbParsedAudioDescriptorFacts {
        val endpoints = listOfNotNull(data, feedback)
        val first = UsbParsedStreamingAlternate(
            protocol = UsbAudioProtocol.UAC1,
            interfaceNumber = 1,
            alternateSetting = 1,
            terminalLink = 1,
            formatIsPcm = true,
            format = UsbParsedTypeIPcmFormat(
                channelCount = 2,
                subslotBytes = 2,
                bitResolution = 16,
                sampleRates = rates,
            ),
            endpoints = endpoints,
        )
        val alternates = if (duplicateAlternate) {
            listOf(first, first.copy(alternateSetting = 2))
        } else {
            listOf(first)
        }
        return UsbParsedAudioDescriptorFacts(
            audioFunction = UsbAudioFunction(
                protocol = UsbAudioProtocol.UAC1,
                controlInterfaceNumber = 0,
                streamingInterfaceNumbers = setOf(1),
            ),
            busSpeed = UsbBusSpeed.FULL,
            streamingAlternates = alternates,
            uac2ClockEntities = emptyMap(),
            uac2TerminalClockLinks = emptyMap(),
        )
    }

    private fun uac2Facts(): UsbParsedAudioDescriptorFacts = UsbParsedAudioDescriptorFacts(
        audioFunction = UsbAudioFunction(
            protocol = UsbAudioProtocol.UAC2,
            controlInterfaceNumber = 1,
            streamingInterfaceNumbers = setOf(2),
        ),
        busSpeed = UsbBusSpeed.HIGH,
        streamingAlternates = listOf(
            UsbParsedStreamingAlternate(
                protocol = UsbAudioProtocol.UAC2,
                interfaceNumber = 2,
                alternateSetting = 1,
                terminalLink = 2,
                formatIsPcm = true,
                format = UsbParsedTypeIPcmFormat(
                    channelCount = 2,
                    subslotBytes = 4,
                    bitResolution = 24,
                    sampleRates = UsbSampleRateSupport.Unverified,
                ),
                endpoints = listOf(
                    endpoint(address = 0x03, attributes = 0x05, maxPacket = 400, synchAddress = 0x84),
                    endpoint(address = 0x84, attributes = 0x11, maxPacket = 4, interval = 4),
                ),
            ),
        ),
        uac2ClockEntities = mapOf(
            4 to UsbUac2ClockEntity.Source(id = 4, attributes = 3, controls = 3),
        ),
        uac2TerminalClockLinks = mapOf(2 to 4),
    )

    private fun endpoint(
        address: Int,
        attributes: Int,
        maxPacket: Int,
        interval: Int = 1,
        synchAddress: Int? = null,
        rateControl: Boolean = false,
    ) = UsbParsedEndpoint(
        address = address,
        attributes = attributes,
        rawMaxPacketSize = maxPacket,
        interval = interval,
        synchAddress = synchAddress,
        samplingFrequencyControl = rateControl,
    )
}
