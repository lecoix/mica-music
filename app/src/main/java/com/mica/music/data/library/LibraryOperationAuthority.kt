package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns operation-token and source-activation eligibility rules. */
internal class LibraryOperationAuthority(
    private val backing: MusicLibraryBacking,
) {
    private var nextActivationEpoch = 0L

    fun isActiveGeneration(generation: Int): Boolean =
        !backing.released && !backing.releaseRequested && generation == backing.scanGeneration

    fun sourceIdentityFor(source: ScanSource): SourceIdentityKey? = when (source) {
        ScanSource.DEVICE -> SourceIdentityKey.device()
        ScanSource.FOLDER -> (backing.pendingLibraryFolderUri ?: backing.libraryFolderUri)
            ?.takeIf(String::isNotBlank)
            ?.let(SourceIdentityKey::folder)
    }

    suspend fun captureShadowObservationStamp(
        expectedSource: ScanSource,
    ): LibraryShadowObservationStamp? = backing.publicationMutex.withLock {
        if (backing.released || backing.releaseRequested) return@withLock null
        if (backing.intentState != LibraryIntentState.ACTIVE) return@withLock null
        if (backing.accessState != LibraryAccessState.AVAILABLE) return@withLock null
        val active = backing.sourceState.active ?: return@withLock null
        if (active.sourceIdentity.source != expectedSource) return@withLock null
        LibraryShadowObservationStamp(
            libraryGeneration = backing.scanGeneration,
            sourceActivation = active,
            configFingerprint = LibraryScanSettings.configFingerprint(backing.context),
            shadowAuthorityRevision = backing.shadowAuthorityRevision,
            intent = backing.intentState,
            access = backing.accessState,
        )
    }

    suspend fun beginActiveAutoSyncOperationToken(
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        cause: LibraryOperationCause,
        enforceAutoSyncGate: Boolean = true,
    ): LibraryOperationToken? = backing.publicationMutex.withLock {
        if (backing.released || backing.releaseRequested) return@withLock null
        if (backing.intentState != LibraryIntentState.ACTIVE) return@withLock null
        if (backing.accessState != LibraryAccessState.AVAILABLE) return@withLock null
        val active = backing.sourceState.active ?: return@withLock null
        if (enforceAutoSyncGate && !backing.autoSyncEnabled(active.sourceIdentity.source)) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "feature-gate disabled source=${active.sourceIdentity.source} " +
                    "request=$requestSequence cause=$cause",
            )
            return@withLock null
        }
        val currentFingerprint = LibraryScanSettings.configFingerprint(backing.context)
        val generation = ++backing.scanGeneration
        backing.configFingerprint = currentFingerprint
        LibraryOperationToken(
            libraryGeneration = generation,
            requestSequence = requestSequence,
            dirtySequenceAtStart = dirtySequenceAtStart,
            mode = LibraryOperationMode.AUTO_SYNC,
            cause = cause,
            sourceIdentity = active.sourceIdentity,
            activationEpoch = active.activationEpoch,
            configFingerprint = currentFingerprint,
            catalogRevisionAtStart = backing.catalogRevision,
            presentationRevisionAtStart = backing.presentationRevision,
            autoSyncGateEnforced = enforceAutoSyncGate,
        )
    }

    suspend fun beginOperationToken(
        source: ScanSource,
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        mode: LibraryOperationMode,
        cause: LibraryOperationCause,
    ): LibraryOperationToken? = backing.publicationMutex.withLock {
        if (backing.released || backing.releaseRequested) return@withLock null
        val sourceIdentity = sourceIdentityFor(source) ?: return@withLock null
        val currentFingerprint = LibraryScanSettings.configFingerprint(backing.context)
        val active = backing.sourceState.active
        val activation = if (active?.sourceIdentity == sourceIdentity) {
            active
        } else {
            val pending = backing.sourceState.pendingTransition
                ?.takeIf { it.sourceIdentity == sourceIdentity }
                ?: SourceActivation(
                    sourceIdentity = sourceIdentity,
                    activationEpoch = ++nextActivationEpoch,
                )
            backing.sourceState = backing.sourceState.copy(pendingTransition = pending)
            pending
        }

        val generation = ++backing.scanGeneration
        backing.configFingerprint = currentFingerprint
        LibraryOperationToken(
            libraryGeneration = generation,
            requestSequence = requestSequence,
            dirtySequenceAtStart = dirtySequenceAtStart,
            mode = mode,
            cause = cause,
            sourceIdentity = sourceIdentity,
            activationEpoch = activation.activationEpoch,
            configFingerprint = currentFingerprint,
            catalogRevisionAtStart = backing.catalogRevision,
            presentationRevisionAtStart = backing.presentationRevision,
        )
    }

    fun isCurrentOperationToken(token: LibraryOperationToken): Boolean {
        if (!isActiveGeneration(token.libraryGeneration)) return false
        if (token.autoSyncGateEnforced && !backing.autoSyncEnabled(token.sourceIdentity.source)) return false
        if (LibraryScanSettings.configFingerprint(backing.context) != token.configFingerprint) return false
        return sequenceOf(
            backing.sourceState.active,
            backing.sourceState.pendingTransition,
        ).filterNotNull().any { activation ->
            activation.sourceIdentity == token.sourceIdentity &&
                activation.activationEpoch == token.activationEpoch
        }
    }

    fun isPendingSourceOperation(token: LibraryOperationToken): Boolean {
        val pending = backing.sourceState.pendingTransition
        return pending?.sourceIdentity == token.sourceIdentity &&
            pending.activationEpoch == token.activationEpoch &&
            backing.sourceState.active?.sourceIdentity != token.sourceIdentity
    }

    fun operationStagingId(token: LibraryOperationToken): String =
        "op-${token.libraryGeneration}-${token.requestSequence}-${token.activationEpoch}"

    fun persistedStateAfterActivation(token: LibraryOperationToken): PersistedLibraryState =
        PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(token.sourceIdentity, token.activationEpoch),
                pendingTransition = null,
            ),
            configFingerprint = token.configFingerprint,
        )

    fun clearedPersistedState(): PersistedLibraryState = PersistedLibraryState(
        intent = LibraryIntentState.CLEARED_BY_USER,
        access = backing.accessState,
        sourceState = LibrarySourceState(),
        configFingerprint = LibraryScanSettings.configFingerprint(backing.context),
    )

    fun activateOperationSourceAfterFinalCommit(token: LibraryOperationToken) {
        check(backing.scanGeneration == token.libraryGeneration) {
            "Final publication generation changed while publication gate was held"
        }
        val matchingActivation = sequenceOf(
            backing.sourceState.active,
            backing.sourceState.pendingTransition,
        ).filterNotNull().any { activation ->
            activation.sourceIdentity == token.sourceIdentity &&
                activation.activationEpoch == token.activationEpoch
        }
        check(matchingActivation) {
            "Final publication source activation changed while publication gate was held"
        }
        backing.restorePersistedState(persistedStateAfterActivation(token))
    }

    suspend fun abandonPendingTransition(token: LibraryOperationToken) {
        withContext(NonCancellable) {
            backing.publicationMutex.withLock {
                val pending = backing.sourceState.pendingTransition
                if (
                    pending?.sourceIdentity == token.sourceIdentity &&
                    pending.activationEpoch == token.activationEpoch &&
                    backing.sourceState.active?.sourceIdentity != token.sourceIdentity
                ) {
                    backing.sourceState = backing.sourceState.copy(pendingTransition = null)
                }
            }
        }
    }

    fun observeRestoredState(state: PersistedLibraryState) {
        nextActivationEpoch = maxOf(
            nextActivationEpoch,
            state.sourceState.active?.activationEpoch ?: 0L,
            state.sourceState.pendingTransition?.activationEpoch ?: 0L,
        )
    }
}