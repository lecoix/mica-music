package com.mica.music.media

import com.mica.music.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcmDeliveryExperimentTest {

    @Test
    fun g31aGlobalFloatSink_isDisabled() {
        assertFalse(PcmDeliveryExperiment.g31NoDspFloatSink)
    }

    @Test
    fun rendererSplit_enabledOnDebugAndPerfBuildTypes() {
        val expected = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"
        assertEquals(expected, PcmDeliveryExperiment.rendererSplit)
    }

    @Test
    fun g31bPerSongSink_deprecated_alwaysDisabled() {
        // G3-1b is deprecated (superseded by R1b renderer split, the terminal choice).
        assertFalse(PcmDeliveryExperiment.g31bPerSongSink)
    }
}
