package com.mica.music.media.dsd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualNavigationTransitionStructureTest {
    @Test
    fun bridgeOwnsLogicalCurrentnessAndNeverCallsPlayerGetters() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/ManualNavigationTransitionBridge.kt")
        assertTrue(source.contains("private var currentMediaId: String? = null"))
        assertTrue(source.contains("fun updateCurrentMediaId(mediaId: String?)"))
        assertFalse(source.contains("ExoPlayer"))
        assertFalse(source.contains("Player."))
        assertFalse(source.contains("currentMediaItem"))
        assertFalse(source.contains("currentMediaItemIndex"))
        assertFalse(source.contains("currentMediaIdProvider"))
    }

    @Test
    fun exoStackSharesOneNavigationBridgeAcrossPlayerAndRenderers() {
        val source = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        assertOrdered(
            source,
            "val manualNavigationTransitionBridge = ManualNavigationTransitionBridge()",
            "val renderersFactory = MicaRenderersFactory(",
            "manualNavigationTransitionBridge,",
            "val compositePlayer = MicaCompositePlayer(",
            "manualNavigationTransitionBridge = manualNavigationTransitionBridge",
            "exoPlayer.addListener(object : Player.Listener",
            "manualNavigationTransitionBridge.updateCurrentMediaId(mediaItem?.mediaId)",
        )
    }

    @Test
    fun productNavigationPublishesBeforeIndexedExoSeek() {
        val source = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val body = source.substringAfter("fun startExistingItem(")
            .substringBefore("fun selectExistingWithoutPlayback")
        assertOrdered(
            body,
            "publishManualNavigation",
            "exoPlayer.seekTo(safeIndex, safePositionMs)",
        )
    }

    @Test
    fun qaSelectIndexUsesCanonicalPlayerSeekAndNeverDefaultPositionBypass() {
        val source = source("app/src/main/java/com/mica/music/media/MicaMediaService.kt")
        val body = source.substringAfter("DebugPlaybackControl.SELECT_INDEX ->")
            .substringBefore("DebugPlaybackControl.SEEK_NEAR_END")
        assertTrue(body.contains("player.seekTo(mediaIndex, 0L)"))
        assertFalse(body.contains("seekToDefaultPosition(mediaIndex)"))
    }

    @Test
    fun directRetirementNavigationSuppressesGapBeforeOrdinaryPausePath() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val body = source.substringAfter("override fun onStopped()")
            .substringBefore("override fun onPositionReset")
        assertOrdered(
            body,
            "observeDirectRetirementStop()",
            "navigationPending=true",
            "gapStarted=false",
            "return",
            "onDirectPlayState(paused = true)",
            "startPauseGapLiveness()",
        )
    }

    @Test
    fun freshDirectNavigationCompletesOnlyAfterCurrentnessArmAndCoordinatorAccept() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val body = source.substringAfter("private fun maybeArmAfterFreshTrackTransition(")
            .substringBefore("override fun onStreamChanged(")
        assertOrdered(
            body,
            "isCurrentDestination(requestId, navigationFacts)",
            "active.armPlayback()",
            "transitionCoordinator?.beforeDirectAccept(isPlaying = true)",
            "manualNavigationTransitionBridge?.complete(",
            "pendingFreshDirectDestination = null",
            "trackTransition=NEW_SOURCE_ACCEPT_ALLOWED",
        )
    }

    @Test
    fun directNavigationWaitsForBridgeOwnedCurrentnessInsteadOfFallingThroughInitial() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val streamChanged = source.substringAfter("override fun onStreamChanged(")
            .substringBefore("private fun resetTrackSourceCounters()")
        assertOrdered(
            streamChanged,
            "val navigationEpoch = manualNavigationTransitionBridge?.bindDirectDestination(newFacts)",
            "val navigationSnapshot = manualNavigationTransitionBridge?.snapshot()",
            "if (navigationEpoch == null && navigationSnapshot != null)",
        )
        val waiting = streamChanged.substringAfter("if (navigationEpoch == null && navigationSnapshot != null)")
            .substringBefore("if (navigationEpoch != null)")
        assertOrdered(
            waiting,
            "trackTransition=MANUAL_NAVIGATION_WAIT_CURRENTNESS",
            "return",
        )
        assertTrue(
            streamChanged.indexOf("if (navigationEpoch == null && navigationSnapshot != null)") <
                streamChanged.indexOf("DirectDsdTrackTransitionPolicy.decide"),
        )
        val render = source.substringAfter("override fun render(positionUs: Long, elapsedRealtimeUs: Long)")
            .substringBefore("private fun renderDrainStep")
        assertOrdered(
            render,
            "refreshPendingNavigationBinding()",
            "drainLoop.drain",
        )
    }

    @Test
    fun pcmNavigationCompletesOnlyAfterFamilyAcceptanceAndConfiguration() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val body = source.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            body,
            "bindPcmDestination(inputFormat)",
            "activeFamily == DirectDsdTrackTransportFamily.DOP",
            "beforePcmAccept(",
            "super.configure(",
            "manualNavigationTransitionBridge.complete(",
        )
    }

    @Test
    fun pcmNavigationWaitsForLogicalCurrentnessBeforeFamilyAcceptance() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val configure = source.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            configure,
            "bindPcmDestination(inputFormat)",
            "val navigationSnapshot = manualNavigationTransitionBridge.snapshot()",
            "navigationEpoch == null && navigationSnapshot != null",
            "pendingConfiguration = PendingConfiguration(",
            "return",
            "beforePcmAccept(",
        )
        val activation = source.substringAfter("private fun activatePendingConfiguration(")
        assertOrdered(
            activation,
            "bindPcmDestination(pending.format)",
            "bound.requestId != requestId",
            "beforePcmAccept(isPlaying = pending.navigationRequestedPlaying)",
            "super.configure(",
            "manualNavigationTransitionBridge.complete(",
        )
    }

    @Test
    fun stackRetirementAndServiceDestroyAbortExternalNavigation() {
        val source = source("app/src/main/java/com/mica/music/media/MicaMediaService.kt")
        assertTrue(source.contains("compositePlayer?.abortManualNavigation(\"service-destroy\")"))
        val retire = source.substringAfter("private fun retirePublishedPlaybackStack()")
            .substringBefore("private fun installUsbPlaybackIntentObserver")
        assertOrdered(
            retire,
            "barrier=retire-start",
            "previousComposite.abortManualNavigation(\"playback-stack-retire\")",
            "previousExo.release()",
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

    private fun source(path: String): String {
        val candidates = listOf(path, path.removePrefix("app/"))
        return candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
    }
}
