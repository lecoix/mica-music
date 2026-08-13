package com.mica.music.media.usbprototype

import com.mica.music.media.dsd.ByteSourceIdentity
import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlan
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DsdCarrierPlanner
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsd.DsdContainerReader
import com.mica.music.media.dsd.DsdContainerType
import com.mica.music.media.dsd.DsdSourceBitOrder
import com.mica.music.media.dsd.DsdStreamInfo
import com.mica.music.media.dsd.ProvenPcmStreamingFacts
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.ExactCarrierFeeder
import com.mica.music.media.usb.ExactCarrierFrameSink
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDoPContentPumpTest {
    @Test
    fun readerShortReadsAndZeroAlignedShortSinkPreserveCanonicalIdentityAndMarkerPhase() {
        val plan = plan()
        val source = ByteArray(160) { index -> (index * 37 + 11).toByte() }
        val reader = ScriptedReader(source, intArrayOf(3, 1, 7, 2, 5, 4, 1, 8, 3, 6))
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, call ->
            when {
                call <= 5 -> 0
                call % 4 == 0 -> plan.bytesPerRuntimeFrame
                else -> minOf(offered, plan.bytesPerRuntimeFrame * 2)
            }
        }
        val session = DoPCarrierSession(plan)
        val feeder = ExactCarrierFeeder(session, sink, stagingFrameCapacity = 8)
        val pump = UsbDoPContentPump(reader, feeder, chunkFrames = 8)

        var guard = 0
        while (true) {
            val snapshot = pump.snapshot()
            if (snapshot.readerEof && pump.isCleanBoundary()) break
            val step = pump.step()
            assertFalse(step.status == ExactCarrierFeedStatus.FAILED)
            assertTrue(++guard < 100_000)
        }

        val expected = directContent(plan, source)
        assertArrayEquals(expected.first, sink.bytes())
        val actual = feeder.accounting()
        assertEquals(source.size.toLong(), actual.canonicalBytesConsumed)
        assertEquals(expected.second.canonicalBytesConsumed, actual.canonicalBytesConsumed)
        assertEquals(expected.second.canonicalFramesConsumed, actual.canonicalFramesConsumed)
        assertEquals(expected.second.runtimeFramesPacked, actual.runtimeFramesPacked)
        assertEquals(expected.second.contentRuntimeFramesPacked, actual.contentRuntimeFramesPacked)
        assertEquals(0L, actual.idleRuntimeFramesPacked)
        assertEquals(expected.second.lastPackedMarker, actual.lastPackedMarker)
        assertEquals(expected.second.nextMarker, actual.nextMarker)

        val pumpSnapshot = pump.snapshot()
        assertEquals(source.size.toLong(), pumpSnapshot.readerCanonicalBytesRead)
        assertEquals(source.size.toLong(), pumpSnapshot.canonicalBytesConsumed)
        assertEquals(0, pumpSnapshot.pendingCanonicalBytes)
        assertEquals(0, pumpSnapshot.feederStagedBytes)
        assertEquals(0, pumpSnapshot.feederUpstreamPendingBytes)
        assertNull(pumpSnapshot.feederContractError)
        assertTrue(sink.zeroAccepts > 0)
        assertTrue(sink.shortAccepts > 0)
    }

    @Test
    fun drainBufferedNeverReadsAnotherReaderChunk() {
        val plan = plan()
        val source = ByteArray(64) { it.toByte() }
        val reader = ScriptedReader(source, intArrayOf(4, 4, 4, 4))
        var allow = false
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, _ -> if (allow) offered else 0 }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)
        val pump = UsbDoPContentPump(reader, feeder, chunkFrames = 4)

        val first = pump.step()
        assertTrue(first.readerFramesAdded > 0)
        val readsBefore = reader.readCalls
        assertFalse(pump.drainBuffered(maxSteps = 4))
        assertEquals(readsBefore, reader.readCalls)

        allow = true
        assertTrue(pump.drainBuffered())
        assertEquals(readsBefore, reader.readCalls)
        assertEquals(8L, pump.snapshot().readerCanonicalBytesRead)
        assertEquals(8L, pump.snapshot().canonicalBytesConsumed)
    }

    private fun plan(): DoPCarrierPlan {
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(2_822_400L, 2),
            pcm = ProvenPcmStreamingFacts(
                runtimeFrameRateHz = 176_400L,
                channelCount = 2,
                subslotBytesPerChannel = 3,
                bitResolution = 24,
                bytesPerRuntimeFrame = 6,
                maxBytesPerServiceInterval = 300,
                servicePeriodNumeratorSeconds = 1,
                servicePeriodDenominatorSeconds = 8_000,
            ),
        )
        assertTrue(result is DoPCarrierPlanningResult.Ready)
        return (result as DoPCarrierPlanningResult.Ready).plan
    }

    private fun directContent(plan: DoPCarrierPlan, source: ByteArray): Pair<ByteArray, com.mica.music.media.dsd.DoPPipelineAccounting> {
        val session = DoPCarrierSession(plan)
        val output = ByteArrayOutputStream()
        var offset = 0
        var guard = 0
        while (offset < source.size) {
            val destination = ByteArray(4_096)
            val result = session.writeContentBytes(
                source = source,
                sourceOffset = offset,
                sourceByteCount = source.size - offset,
                destination = destination,
            )
            output.write(destination, 0, result.carrierBytesEmitted)
            offset += result.canonicalBytesConsumed
            assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
            assertTrue(++guard < 10_000)
        }
        while (session.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(4_096)
            val flushed = session.flushCarrierOutput(destination)
            output.write(destination, 0, flushed.carrierBytesEmitted)
            assertTrue(flushed.carrierBytesEmitted > 0)
        }
        return output.toByteArray() to session.accounting()
    }

    private class ScriptedReader(
        private val canonical: ByteArray,
        private val scriptFrames: IntArray,
    ) : DsdContainerReader {
        override val sourceIdentity = ByteSourceIdentity("test://canonical")
        override val info = DsdStreamInfo(
            container = DsdContainerType.DSF,
            sampleRateHz = 2_822_400,
            channelCount = 2,
            sampleCountPerChannel = canonical.size.toLong() / 2L * 8L,
            sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
        )
        override var framePosition: Long = 0L
            private set
        var readCalls = 0
            private set

        override fun readFrames(destination: ByteArray, destinationOffset: Int, maxFrames: Int): Int {
            readCalls++
            val totalFrames = canonical.size / info.channelCount
            if (framePosition >= totalFrames) return 0
            val requested = scriptFrames[(readCalls - 1) % scriptFrames.size]
            val frames = minOf(requested, maxFrames, totalFrames - framePosition.toInt())
            val bytes = frames * info.channelCount
            val sourceOffset = framePosition.toInt() * info.channelCount
            canonical.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + bytes)
            framePosition += frames.toLong()
            return frames
        }

        override fun seekToSample(sampleIndex: Long): Long = error("not used")
        override fun close() = Unit
    }

    private class CollectingSink(
        override val bytesPerRuntimeFrame: Int,
        private val response: (Int, Int) -> Int,
    ) : ExactCarrierFrameSink {
        private val output = ByteArrayOutputStream()
        private var calls = 0
        var zeroAccepts = 0
            private set
        var shortAccepts = 0
            private set

        override fun writeCarrierFrames(source: ByteArray, offset: Int, byteCount: Int): Int {
            calls++
            val accepted = response(byteCount, calls)
            if (accepted == 0) zeroAccepts++
            if (accepted in 1 until byteCount) shortAccepts++
            if (accepted > 0) output.write(source, offset, accepted)
            return accepted
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}