package com.mica.music.media.usb.shadow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbExclusiveShadowStructureTest {

    @Test
    fun technicalIntentRoutingUsesFenceAndRuntimeObservationNeverPublishesSemanticIntent() {
        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val flush = composite.substringAfter("fun flushPlaybackPipeline(positionMs: Long)")
            .substringBefore("fun playbackQueueSnapshot")
        assertOrdered(
            flush,
            "playbackStack.captureTechnicalIntentFence()",
            "setTechnicalExecutionPlaying(false)",
            "exoPlayer.stop()",
            "exoPlayer.seekTo(positionMs.coerceAtLeast(0L))",
            "exoPlayer.prepare()",
            "playbackStack.restoreAfterTechnicalQuiesce(intentFence)",
            "setTechnicalExecutionPlaying(latestIntent?.desired == PlaybackIntent.PLAY)",
        )
        assertFalse(flush.contains("resumePlayback"))
        assertFalse(flush.contains("playWhenReady"))

        val technicalHelper = composite.substringAfter("private fun setTechnicalExecutionPlaying")
            .substringBefore("private fun prepareQueueMutation")
        assertTrue(technicalHelper.contains("exoPlayer.playWhenReady = playing"))
        assertFalse(technicalHelper.contains("publishProtocolIntent"))
        assertFalse(technicalHelper.contains("onPlaybackIntentChanged"))

        val state = source("app/src/main/java/com/mica/music/media/ServicePlaybackStateCoordinator.kt")
        val runtimeObservation = state.substringAfter("override fun onPlayWhenReadyChanged")
            .substringBefore("override fun onRepeatModeChanged")
        assertTrue(runtimeObservation.contains("persistCursor(force = true)"))
        assertFalse(runtimeObservation.contains("onExplicitPlaybackIntent"))

        val service = source("app/src/main/java/com/mica/music/media/MicaMediaService.kt")
        val serviceFlush = service.substringAfter("private fun flushAudioPipeline(reason: String)")
            .substringBefore("private fun installAudioPipelineCoordinator")
        assertTrue(serviceFlush.contains("player.flushPlaybackPipeline(positionMs)"))
        assertFalse(serviceFlush.contains("shouldResume"))
        assertFalse(serviceFlush.contains("player.playWhenReady"))

        val engine = source("app/src/main/java/com/mica/music/media/ServicePlaybackEngineCoordinator.kt")
        assertTrue(engine.contains("player.dispatchSemanticPlay()"))
        assertTrue(engine.contains("player.dispatchSemanticPause()"))
        assertFalse(engine.contains("playExoDirect"))
        assertFalse(engine.contains("pauseExoDirect"))
    }
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
            "playbackStack.beginManualNavigation(",
            "manualNavigationTransitionBridge.publish(",
            "playbackStack.observeLegacyNavigationCorrelation(epoch.requestId)",
        )
        val seek = source.substringAfter("override fun seekTo(positionMs: Long)")
            .substringBefore("override fun seekTo(mediaItemIndex")
        assertOrdered(
            seek,
            "playbackStack.beginSeek",
            "DirectDsdSeekDiscontinuityCoordinator.publishPlayingSeek",
            "super.seekTo(safePositionMs)",
        )
        assertFalse(source.contains("return playbackStack"))
        val directPlay = source.substringAfter("fun dispatchSemanticPlay()")
            .substringBefore("fun dispatchSemanticPause()")
        val directPause = source.substringAfter("fun dispatchSemanticPause()")
            .substringBefore("override fun setPlayWhenReady")
        assertOrdered(directPlay, "publishProtocolIntent(true)", "onPlaybackIntentChanged?.invoke(true)", "exoPlayer.play()")
        assertOrdered(directPause, "publishProtocolIntent(false)", "onPlaybackIntentChanged?.invoke(false)", "exoPlayer.pause()")
    }

    @Test
    fun applicationCurrentnessRawHooksPrecedeLegacyBridge() {
        val source = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        val currentness = source.substringAfter("fun publishApplicationCurrentness(")
            .substringBefore("fun topologyFacts")
        assertOrdered(
            currentness,
            "producerToken?.let { playbackStack.observeApplicationMedia(mediaId, windowIndex, it) }",
            "ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(",
            "manualNavigationTransitionBridge.updateApplicationCurrentness(",
        )
        assertFalse(currentness.contains("topologyProvenance.resolve("))

        val itemTransition = source.substringAfter("override fun onMediaItemTransition")
            .substringBefore("override fun onTimelineChanged")
        assertOrdered(
            itemTransition,
            "topologyProvenance.producerTokenOf(mediaItem)",
            "invalidatePlayingOccurrence = true",
        )

        val timeline = source.substringAfter("override fun onTimelineChanged")
            .substringBefore("exoPlayer.addAnalyticsListener")
        assertOrdered(
            timeline,
            "val producerToken = topologyProvenance.producerTokenOf(timeline)",
            "playbackStack.observeTimelineSnapshot(topologyFacts(timeline), reason, producerToken)",
            "val callbackMediaItem",
            "publishApplicationCurrentness(timeline, callbackMediaItem, producerToken)",
        )
        assertFalse(timeline.contains("advancePlaybackTopology"))
        assertFalse(timeline.contains("topologyProvenance.resolve("))

        val analytics = source.substringAfter("exoPlayer.addAnalyticsListener")
        assertOrdered(
            analytics,
            "eventTime.currentWindowIndex",
            "eventTimeMediaId(eventTime.currentTimeline, it)",
            "topologyProvenance.producerTokenOf(eventTime.currentTimeline)",
            "playbackStack.observeEventTimeCurrent(",
            "manualNavigationTransitionBridge.updateApplicationPlayingOccurrence(",
        )
        assertFalse(analytics.contains("player.currentMediaItem"))
        assertFalse(analytics.contains("topologyProvenance.resolve("))
    }

    @Test
    fun rendererAndPcmConfigureHooksObserveBeforeLegacyGates() {
        val platform = source("app/src/main/java/com/mica/music/media/PeriodAwareMediaCodecAudioRenderer.kt")
        val platformStream = platform.substringAfter("override fun onStreamChanged(")
        assertOrdered(
            platformStream,
            "playbackAdapter.observeStream(",
            "producerHandle = getStream().producerHandle()",
            "playbackPeriodProjection.onStreamChanged(mediaPeriodId)",
            "super.onStreamChanged(",
        )

        val factory = source("app/src/main/java/com/mica/music/media/MicaRenderersFactory.kt")
        val ffmpegDsdObserver = factory.substringAfter("ffmpegDsdPlaybackAdapter.observeStream(")
            .substringBefore("dsdPeriodProjection.onStreamChanged(mediaPeriodId)")
        assertTrue(ffmpegDsdObserver.contains("producerHandle = sampleStream.producerHandle()"))
        assertTrue(factory.contains("dsdPeriodProjection.onStreamChanged(mediaPeriodId)"))
        val ffmpegPcmObserver = factory.substringAfter("ffmpegPcmPlaybackAdapter.observeStream(")
            .substringBefore("pcmPeriodProjection.onStreamChanged(mediaPeriodId)")
        assertTrue(ffmpegPcmObserver.contains("producerHandle = sampleStream.producerHandle()"))
        assertTrue(factory.contains("pcmPeriodProjection.onStreamChanged(mediaPeriodId)"))
        val ffmpeg = source("third_party/media3-ffmpeg-decoder/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java")
        assertTrue(ffmpeg.contains("@Nullable SampleStream stream"))
        assertTrue(ffmpeg.contains("streamPeriodObserver.onStreamChanged(formats, mediaPeriodId, getStream());"))

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
            "playbackAdapter.observeStream(",
            "producerHandle = getStream().producerHandle()",
            "manualNavigationTransitionBridge.observePlaybackStream(mediaPeriodId)",
        )
        val started = direct.substringAfter("override fun onStarted()")
            .substringBefore("override fun onStopped()")
        assertTrue(started.contains("playbackAdapter.acceptDirectStarted(shadowOccurrence)"))
        assertFalse(direct.contains("transitionCoordinator?.onDirectPlayState"))
        val stopped = direct.substringAfter("override fun onStopped()")
            .substringBefore("override fun onPositionReset")
        assertOrdered(
            stopped,
            "playbackAdapter.observeDirectStopped(shadowOccurrence)",
            "manualNavigationTransitionBridge.observeDirectRetirementStop()",
        )
        val reset = direct.substringAfter("override fun onPositionReset")
            .substringBefore("override fun onDisabled")
        assertOrdered(
            reset,
            "playbackAdapter.observeDirectPositionReset(shadowOccurrence, sourcePositionUs)",
            "DirectDsdSeekDiscontinuityCoordinator.consumePositionReset(",
            "closePump(\"position-reset:${'$'}positionUs\")",
        )
        val close = direct.substringAfter("private fun closePump(reason: String)")
            .substringBefore("companion object")
        assertOrdered(
            close,
            "closingPump?.close()",
            "playbackAdapter.observeDirectRuntimeReleased(",
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
        assertTrue(service.contains("usbExclusivePlaybackCoordinator.publishStack(candidate.playbackStack)"))
        val factory = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        assertTrue(factory.contains("OutputTarget.SharedPcm"))
        assertTrue(factory.contains("OutputTarget.Unavailable"))
        assertTrue(factory.contains("playbackCoordinator.createStack(initialOutputTarget)"))
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
            "usbExclusivePlaybackCoordinator.retireStack(retiringStack)",
            "error(\"playback stack retirement refused\")",
            "previousComposite.abortManualNavigation(\"playback-stack-retire\")",
            "previousExo.playWhenReady = false",
            "previousExo.release()",
            "hasTerminalOldRuntimeProof()",
            "error(\"old-stack retirement lacked terminal proof\")",
        )
        assertTrue(service.contains("snapshot.stageInto(candidate.exoPlayer)"))
        assertTrue(service.contains("snapshot.activate(candidate.exoPlayer, resumePlayback = false)"))
        assertTrue(service.contains("restoreAfterTechnicalQuiesce()"))
        assertTrue(service.contains("candidate.exoPlayer.playWhenReady = resumePlayback"))
        assertFalse(service.contains("candidate.compositePlayer.playWhenReady = snapshot.playWhenReady"))
    }

    @Test
    fun applicationTopologyTransactionSurroundsCanonicalDispatchAndTimelineCannotAdvanceIt() {
        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val setItems = composite.substringAfter("override fun setMediaItems(")
            .substringBefore("override fun setMediaItem(mediaItem: MediaItem)")
        assertOrdered(
            setItems,
            "reserveTopologyMutation(",
            "\"set-media-items\"",
            "topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)",
            "prepareTopologyProvenance(reservation, taggedItems)",
            "prepareQueueMutation(",
            "super.setMediaItems(taggedItems, startIndex, startPositionMs)",
            "commitTopologyMutation(reservation)",
        )
        assertTrue(setItems.contains("markTopologyDispatchUncertain(reservation, \"set-media-items-exception\")"))
        assertFalse(setItems.contains("abortTopologyMutation(reservation, \"exo-dispatch-error\")"))

        val queuePrepare = composite.substringAfter("private fun prepareQueueMutation(")
            .substringBefore("private fun currentQueueItems")
        assertTrue(queuePrepare.contains("topologyReservation?.let(playbackStack::stageTopologyQueueClear)"))
        assertTrue(queuePrepare.contains("abortTopologyMutation(it, \"queue-mutation-prepare-error\")"))

        val commit = composite.substringAfter("private fun commitTopologyMutation(")
            .substringBefore("private fun abortTopologyMutation(")
        assertOrdered(
            commit,
            "topologyProvenance.canCommit(reservation)",
            "playbackStack.markPlaybackTopologyDispatchSucceeded(reservation)",
            "playbackStack.commitPlaybackTopologyMutation(reservation)",
            "topologyProvenance.commit(reservation)",
        )
        assertFalse(commit.contains("beginQueueClear("))
        assertFalse(commit.contains("beginManualMutationUnbound("))

        val replace = composite.substringAfter("override fun replaceMediaItems(")
            .substringBefore("fun startExoPlayback")
        assertOrdered(
            replace,
            "topologyProvenance.queuePlaybackSourceEquivalent(current, expected)",
            "topologyProvenance.preserveProducerTag(current[index], expected[index])",
            "dispatchCanonicalTopologyReplacement(",
            "seam = \"replace-media-items\"",
        )
        assertTrue(replace.contains("queueRevision++\n            return\n        }"))

        val canonicalHelper = composite.substringAfter("private fun dispatchCanonicalTopologyReplacement(")
            .substringBefore("private fun reserveTopologyMutation(")
        assertOrdered(
            canonicalHelper,
            "reserveTopologyMutation(seam, targetMediaId, queueClear)",
            "topologyProvenance.tagForProducer(expectedItems, reservation.producerToken)",
            "prepareTopologyProvenance(reservation, taggedItems)",
            "super.setMediaItems(",
            "commitTopologyMutation(reservation)",
        )
        listOf("add-media-items", "move-media-items", "remove-media-items", "replace-media-items").forEach { seam ->
            assertTrue("missing canonical full-queue seam $seam", composite.contains("seam = \"$seam\""))
        }

        val start = composite.substringAfter("fun startExoPlayback(")
            .substringBefore("fun startExistingItem")
        assertOrdered(
            start,
            "topologyProvenance.queuePlaybackSourceEquivalent(currentItems, mediaItems)",
            "startExistingItem(safeIndex, startPositionMs, playWhenReady)",
            "reserveTopologyMutation(",
            "\"start-exo-playback\"",
            "topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)",
            "prepareTopologyProvenance(reservation, taggedItems)",
            "exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))",
            "commitTopologyMutation(reservation)",
        )

        val stack = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        val timeline = stack.substringAfter("override fun onTimelineChanged")
            .substringBefore("exoPlayer.addAnalyticsListener")
        assertTrue(timeline.contains("playbackStack.observeTimelineSnapshot(topologyFacts(timeline), reason, producerToken)"))
        assertFalse(timeline.contains("advancePlaybackTopology"))
        assertFalse(timeline.contains("topologyProvenance.resolve("))
    }

    @Test
    fun canonicalTopologySeamsDelegateAndFenceBeforeFullQueueDispatch() {
        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")

        val addSingle = composite.substringAfter("override fun addMediaItem(index: Int, mediaItem: MediaItem)")
            .substringBefore("override fun moveMediaItem")
        assertTrue(addSingle.contains("addMediaItems(index, listOf(mediaItem))"))
        val moveSingle = composite.substringAfter("override fun moveMediaItem(currentIndex: Int, newIndex: Int)")
            .substringBefore("override fun moveMediaItems")
        assertTrue(moveSingle.contains("moveMediaItems(currentIndex, currentIndex + 1, newIndex)"))
        val removeSingle = composite.substringAfter("override fun removeMediaItem(index: Int)")
            .substringBefore("override fun removeMediaItems")
        assertTrue(removeSingle.contains("removeMediaItems(index, index + 1)"))
        val replaceSingle = composite.substringAfter("override fun replaceMediaItem(index: Int, mediaItem: MediaItem)")
            .substringBefore("override fun replaceMediaItems")
        assertTrue(replaceSingle.contains("replaceMediaItems(index, index + 1, listOf(mediaItem))"))

        val addBulk = composite.substringAfter("override fun addMediaItems(index: Int, mediaItems: List<MediaItem>)")
            .substringBefore("override fun addMediaItem(index: Int")
        assertOrdered(
            addBulk,
            "require(index >= 0)",
            "val insertionIndex = index.coerceAtMost(current.size)",
            "dispatchCanonicalTopologyReplacement(",
            "seam = \"add-media-items\"",
        )

        val moveBulk = composite.substringAfter("override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int)")
            .substringBefore("override fun removeMediaItem")
        assertOrdered(
            moveBulk,
            "require(fromIndex >= 0 && fromIndex <= toIndex && newIndex >= 0)",
            "val effectiveToIndex = toIndex.coerceAtMost(current.size)",
            "val effectiveNewIndex = newIndex.coerceAtMost(current.size - movedCount)",
            "indexed.addAll(effectiveNewIndex, moved)",
            "dispatchCanonicalTopologyReplacement(",
            "seam = \"move-media-items\"",
        )

        val removeBulk = composite.substringAfter("override fun removeMediaItems(fromIndex: Int, toIndex: Int)")
            .substringBefore("override fun clearMediaItems")
        assertOrdered(
            removeBulk,
            "require(fromIndex >= 0 && toIndex >= fromIndex)",
            "val effectiveToIndex = toIndex.coerceAtMost(current.size)",
            "Media3PlaylistIndexSemantics.currentIndexAfterRemove(",
            "dispatchCanonicalTopologyReplacement(",
            "seam = \"remove-media-items\"",
        )

        val replaceBulk = composite.substringAfter("override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>)")
            .substringBefore("fun startExoPlayback")
        assertOrdered(
            replaceBulk,
            "require(fromIndex >= 0 && toIndex >= fromIndex)",
            "if (fromIndex > current.size) return",
            "val effectiveToIndex = toIndex.coerceAtMost(current.size)",
            "topologyProvenance.queuePlaybackSourceEquivalent(current, expected)",
            "topologyProvenance.preserveProducerTag(current[index], expected[index])",
            "dispatchCanonicalTopologyReplacement(",
            "seam = \"replace-media-items\"",
        )

        val clear = composite.substringAfter("override fun clearMediaItems()")
            .substringBefore("override fun replaceMediaItem")
        assertOrdered(
            clear,
            "reserveTopologyMutation(\"clear-media-items\", queueClear = true)",
            "prepareTopologyProvenance(reservation, emptyList())",
            "playbackStack.stageTopologyQueueClear(reservation)",
            "super.clearMediaItems()",
            "markTopologyDispatchUncertain(reservation, \"clear-media-items-exception\")",
            "commitTopologyMutation(reservation)",
        )

        val selectReplacement = composite.substringAfter("fun selectWithoutPlayback(")
            .substringBefore("override fun seekTo(mediaItemIndex")
        assertOrdered(
            selectReplacement,
            "topologyProvenance.queuePlaybackSourceEquivalent(currentItems, mediaItems)",
            "selectExistingWithoutPlayback(safeIndex, startPositionMs)",
            "reserveTopologyMutation(",
            "\"select-without-playback\"",
            "targetMediaId = targetId",
            "topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)",
            "prepareTopologyProvenance(reservation, taggedItems)",
            "prepareQueueMutation(",
            "exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))",
            "markTopologyDispatchUncertain(reservation, \"select-without-playback-set-items-exception\")",
            "commitTopologyMutation(reservation)",
        )
        assertTrue(
            selectReplacement.contains(
                "selectExistingWithoutPlayback(safeIndex, startPositionMs)\n            return",
            ),
        )
        assertTrue(selectReplacement.contains("abortTopologyMutation(reservation, \"pre-dispatch-error\")"))
        assertFalse(selectReplacement.contains("if (!dispatched) abortTopologyMutation"))
    }

    @Test
    fun destinationAdapterAndUsbAvailabilityAreExplicitAuthorityFacts() {
        val protocol = source("app/src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt")
        assertTrue(protocol.contains("val destinationAdapterInstanceId: AdapterInstanceId? = null"))
        assertTrue(protocol.contains("epoch.destinationAdapterInstanceId != adapterInstanceId"))
        assertTrue(protocol.contains("destinationAdapterInstanceId = candidate.adapterInstanceId"))
        assertFalse(protocol.contains("nonSourceAdapters"))
        assertFalse(protocol.contains("adapters.size == 1"))

        val coordinator = source("app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt")
        assertTrue(coordinator.contains("private var currentObservedP2Generation: Long? = null"))
        val generation = coordinator.substringAfter("fun observeUsbGeneration(generation: Long)")
            .substringBefore("fun observeUsbFacts")
        assertTrue(generation.contains("OutputTarget.Unavailable"))
        assertFalse(generation.contains("OutputTarget.UsbBound"))
        val facts = coordinator.substringAfter("fun observeUsbFacts(facts: PlaybackOutputFacts)")
            .substringBefore("fun observeSharedPcmOutput")
        assertTrue(facts.contains("facts.generation != currentGeneration"))
        assertTrue(facts.indexOf("facts.generation != currentGeneration") < facts.indexOf("latestUsbFacts = facts"))
        assertTrue(facts.contains("facts.usableUsbTarget()"))

        val provenance = source("app/src/main/java/com/mica/music/media/PlaybackTopologyMedia3Provenance.kt")
        assertTrue(provenance.contains("setMediaMetadata(MediaMetadata.EMPTY)"))
        assertTrue(provenance.contains("fun preserveProducerTag(previous: MediaItem, replacement: MediaItem)"))
        assertTrue(provenance.contains("fun producerTokenOf(items: List<MediaItem>): PlaybackTopologyProducerToken?"))
        assertTrue(provenance.contains("fun producerTokenOf(timeline: Timeline): PlaybackTopologyProducerToken?"))
        assertFalse(provenance.contains("TimelineIdentity"))
        assertFalse(provenance.contains("fun resolve("))
        assertFalse(provenance.contains("MAX_COMMITTED_HISTORY"))

        val mediaSource = source("app/src/main/java/com/mica/music/media/PlaybackTopologyMediaSourceFactory.kt")
        assertOrdered(
            mediaSource,
            "provenance.producerTokenOf(mediaItem)",
            "override fun createPeriod(",
            "streamProducerHandles.capture(",
            "occurrence = UsbExclusiveShadowMedia3Facts.occurrence(id)",
            "StreamProducerHandleMediaPeriod(period, handle)",
        )
        assertTrue(mediaSource.contains("StreamProducerHandleSampleStream(handle, inner)"))
        val handleRegistry = source("app/src/main/java/com/mica/music/media/usb/shadow/StreamProducerHandleRegistry.kt")
        assertTrue(handleRegistry.contains("data class StreamProducerHandle("))
        assertTrue(handleRegistry.contains("val producerToken: PlaybackTopologyProducerToken"))
        assertTrue(handleRegistry.contains("val occurrence: PlaybackOccurrence"))
        assertTrue(handleRegistry.contains("val sourceInstanceId: StreamSourceInstanceId"))
        assertTrue(handleRegistry.contains("val periodInstanceId: StreamPeriodInstanceId"))
        assertTrue(handleRegistry.contains("fun redeem(periodInstanceId: StreamPeriodInstanceId)"))
        assertFalse(handleRegistry.contains("active.values.singleOrNull { it.occurrence == occurrence }"))
        assertFalse(handleRegistry.contains("fun redeem(occurrence: PlaybackOccurrence)"))

        val adapter = coordinator.substringAfter("internal class UsbExclusiveShadowAdapter")
        assertOrdered(
            adapter,
            "producerHandle: StreamProducerHandle? = null",
            "exactStreamProducerToken(occurrence, producerToken, producerHandle)",
            "stack.observeRawStream(this, occurrence, family, facts, exactProducer)",
        )
        assertFalse(adapter.contains("streamProducerHandles.redeem(occurrence)"))
        assertTrue(
            adapter.contains("it.stackId == stack.protocol.stackId && it.occurrence == occurrence"),
        )
        assertFalse(coordinator.contains("scopeUnscopedRawStreams"))
        assertFalse(coordinator.contains("scopeExactUnscopedRawStreams"))
        assertFalse(coordinator.contains("periodUidLastObservedEpoch"))
        assertFalse(coordinator.contains("ambiguousPeriodUids"))
        val usable = coordinator.substringAfter("private fun PlaybackOutputFacts.usableUsbTarget")
            .substringBefore("private companion object")
        listOf(
            "phase != UsbOutputPhase.ACTIVE",
            "permission != UsbPermissionState.GRANTED",
            "!attached",
            "!claimed",
            "!exclusive",
            "!signalExact",
            "runtimeHandle == null",
            "request == null",
        ).forEach { assertTrue("missing USB usability proof $it", usable.contains(it)) }
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
        val publishFor = ownerSource.substringAfter("private fun publishFor(token: UsbOutputRequestToken")
            .substringBefore("internal fun cleanupLeaseForCurrentThread")
        assertOrdered(
            publishFor,
            "factsRef.set(next)",
            "afterFactsPublication(next)",
        )
        val runtime = ownerSource.substringAfter("internal object UsbOutputRuntime")
        assertTrue(runtime.contains("onGenerationPublished = generationFanout::publish"))
        assertTrue(runtime.contains("afterFactsPublication = factsFanout::publish"))
        assertTrue(runtime.contains("generationFanout.installPublisher(publisher)"))
        assertTrue(runtime.contains("generationFanout.installObserver(observer)"))
        assertTrue(runtime.contains("factsFanout.installObserver(observer)"))
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
