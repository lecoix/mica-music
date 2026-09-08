package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverySafetyModelsTest {

    @Test
    fun emptyReportFailsClosedAsPartial() {
        val report = DiscoveryReport()

        assertEquals(DiscoveryCompleteness.PARTIAL, report.aggregate)
        assertFalse(report.isComplete(DiscoveryPartitions.MEDIASTORE_AUDIO))
    }

    @Test
    fun allCompletePartitionsProduceCompleteAggregate() {
        val report = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_AUDIO,
                DiscoveryCompleteness.COMPLETE,
            ),
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                DiscoveryCompleteness.COMPLETE,
            ),
        )

        assertEquals(DiscoveryCompleteness.COMPLETE, report.aggregate)
        assertTrue(report.isComplete(DiscoveryPartitions.MEDIASTORE_AUDIO))
        assertTrue(report.isComplete(DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK))
    }

    @Test
    fun mixedCompletenessProducesPartialAggregate() {
        val report = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_AUDIO,
                DiscoveryCompleteness.COMPLETE,
            ),
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                DiscoveryCompleteness.PARTIAL,
                "provider query failed",
            ),
        )

        assertEquals(DiscoveryCompleteness.PARTIAL, report.aggregate)
        assertTrue(report.isComplete(DiscoveryPartitions.MEDIASTORE_AUDIO))
        assertFalse(report.isComplete(DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK))
    }

    @Test
    fun allUnavailablePartitionsProduceUnavailableAggregate() {
        val report = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_AUDIO,
                DiscoveryCompleteness.UNAVAILABLE,
            ),
            DiscoveryPartitionStatus(
                DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                DiscoveryCompleteness.UNAVAILABLE,
            ),
        )

        assertEquals(DiscoveryCompleteness.UNAVAILABLE, report.aggregate)
    }
}
