package com.mica.music.data.scanner

import android.provider.MediaStore

/**
 * Runtime evidence describing what a MediaStore Presence partition can actually prove.
 *
 * Query success and projection support are deliberately separate from hidden-row inclusion
 * semantics. Destructive absence requires both pending/trashed columns plus known include
 * semantics for the queried permission/volume scope.
 */
data class DeviceMediaStoreChannelCapability(
    val partitionKey: String,
    val columnsAvailable: Set<String>,
    val rowInclusionSemanticsKnown: Boolean,
    val permissionScope: String,
    val permissionScopeComplete: Boolean,
    val volumeScope: String,
    val apiLevel: Int,
) {
    val destructiveAbsenceSafe: Boolean
        get() =
            MediaStore.MediaColumns.IS_PENDING in columnsAvailable &&
                MediaStore.MediaColumns.IS_TRASHED in columnsAvailable &&
                rowInclusionSemanticsKnown &&
                permissionScope.isNotBlank() &&
                permissionScopeComplete &&
                volumeScope.isNotBlank()
}

data class DeviceMediaStorePresenceCapabilityProfile(
    val channels: Map<String, DeviceMediaStoreChannelCapability>,
) {
    fun destructiveAbsenceSafe(partitionKey: String): Boolean =
        channels[partitionKey]?.destructiveAbsenceSafe == true

    val allChannelsDestructiveAbsenceSafe: Boolean
        get() = channels.isNotEmpty() &&
            channels.values.all(DeviceMediaStoreChannelCapability::destructiveAbsenceSafe)

    companion object {
        val Unknown = DeviceMediaStorePresenceCapabilityProfile(emptyMap())
    }
}
