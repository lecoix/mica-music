package com.mica.music.media.dsd

import java.io.File
import kotlin.math.roundToLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoPRuntimeHotPathEvidenceTest {
    @Test
    fun dsd128GranularBaselineOperationModelMatchesPreFastPathGeometry() {
        val rows = loadOperationModel().associateBy { it.operation }
        assertEquals(13, rows.size)
        assertEquals(4_096L, rows.getValue("canonical-frame-loop").calls)
        assertEquals(4_096L, rows.getValue("encodeFrames-call").calls)
        assertEquals(2_048L, rows.getValue("packWords-call").calls)
        assertEquals(2_049L, rows.getValue("emitPending-call").calls)
        assertEquals(8_192L, rows.getValue("source-to-partial-copy").bytesMoved)
        assertEquals(12_288L, rows.getValue("packed-frame-to-upstream-copy").bytesMoved)
        assertEquals(24_576L, rows.getValue("upstream-buffer-allocation").bytesMoved)
        assertEquals("SEMANTIC_REQUIRED", rows.getValue("logical-word-write").classification)
        assertTrue(rows.values.count { it.classification == "IMPLEMENTATION_ACCIDENT" } >= 5)
    }

    @Test
    fun batchedExistingEncoderAndPackerAreByteExactAgainstCurrentAlignedPacketPath() {
        val source = sourcePacket()
        val currentSession = DoPCarrierSession(plan())
        val current = ByteArray(CARRIER_BYTES)
        val write = currentSession.writeContentBytes(source, destination = current)
        assertEquals(SOURCE_BYTES, write.canonicalBytesConsumed)
        assertEquals(CANONICAL_FRAMES, write.canonicalFramesCompleted)
        assertEquals(RUNTIME_FRAMES, write.runtimeFramesPacked)
        assertEquals(CARRIER_BYTES, write.carrierBytesEmitted)
        assertEquals(RUNTIME_FRAMES, write.runtimeFramesFullyEmitted)

        val batched = ByteArray(CARRIER_BYTES)
        val encoder = DoPEncoder(CHANNELS)
        val words = IntArray(CANONICAL_FRAMES)
        val produced = encoder.encodeFrames(source, frameCount = CANONICAL_FRAMES, destinationWords = words)
        assertEquals(RUNTIME_FRAMES, produced)
        val packed = DoPEncoder.packWords(
            words = words,
            wordCount = produced * CHANNELS,
            packing = DoPCarrierPacking.PACKED_24_LE,
            destination = batched,
        )
        assertEquals(CARRIER_BYTES, packed)
        assertArrayEquals(current, batched)
        assertEquals(currentSession.accounting().nextMarker, encoder.marker)
    }

    @Test
    fun optimizationSeamIsP5OwnedAndKeepsGranularFallback() {
        val lines = fixture("dop-hotpath-optimization-seam-v1.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, lines.size)
        val header = lines[0].split('\t')
        val row = lines[1].split('\t')
        assertEquals(header.size, row.size)
        val values = header.zip(row).toMap()
        assertEquals("p5.dop.bulk-aligned-writebytes-fastpath.v1", values.getValue("id"))
        assertEquals("A_LOCAL_CPU_COPY_HOTSPOT", values.getValue("resultClass"))
        assertEquals("P5", values.getValue("owner"))
        assertEquals("DoPRuntimePipeline.writeBytes", values.getValue("seam"))
        assertTrue(values.getValue("entryPreconditions").contains("destinationCanHoldWholeDerivedCarrierChunk"))
        assertTrue(values.getValue("entryPreconditions").contains("noPendingCanonicalHalfFrame"))
        assertTrue(values.getValue("mustPreserve").contains("markerPhase"))
        assertTrue(values.getValue("mustPreserve").contains("pendingTailChronology"))
        assertTrue(values.getValue("fallback").contains("existing granular implementation"))
        assertTrue(values.getValue("notAuthorized").contains("No feeder/sink/Native"))
    }

    @Test
    fun desktopJvmRelativeProbeRecordsOptimizedPipelineAgainstGranularAndBatchPrimitives() {
        val source = sourcePacket()
        val currentProbe = CurrentProbe()
        val granularProbe = GranularPrimitiveProbe()
        val batchedProbe = BatchedProbe()
        repeat(WARMUP_PACKETS) {
            currentProbe.run(source)
            granularProbe.run(source)
            batchedProbe.run(source)
        }

        val currentSamples = LongArray(SAMPLES) { measurePackets(source, currentProbe::run) }
        val granularSamples = LongArray(SAMPLES) { measurePackets(source, granularProbe::run) }
        val batchedSamples = LongArray(SAMPLES) { measurePackets(source, batchedProbe::run) }
        val currentMedian = median(currentSamples)
        val granularMedian = median(granularSamples)
        val batchedMedian = median(batchedSamples)
        val ratio = currentMedian.toDouble() / batchedMedian.coerceAtLeast(1L).toDouble()
        val currentVsGranular = currentMedian.toDouble() / granularMedian.coerceAtLeast(1L).toDouble()
        val granularVsBatch = granularMedian.toDouble() / batchedMedian.coerceAtLeast(1L).toDouble()
        println(
            "P5_DOP_RELATIVE_BENCH packets=$PACKETS_PER_SAMPLE currentMedianNs=$currentMedian " +
                "granularMedianNs=$granularMedian batchedMedianNs=$batchedMedian " +
                "currentVsBatch=${"%.3f".format(java.util.Locale.ROOT, ratio)} " +
                "currentVsGranular=${"%.3f".format(java.util.Locale.ROOT, currentVsGranular)} " +
                "granularVsBatch=${"%.3f".format(java.util.Locale.ROOT, granularVsBatch)}",
        )

        // This is deliberately relative-only and non-gating: timing thresholds would make the JVM
        // suite machine-load dependent. The deterministic operation model and byte-exact comparison
        // freeze the seam; this probe only records relative ranking evidence for the current run.
        assertTrue(currentMedian > 0L)
        assertTrue(granularMedian > 0L)
        assertTrue(batchedMedian > 0L)
    }

    private fun measurePackets(source: ByteArray, operation: (ByteArray) -> Unit): Long {
        val start = System.nanoTime()
        repeat(PACKETS_PER_SAMPLE) { operation(source) }
        return System.nanoTime() - start
    }

    private class CurrentProbe {
        private val session = DoPCarrierSession(plan())
        private val destination = ByteArray(CARRIER_BYTES)

        fun run(source: ByteArray) {
            val result = session.writeContentBytes(source, destination = destination)
            check(result.canonicalBytesConsumed == SOURCE_BYTES)
            check(result.carrierBytesEmitted == CARRIER_BYTES)
        }
    }

    private class GranularPrimitiveProbe {
        private val encoder = DoPEncoder(CHANNELS)
        private val words = IntArray(CHANNELS)
        private val packed = ByteArray(CHANNELS * 3)
        private var checksum = 0

        fun run(source: ByteArray) {
            var frame = 0
            while (frame < CANONICAL_FRAMES) {
                val produced = encoder.encodeFrames(
                    source = source,
                    sourceOffset = frame * CHANNELS,
                    frameCount = 1,
                    destinationWords = words,
                )
                if (produced == 1) {
                    check(
                        DoPEncoder.packWords(
                            words = words,
                            wordCount = CHANNELS,
                            packing = DoPCarrierPacking.PACKED_24_LE,
                            destination = packed,
                        ) == packed.size,
                    )
                    checksum = checksum xor (packed[0].toInt() and 0xff)
                }
                frame++
            }
            check(!encoder.hasPendingHalfFrame())
            check(checksum >= 0)
        }
    }

    private class BatchedProbe {
        private val encoder = DoPEncoder(CHANNELS)
        private val words = IntArray(CANONICAL_FRAMES)
        private val destination = ByteArray(CARRIER_BYTES)

        fun run(source: ByteArray) {
            val produced = encoder.encodeFrames(source, frameCount = CANONICAL_FRAMES, destinationWords = words)
            check(produced == RUNTIME_FRAMES)
            check(
                DoPEncoder.packWords(
                    words = words,
                    wordCount = produced * CHANNELS,
                    packing = DoPCarrierPacking.PACKED_24_LE,
                    destination = destination,
                ) == CARRIER_BYTES,
            )
        }
    }

    private fun sourcePacket(): ByteArray = ByteArray(SOURCE_BYTES) { index ->
        ((index * 73 + 19) and 0xff).toByte()
    }

    private fun median(values: LongArray): Long {
        val sorted = values.sortedArray()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            ((sorted[sorted.size / 2 - 1].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0).roundToLong()
        }
    }

    private fun loadOperationModel(): List<OperationRow> {
        val lines = fixture("dop-hotpath-operation-model-v1.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(
            listOf("stage", "operation", "callsPerPacket", "bytesTouchedOrAllocatedPerPacket", "classification", "note"),
            lines.first().split('\t'),
        )
        return lines.drop(1).map { line ->
            val c = line.split('\t')
            require(c.size == 6)
            OperationRow(c[0], c[1], c[2].toLong(), c[3].toLong(), c[4], c[5])
        }
    }

    private fun fixture(name: String): File = listOf(
        File("src/test/resources/usb/p5/$name"),
        File("app/src/test/resources/usb/p5/$name"),
    ).firstOrNull(File::isFile) ?: error("P5 fixture is missing: $name")

    private data class OperationRow(
        val stage: String,
        val operation: String,
        val calls: Long,
        val bytesMoved: Long,
        val classification: String,
        val note: String,
    )

    companion object {
        private const val CHANNELS = 2
        private const val SOURCE_BYTES = 8_192
        private const val CANONICAL_FRAMES = SOURCE_BYTES / CHANNELS
        private const val RUNTIME_FRAMES = CANONICAL_FRAMES / 2
        private const val CARRIER_BYTES = RUNTIME_FRAMES * CHANNELS * 3
        private const val WARMUP_PACKETS = 12
        private const val SAMPLES = 7
        private const val PACKETS_PER_SAMPLE = 24

        private fun plan() = DoPCarrierPlan(
            dsdBitRateHz = 5_644_800L,
            channelCount = CHANNELS,
            runtimeFrameRateHz = 352_800L,
            bytesPerRuntimeFrame = 6,
            packing = DoPCarrierPacking.PACKED_24_LE,
            maxRuntimeFramesPerServiceInterval = 45L,
            requiredMaxBytesPerServiceInterval = 270L,
        )
    }
}
