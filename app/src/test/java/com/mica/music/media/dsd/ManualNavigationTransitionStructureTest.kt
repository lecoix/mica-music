package com.mica.music.media.dsd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualNavigationTransitionStructureTest {
    @Test
    fun bridgeOwnsLogicalAndPlaybackCurrentnessWithoutPlayerGetters() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/ManualNavigationTransitionBridge.kt")
        assertTrue(source.contains("private var currentMediaId: String? = null"))
        assertTrue(source.contains("private var lastObservedPlaybackIdentity: ManualNavigationPlaybackIdentity? = null"))
        assertTrue(source.contains("fun updateApplicationCurrentness(mediaId: String?, targetPeriodUid: Any?)"))
        assertTrue(source.contains("val expectedTargetPeriodUid: Any? = null"))
        assertTrue(source.contains("val targetPlaybackIdentity: ManualNavigationPlaybackIdentity? = null"))
        assertTrue(source.contains("windowSequenceNumber"))
        assertFalse(source.contains("ExoPlayer"))
        assertFalse(source.contains("Player."))
        assertFalse(source.contains("currentMediaItem"))
        assertFalse(source.contains("currentMediaItemIndex"))
        assertFalse(source.contains("currentMediaIdProvider"))
    }

    @Test
    fun applicationListenerPublishesResolvedSinglePeriodCurrentness() {
        val source = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        assertOrdered(
            source,
            "val manualNavigationTransitionBridge = ManualNavigationTransitionBridge()",
            "val renderersFactory = MicaRenderersFactory(",
            "manualNavigationTransitionBridge,",
            "val compositePlayer = MicaCompositePlayer(",
            "manualNavigationTransitionBridge = manualNavigationTransitionBridge",
            "fun publishApplicationCurrentness(timeline: Timeline, mediaItem: MediaItem?)",
            "ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(",
            "manualNavigationTransitionBridge.updateApplicationCurrentness(mediaId, targetPeriodUid)",
            "exoPlayer.addListener(object : Player.Listener",
        )
        assertTrue(source.contains("override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)"))
        assertTrue(source.contains("override fun onTimelineChanged(timeline: Timeline, reason: Int)"))
    }

    @Test
    fun existingQueueNavigationPublishesTargetPeriodBeforeIndexedSeek() {
        val source = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val body = source.substringAfter("fun startExistingItem(")
            .substringBefore("fun selectExistingWithoutPlayback")
        assertOrdered(
            body,
            "publishManualNavigation(",
            "expectedTargetPeriodUid = ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(",
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
    fun directStreamGenerationObservedBeforeNavigationBinding() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val body = source.substringAfter("override fun onStreamChanged(")
            .substringBefore("transitionCoordinator?.completePcmReleaseForDirectHandoff()")
        assertOrdered(
            body,
            "observePlaybackStream(mediaPeriodId)",
            "bindDirectDestination(newFacts, it)",
            "val navigationSnapshot = manualNavigationTransitionBridge?.snapshot()",
            "if (navigationEpoch == null && navigationSnapshot != null && playbackIdentity != null)",
        )
        val waiting = body.substringAfter("if (navigationEpoch == null && navigationSnapshot != null && playbackIdentity != null)")
            .substringBefore("if (navigationEpoch != null)")
        assertOrdered(waiting, "MANUAL_NAVIGATION_WAIT_PLAYBACK_IDENTITY", "return")
    }

    @Test
    fun freshDirectNavigationCompletesOnlyAfterGenerationCurrentnessArmAndCoordinatorAccept() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val body = source.substringAfter("private fun maybeArmAfterFreshTrackTransition(")
            .substringBefore("override fun onStreamChanged(")
        assertOrdered(
            body,
            "val playbackIdentity = checkNotNull(pending.navigationPlaybackIdentity)",
            "isCurrentDestination(",
            "active.armPlayback()",
            "transitionCoordinator?.beforeDirectAccept(isPlaying = true)",
            "manualNavigationTransitionBridge?.complete(",
            "pendingFreshDirectDestination = null",
            "trackTransition=NEW_SOURCE_ACCEPT_ALLOWED",
        )
    }

    @Test
    fun directRenderWaitsForExactPlaybackGenerationBeforeSourceDrain() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val render = source.substringAfter("override fun render(positionUs: Long, elapsedRealtimeUs: Long)")
            .substringBefore("private fun renderDrainStep")
        assertOrdered(
            render,
            "refreshPendingNavigationBinding()",
            "drainLoop.drain",
        )
        val refresh = source.substringAfter("private fun refreshPendingNavigationBinding()")
            .substringBefore("private fun openPumpIfNeeded")
        assertOrdered(
            refresh,
            "val playbackIdentity = pending.navigationPlaybackIdentity ?: return false",
            "bridge.isCurrentDestination(requestId, navigationFacts, playbackIdentity)",
            "bridge.bindDirectDestination(facts, playbackIdentity)",
        )
    }

    @Test
    fun pcmConfigureFreezesRendererProjectedGenerationBeforeAcceptance() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val body = source.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            body,
            "val playbackIdentity = playbackPeriodProjection.snapshot()",
            "bindPcmDestination(inputFormat, playbackIdentity)",
            "val navigationSnapshot = manualNavigationTransitionBridge.snapshot()",
            "pendingConfiguration = PendingConfiguration(",
            "playbackIdentity = playbackIdentity",
            "return",
            "beforePcmAccept(",
            "super.configure(",
            "manualNavigationTransitionBridge.complete(",
        )
        val activation = source.substringAfter("private fun activatePendingConfiguration(")
        assertOrdered(
            activation,
            "bindPcmDestination(",
            "pending.playbackIdentity",
            "bound.requestId != requestId",
            "beforePcmAccept(isPlaying = pending.navigationRequestedPlaying)",
            "super.configure(",
            "manualNavigationTransitionBridge.complete(",
        )
    }

    @Test
    fun ffmpegAndPlatformRenderersProjectPeriodBeforeSinkCanConfigure() {
        val ffmpeg = source("third_party/media3-ffmpeg-decoder/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java")
        val ffmpegStream = ffmpeg.substringAfter("protected void onStreamChanged(")
            .substringBefore("protected @C.FormatSupport int supportsFormatInternal")
        assertOrdered(
            ffmpegStream,
            "streamPeriodObserver.onStreamChanged(mediaPeriodId)",
            "super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)",
        )

        val platform = source("app/src/main/java/com/mica/music/media/PeriodAwareMediaCodecAudioRenderer.kt")
        val platformStream = platform.substringAfter("override fun onStreamChanged(")
            .substringBefore("override fun onDisabled()")
        assertOrdered(
            platformStream,
            "playbackPeriodProjection.onStreamChanged(mediaPeriodId)",
            "super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)",
        )
    }

    @Test
    fun rendererFactoryKeepsPeriodProjectionPerRendererAndReplacesPlatformInPlace() {
        val source = source("app/src/main/java/com/mica/music/media/MicaRenderersFactory.kt")
        assertTrue(source.contains("val dsdPeriodProjection = ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge)"))
        assertTrue(source.contains("val pcmPeriodProjection = ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge)"))
        assertTrue(source.contains("FfmpegAudioRenderer.StreamPeriodObserver(dsdPeriodProjection::onStreamChanged)"))
        assertTrue(source.contains("FfmpegAudioRenderer.StreamPeriodObserver(pcmPeriodProjection::onStreamChanged)"))
        val replace = source.substringAfter("private fun replacePlatformAudioRenderer(")
            .substringBefore("private fun buildUnifiedFixedChain")
        assertOrdered(
            replace,
            "out.indexOfFirst",
            "out[index] = PeriodAwareMediaCodecAudioRenderer(",
            "platformPlaybackPeriodProjection",
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
        val candidates = listOf(path, path.removePrefix("app/"), "../$path")
        return candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
    }
}
