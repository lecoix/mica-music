package com.mica.music.media.usbhybrid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbHybridDiagnosticsReportTest {
    @Test
    fun reportIncludesNegotiatedFactsAndExplicitlyExcludesSerial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = UsbHybridDiagnosticsReport.build(
            context,
            UsbPlaybackFacts(
                requestEpoch = 3,
                identity = UsbStableIdentity(0x262a, 1, 4, "digest"),
                runtimeHandle = UsbRuntimeHandle(7, "/dev/bus/usb/001/007"),
                telemetry = UsbRealtimeTelemetry(1, 2, 3, 4),
                failure = UsbFailure("WRITE", "stale"),
            ),
        )

        assertTrue(report.contains("descriptorDigest=digest"))
        assertTrue(report.contains("pendingOutputUrbs:3"))
        assertTrue(report.contains("lastError=WRITE:stale"))
        assertTrue(report.contains("serial=not-exported"))
        assertFalse(report.contains("serialNumber="))
    }
}
