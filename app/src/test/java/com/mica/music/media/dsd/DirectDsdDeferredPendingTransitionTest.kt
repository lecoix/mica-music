package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import com.mica.music.media.dsf.DsfFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdDeferredPendingTransitionTest {
    @Test
    fun repeatedPausedDirectDifferentFactsRebindsLatestPendingWithoutAcceptanceOrRuntime() {
        val events = mutableListOf<String>()
        var openCalls = 0
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory {
                openCalls++
                error("deferred replacement must not open runtime")
            },
            milestone = events::add,
            transitionCoordinator = coordinator,
        )
        val oldFormat = format(facts(5_644_800))
        val newFormat = format(facts(2_822_400))
        seedDeferred(renderer, oldFormat)

        invokeStreamChanged(renderer, newFormat)

        assertSame(newFormat, privateField<Format>(renderer, "currentFormat"))
        assertSame(newFormat, privateField<Format>(renderer, "deferredPausedFreshFormat"))
        assertTrue(privateField<Boolean>(renderer, "playingTrackTransitionPending"))
        assertFalse(privateField<Boolean>(renderer, "deferredResumeAuthorityObserved"))
        assertEquals(0, openCalls)
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertTrue(events.any { it.contains("PENDING_DESTINATION_REPLACED") && it.contains("sameFacts=false") })
        assertTrue(events.any { it.contains("FRESH_DIRECT_DEFERRED replacement=true") })
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") })
        assertFalse(events.any { it.contains("dop-accept-allowed") })
    }

    @Test
    fun repeatedPausedDirectSameFactsStillReplacesLogicalPendingFormat() {
        val events = mutableListOf<String>()
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory {
                error("same-facts deferred replacement must not open runtime")
            },
            milestone = events::add,
            transitionCoordinator = coordinator,
        )
        val oldFormat = format(facts(5_644_800))
        val replacementFormat = format(facts(5_644_800))
        assertTrue(oldFormat !== replacementFormat)
        seedDeferred(renderer, oldFormat)

        invokeStreamChanged(renderer, replacementFormat)

        assertSame(replacementFormat, privateField<Format>(renderer, "currentFormat"))
        assertSame(replacementFormat, privateField<Format>(renderer, "deferredPausedFreshFormat"))
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertTrue(events.any { it.contains("PENDING_DESTINATION_REPLACED") && it.contains("sameFacts=true") })
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") })
    }

    @Test
    fun disableAndResetClearDeferredPendingDestination() {
        listOf("onDisabled", "onReset").forEach { lifecycleMethod ->
            val renderer = DirectDsdMedia3Renderer(
                sessionFactory = DirectDsdTransportSessionFactory {
                    error("clearing inert pending state must not open runtime")
                },
                transitionCoordinator = DirectDsdTrackTransitionCoordinator {},
            )
            seedDeferred(renderer, format(facts(5_644_800)))

            invokeNoArg(renderer, lifecycleMethod)

            assertNull(privateField<Format?>(renderer, "currentFormat"))
            assertNull(privateField<Format?>(renderer, "deferredPausedFreshFormat"))
            assertFalse(privateField<Boolean>(renderer, "playingTrackTransitionPending"))
            assertFalse(privateField<Boolean>(renderer, "deferredResumeAuthorityObserved"))
        }
    }

    private fun seedDeferred(renderer: DirectDsdMedia3Renderer, pendingFormat: Format) {
        setPrivateField(renderer, "currentFormat", pendingFormat)
        setPrivateField(renderer, "deferredPausedFreshFormat", pendingFormat)
        setPrivateField(renderer, "deferredResumeAuthorityObserved", false)
        setPrivateField(renderer, "playingTrackTransitionPending", true)
        assertNull(privateField<Any?>(renderer, "pump"))
    }

    private fun invokeStreamChanged(renderer: DirectDsdMedia3Renderer, format: Format) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "onStreamChanged",
            Array<Format>::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            MediaPeriodId::class.java,
        )
        method.isAccessible = true
        method.invoke(renderer, arrayOf(format), 0L, 0L, MediaPeriodId("deferred-pending-test"))
    }

    private fun invokeNoArg(renderer: DirectDsdMedia3Renderer, name: String) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(renderer)
    }

    private fun setPrivateField(renderer: DirectDsdMedia3Renderer, name: String, value: Any?) {
        val field = DirectDsdMedia3Renderer::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(renderer, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(renderer: DirectDsdMedia3Renderer, name: String): T {
        val field = DirectDsdMedia3Renderer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(renderer) as T
    }

    private fun facts(rate: Int) = DsfExtractorPacketFacts(
        sourceSampleRateHz = rate,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )

    private fun format(packetFacts: DsfExtractorPacketFacts): Format = Format.Builder()
        .setSampleMimeType(DsfFormat.MIME_DSF)
        .setContainerMimeType(DsfFormat.MIME_CONTAINER_DSF)
        .setChannelCount(packetFacts.channelCount)
        .setSampleRate(packetFacts.sourceSampleRateHz / 8)
        .setCustomData(packetFacts)
        .build()
}
