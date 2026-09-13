package com.mica.music.data.library

internal object LibraryFollowupProtocol {
    const val PLAYLIST_REMOVE_CONFIRMED_MISSING = "PLAYLIST_REMOVE_CONFIRMED_MISSING"
    private const val SONG_ID_PREFIX = "songId="

    fun playlistRemovalPayload(songId: String): String = "$SONG_ID_PREFIX$songId"

    fun playlistRemovalSongId(payload: String): String? =
        payload.takeIf { it.startsWith(SONG_ID_PREFIX) }
            ?.removePrefix(SONG_ID_PREFIX)
            ?.takeIf(String::isNotBlank)

    fun playlistRemovalEventId(
        sourceIdentity: SourceIdentityKey,
        stableObjectKey: String,
        evidenceRevision: String,
    ): String =
        "playlist-remove-confirmed-missing:${sourceIdentity.storageKey()}:$stableObjectKey:$evidenceRevision"

    fun planPlaylistRemovalFollowups(
        changeSet: LibraryChangeSet,
        activationEpoch: Long?,
        createdAtMs: Long,
    ): List<LibraryFollowupOutboxItem> {
        if (!changeSet.cause.isAutoSyncCause()) return emptyList()
        return changeSet.membershipChanges
            .asSequence()
            .filter { it.reason == MembershipRemovalReason.CONFIRMED_MISSING }
            .mapNotNull { change ->
                val songId = change.songId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val evidenceRevision =
                    change.evidenceRevision.takeIf(String::isNotBlank) ?: return@mapNotNull null
                LibraryFollowupOutboxItem(
                    eventId = playlistRemovalEventId(
                        sourceIdentity = change.sourceIdentity,
                        stableObjectKey = change.stableObjectKey,
                        evidenceRevision = evidenceRevision,
                    ),
                    libraryRevision = changeSet.libraryRevision,
                    action = PLAYLIST_REMOVE_CONFIRMED_MISSING,
                    sourceIdentity = change.sourceIdentity,
                    activationEpoch = activationEpoch,
                    stableObjectKey = change.stableObjectKey,
                    evidenceRevision = evidenceRevision,
                    removalReason = change.reason,
                    payload = playlistRemovalPayload(songId),
                    createdAtMs = createdAtMs,
                )
            }
            .distinctBy(LibraryFollowupOutboxItem::eventId)
            .toList()
    }
}

internal fun LibraryOperationCause.isAutoSyncCause(): Boolean =
    when (this) {
        LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
        LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
        LibraryOperationCause.MEDIA_SCANNER_FINISHED,
        LibraryOperationCause.STORAGE_CHANGED,
        LibraryOperationCause.FOREGROUND_CATCH_UP,
        LibraryOperationCause.PLAYBACK_IO_RELEASE,
        LibraryOperationCause.DEVICE_RETRY_DUE,
        LibraryOperationCause.SAF_TREE_DIRTY,
        LibraryOperationCause.SAF_PERIODIC_VERIFY,
        LibraryOperationCause.SAF_BUDGET_CONTINUATION,
        LibraryOperationCause.SAF_RETRY_DUE,
        -> true
        LibraryOperationCause.INITIAL_SCAN,
        LibraryOperationCause.USER_RESCAN,
        LibraryOperationCause.SOURCE_SWITCH,
        LibraryOperationCause.TAG_EDITOR_RETURN,
        LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
        LibraryOperationCause.LOCAL_USER_DELETE,
        LibraryOperationCause.ARTWORK_REPAIR,
        -> false
    }
