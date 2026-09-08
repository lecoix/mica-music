package com.mica.music.data.scanner

sealed interface Observed<out T> {
    data object Unknown : Observed<Nothing>
    data class Present<T>(val value: T) : Observed<T>
    data object AbsentConfirmed : Observed<Nothing>
}

internal fun <T> Observed<T>.applyTo(current: T?): T? = when (this) {
    Observed.Unknown -> current
    is Observed.Present -> value
    Observed.AbsentConfirmed -> null
}

enum class LibraryEligibility {
    ELIGIBLE,
    FILTERED_OUT,
    TRASHED,
    PENDING,
}

/**
 * Positive physical-presence evidence. Missing keys only become negative evidence when the
 * corresponding [DiscoveryReport] partition is COMPLETE.
 */
data class PresenceEntry(
    val stableObjectKey: String,
    val partitionKey: String,
    val eligibility: LibraryEligibility,
    val evidenceRevision: String,
) {
    init {
        require(stableObjectKey.isNotBlank())
        require(partitionKey.isNotBlank())
    }
}

data class PresenceKey(
    val stableObjectKey: String,
    val partitionKey: String,
)

data class PresenceInventory(
    val entries: Map<PresenceKey, PresenceEntry>,
    val discoveryReport: DiscoveryReport,
    val deviceMediaStoreCapabilityProfile: DeviceMediaStorePresenceCapabilityProfile? = null,
) {
    fun entry(stableObjectKey: String, partitionKey: String): PresenceEntry? =
        entries[PresenceKey(stableObjectKey, partitionKey)]

    /**
     * Absence is only authoritative when the exact owning partition was completely enumerated.
     */
    fun absenceConfirmed(
        stableObjectKey: String,
        partitionKey: String,
    ): Boolean {
        if (PresenceKey(stableObjectKey, partitionKey) in entries) return false
        if (!discoveryReport.isComplete(partitionKey)) return false
        val mediaStoreCapability = deviceMediaStoreCapabilityProfile
        return mediaStoreCapability == null ||
            mediaStoreCapability.destructiveAbsenceSafe(partitionKey)
    }

    companion object {
        fun fromEntries(
            entries: Collection<PresenceEntry>,
            discoveryReport: DiscoveryReport,
            deviceMediaStoreCapabilityProfile: DeviceMediaStorePresenceCapabilityProfile? = null,
        ): PresenceInventory = PresenceInventory(
            entries = entries.associateBy { PresenceKey(it.stableObjectKey, it.partitionKey) },
            discoveryReport = discoveryReport,
            deviceMediaStoreCapabilityProfile = deviceMediaStoreCapabilityProfile,
        )
    }
}

data class AutoSyncVisibleDelta(
    val addedIds: Set<String> = emptySet(),
    val updatedIds: Set<String> = emptySet(),
    val removedStableObjectKeys: Set<String> = emptySet(),
    val derivedInvalidationKeys: Set<String> = emptySet(),
) {
    val hasVisibleChanges: Boolean
        get() = addedIds.isNotEmpty() ||
            updatedIds.isNotEmpty() ||
            removedStableObjectKeys.isNotEmpty() ||
            derivedInvalidationKeys.isNotEmpty()
}

enum class AutoSyncPublicationDecision {
    CHECKPOINT_ONLY,
    SNAPSHOT_PUBLICATION,
}

fun AutoSyncVisibleDelta.publicationDecision(): AutoSyncPublicationDecision =
    if (hasVisibleChanges) {
        AutoSyncPublicationDecision.SNAPSHOT_PUBLICATION
    } else {
        AutoSyncPublicationDecision.CHECKPOINT_ONLY
    }
