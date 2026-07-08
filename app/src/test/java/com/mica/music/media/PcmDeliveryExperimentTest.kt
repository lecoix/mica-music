package com.mica.music.media

import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDeliveryExperimentTest {

    @Test
    fun rendererSplit_enabledOnAllBuildTypes() {
        assertTrue(PcmDeliveryExperiment.rendererSplit)
    }
}
