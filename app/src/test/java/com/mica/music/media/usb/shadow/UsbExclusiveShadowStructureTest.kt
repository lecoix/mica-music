package com.mica.music.media.usb.shadow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbExclusiveShadowStructureTest {
    @Test
    fun mainProtocolHasNoOwnershipFabricationSeam() {
        val main = source("app/src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt")
        val fixture = source("app/src/test/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocolFixture.kt")
        assertFalse(main.contains("installOwnedFamilyForModel"))
        assertTrue(fixture.contains("fun UsbExclusivePlaybackProtocol.installOwnedFamilyForModel("))
        assertTrue(fixture.contains("preparePcmConfigure("))
        assertTrue(fixture.contains("commitPcmConfigure("))
        assertTrue(fixture.contains("prepareDirectStage("))
        assertTrue(fixture.contains("commitDirectStage("))
    }

    @Test
    fun manualAndSeekRawHooksPrecedeLegacyAuthoritiesAndNeverDriveReturnValues() {
        val source = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val manual = source.substringAfter("private fun publishManualNavigation(")
            .substringBefore("private fun resolveTargetPeriodUid")
        assertOrdered(
            manual,
            "playbackStack?.beginManualNavigation(targetMediaId, seam)",
            "manualNavigationTransitionBridge.publish(",
            "playbackStack?.observeLegacyNavigationCorrelation(epoch.requestId)",
        )
        val seek = source.substringAfter("override fun seekTo(positionMs: Long)")
            .substringBefore("override fun seekTo(mediaItemIndex")
        assertOrdered(
            seek,
            "playbackStack?.beginSeek",
            "DirectDsdSeekDiscontinuityCoordinator.publishPlayingSeek",
            "super.seekTo(safePositionMs)",
        )
        assertFalse(source.contains("return playbackStack"))
        val directPlay = source.substringAfter("fun playExoDirect()")
            .substringBefore("fun pauseExoDirect()")
        val directPause = source.substringAfter("fun pauseExoDirect()")
            .substringBefore("override fun setPlayWhenReady")
        assertFalse(directPlay.contains("onPlaybackIntentChanged"))
        assertFalse(directPause.contains("onPlaybackIntentChanged"))
    }

    @Test
    fun applicationCurrentnessRawHooksPrecedeLegacyBridge() {
        val source = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        val currentness = source.substringAfter("fun publishApplicationCurrentness(")
            .substringBefore("exoPlayer.addListener")
        assertOrdered(
            currentness,
            "playbackStack?.observeTimelinePeriod",
            "manualNavigationTransitionBridge.updateApplicationCurrentness(",
        )
        val itemTransition = source.substringAfter("override fun onMediaItemTransition")
            .substringBefore("override fun onTimelineChanged")
        assertOrdered(itemTransition, "playbackStack?.observeApplicationMedia", "publishApplicationCurrentness(")
        val analytics = source.substringAfter("exoPlayer.addAnalyticsListener")
        assertOrdered(
            analytics,
            "playbackStack?.observeCurrentPlayerOccurrence(",
            "manualNavigationTransitionBridge.updateApplicationPlayingOccurrence(",
        )
    }

    @Test
    fun rendererAndPcmConfigureHooksObserveBeforeLegacyGates() {
        val platform = source("app/src/main/java/com/mica/music/media/PeriodAwareMediaCodecAudioRenderer.kt")
        val platformStream = platform.substringAfter("override fun onStreamChanged(")
        assertOrdered(
            platformStream,
            "playbackAdapter?.observeStream(",
            "playbackPeriodProjection.onStreamChanged(mediaPeriodId)",
            "super.onStreamChanged(",
        )

        val factory = source("app/src/main/java/com/mica/music/media/MicaRenderersFactory.kt")
        assertOrdered(
            factory,
            "ffmpegDsdPlaybackAdapter?.observeStream(",
            "dsdPeriodProjection.onStreamChanged(mediaPeriodId)",
        )
        assertOrdered(
            factory,
            "ffmpegPcmPlaybackAdapter?.observeStream(",
            "pcmPeriodProjection.onStreamChanged(mediaPeriodId)",
        )
        val ffmpeg = source("third_party/media3-ffmpeg-decoder/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java")
        assertTrue(ffmpeg.contains("void onStreamChanged(Format[] formats, MediaSource.MediaPeriodId mediaPeriodId);"))
        assertTrue(ffmpeg.contains("streamPeriodObserver.onStreamChanged(formats, mediaPeriodId);"))

        val sink = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val configure = sink.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            configure,
            "manualNavigationTransitionBridge.bindPcmDestination",
            "preparePcmConfigure(",
            "configureWithProtocol(",
        )
        val protocolConfigure = sink.substringAfter("private fun configureWithProtocol(")
        assertOrdered(
            protocolConfigure,
            "super.configure(inputFormat, specifiedBufferSize, outputChannels)",
            "commitPcmConfigure(",
        )
    }

    @Test
    fun directHooksAreObservationOnlyAndPrecedeLegacyLifecycleWhereRequired() {
        val direct = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val stream = direct.substringAfter("override fun onStreamChanged(")
            .substringBefore("override fun onPositionReset")
        assertOrdered(
            stream,
            "playbackAdapter?.observeStream(",
            "manualNavigationTransitionBridge?.observePlaybackStream(mediaPeriodId)",
        )
        val started = direct.substringAfter("override fun onStarted()")
            .substringBefore("override fun onStopped()")
        assertOrdered(
            started,
            "acceptDirectStarted(shadowOccurrence)",
            "transitionCoordinator?.onDirectPlayState(paused = false)",
        )
        val stopped = direct.substringAfter("override fun onStopped()")
            .substringBefore("override fun onPositionReset")
        assertOrdered(
            stopped,
            "playbackAdapter?.observeDirectStopped(shadowOccurrence)",
            "manualNavigationTransitionBridge?.observeDirectRetirementStop()",
        )
        val reset = direct.substringAfter("override fun onPositionReset")
            .substringBefore("override fun onDisabled")
        assertOrdered(
            reset,
            "playbackAdapter?.observeDirectPositionReset(shadowOccurrence, sourcePositionUs)",
            "DirectDsdSeekDiscontinuityCoordinator.consumePositionReset(",
            "closePump(\"position-reset:${'$'}positionUs\")",
        )
        val close = direct.substringAfter("private fun closePump(reason: String)")
            .substringBefore("companion object")
        assertOrdered(
            close,
            "closingPump?.close()",
            "playbackAdapter?.observeDirectRuntimeReleased(",
        )
    }

    @Test
    fun serviceOwnsOneLedgerAndPublishesSemanticIntentBeforeExistingUsbBookkeeping() {
        val service = source("app/src/main/java/com/mica/music/media/MicaMediaService.kt")
        assertTrue(service.contains("private val usbExclusivePlaybackCoordinator = UsbExclusivePlaybackCoordinator()"))
        assertTrue(service.contains("ExoPlaybackStackFactory.build(this, activeOutputPath, usbExclusivePlaybackCoordinator)"))
        assertTrue(service.contains("ExoPlaybackStackFactory.build(this, target, usbExclusivePlaybackCoordinator)"))
        val candidateBuild = service.substringAfter("buildCandidate = { target, _ ->")
            .substringBefore("stageCandidate =")
        assertFalse(candidateBuild.contains("observeSharedPcmOutput"))
        assertFalse(candidateBuild.contains("observeUnavailableOutput"))
        assertTrue(service.contains("candidate.playbackStack?.let(usbExclusivePlaybackCoordinator::publishStack)"))
        val factory = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        assertTrue(factory.contains("OutputTarget.SharedPcm"))
        assertTrue(factory.contains("OutputTarget.Unavailable"))
        assertTrue(factory.contains("playbackCoordinator?.createStack(initialOutputTarget)"))
        val intent = service.substringAfter("private fun installUsbPlaybackIntentObserver")
        assertOrdered(
            intent,
            "usbResumePlaybackRequested = resumePlaybackRequested",
            "playbackStateCoordinator?.onExplicitPlaybackIntent",
        )
        val retire = service.substringAfter("private fun retirePublishedPlaybackStack()")
            .substringBefore("private fun installUsbPlaybackIntentObserver")
        assertOrdered(
            retire,
            "activePlaybackStack?.let(usbExclusivePlaybackCoordinator::retireStack)",
            "previousComposite.abortManualNavigation(\"playback-stack-retire\")",
            "previousExo.playWhenReady = false",
            "previousExo.release()",
        )
        assertTrue(service.contains("snapshot.stageInto(candidate.exoPlayer)"))
        assertTrue(service.contains("snapshot.activate(candidate.exoPlayer, resumePlayback = false)"))
        assertTrue(service.contains("restoreAfterTechnicalQuiesce()"))
        assertTrue(service.contains("candidate.exoPlayer.playWhenReady = resumePlayback"))
        assertFalse(service.contains("candidate.compositePlayer.playWhenReady = snapshot.playWhenReady"))
    }

    @Test
    fun p2GenerationPublisherRemainsFirstAndShadowObserverIsExceptionIsolated() {
        val fanout = source("app/src/main/java/com/mica/music/media/usb/UsbOutputGenerationObserverFanout.kt")
        assertOrdered(
            fanout,
            "publisher.get()(generation)",
            "observers.forEach",
            "observer(generation)",
            "onObserverFailure(generation, error)",
        )
        val ownerSource = source("app/src/main/java/com/mica/music/media/usb/UsbOutputSessionOwner.kt")
        val runtime = ownerSource.substringAfter("internal object UsbOutputRuntime")
        assertTrue(runtime.contains("onGenerationPublished = generationFanout::publish"))
        assertTrue(runtime.contains("generationFanout.installPublisher(publisher)"))
        assertTrue(runtime.contains("generationFanout.installObserver(observer)"))
    }

    @Test
    fun shadowPackageCannotInvokeProductionSideEffectAuthorities() {
        val shadow = source("app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt")
        listOf(
            "super.configure(",
            "armPlayback(",
            "sessionFactory.",
            "UsbOutputRuntime.owner",
            "manualNavigationTransitionBridge",
            "DirectDsdTrackTransitionCoordinator",
            "DirectDsdSeekDiscontinuityCoordinator",
            ".write(",
            ".grant(",
            ".revoke(",
        ).forEach { forbidden ->
            assertFalse("shadow must not call $forbidden", shadow.contains(forbidden))
        }
        assertFalse(shadow.contains("if (coordinator.emit"))
        assertFalse(shadow.contains("return coordinator.emit"))
        assertTrue(shadow.contains("private val diagnosticSink"))
        assertTrue(shadow.contains("observeSafely"))
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
        val candidates = listOf(path, path.removePrefix("app/"), "../$path", "../../$path")
        return candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
    }
}
