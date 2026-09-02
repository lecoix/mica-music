package com.mica.music.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStackArchitectureStructureTest {
    @Test
    fun serviceHasSingleMutablePlaybackStackAuthority() {
        val service = source("com/mica/music/media/MicaMediaService.kt")

        assertTrue(service.contains("private val playbackStackLifecycle = PlaybackStackLifecycleOwner"))
        assertFalse(Regex("private\\s+var\\s+exoPlayer\\b").containsMatchIn(service))
        assertFalse(Regex("private\\s+var\\s+compositePlayer\\b").containsMatchIn(service))
        assertFalse(Regex("\\bexoPlayer\\s*=").containsMatchIn(service))
        assertFalse(Regex("\\bcompositePlayer\\s*=").containsMatchIn(service))
    }

    @Test
    fun usbCoordinatorConsumesPlaybackOwnedHandoffInsteadOfDefiningIt() {
        val lifecycle = source("com/mica/music/media/PlaybackStackLifecycleOwner.kt")
        val usb = source("com/mica/music/media/UsbOutputCoordinator.kt")
        val service = source("com/mica/music/media/MicaMediaService.kt")

        assertTrue(lifecycle.contains("data class PlaybackStackHandoff("))
        assertFalse(usb.contains("data class PlaybackStackHandoff("))
        assertTrue(usb.contains("fun captureHandoff(): PlaybackStackHandoff?"))
        assertTrue(service.contains("playbackStackLifecycle.captureHandoff()"))
    }

    @Test
    fun stackScopedAsyncCallbacksFenceRetiredStackIdentity() {
        val service = source("com/mica/music/media/MicaMediaService.kt")

        assertTrue(service.contains("if (!playbackStackLifecycle.isActive(stack)) return@post"))
        assertTrue(service.contains("if (playbackStackLifecycle.isActive(stack))"))
        assertTrue(service.contains("attachEqualizerSessionListener(stack)"))
    }

    @Test
    fun mediaSessionPlayerPublicationIsOwnedByLifecycleBoundary() {
        val service = source("com/mica/music/media/MicaMediaService.kt")
        val lifecycle = source("com/mica/music/media/PlaybackStackLifecycleOwner.kt")

        assertEquals(1, Regex("mediaSession\\?\\.setPlayer\\(").findAll(service).count())
        val attach = lifecycle.indexOf("attachOwners(stack)")
        val publish = lifecycle.indexOf("publishPlayer(stack.compositePlayer)")
        assertTrue("lifecycle must attach all stack owners before MediaSession publication", attach >= 0)
        assertTrue("lifecycle must own the final player publication", publish > attach)
    }

    private fun source(relativePath: String): String = File(findMainSourceRoot(), relativePath).readText()

    private fun findMainSourceRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            listOf(
                File(current, "app/src/main/java"),
                File(current, "src/main/java"),
            ).firstOrNull { File(it, "com/mica/music/media").isDirectory }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate app/src/main/java from ${System.getProperty("user.dir")}")
    }
}