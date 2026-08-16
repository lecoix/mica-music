package com.mica.music.media.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import com.mica.music.media.dsf.DsfFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DirectDsdMedia3FormatPolicyTest {
    private val facts = DsfExtractorPacketFacts(
        sourceSampleRateHz = 5_644_800,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )

    @Test
    fun claimsOnlyAuthoritativeStereoDsfWithExtractorFacts() {
        val valid = format(facts)
        assertSame(facts, DirectDsdMedia3FormatPolicy.factsOrNull(valid))

        assertNull(DirectDsdMedia3FormatPolicy.factsOrNull(valid.buildUpon().setCustomData(null).build()))
        assertNull(DirectDsdMedia3FormatPolicy.factsOrNull(valid.buildUpon().setContainerMimeType("audio/dff").build()))
        assertNull(DirectDsdMedia3FormatPolicy.factsOrNull(valid.buildUpon().setSampleMimeType("audio/flac").build()))
        assertNull(DirectDsdMedia3FormatPolicy.factsOrNull(valid.buildUpon().setSampleRate(176_400).build()))
        assertNull(
            DirectDsdMedia3FormatPolicy.factsOrNull(
                format(facts.copy(sourceSampleRateHz = 11_289_600)),
            ),
        )
    }

    @Test
    fun rendererSupportIsHandledOnlyForAuthoritativePrototypeDsf() {
        val renderer = DirectDsdMedia3Renderer(
            DirectDsdTransportSessionFactory { error("support probe must not open transport") },
            testDirectPlaybackAdapter(),
        )
        assertEquals(C.FORMAT_HANDLED, renderer.supportsFormat(format(facts)))
        assertEquals(
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            renderer.supportsFormat(format(facts).buildUpon().setCustomData(null).build()),
        )
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            renderer.supportsFormat(format(facts).buildUpon().setSampleMimeType("audio/flac").build()),
        )
    }

    @Test
    fun rendererReusesOneFormatHolderThroughAuthoritativeSessionOpen() {
        val source = File(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        ).readText()
        val renderBody = source.substringAfter("override fun render(")
            .substringBefore("override fun isReady()")

        assertEquals(1, Regex("\\bformatHolder\\b").findAll(renderBody).count())
        assertTrue(renderBody.contains("val holder = formatHolder"))
        assertTrue(renderBody.contains("readSource(holder, inputBuffer, 0)"))
        assertTrue(renderBody.contains("val format = checkNotNull(holder.format)"))
        assertTrue(renderBody.contains("DirectDsdMedia3FormatPolicy.factsOrNull(format)"))
        assertTrue(renderBody.contains("sessionFactory.open(facts, writeAuthority)"))
    }

    @Test
    fun rendererLifecycleArmsOnceThenJoinsGapBeforeResumeContent() {
        val source = File(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        ).readText()

        assertTrue(source.contains("pump?.isStartupPrefillReady() == true"))
        assertTrue(source.contains("override fun onStarted()"))
        assertTrue(source.contains("if (active.isPlaybackArmed())"))
        assertTrue(source.contains("if (pauseGapActive)"))
        assertTrue(source.contains("active.stopPauseGapLiveness()"))
        assertTrue(source.contains("pauseGapActive = false"))
        assertTrue(source.contains("active.armPlayback()"))
        assertTrue(source.contains("override fun onStopped()"))
        assertTrue(source.contains("startPauseGapLiveness()"))
        assertTrue(source.contains("pauseGapActive = true"))
        assertTrue(source.contains("if (ended || pauseGapActive) return"))
        assertTrue(source.contains("closePump(\"disabled\")"))
        assertTrue(source.contains("closePump(\"reset\")"))
        assertTrue(!source.contains("closePump(\"stopped-after-arm\")"))
        assertTrue(!source.contains("resumeBlockedAfterStop"))
    }

    @Test
    fun rendererRegistersAndUnregistersExactTeardownGenerationAroundPumpLifetime() {
        val source = File(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        ).readText()
        val openBody = source.substringAfter("private fun openPumpIfNeeded(")
            .substringBefore("private fun maybeArmAfterPlayingReset")
        val closeBody = source.substringAfter("private fun closePump(reason: String)")
            .substringBefore("companion object")

        assertTrue(openBody.contains("DirectDsdTeardownQuiescenceCoordinator.register(sessionGeneration)"))
        assertTrue(openBody.contains("freshPump.quiescePauseGapForOutputRebuild()"))
        assertTrue(closeBody.contains("DirectDsdTeardownQuiescenceCoordinator.unregister(generation)"))
        assertTrue(closeBody.indexOf("unregister(generation)") < closeBody.indexOf("closingPump?.close()"))
    }

    @Test
    fun rendererPlayingPositionResetReopensFromRetainedFormatAndArmsFreshSessionExplicitly() {
        val source = File(
            "src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt",
        ).readText()
        val resetBody = source.substringAfter("override fun onPositionReset(")
            .substringBefore("override fun onDisabled()")
        val bufferReadBody = source.substringAfter("C.RESULT_BUFFER_READ ->")
            .substringBefore("override fun isReady()")

        assertTrue(resetBody.contains("val hadPump = oldPump != null"))
        assertTrue(resetBody.contains("val streamOffsetUs = getStreamOffsetUs()"))
        assertTrue(resetBody.contains("val sourcePositionUs = positionUs - streamOffsetUs"))
        assertTrue(resetBody.contains("DirectDsdSeekDiscontinuityCoordinator.consumePositionReset("))
        assertTrue(resetBody.contains("val matchedPlayingSeek = seekDecision.match == DirectDsdSeekResetMatch.MATCHED"))
        assertTrue(resetBody.contains("isPlaying = isPlaying && matchedPlayingSeek"))
        assertTrue(resetBody.contains("closePump(\"position-reset:\$positionUs\")"))
        assertTrue(!resetBody.contains("currentFormat = null"))
        assertTrue(bufferReadBody.contains("pump ?: openPumpIfNeeded(authoritativeCurrentFacts())"))
        assertTrue(source.contains("val seekPending = seekDispatch != null"))
        assertTrue(source.contains("val gapStarted = wasArmed && !ended && !seekPending"))
        assertTrue(source.contains("renderer=post-reset-open positionUs=\$resetPositionUs"))
        assertTrue(source.contains("maybeArmAfterPlayingReset(active, sampleTimeUs = inputBuffer.timeUs)"))
        assertTrue(source.contains("renderer=post-reset-arm positionUs=\$resetPositionUs"))
    }
    private fun format(packetFacts: DsfExtractorPacketFacts): Format = Format.Builder()
        .setSampleMimeType(DsfFormat.MIME_DSF)
        .setContainerMimeType(DsfFormat.MIME_CONTAINER_DSF)
        .setChannelCount(packetFacts.channelCount)
        .setSampleRate(packetFacts.sourceSampleRateHz / 8)
        .setCustomData(packetFacts)
        .build()
}
