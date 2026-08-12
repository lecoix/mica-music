package com.mica.music.ui.screens.settings

import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.UsbOutputFailure
import com.mica.music.media.usb.UsbOutputPhase
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSettingsPresentationTest {
    @Test
    fun disabledUsesSystemOutputCopy() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = false,
            deviceAvailable = false,
            devicePermission = UsbPermissionState.UNKNOWN,
            facts = PlaybackOutputFacts(),
        )

        assertEquals(UsbExclusiveSettingsState.DISABLED, presentation.state)
        assertEquals("USB 独占已关闭", presentation.title)
        assertTrue(presentation.subtitle.contains("Android 系统音频"))
    }

    @Test
    fun deniedPermissionOffersRetryWithoutClearingPlaybackIntent() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = true,
            devicePermission = UsbPermissionState.DENIED,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.FAILED,
                attached = true,
                permission = UsbPermissionState.DENIED,
                failure = UsbOutputFailure("permission", "denied"),
            ),
        )

        assertEquals(UsbExclusiveSettingsState.PERMISSION_REQUIRED, presentation.state)
        assertTrue(presentation.subtitle.contains("SharedPcm"))
        assertEquals(UsbExclusiveSettingsAction.REQUEST_PERMISSION, presentation.action)
    }

    @Test
    fun detachedStateExplainsAutomaticIntentRecovery() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = false,
            devicePermission = UsbPermissionState.UNKNOWN,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.FAILED,
                attached = false,
                failure = UsbOutputFailure("detach", "detached"),
            ),
        )

        assertEquals(UsbExclusiveSettingsState.SHARED_PCM_FALLBACK, presentation.state)
        assertTrue(presentation.subtitle.contains("恢复之前的播放意图"))
        assertNull(presentation.action)
    }

    @Test
    fun exhaustedRecoveryExplainsAutomaticSharedFallbackAndClearedIntent() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = false,
            deviceAvailable = true,
            devicePermission = UsbPermissionState.GRANTED,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.FAILED,
                failure = UsbOutputFailure(
                    stage = "recovery-exhausted",
                    message = "failed",
                    fallbackToSharedPcm = true,
                ),
            ),
        )

        assertEquals(UsbExclusiveSettingsState.SHARED_PCM_FALLBACK, presentation.state)
        assertTrue(presentation.subtitle.contains("SharedPcm"))
        assertNull(presentation.action)
    }

    @Test
    fun permissionRequestExplainsThatSystemConfirmationIsRequired() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = true,
            devicePermission = UsbPermissionState.REQUESTED,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.REQUESTED,
                attached = true,
                permission = UsbPermissionState.REQUESTED,
            ),
        )

        assertEquals(UsbExclusiveSettingsState.PERMISSION_PENDING, presentation.state)
        assertTrue(presentation.subtitle.contains("Android 系统弹窗"))
        assertNull(presentation.action)
    }

    @Test
    fun grantedOutputRequestIsShownAsConnecting() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = true,
            devicePermission = UsbPermissionState.GRANTED,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.REQUESTED,
                attached = true,
                permission = UsbPermissionState.GRANTED,
            ),
        )

        assertEquals(UsbExclusiveSettingsState.CONNECTING, presentation.state)
        assertEquals("正在连接 SK02", presentation.title)
    }

    @Test
    fun enabledWithoutDeviceWaitsWithoutOfferingPermissionAction() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = false,
            devicePermission = UsbPermissionState.UNKNOWN,
            facts = PlaybackOutputFacts(),
        )

        assertEquals(UsbExclusiveSettingsState.WAITING_FOR_DEVICE, presentation.state)
        assertTrue(presentation.subtitle.contains("Fosi Audio SK02"))
        assertNull(presentation.action)
    }

    @Test
    fun activeStateShowsNegotiatedFormatAndExactSignalFact() {
        val presentation = presentUsbExclusiveSettings(
            intentEnabled = true,
            deviceAvailable = true,
            devicePermission = UsbPermissionState.GRANTED,
            facts = PlaybackOutputFacts(
                phase = UsbOutputPhase.ACTIVE,
                attached = true,
                permission = UsbPermissionState.GRANTED,
                exclusive = true,
                signalExact = true,
                negotiatedFormat = UsbPcmFormat(
                    sampleRateHz = 96_000,
                    channelCount = 2,
                    encoding = UsbPcmEncoding.PCM_24_PACKED,
                ),
            ),
        )

        assertEquals(UsbExclusiveSettingsState.ACTIVE, presentation.state)
        assertTrue(presentation.subtitle.contains("96 kHz · 24-bit · 2 声道"))
        assertTrue(presentation.subtitle.contains("信号保持原样"))
    }
}
