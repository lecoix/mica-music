package com.mica.music.data.scanner

import android.provider.MediaStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaStorePresenceCapabilityTest {

    @Test
    fun destructiveAbsenceRequiresColumnsInclusionPermissionAndVolumeScope() {
        val safe = capability()

        assertTrue(safe.destructiveAbsenceSafe)
        assertFalse(
            safe.copy(
                columnsAvailable = setOf(MediaStore.MediaColumns.IS_PENDING),
            ).destructiveAbsenceSafe,
        )
        assertFalse(
            safe.copy(rowInclusionSemanticsKnown = false).destructiveAbsenceSafe,
        )
        assertFalse(
            safe.copy(permissionScopeComplete = false).destructiveAbsenceSafe,
        )
        assertFalse(
            safe.copy(permissionScope = "").destructiveAbsenceSafe,
        )
        assertFalse(
            safe.copy(volumeScope = "").destructiveAbsenceSafe,
        )
    }

    @Test
    fun profileIsPartitionSpecificAndUnknownNeverAuthorizesAbsence() {
        val audio = capability(partition = DiscoveryPartitions.MEDIASTORE_AUDIO)
        val profile = DeviceMediaStorePresenceCapabilityProfile(
            channels = mapOf(audio.partitionKey to audio),
        )

        assertTrue(profile.destructiveAbsenceSafe(DiscoveryPartitions.MEDIASTORE_AUDIO))
        assertFalse(
            profile.destructiveAbsenceSafe(
                DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
            ),
        )
        assertFalse(
            DeviceMediaStorePresenceCapabilityProfile.Unknown
                .destructiveAbsenceSafe(DiscoveryPartitions.MEDIASTORE_AUDIO),
        )
        assertFalse(DeviceMediaStorePresenceCapabilityProfile.Unknown.allChannelsDestructiveAbsenceSafe)
    }

    private fun capability(
        partition: String = DiscoveryPartitions.MEDIASTORE_AUDIO,
    ) = DeviceMediaStoreChannelCapability(
        partitionKey = partition,
        columnsAvailable = setOf(
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
        ),
        rowInclusionSemanticsKnown = true,
        permissionScope = "READ_MEDIA_AUDIO=granted",
        permissionScopeComplete = true,
        volumeScope = "external-aggregate",
        apiLevel = 35,
    )
}
