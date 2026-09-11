package com.mica.music.data.library

/** Follow-up work requested by an AUTO pipeline after its scan/publication phase finishes. */
internal data class AutoSyncPostCommit(
    val actions: List<AutoSyncPostCommitAction>,
) {
    companion object {
        val Empty = AutoSyncPostCommit(emptyList())
    }
}

internal sealed interface AutoSyncPostCommitAction {
    data class RetryWake(
        val cause: LibraryOperationCause,
        val delayMs: Long?,
        val sourceIdentity: SourceIdentityKey,
        val activationEpoch: Long,
    ) : AutoSyncPostCommitAction

    data class DirtyFollowUp(
        val cause: LibraryOperationCause,
    ) : AutoSyncPostCommitAction

    data class AutoContinuation(
        val cause: LibraryOperationCause,
    ) : AutoSyncPostCommitAction

    data class ArtworkHydration(
        val songIds: Set<String>,
    ) : AutoSyncPostCommitAction
}

/** Mutable request collector scoped to one AUTO execution. */
internal class AutoSyncPostCommitCollector {
    private val actions = mutableListOf<AutoSyncPostCommitAction>()

    fun requestRetryWake(
        cause: LibraryOperationCause,
        delayMs: Long?,
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
    ) {
        actions += AutoSyncPostCommitAction.RetryWake(
            cause = cause,
            delayMs = delayMs,
            sourceIdentity = sourceIdentity,
            activationEpoch = activationEpoch,
        )
    }

    fun requestDirtyFollowUp(cause: LibraryOperationCause) {
        actions += AutoSyncPostCommitAction.DirtyFollowUp(cause)
    }

    fun requestAutoContinuation(cause: LibraryOperationCause) {
        actions += AutoSyncPostCommitAction.AutoContinuation(cause)
    }

    fun requestArtworkHydration(songIds: Set<String>) {
        if (songIds.isNotEmpty()) {
            actions += AutoSyncPostCommitAction.ArtworkHydration(songIds.toSet())
        }
    }

    fun build(): AutoSyncPostCommit =
        if (actions.isEmpty()) AutoSyncPostCommit.Empty else AutoSyncPostCommit(actions.toList())
}
