package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.scanner.DeviceGenerationSnapshot
import com.mica.music.data.scanner.DeviceVolumeGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGenerationCheckpointCodecTest {

    @Test
    fun replacementMutationReplacesPerVolumeAnchorAndDeletesStaleVolumes() {
        val source = SourceIdentityKey(ScanSource.DEVICE, "mediastore:external")
        val existing = listOf(
            checkpoint(source, "external_primary", "v0", 9L, "old-config"),
            checkpoint(source, "stale-volume", "v0", 3L, "old-config"),
            LibrarySyncCheckpoint(
                sourceIdentity = source,
                partitionKey = "other-partition",
                providerVersion = "keep",
                generation = 1L,
                configFingerprint = "old-config",
                lastSuccessfulAutoSyncAtMs = 1L,
            ),
        )
        val snapshot = DeviceGenerationSnapshot.Available(
            mapOf(
                "external_primary" to DeviceVolumeGeneration(
                    volumeName = "external_primary",
                    providerVersion = "v1",
                    generation = 10L,
                ),
                "0123-4567" to DeviceVolumeGeneration(
                    volumeName = "0123-4567",
                    providerVersion = "sd-v2",
                    generation = 20L,
                ),
            ),
        )

        val mutation = DeviceGenerationCheckpointCodec.replacementMutation(
            sourceIdentity = source,
            snapshot = snapshot,
            configFingerprint = "config-2",
            committedAtMs = 1234L,
            existingCheckpoints = existing,
        )

        assertEquals(
            setOf(DeviceGenerationCheckpointCodec.partitionKey("stale-volume")),
            mutation.checkpointDeleteKeys,
        )
        assertEquals(
            listOf("0123-4567", "external_primary"),
            mutation.checkpoints.map {
                it.partitionKey.removePrefix("mediastore-volume:")
            },
        )
        assertTrue(mutation.checkpoints.all { it.configFingerprint == "config-2" })
        assertTrue(mutation.checkpoints.all { it.lastSuccessfulAutoSyncAtMs == 1234L })
    }

    @Test
    fun restoreSnapshotRequiresCurrentDeviceSourceAndConfig() {
        val source = SourceIdentityKey(ScanSource.DEVICE, "mediastore:external")
        val checkpoints = listOf(
            checkpoint(source, "external_primary", "v1", 10L, "config"),
            checkpoint(source, "0123-4567", "v2", 20L, "config"),
        )

        val restored = DeviceGenerationCheckpointCodec.restoreSnapshot(
            sourceIdentity = source,
            configFingerprint = "config",
            checkpoints = checkpoints,
        )

        assertEquals(
            mapOf(
                "0123-4567" to DeviceVolumeGeneration("0123-4567", "v2", 20L),
                "external_primary" to DeviceVolumeGeneration("external_primary", "v1", 10L),
            ),
            restored?.volumes?.toSortedMap(),
        )
        assertNull(
            DeviceGenerationCheckpointCodec.restoreSnapshot(
                sourceIdentity = source,
                configFingerprint = "changed-config",
                checkpoints = checkpoints,
            ),
        )
        assertNull(
            DeviceGenerationCheckpointCodec.restoreSnapshot(
                sourceIdentity = SourceIdentityKey(ScanSource.FOLDER, "tree"),
                configFingerprint = "config",
                checkpoints = checkpoints,
            ),
        )
    }

    private fun checkpoint(
        source: SourceIdentityKey,
        volumeName: String,
        providerVersion: String,
        generation: Long,
        config: String,
    ) = LibrarySyncCheckpoint(
        sourceIdentity = source,
        partitionKey = DeviceGenerationCheckpointCodec.partitionKey(volumeName),
        providerVersion = providerVersion,
        generation = generation,
        configFingerprint = config,
        lastSuccessfulAutoSyncAtMs = 1L,
    )
}
