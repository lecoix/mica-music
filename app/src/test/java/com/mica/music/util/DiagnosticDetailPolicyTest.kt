package com.mica.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDetailPolicyTest {
    @Test
    fun allDetailedDomainsAreOffByDefault() {
        val config = DiagnosticDetailConfig()
        DiagnosticDetailDomain.entries.forEach { domain ->
            assertFalse(config.isEnabled(domain))
        }
    }

    @Test
    fun masterAndDomainMustBothBeEnabled() {
        val selectedOnly = DiagnosticDetailConfig(libraryScan = true)
        assertFalse(selectedOnly.isEnabled(DiagnosticDetailDomain.LIBRARY_SCAN))

        val enabled = selectedOnly.copy(enabled = true)
        assertTrue(enabled.isEnabled(DiagnosticDetailDomain.LIBRARY_SCAN))
        assertFalse(enabled.isEnabled(DiagnosticDetailDomain.PLAYBACK_MEDIA))
    }

    @Test
    fun representativeCategoriesMapToStableDomains() {
        assertEquals(DiagnosticDetailDomain.LIBRARY_SCAN, diagnosticDetailDomainFor("LibraryAutoSync"))
        assertEquals(DiagnosticDetailDomain.LIBRARY_SCAN, diagnosticDetailDomainFor("RemoteSync"))
        assertEquals(DiagnosticDetailDomain.PLAYBACK_MEDIA, diagnosticDetailDomainFor("QueueSync"))
        assertEquals(DiagnosticDetailDomain.PLAYBACK_MEDIA, diagnosticDetailDomainFor("MediaSession"))
        assertEquals(DiagnosticDetailDomain.AUDIO_PIPELINE, diagnosticDetailDomainFor("Spectrum"))
        assertEquals(DiagnosticDetailDomain.AUDIO_PIPELINE, diagnosticDetailDomainFor("RendererSupportProbe"))
        assertEquals(DiagnosticDetailDomain.USB_DEVICE, diagnosticDetailDomainFor("UsbOutputState"))
        assertEquals(DiagnosticDetailDomain.USB_DEVICE, diagnosticDetailDomainFor("AudioRoute"))
        assertEquals(DiagnosticDetailDomain.UI_RENDERING, diagnosticDetailDomainFor("ParticleCover"))
        assertEquals(DiagnosticDetailDomain.UI_RENDERING, diagnosticDetailDomainFor("WindowTouchTrace"))
    }

    @Test
    fun unknownCategoryRemainsOutsideVerboseGate() {
        assertNull(diagnosticDetailDomainFor("StorageDiagnostics"))
        assertNull(diagnosticDetailDomainFor("App"))
        assertNull(diagnosticDetailDomainFor("PreviousExit"))
    }
}
