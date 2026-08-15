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

class DirectDsdManualNavigationGenerationTest {
    @Test
    fun staleBStreamCannotMutateSourceStateAndTrueCBecomesOnlyPendingDestination() {
        val events = mutableListOf<String>()
        val bridge = ManualNavigationTransitionBridge(events::add)
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory {
                error("generation quarantine must not open Direct runtime")
            },
            playbackAdapter = testDirectPlaybackAdapter(),
            milestone = events::add,
            transitionCoordinator = coordinator,
            manualNavigationTransitionBridge = bridge,
        )
        val aFormat = format(5_644_800)
        val bFormat = format(5_644_800)
        val cFormat = format(5_644_800)
        setPrivate(renderer, "currentFormat", aFormat)

        bridge.updateApplicationCurrentness("A", "period-A")
        bridge.observePlaybackStream(MediaPeriodId("period-A", 1L))
        bridge.publish(
            targetMediaId = "B",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val latest = bridge.publish(
            targetMediaId = "C",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        bridge.updateApplicationCurrentness("C", "period-C")

        invokeStreamChanged(renderer, bFormat, MediaPeriodId("period-B", 2L))

        assertSame(aFormat, privateField<Format>(renderer, "currentFormat"))
        assertNull(privateField<Any?>(renderer, "pendingFreshDirectDestination"))
        assertTrue(privateField<Any?>(renderer, "pendingManualNavigationBoundary") != null)
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertNull(bridge.snapshot()?.targetFacts)
        assertNull(bridge.snapshot()?.targetPlaybackIdentity)
        assertFalse(events.any { it.contains("OLD_SOURCE_INTAKE_CLOSED") })
        assertFalse(events.any { it.contains("NEW_SOURCE_FACTS_BOUND") })
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") })

        invokeStreamChanged(renderer, cFormat, MediaPeriodId("period-C", 3L))

        assertNull(privateField<Any?>(renderer, "pendingManualNavigationBoundary"))
        val pending = privateField<Any>(renderer, "pendingFreshDirectDestination")
        assertSame(cFormat, pendingField<Format>(pending, "format"))
        assertEquals(latest.requestId, pendingField<Long>(pending, "navigationRequestId"))
        assertEquals(
            ManualNavigationPlaybackIdentity("period-C", 3L),
            pendingField<ManualNavigationPlaybackIdentity>(pending, "navigationPlaybackIdentity"),
        )
        assertEquals(latest.requestId, bridge.snapshot()?.requestId)
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") })
    }

    @Test
    fun disableClearsUnprovenNavigationBoundary() {
        val bridge = ManualNavigationTransitionBridge()
        val renderer = DirectDsdMedia3Renderer(
            sessionFactory = DirectDsdTransportSessionFactory { error("must stay closed") },
            playbackAdapter = testDirectPlaybackAdapter(),
            manualNavigationTransitionBridge = bridge,
        )
        bridge.updateApplicationCurrentness("A", "period-A")
        bridge.observePlaybackStream(MediaPeriodId("period-A", 10L))
        bridge.publish(
            targetMediaId = "C",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        bridge.updateApplicationCurrentness("C", "period-C")
        invokeStreamChanged(renderer, format(5_644_800), MediaPeriodId("period-B", 11L))
        assertTrue(privateField<Any?>(renderer, "pendingManualNavigationBoundary") != null)

        invokeNoArg(renderer, "onDisabled")

        assertNull(privateField<Any?>(renderer, "pendingManualNavigationBoundary"))
    }

    private fun invokeStreamChanged(
        renderer: DirectDsdMedia3Renderer,
        format: Format,
        mediaPeriodId: MediaPeriodId,
    ) {
        DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "onStreamChanged",
            Array<Format>::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            MediaPeriodId::class.java,
        ).also {
            it.isAccessible = true
            it.invoke(renderer, arrayOf(format), 0L, 0L, mediaPeriodId)
        }
    }

    private fun invokeNoArg(renderer: DirectDsdMedia3Renderer, name: String) {
        DirectDsdMedia3Renderer::class.java.getDeclaredMethod(name).also {
            it.isAccessible = true
            it.invoke(renderer)
        }
    }

    private fun setPrivate(renderer: DirectDsdMedia3Renderer, name: String, value: Any?) {
        DirectDsdMedia3Renderer::class.java.getDeclaredField(name).also {
            it.isAccessible = true
            it.set(renderer, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(renderer: DirectDsdMedia3Renderer, name: String): T =
        DirectDsdMedia3Renderer::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(renderer) as T
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> pendingField(pending: Any, name: String): T =
        pending.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(pending) as T
        }

    private fun format(rate: Int): Format {
        val facts = DsfExtractorPacketFacts(rate, 2, DsdSourceBitOrder.LSB_FIRST)
        return Format.Builder()
            .setSampleMimeType(DsfFormat.MIME_DSF)
            .setContainerMimeType(DsfFormat.MIME_CONTAINER_DSF)
            .setChannelCount(2)
            .setSampleRate(rate / 8)
            .setCustomData(facts)
            .build()
    }
}
