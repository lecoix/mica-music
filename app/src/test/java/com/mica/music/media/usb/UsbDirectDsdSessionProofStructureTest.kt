package com.mica.music.media.usb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectDsdSessionProofStructureTest {
    @Test
    fun ownerDrivenReleaseMintsTypedFullReleaseFactsFromPhysicalConjunction() {
        val source = source(
            "src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
            "app/src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        )
        val release = source.substringAfter("override fun release(lease: UsbOutputCleanupLease, reason: String)")
            .substringBefore("override fun isExactCleanupComplete()")
        assertOrdered(
            release,
            "UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle)",
            "mintedFullReleaseFacts = DirectFullReleaseFacts(",
            "writerJoined = pauseLiveness.isWriterIdle()",
            "pauseWorkerJoined = !pauseSnapshot.workerAlive && pauseSnapshot.workerFailure == null",
            "nativeDestroyed = nativeDestroyed",
            "driversRebound = reconnectErrno == 0 && driversBound",
        )
        assertTrue(source.contains("override fun typedFullReleaseFacts(): DirectFullReleaseFacts? = mintedFullReleaseFacts"))
        assertFalse(release.contains("FamilyProof.DirectFamilyReleased(\""))
    }

    @Test
    fun retainedTransitionExposesP5ZeroAndMarkerContinuityFacts() {
        val source = source(
            "src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
            "app/src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        )
        val body = source.substringAfter("override fun transitionRetainedSource(")
            .substringBefore("override fun prepareFreshTrackTransition(")
        assertOrdered(
            body,
            "feeder.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)",
            "DirectDsdRetainedSourceTransitionResult(",
            "p5PendingPackedZero = true",
            "markerContinuityRetained = sourceReset.markerBeforeReset == sourceReset.markerAfterReset",
        )
    }

    @Test
    fun rendererRedeemsPrefillAndArmBeforeSideEffectsAndObservesTypedProofAfterClose() {
        val source = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        val prefill = source.substringAfter("private fun pumpWithProtocol(")
            .substringBefore("private fun transitionRetainedSourceWithProtocol(")
        assertOrdered(
            prefill,
            "adapter.redeemDirectStage(",
            "directWriteAuthority.withActivationStage(DirectStage.PREFILL)",
            "active.pump()",
        )
        val arm = source.substringAfter("private fun observeShadowArmAndSourceAccept(")
            .substringBefore("private fun maybeArmAfterFreshTrackTransition(")
        assertOrdered(arm, "adapter.redeemDirectStage(", "active.armPlayback()")
        val close = source.substringAfter("private fun closePump(reason: String)")
        assertOrdered(
            close,
            "closingPump?.close()",
            "observeDirectRuntimeReleased(",
        )
        assertFalse(close.contains("FamilyProof.DirectFamilyReleased("))
        assertFalse(close.contains("typedFullReleaseFacts()"))
    }

    @Test
    fun optionBRemovesPublicDirectProofAndSeekBarrierInjection() {
        val protocol = source(
            "src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt",
            "app/src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt",
        )
        assertFalse(protocol.contains("fun publishDirectSeekBarrier"))
        assertTrue(protocol.contains("if (owned is FamilyOwnership.DopOwned) return null"))
        assertTrue(protocol.contains("private fun mintRetiringDirectRuntimeReceipt"))
        assertTrue(protocol.contains("private fun acceptRetiringDirectRuntimeReceipt"))
        assertTrue(protocol.contains("fun completeDirectFamilyReleaseFromEndpoint"))
        assertTrue(protocol.contains("fun completeRetiringDirectFamilyReleaseFromEndpoint"))
        assertTrue(protocol.contains("tryPublishSeekBarrierFromAcceptedRetirementLocked"))
        val renderer = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        assertFalse(renderer.contains("FamilyProof.DirectFamilyReleased("))
        assertFalse(renderer.contains("FamilyProof.DirectRuntimeRetained("))
        val coordinator = source(
            "src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt",
            "app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt",
        )
        assertFalse(coordinator.contains("FamilyProof.DirectFamilyReleased("))
        assertFalse(coordinator.contains("FamilyProof.DirectRuntimeRetained("))
        assertTrue(coordinator.contains("completeDirectFamilyReleaseFromEndpoint("))
        assertTrue(coordinator.contains("completeRetiringDirectFamilyReleaseFromEndpoint("))
    }

    private fun assertOrdered(body: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = body.indexOf(token)
            assertTrue("missing $token", index >= 0)
            assertTrue("$token out of order", index > previous)
            previous = index
        }
    }

    private fun source(vararg paths: String): String =
        paths.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${paths.joinToString()}")
}
