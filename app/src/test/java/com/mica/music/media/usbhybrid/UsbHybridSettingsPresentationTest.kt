package com.mica.music.media.usbhybrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mica.music.data.preferences.UsbHybridOutputMode

class UsbHybridSettingsPresentationTest {
    @Test
    fun requestedModeIsNotPresentedAsActiveWithoutOwnerEvidence() {
        val lines = UsbHybridSettingsPresentation.lines(
            UsbPlaybackFacts(
                requestEpoch = 8,
                requestedMode = UsbExclusiveMode.USB_EXACT_PCM,
                permission = PermissionState.GRANTED,
            ),
        )

        assertTrue(lines.any { it.contains("未激活") })
        assertFalse(lines.any { it.contains("ACTIVE") })
    }

    @Test
    fun exactnessDimensionsRemainSeparate() {
        val lines = UsbHybridSettingsPresentation.lines(
            UsbPlaybackFacts(
                requestEpoch = 9,
                requestedMode = UsbExclusiveMode.USB_EXACT_PCM,
                activeMode = UsbExclusiveMode.USB_EXACT_PCM,
                sessionId = 41,
                permission = PermissionState.GRANTED,
                claimed = true,
                exclusive = true,
                transportExact = true,
                signalExact = false,
                sourceEncoding = 4,
                usbBitResolution = 32,
                sampleRate = 96_000,
                channels = 2,
            ),
        )

        assertTrue(lines.any { it == "独占：是" })
        assertTrue(lines.any { it == "传输保持：是" })
        assertTrue(lines.any { it == "信号保持：否" })
    }

    @Test
    fun nativeModeAlwaysDisclosesExperimentalQualification() {
        val lines = UsbHybridSettingsPresentation.lines(
            UsbPlaybackFacts(requestedMode = UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL),
        )

        assertTrue(lines.any { it.contains("实验") })
        assertTrue(lines.any { it.contains("尚未重新资格化") })
    }

    @Test
    fun selectedPreferenceIsNotPresentedAsActiveWithoutOwnerFacts() {
        val summary = UsbHybridSettingsPresentation.entrySummary(
            facts = UsbPlaybackFacts(),
            selectedMode = UsbHybridOutputMode.ExactPcm,
        )

        assertTrue(summary.contains("未激活"))
        assertFalse(summary.contains("ACTIVE"))
    }

    @Test
    fun activeSummaryUsesNegotiatedOwnerFormat() {
        val facts = UsbPlaybackFacts(
            requestedMode = UsbExclusiveMode.USB_DOP,
            activeMode = UsbExclusiveMode.USB_DOP,
            streamFormat = "DoP DSD64",
            sampleRate = 2_822_400,
            usbBitResolution = 24,
            channels = 2,
        )

        assertTrue(
            UsbHybridSettingsPresentation.entrySummary(facts, UsbHybridOutputMode.Dop)
                .contains("ACTIVE · USB DoP · DoP DSD64"),
        )
        assertTrue(UsbHybridSettingsPresentation.rateLabel(facts) == "DSD64")
    }

    @Test
    fun transportHealthDoesNotInventTelemetry() {
        assertTrue(
            UsbHybridSettingsPresentation.transportHealthLabel(
                UsbPlaybackFacts(activeMode = UsbExclusiveMode.USB_EXACT_PCM),
            ) == "活动",
        )
    }
}
