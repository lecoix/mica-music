package com.mica.music.media

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MicaMediaServiceDirectDsdTeardownOrderingTest {
    @Test
    fun playbackGenerationCallbackQuiescesDirectGapBeforeOwnerInvalidation() {
        val source = File("src/main/java/com/mica/music/media/MicaMediaService.kt").readText()
        val rebuildWiring = source
            .substringAfter("outputRebuildCoordinator = PlaybackOutputRebuildCoordinator(")
            .substringBefore("capture = {")

        val quiesceCall = rebuildWiring.indexOf(
            "DirectDsdTeardownQuiescenceCoordinator.quiesceBeforeOwnerInvalidation(",
        )
        val quiesceBarrier = rebuildWiring.indexOf("barrier=pre-invalidate-quiesce")
        val invalidateBarrier = rebuildWiring.indexOf("barrier=owner-invalidate")
        val ownerInvalidate = rebuildWiring.indexOf("UsbOutputRuntime.owner.invalidate()")

        assertTrue(quiesceCall >= 0)
        assertTrue(quiesceBarrier > quiesceCall)
        assertTrue(invalidateBarrier > quiesceBarrier)
        assertTrue(ownerInvalidate > invalidateBarrier)
    }
}
