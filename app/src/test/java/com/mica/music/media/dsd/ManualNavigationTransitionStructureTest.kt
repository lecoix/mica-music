package com.mica.music.media.dsd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualNavigationTransitionStructureTest {
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
            "bindCurrentMediaIdProvider",
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
