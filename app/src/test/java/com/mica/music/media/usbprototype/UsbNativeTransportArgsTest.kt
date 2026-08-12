package com.mica.music.media.usbprototype

import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbBusSpeed
import com.mica.music.media.usb.UsbEndpointSyncMode
import com.mica.music.media.usb.UsbExactRatio
import com.mica.music.media.usb.UsbFormatDecision
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbSignalPolicy
import com.mica.music.media.usb.UsbTransportConfig
import com.mica.music.media.usb.UsbTransportConfigBuilder
import com.mica.music.media.usb.UsbTransportConfigResult
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbNativeTransportArgsTest {
    @Test
    fun sk02CandidateConfigFlattensFactualTransportProfile() {
        val decision = Sk02UsbContract.negotiate(
            source = UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            capability = Sk02UsbContract.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        ) as UsbFormatDecision.Accepted
        val config = (UsbTransportConfigBuilder.build(
            decision,
            Sk02UsbContract.capability.busSpeed,
        ) as UsbTransportConfigResult.Ready).config

        val args = UsbNativeTransportArgs.from(config)

        assertEquals(48_000L, args.nominalRuntimeFrameRateHz)
        assertEquals(0x03, args.dataEndpointAddress)
        assertEquals(4, args.bytesPerRuntimeFrame)
        assertEquals(200, args.dataMaxBytesPerServiceInterval)
        assertEquals(1L, args.dataServicePeriodNumerator)
        assertEquals(8_000L, args.dataServicePeriodDenominator)
        assertEquals(8, args.packetsPerTransfer)
        assertEquals(16, args.dataQueueDepth)
        assertEquals(0x84, args.feedbackEndpointAddress)
        assertEquals(4, args.feedbackEndpointCapacityBytesPerServiceInterval)
        assertEquals(4, args.feedbackExpectedPayloadBytes)
        assertEquals(16, args.feedbackFractionalBits)
        assertEquals(UsbNativeTransportArgs.RAW_TIME_UNIT_MICROFRAME, args.feedbackRawTimeUnit)
        assertEquals(1L, args.feedbackRawToDataScaleNumerator)
        assertEquals(1L, args.feedbackRawToDataScaleDenominator)
        assertEquals(1L, args.feedbackPollPeriodNumerator)
        assertEquals(1_000L, args.feedbackPollPeriodDenominator)
        assertEquals(0L, args.feedbackRequiredZeroMask)
    }

    @Test
    fun noFeedbackConfigPreservesExactPeriodAndUsesNoProfileSentinel() {
        val config = UsbTransportConfig(
            nominalRuntimeFrameRateHz = 44_100,
            dataEndpointAddress = 0x03,
            dataMaxBytesPerServiceInterval = 200,
            bytesPerRuntimeFrame = 4,
            busSpeed = UsbBusSpeed.HIGH,
            dataServicePeriodSeconds = UsbExactRatio(2, 125),
            syncMode = UsbEndpointSyncMode.SYNCHRONOUS,
            feedback = null,
            packetsPerTransfer = 8,
            dataQueueDepth = 1,
            aheadWindowTargetSeconds = UsbExactRatio(2, 125),
            aheadWindowCoverageSeconds = UsbExactRatio(16, 125),
        )

        val args = UsbNativeTransportArgs.from(config)

        assertEquals(2L, args.dataServicePeriodNumerator)
        assertEquals(125L, args.dataServicePeriodDenominator)
        assertEquals(0, args.feedbackEndpointAddress)
        assertEquals(0, args.feedbackExpectedPayloadBytes)
        assertEquals(UsbNativeTransportArgs.RAW_TIME_UNIT_NONE, args.feedbackRawTimeUnit)
        assertEquals(0L, args.feedbackRawToDataScaleNumerator)
        assertEquals(0L, args.feedbackPollPeriodNumerator)
    }
}
