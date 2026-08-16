package com.mica.music.media.usb.m3

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveM3AuthorityStructureTest {
    @Test
    fun productionProtocolDependenciesCannotBeOmittedIntoLegacyAuthority() {
        val factory = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        assertFalse(factory.contains("playbackCoordinator: UsbExclusivePlaybackCoordinator? = null"))

        val renderers = source("app/src/main/java/com/mica/music/media/MicaRenderersFactory.kt")
        assertFalse(renderers.contains("playbackStack: UsbExclusivePlaybackStack? = null"))

        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        assertFalse(composite.contains("playbackStack: UsbExclusivePlaybackStack?"))

        val pcm = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        assertFalse(pcm.contains("playbackAdapter: UsbExclusivePlaybackAdapter? = null"))

        val direct = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        assertFalse(direct.contains("private var playbackAdapter: UsbExclusivePlaybackAdapter?"))
    }

    @Test
    fun pcmProductionPathGetsPermitBeforeConfigureAndCommitsReceiptAfterwards() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val configure = source.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            configure,
            "preparePcmConfigure(",
            "configureWithProtocol(",
        )
        val protocolConfigure = source.substringAfter("private fun configureWithProtocol(")
        assertOrdered(
            protocolConfigure,
            "super.configure(inputFormat, specifiedBufferSize, outputChannels)",
            "commitPcmConfigure(",
        )
        assertTrue(source.contains("tryEnterWrite("))
        assertTrue(source.contains("prepareRetainedPcmHandoff("))
        assertTrue(source.contains("return false"))
    }

    @Test
    fun directProductionPathGetsEachStagePermitBeforeRuntimeSideEffectAndCommitsReceiptAfterwards() {
        val source = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val create = source.substringAfter("private fun openPumpIfNeeded")
            .substringBefore("private fun observeShadowPrefillIfReady")
        assertOrdered(create, "prepareDirectStage(", "sessionFactory.open", "commitDirectStage(")

        val arm = source.substringAfter("private fun maybeArmAfterFreshTrackTransition(")
            .substringBefore("override fun onStreamChanged")
        assertTrue(arm.contains("observeShadowArmAndSourceAccept(active)"))
        val stage = source.substringAfter("private fun observeShadowArmAndSourceAccept(")
            .substringAfter("val armPermit = adapter.prepareDirectStage(")
            .let { "prepareDirectStage($it" }
        assertOrdered(stage, "prepareDirectStage(", "active.armPlayback()", "commitDirectStage(")
        val retained = source.substringAfter("private fun transitionRetainedSourceWithProtocol(")
            .substringAfter("val permit = adapter.prepareRetainedDirectHandoff(")
        assertOrdered(
            retained,
            "active.transitionRetainedSource(newFacts)",
            "commitRetainedDirectHandoff(",
        )
        val close = source.substringAfter("private fun closePump(reason: String)")
        assertOrdered(close, "closingPump?.close()", "observeDirectRuntimeReleased(")
    }

    @Test
    fun productionUsesProtocolForCommandsAndKeepsLegacyAuthorityOutOfProductionBranches() {
        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        assertOrdered(
            composite.substringAfter("override fun setPlayWhenReady("),
            "publishProtocolIntent(playWhenReady)",
            "onPlaybackIntentChanged?.invoke(playWhenReady)",
        )
        assertOrdered(
            composite.substringAfter("private fun publishManualNavigation("),
            "playbackStack.beginManualNavigation(",
            "manualNavigationTransitionBridge.publish(",
        )
        val direct = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        assertFalse(direct.contains("if (playbackAdapter == null)"))
        assertFalse(direct.contains("playbackAdapter?."))
        val coordinator = source("app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt")
        assertTrue(coordinator.contains("directSeekCarrierBarriers[adapter.id] == (mutation.mutationId to occurrence)"))
        val service = source("app/src/main/java/com/mica/music/media/MicaMediaService.kt")
        assertTrue(service.contains("snapshot.stageInto(candidate.exoPlayer)"))
        assertTrue(service.contains("restoreAfterTechnicalQuiesce()"))
    }

    @Test
    fun productionCommitDispositionsHaveExactCleanupContinuation() {
        val pcm = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val direct = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        assertTrue(pcm.contains("completeCleanup("))
        assertTrue(direct.contains("completeCleanup("))
        assertTrue(direct.contains("cleanupRequirements"))
    }

    @Test
    fun queueReplacementObtainsProtocolMutationBeforeExoDispatch() {
        val composite = source("app/src/main/java/com/mica/music/media/MicaCompositePlayer.kt")
        val replacement = composite.substringAfter("override fun setMediaItems(")
            .substringBefore("override fun addMediaItem(")
        assertOrdered(replacement, "prepareQueueMutation(", "super.setMediaItems(")
        assertTrue(composite.contains("beginQueueClear()"))
        assertFalse(replacement.contains("super.setMediaItems(") && replacement.indexOf("prepareQueueMutation(") < 0)
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
