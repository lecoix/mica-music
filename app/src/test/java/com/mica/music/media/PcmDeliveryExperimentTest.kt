package com.mica.music.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDeliveryExperimentTest {

    @Test
    fun g31aGlobalFloatSink_isDisabled() {
        assertFalse(PcmDeliveryExperiment.g31NoDspFloatSink)
    }

    @Test
    fun rendererSplit_enabledOnAllBuildTypes() {
        assertTrue(PcmDeliveryExperiment.rendererSplit)
    }

    @Test
    fun g31bPerSongSink_deprecated_alwaysDisabled() {
        // G3-1b is deprecated (superseded by R1b renderer split, the terminal choice).
        assertFalse(PcmDeliveryExperiment.g31bPerSongSink)
    }
}
