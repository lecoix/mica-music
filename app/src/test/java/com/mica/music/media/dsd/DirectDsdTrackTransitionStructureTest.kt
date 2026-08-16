package com.mica.music.media.dsd

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdTrackTransitionStructureTest {
    @Test
    fun pausedRetainedBoundaryStopsGapBeforeResetAndReestablishesOnlyAfterBind() {
        val source = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        val retained = source.substringAfter(
            "if (transitionMode == DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN)",
        ).substringBefore(
            "if (transitionMode == DirectDsdTrackTransitionMode.DEFERRED_PAUSED_FRESH_RUNTIME)",
        )

        assertOrdered(
            retained,
            "trackTransition=PAUSE_GAP_STOPPED",
            "trackTransition=OLD_SOURCE_INTAKE_CLOSED",
            "transitionRetainedSourceWithProtocol(activePump, newFacts)",
            "trackTransition=NEW_SOURCE_FACTS_BOUND",
            "trackTransition=PAUSE_GAP_REESTABLISHED_AFTER_BOUNDARY",
        )
    }

    @Test
    fun retainedSessionDrainsFeederBeforeP5SourceReset() {
        val source = source(
            "src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
            "app/src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        )
        val body = source.substringAfter("override fun transitionRetainedSource(")
            .substringBefore("override fun finishEndOfStream()")

        assertOrdered(
            body,
            "feeder.snapshot()",
            "trackTransition=OLD_FEEDER_DRAINED",
            "feeder.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)",
            "trackTransition=SOURCE_RESET_APPLIED",
        )
        assertTrue(body.contains("pendingPartialCanonicalFrameBytes == 0"))
        assertTrue(body.contains("!accountingAfterReset.hasPendingCanonicalHalfFrame"))
    }

    @Test
    fun pausedFreshRuntimeDefersWithoutRuntimeOrSourceAcceptanceUntilStartedAuthority() {
        val source = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        val deferred = source.substringAfter(
            "if (transitionMode == DirectDsdTrackTransitionMode.DEFERRED_PAUSED_FRESH_RUNTIME)",
        ).substringBefore("closePump(\"track-reconfigure\")")

        assertTrue(deferred.contains("trackTransition=DEFERRED_PAUSED_FRESH_RUNTIME"))
        assertTrue(deferred.contains("acceptAllowed=false"))
        assertTrue(deferred.contains("bindPendingFreshDestination(newFormat, requiresStartedAuthority = true"))
        assertTrue(!deferred.contains("sessionFactory.open"))
        assertTrue(!deferred.contains("trackTransition=NEW_SOURCE_ACCEPT_ALLOWED"))
        assertOrdered(
            deferred,
            "trackTransition=PAUSE_GAP_STOPPED",
            "trackTransition=OLD_SOURCE_INTAKE_CLOSED",
            "prepareFreshTrackTransitionWithP2(activePump, DoPCarrierSessionReset.RECONFIGURE)",
            "trackTransition=OLD_DIRECT_RUNTIME_RELEASED",
            "trackTransition=PENDING_DESTINATION_FACTS_BOUND",
            "trackTransition=FRESH_DIRECT_DEFERRED",
        )

        val started = source.substringAfter("override fun onStarted()")
            .substringBefore("override fun onStopped()")
        assertOrdered(
            started,
            "trackTransition=RENDERER_STARTED_AUTHORITY_OBSERVED",
            "trackTransition=DESTINATION_CURRENTNESS_REVALIDATED",
        )
        val arm = source.substringAfter("private fun maybeArmAfterFreshTrackTransition(")
            .substringBefore("override fun onStreamChanged(")
        assertOrdered(
            arm,
            "observeShadowArmAndSourceAccept(active)",
            "trackTransition=FRESH_RUNTIME_ARMED",
            "trackTransition=QUALIFIED_STARTED_PREFILL_ARM_COMPLETE",
            "trackTransition=NEW_SOURCE_ACCEPT_ALLOWED",
        )
    }

    @Test
    fun unifiedPendingReplacementPrecedesInitialPolicyAndNeverAccepts() {
        val source = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        val streamChanged = source.substringAfter("override fun onStreamChanged(")
            .substringBefore("private fun resetTrackSourceCounters()")
        val replacement = streamChanged.substringAfter("pendingFreshDirectDestination?.let { pending ->")
            .substringBefore("if (navigationEpoch != null)")

        assertOrdered(
            replacement,
            "check(!pendingPump.isPlaybackArmed())",
            "closePump(\"track-pending-destination-replaced\")",
            "effectiveNavigationEpoch",
            "bindPendingFreshDestination(",
            "navigationEpoch = effectiveNavigationEpoch",
            "return",
        )
        assertTrue(!replacement.contains("beforeDirectAccept"))
        assertTrue(!replacement.contains("sessionFactory.open"))
        assertTrue(!replacement.contains("NEW_SOURCE_ACCEPT_ALLOWED"))
        assertTrue(
            streamChanged.indexOf("pendingFreshDirectDestination?.let { pending ->") <
                streamChanged.indexOf("DirectDsdTrackTransitionPolicy.decide"),
        )
    }

    @Test
    fun freshSessionPreparationDrainsBeforeCarrierResetAndRuntimeRelease() {
        val sessionSource = source(
            "src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
            "app/src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        )
        val sessionBody = sessionSource.substringAfter("override fun prepareFreshTrackTransition(")
            .substringBefore("override fun finishEndOfStream()")
        assertOrdered(
            sessionBody,
            "feeder.snapshot()",
            "trackTransition=OLD_FEEDER_P5_PENDING_ZERO",
            "feeder.resetCarrier(reason)",
            "trackTransition=CARRIER_RECONFIGURE_RESET_APPLIED",
        )
        assertTrue(sessionBody.contains("pendingPartialCanonicalFrameBytes == 0"))
        assertTrue(sessionBody.contains("!afterReset.hasPendingCanonicalHalfFrame"))

        val rendererSource = source(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
            "app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        )
        val playingFresh = rendererSource.substringAfter(
            "trackTransition=OLD_SOURCE_INTAKE_CLOSED playing=true",
        ).substringBefore("private fun resetTrackSourceCounters()")
        assertOrdered(
            playingFresh,
            "prepareFreshTrackTransitionWithP2(activePump, DoPCarrierSessionReset.RECONFIGURE)",
            "closePump(\"track-reconfigure\")",
            "trackTransition=OLD_DIRECT_RUNTIME_RELEASED",
            "trackTransition=NEW_RATE_FACTS_BOUND",
        )
    }

    @Test
    fun pausedDopToPcmRequiresRequestScopedGrantAndActivatesFromHandleBuffer() {
        val source = source(
            "src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt",
            "app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt",
        )
        assertOrdered(
            source,
            "preparePcmConfigure(",
            "pendingConfiguration = PendingConfiguration(",
            "override fun handleBuffer(",
            "activatePendingConfiguration()",
        )
        val playBody = source.substringAfter("override fun play()")
            .substringBefore("override fun pause()")
        assertTrue(playBody.contains("if (pendingConfiguration != null) return"))
        assertTrue(!playBody.contains("activatePendingConfiguration"))
        val activation = source.substringAfter("private fun activatePendingConfiguration(")
        assertOrdered(
            activation,
            "preparePcmConfigure(",
            "super.configure(",
            "commitPcmConfigure(",
            "pendingConfiguration = null",
        )
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

    private fun source(vararg candidates: String): String =
        candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
}
