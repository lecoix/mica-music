package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.scanner.DeviceGenerationSnapshot
import com.mica.music.data.scanner.DeviceVolumeGeneration

/**
 * Durable DEVICE generation anchor codec.
 *
 * One Room checkpoint row owns one MediaStore volume. The complete set is replaced after a
 * successful DEVICE Full Scan or validated DEVICE AUTO publication; a restart may restore the
 * in-memory generation cursor only when every restored row belongs to the current source/config.
 */
internal object DeviceGenerationCheckpointCodec {
    private const val PARTITION_PREFIX = "mediastore-volume:"

    fun replacementMutation(
        sourceIdentity: SourceIdentityKey,
        snapshot: DeviceGenerationSnapshot.Available,
        configFingerprint: String,
        committedAtMs: Long,
        existingCheckpoints: List<LibrarySyncCheckpoint>,
    ): LibraryAutoSyncStateMutation {
        require(sourceIdentity.source == ScanSource.DEVICE)
        val checkpoints = snapshot.volumes
            .toSortedMap()
            .map { (volumeName, state) ->
                LibrarySyncCheckpoint(
                    sourceIdentity = sourceIdentity,
                    partitionKey = partitionKey(volumeName),
                    providerVersion = state.providerVersion,
                    generation = state.generation,
                    configFingerprint = configFingerprint,
                    lastSuccessfulAutoSyncAtMs = committedAtMs,
                )
            }
        val nextKeys = checkpoints.mapTo(linkedSetOf(), LibrarySyncCheckpoint::partitionKey)
        val staleKeys = existingCheckpoints.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .map(LibrarySyncCheckpoint::partitionKey)
            .filter(::isDeviceVolumePartition)
            .filterNot(nextKeys::contains)
            .toSortedSet()
        return LibraryAutoSyncStateMutation(
            sourceIdentity = sourceIdentity,
            checkpoints = checkpoints,
            checkpointDeleteKeys = staleKeys,
        )
    }

    fun restoreSnapshot(
        sourceIdentity: SourceIdentityKey,
        configFingerprint: String,
        checkpoints: List<LibrarySyncCheckpoint>,
    ): DeviceGenerationSnapshot.Available? {
        if (sourceIdentity.source != ScanSource.DEVICE) return null
        val matching = checkpoints.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.configFingerprint == configFingerprint }
            .filter { isDeviceVolumePartition(it.partitionKey) }
            .toList()
        if (matching.isEmpty()) return null

        val volumes = linkedMapOf<String, DeviceVolumeGeneration>()
        matching.sortedBy(LibrarySyncCheckpoint::partitionKey).forEach { checkpoint ->
            val volumeName = volumeName(checkpoint.partitionKey) ?: return null
            if (checkpoint.providerVersion.isBlank() || checkpoint.generation < 0L) return null
            if (volumes.containsKey(volumeName)) return null
            volumes[volumeName] = DeviceVolumeGeneration(
                volumeName = volumeName,
                providerVersion = checkpoint.providerVersion,
                generation = checkpoint.generation,
            )
        }
        return DeviceGenerationSnapshot.Available(volumes)
    }

    internal fun partitionKey(volumeName: String): String {
        require(volumeName.isNotBlank())
        return PARTITION_PREFIX + volumeName
    }

    internal fun isDeviceVolumePartition(partitionKey: String): Boolean =
        partitionKey.startsWith(PARTITION_PREFIX) &&
            partitionKey.length > PARTITION_PREFIX.length

    private fun volumeName(partitionKey: String): String? =
        partitionKey.takeIf(::isDeviceVolumePartition)
            ?.removePrefix(PARTITION_PREFIX)
            ?.takeIf(String::isNotBlank)
}
