package com.mica.music.media.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLifecycleRecoveryCoordinatorTest {
    @Test
    fun detachStillPublishesSharedPcmAfterNativeSessionAlreadyClosed() {
        var sharedPcmPublications = 0
        val coordinator = UsbLifecycleRecoveryCoordinator()

        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))

        assertTrue(coordinator.publishIfCurrent(detach) { sharedPcmPublications++ })
        assertEquals(1, sharedPcmPublications)
        assertTrue(coordinator.hasInterruptedUsbIntent)
    }

    @Test
    fun attachSupersedesDetachPausedAtPublicationBoundary() {
        val oldAtBoundary = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        var sharedPcmPublications = 0
        val coordinator = UsbLifecycleRecoveryCoordinator(
            beforePublication = { token ->
                if (token.generation == 1L) {
                    oldAtBoundary.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        var oldAccepted = true
        val oldPublication = thread(name = "stale-detach-publication") {
            oldAccepted = coordinator.publishIfCurrent(detach) { sharedPcmPublications++ }
        }
        assertTrue(oldAtBoundary.await(5, TimeUnit.SECONDS))

        val attach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(attach.generation > detach.generation)
        releaseOld.countDown()
        oldPublication.join(5_000L)

        assertFalse(oldAccepted)
        assertEquals(0, sharedPcmPublications)
    }

    @Test
    fun onlyMatchingAttachPermissionCanRestoreUsbOnce() {
        var usbPublications = 0
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        val oldAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(oldAttach, permissionGeneration = 16L))
        val currentAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2004))
        assertTrue(coordinator.bindPermissionRequest(currentAttach, permissionGeneration = 17L))

        assertFalse(
            coordinator.publishGrantedPermission(
                runtimeHandle = UsbAudioRuntimeHandle(2003),
                permissionGeneration = 16L,
            ) { intent -> usbPublications++; intent.resumePlaybackRequested },
        )
        assertTrue(
            coordinator.publishGrantedPermission(
                runtimeHandle = UsbAudioRuntimeHandle(2004),
                permissionGeneration = 17L,
            ) { intent -> usbPublications++; intent.resumePlaybackRequested },
        )
        assertFalse(
            coordinator.publishGrantedPermission(
                runtimeHandle = UsbAudioRuntimeHandle(2004),
                permissionGeneration = 17L,
            ) { intent -> usbPublications++; intent.resumePlaybackRequested },
        )
        assertEquals(1, usbPublications)
        assertFalse(coordinator.hasInterruptedUsbIntent)
    }

    @Test
    fun ignoredOldDetachCannotClearNewerAttach() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        val attach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))

        assertFalse(coordinator.clearIfCurrent(detach))
        assertTrue(coordinator.isCurrent(attach))
    }

    @Test
    fun interruptedPlaybackIntentSurvivesPermissionDelay() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        val attach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(attach, 18L))

        var restoredIntent: UsbInterruptedPlaybackIntent? = null
        assertTrue(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2003), 18L) { intent ->
                restoredIntent = intent
                true
            },
        )

        assertEquals(true, restoredIntent?.resumePlaybackRequested)
        assertEquals("device_detached", restoredIntent?.reason)
    }

    @Test
    fun pausedDetachStillRequiresPermissionButDoesNotRequestPlayback() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, false, "device_detached"))
        val attach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(attach, 19L))

        var restoredIntent: UsbInterruptedPlaybackIntent? = null
        assertTrue(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2003), 19L) { intent ->
                restoredIntent = intent
                true
            },
        )

        assertEquals(false, restoredIntent?.resumePlaybackRequested)
        assertFalse(coordinator.hasInterruptedUsbIntent)
    }

    @Test
    fun deniedPermissionCannotRestoreUntilANewerAttachRequestsPermission() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        val deniedAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(deniedAttach, 20L))

        assertTrue(coordinator.rejectPermission(UsbAudioRuntimeHandle(2003), 20L))
        assertFalse(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2003), 20L) { true },
        )
        assertTrue(coordinator.hasInterruptedUsbIntent)

        val retryAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2004))
        assertTrue(coordinator.bindPermissionRequest(retryAttach, 21L))
        assertTrue(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2004), 21L) { true },
        )
    }

    @Test
    fun deniedPermissionIntentSurvivesAnotherPhysicalDetach() {
        val coordinator = UsbLifecycleRecoveryCoordinator()
        val firstDetach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(firstDetach, true, "device_detached"))
        val deniedAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(deniedAttach, 20L))
        assertTrue(coordinator.rejectPermission(UsbAudioRuntimeHandle(2003), 20L))

        val retryDetach = coordinator.beginDetach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.hasInterruptedPlayback(retryDetach))
        val retryAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2004))
        assertTrue(coordinator.bindPermissionRequest(retryAttach, 21L))

        var resumePlaybackRequested: Boolean? = null
        assertTrue(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2004), 21L) { intent ->
                resumePlaybackRequested = intent.resumePlaybackRequested
                true
            },
        )
        assertEquals(true, resumePlaybackRequested)
    }

    @Test
    fun staleDeniedPermissionPausedAtBoundaryCannotClearNewAttach() {
        val oldAtBoundary = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val coordinator = UsbLifecycleRecoveryCoordinator(
            beforePublication = { token ->
                if (token.generation == 2L) {
                    oldAtBoundary.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        val deniedAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(deniedAttach, 20L))
        var oldAccepted = true
        val oldDenial = thread(name = "stale-permission-denial") {
            oldAccepted = coordinator.rejectPermission(UsbAudioRuntimeHandle(2003), 20L)
        }
        assertTrue(oldAtBoundary.await(5, TimeUnit.SECONDS))

        val retryAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2004))
        assertTrue(coordinator.bindPermissionRequest(retryAttach, 21L))
        releaseOld.countDown()
        oldDenial.join(5_000L)

        assertFalse(oldAccepted)
        assertTrue(
            coordinator.publishGrantedPermission(UsbAudioRuntimeHandle(2004), 21L) { true },
        )
    }

    @Test
    fun newerAttachMakesPermissionPausedAtPublicationBoundaryStale() {
        val oldAtBoundary = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        var usbPublications = 0
        val coordinator = UsbLifecycleRecoveryCoordinator(
            beforePublication = { token ->
                if (token.generation == 2L) {
                    oldAtBoundary.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val detach = coordinator.beginDetach(UsbAudioRuntimeHandle(2002))
        assertTrue(coordinator.rememberInterruptedPlayback(detach, true, "device_detached"))
        val oldAttach = coordinator.beginAttach(UsbAudioRuntimeHandle(2003))
        assertTrue(coordinator.bindPermissionRequest(oldAttach, 16L))
        var oldAccepted = true
        val oldPublication = thread(name = "stale-permission-publication") {
            oldAccepted = coordinator.publishGrantedPermission(
                UsbAudioRuntimeHandle(2003),
                16L,
            ) { intent -> usbPublications++; intent.resumePlaybackRequested }
        }
        assertTrue(oldAtBoundary.await(5, TimeUnit.SECONDS))

        coordinator.beginAttach(UsbAudioRuntimeHandle(2004))
        releaseOld.countDown()
        oldPublication.join(5_000L)

        assertFalse(oldAccepted)
        assertEquals(0, usbPublications)
    }
}
