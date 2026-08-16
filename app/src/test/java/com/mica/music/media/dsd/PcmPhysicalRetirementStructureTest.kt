package com.mica.music.media.dsd

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPhysicalRetirementStructureTest {
    @Test
    fun coordinatorCannotFabricatePcmPhysicalOrTailAuthority() {
        val source = source(
            "src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt",
            "app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt",
        )

        assertFalse(source.contains("pcm-adapter-lease-drained"))
        assertFalse(source.contains("FamilyProof.StackReleased"))
        assertTrue(source.contains("preparePcmRetainedRetirement("))
        assertTrue(source.contains("completePcmRetainedRetirement("))
        assertTrue(source.contains("preparePcmFullRelease("))
        assertTrue(source.contains("completePcmFullRelease("))
    }

    @Test
    fun sinkFullReleaseUsesFrozenPermitAndMintsProofOnlyAfterDelegateReturns() {
        val source = sinkSource()
        val teardown = source.substringAfter("private inline fun performPcmFullRelease(")
            .substringBefore("private fun requiredRetiringPcmReleasePermit()")

        assertOrdered(
            teardown,
            "delegateTeardown()",
            "FamilyProof.PcmFamilyReleased(",
            "runtimeIdentity = retirement.source.runtimeIdentity",
            "sourceGeometry = retirement.source.geometry",
            "completePcmFullRelease(retirement, proof)",
        )
        val reset = source.substringAfter("override fun reset()")
            .substringBefore("override fun release()")
        val release = source.substringAfter("override fun release()")
            .substringBefore("private fun activatePendingConfiguration()")
        assertOrdered(reset, "requiredRetiringPcmReleasePermit()", "performPcmFullRelease(")
        assertOrdered(release, "requiredRetiringPcmReleasePermit()", "performPcmFullRelease(")
        assertFalse(reset.contains("playbackPeriodProjection.snapshot()"))
        assertFalse(release.contains("playbackPeriodProjection.snapshot()"))
    }

    @Test
    fun sinkRetainedProofComesFromFirstSuccessorWriteBoundary() {
        val source = sinkSource()
        val retained = source.substringAfter("if (lease == null) {")
            .substringBefore("lease ?: return false")

        assertOrdered(
            retained,
            "preparePcmRetainedRetirement(",
            "FamilyProof.PcmRuntimeRetained(",
            "PcmTailOrderingProof(",
            "completePcmRetainedRetirement(",
            "commitRetainedPcmHandoff(",
        )
        assertTrue(retained.contains("targetGeometry = geometry"))
        assertTrue(retained.contains("sinkBoundarySequence = nextSinkBoundarySequence()"))
    }

    @Test
    fun protocolRetirementBarrierRequiresTypedPcmRuntimeRelease() {
        val source = source(
            "src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt",
            "app/src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt",
        )
        val retiring = source.substringAfter("private fun reevaluateRetiringLocked()")
            .substringBefore("private fun pcmSourceIdentityLocked(")

        assertTrue(source.contains("fun prepareRetiringPcmRuntimeRelease("))
        assertTrue(source.contains("fun completePcmRetirement("))
        assertTrue(source.contains("FamilyProof.PcmFamilyReleased"))
        assertTrue(retiring.contains("retiringPcmRuntimeRelease == null"))
    }

    private fun sinkSource(): String = source(
        "src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt",
        "app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt",
    )

    private fun assertOrdered(body: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = body.indexOf(token)
            assertTrue("missing $token", index >= 0)
            assertTrue("$token out of order", index > previous)
            previous = index
        }
    }

    private fun source(vararg candidates: String): String =
        candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
}
