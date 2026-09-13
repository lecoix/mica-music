package com.mica.music.data.library

import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.data.ScanSource

internal enum class LibraryOperationMode {
    FULL,
    AUTO_SYNC,
    TARGETED_REFRESH,
    ARTWORK_REPAIR,
}

internal data class LibraryScanSideEffectPolicy(
    val ownsGlobalLyricsMaintenance: Boolean,
    val publishUserScanMetadata: Boolean,
    val runAlbumArtMaintenance: Boolean,
    val prefetchVideoCoverPosters: Boolean,
    val clearTransientScanCache: Boolean,
) {
    companion object {
        fun forMode(mode: LibraryOperationMode): LibraryScanSideEffectPolicy =
            when (mode) {
                LibraryOperationMode.FULL -> LibraryScanSideEffectPolicy(
                    ownsGlobalLyricsMaintenance = true,
                    publishUserScanMetadata = true,
                    runAlbumArtMaintenance = true,
                    prefetchVideoCoverPosters = true,
                    clearTransientScanCache = true,
                )
                LibraryOperationMode.TARGETED_REFRESH -> LibraryScanSideEffectPolicy(
                    ownsGlobalLyricsMaintenance = false,
                    publishUserScanMetadata = false,
                    runAlbumArtMaintenance = false,
                    prefetchVideoCoverPosters = false,
                    clearTransientScanCache = true,
                )
                LibraryOperationMode.AUTO_SYNC -> LibraryScanSideEffectPolicy(
                    ownsGlobalLyricsMaintenance = false,
                    publishUserScanMetadata = false,
                    runAlbumArtMaintenance = false,
                    prefetchVideoCoverPosters = false,
                    clearTransientScanCache = false,
                )
                LibraryOperationMode.ARTWORK_REPAIR -> LibraryScanSideEffectPolicy(
                    ownsGlobalLyricsMaintenance = false,
                    publishUserScanMetadata = false,
                    runAlbumArtMaintenance = false,
                    prefetchVideoCoverPosters = false,
                    clearTransientScanCache = false,
                )
            }
    }
}

internal enum class LibraryOperationCause {
    INITIAL_SCAN,
    USER_RESCAN,
    SOURCE_SWITCH,
    MEDIASTORE_AUDIO_DIRTY,
    MEDIASTORE_FILES_DIRTY,
    MEDIA_SCANNER_FINISHED,
    STORAGE_CHANGED,
    FOREGROUND_CATCH_UP,
    PLAYBACK_IO_RELEASE,
    DEVICE_RETRY_DUE,
    SAF_TREE_DIRTY,
    SAF_PERIODIC_VERIFY,
    SAF_BUDGET_CONTINUATION,
    SAF_RETRY_DUE,
    TAG_EDITOR_RETURN,
    AUTO_ARTWORK_HYDRATE,
    LOCAL_USER_DELETE,
    ARTWORK_REPAIR,
}

internal enum class LibraryIntentState {
    UNINITIALIZED,
    ACTIVE,
    CLEARED_BY_USER,
}

internal enum class LibraryAccessState {
    AVAILABLE,
    TEMP_UNAVAILABLE,
    PERMISSION_REQUIRED,
}

internal const val SAF_PROVIDER_RESELECT_REQUIRED_ERROR =
    "曲库提供方暂不可用，请重新选择曲库文件夹"

internal data class SourceIdentityKey(
    val source: ScanSource,
    val stableIdentity: String,
) {
    init {
        require(stableIdentity.isNotBlank())
    }

    fun storageKey(): String = "${source.storageValue}|$stableIdentity"

    fun folderTreeUriOrNull(): String? =
        stableIdentity.takeIf { source == ScanSource.FOLDER && it.startsWith(FOLDER_PREFIX) }
            ?.removePrefix(FOLDER_PREFIX)
            ?.takeIf(String::isNotBlank)

    companion object {
        private const val FOLDER_PREFIX = "saf:"
        private const val DEVICE_STABLE_IDENTITY = "mediastore:external"

        fun device(): SourceIdentityKey =
            SourceIdentityKey(ScanSource.DEVICE, DEVICE_STABLE_IDENTITY)

        fun folder(treeUri: String): SourceIdentityKey =
            SourceIdentityKey(ScanSource.FOLDER, "$FOLDER_PREFIX$treeUri")
    }
}

internal data class SourceActivation(
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
)

internal data class LibrarySourceState(
    val active: SourceActivation? = null,
    val pendingTransition: SourceActivation? = null,
)

internal data class PersistedLibraryState(
    val intent: LibraryIntentState = LibraryIntentState.UNINITIALIZED,
    val access: LibraryAccessState = LibraryAccessState.AVAILABLE,
    val sourceState: LibrarySourceState = LibrarySourceState(),
    val configFingerprint: String = "",
)

internal data class LibraryShadowObservationStamp(
    val libraryGeneration: Int,
    val sourceActivation: SourceActivation,
    val configFingerprint: String,
    val shadowAuthorityRevision: Long,
    val intent: LibraryIntentState,
    val access: LibraryAccessState,
)
internal data class LibraryOperationToken(
    val libraryGeneration: Int,
    val requestSequence: Long,
    val dirtySequenceAtStart: Long,
    val mode: LibraryOperationMode,
    val cause: LibraryOperationCause,
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
    val configFingerprint: String,
    val catalogRevisionAtStart: Long,
    val presentationRevisionAtStart: Long,
    val autoSyncGateEnforced: Boolean = false,
)

internal data class ScheduledLibraryOperation(
    val request: LibraryOperationRequest,
    val requestSequence: Long,
    val dirtySequenceAtStart: Long,
)

internal enum class LibraryRetryKind {
    OBJECT_PROBE,
    DISCOVERY_PARTITION,
    UNKNOWN_FINGERPRINT_VERIFY,
}

internal data class LibrarySyncCheckpoint(
    val sourceIdentity: SourceIdentityKey,
    val partitionKey: String,
    val providerVersion: String,
    val generation: Long,
    val configFingerprint: String,
    val lastSuccessfulAutoSyncAtMs: Long,
)

internal data class LibraryRetryItem(
    val sourceIdentity: SourceIdentityKey,
    val retryKey: String,
    val activationEpoch: Long?,
    val stableObjectKey: String,
    val observedFingerprint: String,
    val retryKind: LibraryRetryKind,
    val failureKind: String,
    val attemptCount: Int,
    val nextRetryAtMs: Long,
    val continuationCursor: Int = 0,
    /** Durable object-level missing proof accumulated across confirmation batches. */
    val confirmedMissingKeysPayload: String = "",
) {
    init {
        require(continuationCursor >= 0)
    }
}

internal data class LibraryAutoSyncStateMutation(
    val sourceIdentity: SourceIdentityKey,
    val checkpoints: List<LibrarySyncCheckpoint> = emptyList(),
    val checkpointDeleteKeys: Set<String> = emptySet(),
    val retryUpserts: List<LibraryRetryItem> = emptyList(),
    val retryDeleteKeys: Set<String> = emptySet(),
) {
    init {
        require(checkpoints.all { it.sourceIdentity == sourceIdentity })
        require(retryUpserts.all { it.sourceIdentity == sourceIdentity })
    }
}

internal enum class LyricsStagingMode {
    FULL_REPLACE,
    EXTERNAL_ONLY,
}

internal data class LibraryFollowupOutboxItem(
    val eventId: String,
    val libraryRevision: Long,
    val action: String,
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long?,
    val stableObjectKey: String,
    val evidenceRevision: String,
    val removalReason: MembershipRemovalReason,
    val payload: String,
    val createdAtMs: Long,
)

internal data class LibraryUserExclusion(
    val sourceIdentity: SourceIdentityKey,
    val stableObjectKey: String,
    val exclusionRevision: Long,
    val createdAtMs: Long,
)

internal enum class MembershipRemovalReason {
    CONFIRMED_MISSING,
    FILTERED_OUT,
    TRASHED,
    SOURCE_REPLACED,
    USER_EXCLUDED,
    UNAVAILABLE,
}

internal data class MembershipChange(
    val stableObjectKey: String,
    val songId: String?,
    val reason: MembershipRemovalReason,
    val evidenceRevision: String,
    val sourceIdentity: SourceIdentityKey,
)

internal data class LibraryChangeSet(
    val libraryRevision: Long,
    val cause: LibraryOperationCause,
    val addedIds: Set<String>,
    val updatedIds: Set<String>,
    val membershipChanges: List<MembershipChange>,
)

internal sealed interface LibraryOperationRequest {
    val mode: LibraryOperationMode
    val cause: LibraryOperationCause

    data object Rescan : LibraryOperationRequest {
        override val mode = LibraryOperationMode.FULL
        override val cause = LibraryOperationCause.USER_RESCAN
    }

    data object ScanDeviceWide : LibraryOperationRequest {
        override val mode = LibraryOperationMode.FULL
        override val cause = LibraryOperationCause.USER_RESCAN
    }

    data object ScanLibraryFolder : LibraryOperationRequest {
        override val mode = LibraryOperationMode.FULL
        override val cause = LibraryOperationCause.USER_RESCAN
    }

    data class TargetedRefresh(
        val songIds: Set<String>,
        override val cause: LibraryOperationCause = LibraryOperationCause.TAG_EDITOR_RETURN,
    ) : LibraryOperationRequest {
        override val mode = LibraryOperationMode.TARGETED_REFRESH
    }

    data class ArtworkRepair(
        val plan: AlbumArtRepairPlan,
    ) : LibraryOperationRequest {
        override val mode = LibraryOperationMode.ARTWORK_REPAIR
        override val cause = LibraryOperationCause.ARTWORK_REPAIR
    }

    data class AutoSync(
        override val cause: LibraryOperationCause,
        val coalescedCauses: Set<LibraryOperationCause> = setOf(cause),
        val mediaStoreUriHints: Set<String> = emptySet(),
        val mediaStoreHintIncomplete: Boolean = false,
    ) : LibraryOperationRequest {
        override val mode = LibraryOperationMode.AUTO_SYNC
    }
}
