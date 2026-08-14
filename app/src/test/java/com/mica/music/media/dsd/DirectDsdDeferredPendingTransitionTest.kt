package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
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
    fun pausedPendingDifferentFactsRebindsLatestEpochWithoutAcceptanceOrRuntime() {
        val events = mutableListOf<String>()
        var openCalls = 0
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        val renderer = renderer(events, coordinator) { openCalls++ }
        bindPending(renderer, format(facts(5_644_800)), requiresStarted = true)
        val firstEpoch = pendingLong(renderer, "epochId")
        val replacement = format(facts(2_822_400))

        invokeStreamChanged(renderer, replacement)

        assertSame(replacement, privateField<Format>(renderer, "currentFormat"))
        assertSame(replacement, pendingField<Format>(renderer, "format"))
        assertTrue(pendingLong(renderer, "epochId") > firstEpoch)
        assertTrue(pendingField<Boolean>(renderer, "requiresStartedAuthority"))
        assertFalse(pendingField<Boolean>(renderer, "startedAuthorityObserved"))
        assertEquals(0, openCalls)
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertTrue(events.any { it.contains("PENDING_DESTINATION_REPLACED") })
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") || it.contains("dop-accept-allowed") })
    }

    @Test
    fun pausedPendingSameFactsStillAdvancesLogicalEpoch() {
        val renderer = renderer(mutableListOf(), DirectDsdTrackTransitionCoordinator {}) {}
        val old = format(facts(5_644_800))
        val replacement = format(facts(5_644_800))
        bindPending(renderer, old, requiresStarted = true)
        val firstEpoch = pendingLong(renderer, "epochId")

        invokeStreamChanged(renderer, replacement)

        assertSame(replacement, pendingField<Format>(renderer, "format"))
        assertTrue(pendingLong(renderer, "epochId") > firstEpoch)
    }

    @Test
    fun playingPendingRapidReplacementUsesSameEpochAbstractionAndNeverAccepts() {
        val events = mutableListOf<String>()
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        val renderer = renderer(events, coordinator) {}
        setBaseRendererState(renderer, Renderer.STATE_STARTED)
        bindPending(renderer, format(facts(5_644_800)), requiresStarted = false)
        val firstEpoch = pendingLong(renderer, "epochId")

        invokeStreamChanged(renderer, format(facts(2_822_400)))
        val secondEpoch = pendingLong(renderer, "epochId")
        invokeStreamChanged(renderer, format(facts(2_822_400)))
        val thirdEpoch = pendingLong(renderer, "epochId")

        assertTrue(secondEpoch > firstEpoch)
        assertTrue(thirdEpoch > secondEpoch)
        assertFalse(pendingField<Boolean>(renderer, "requiresStartedAuthority"))
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertNull(privateField<Any?>(renderer, "pump"))
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") || it.contains("dop-accept-allowed") })
    }

    @Test
    fun pausedReplacementResumeThenRepauseReassertsStartedAuthorityOnSameEpoch() {
        val events = mutableListOf<String>()
        val renderer = renderer(events, DirectDsdTrackTransitionCoordinator {}) {}
        bindPending(renderer, format(facts(5_644_800)), requiresStarted = true)
        invokeStreamChanged(renderer, format(facts(2_822_400)))
        val epoch = pendingLong(renderer, "epochId")

        invokeNoArg(renderer, "onStarted")
        assertEquals(epoch, pendingLong(renderer, "epochId"))
        assertTrue(pendingField<Boolean>(renderer, "requiresStartedAuthority"))
        assertTrue(pendingField<Boolean>(renderer, "startedAuthorityObserved"))
        assertNull(privateField<Any?>(renderer, "pump"))

        invokeNoArg(renderer, "onStopped")
        assertEquals(epoch, pendingLong(renderer, "epochId"))
        assertTrue(pendingField<Boolean>(renderer, "requiresStartedAuthority"))
        assertFalse(pendingField<Boolean>(renderer, "startedAuthorityObserved"))
        assertNull(privateField<Any?>(renderer, "pump"))
        assertFalse(events.any { it.contains("NEW_SOURCE_ACCEPT_ALLOWED") })
    }

    @Test
    fun disableAndResetRetirePendingEpoch() {
        listOf("onDisabled", "onReset").forEach { lifecycleMethod ->
            val renderer = renderer(mutableListOf(), DirectDsdTrackTransitionCoordinator {}) {}
            bindPending(renderer, format(facts(5_644_800)), requiresStarted = true)

            invokeNoArg(renderer, lifecycleMethod)

            assertNull(privateField<Format?>(renderer, "currentFormat"))
            assertNull(privateField<Any?>(renderer, "pendingFreshDirectDestination"))
        }
    }

    private fun renderer(
        events: MutableList<String>,
        coordinator: DirectDsdTrackTransitionCoordinator,
        onOpen: () -> Unit,
    ) = DirectDsdMedia3Renderer(
        sessionFactory = DirectDsdTransportSessionFactory {
            onOpen()
            error("pending epoch test must not open runtime")
        },
        milestone = events::add,
        transitionCoordinator = coordinator,
    )

    private fun bindPending(renderer: DirectDsdMedia3Renderer, format: Format, requiresStarted: Boolean) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(
            "bindPendingFreshDestination",
            Format::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(renderer, format, requiresStarted, false)
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
        method.invoke(renderer, arrayOf(format), 0L, 0L, MediaPeriodId("pending-epoch-test"))
    }

    private fun invokeNoArg(renderer: DirectDsdMedia3Renderer, name: String) {
        DirectDsdMedia3Renderer::class.java.getDeclaredMethod(name).also {
            it.isAccessible = true
            it.invoke(renderer)
        }
    }

    private fun setBaseRendererState(renderer: DirectDsdMedia3Renderer, state: Int) {
        val field = renderer.javaClass.superclass.getDeclaredField("state")
        field.isAccessible = true
        field.setInt(renderer, state)
    }

    private fun pendingLong(renderer: DirectDsdMedia3Renderer, name: String): Long =
        pendingField<Long>(renderer, name)

    @Suppress("UNCHECKED_CAST")
    private fun <T> pendingField(renderer: DirectDsdMedia3Renderer, name: String): T {
        val pending = privateField<Any>(renderer, "pendingFreshDirectDestination")
        val field = pending.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(pending) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(renderer: DirectDsdMedia3Renderer, name: String): T {
        val field = DirectDsdMedia3Renderer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(renderer) as T
    }

    private fun facts(rate: Int) = DsfExtractorPacketFacts(rate, 2, DsdSourceBitOrder.LSB_FIRST)

    private fun format(packetFacts: DsfExtractorPacketFacts): Format = Format.Builder()
        .setSampleMimeType(DsfFormat.MIME_DSF)
        .setContainerMimeType(DsfFormat.MIME_CONTAINER_DSF)
        .setChannelCount(packetFacts.channelCount)
        .setSampleRate(packetFacts.sourceSampleRateHz / 8)
        .setCustomData(packetFacts)
        .build()
}
