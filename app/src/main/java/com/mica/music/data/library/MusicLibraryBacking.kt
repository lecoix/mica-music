package com.mica.music.data.library

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.preferences.LibraryAutoSyncPreferences
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.AutoSyncPublicationDecision
import com.mica.music.data.scanner.DeviceAutoSyncShadow
import com.mica.music.data.scanner.NoopDeviceAutoSyncShadow
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.publicationDecision
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal class MusicLibraryBacking(
    val context: Context,
    val libraryScanner: LibraryScanner,
    val libraryStore: LibraryStore,
    val scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val deviceAutoSyncShadow: DeviceAutoSyncShadow = NoopDeviceAutoSyncShadow,
    val deviceShadowProbeRuntime: DeviceShadowProbeRuntime = NoopDeviceShadowProbeRuntime,
    val deviceRetryObservationRuntime: DeviceRetryObservationRuntime =
        NoopDeviceRetryObservationRuntime,
    val safShadowProbeRuntime: SafShadowProbeRuntime = NoopSafShadowProbeRuntime,
    val autoSyncEnabled: (ScanSource) -> Boolean = { source ->
        LibraryAutoSyncPreferences.enabled(context, source)
    },
    syncSchedulerTiming: LibrarySyncSchedulerTiming = LibrarySyncSchedulerTiming(),
    syncSchedulerNowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    internal data class AutoPublicationTiming(
        val waitMs: Double,
        val holdMs: Double,
        val storeMs: Double,
        val memoryAdoptMs: Double,
    )

    @Volatile
    internal var lastAutoPublicationTiming: AutoPublicationTiming? = null
        private set
    val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    val scanScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    var scanJob: Job? = null
    /**
     * Library-wide publication generation (historically named scanGeneration).
     * Bumped when starting cache hydrate, scan, clear, or release so stale
     * complete-snapshot publishers discard their results.
     */
    @Volatile
    var scanGeneration = 0
    @Volatile
    var releaseRequested = false
    @Volatile
    var released = false
    /** Serializes long-running library operations. Scanner/provider IO is allowed under this lock. */
    val operationExecutionMutex = Mutex()
    /** Serializes short authority publications and catalog-derived mutations. */
    val publicationMutex = Mutex()
    private val storeSyncMutex = Mutex()
    private val lifecycleLock = Any()
    private val latestStoreRevision = AtomicLong(0L)
    private var nextActivationEpoch = 0L
    @Volatile
    private var playbackIoSnapshotProvider: () -> LibraryPlaybackIoSnapshot =
        { LibraryPlaybackIoSnapshot.Idle }
    @Volatile
    var hasPlaybackDeferredAutoWork: Boolean = false

    var intentState by mutableStateOf(LibraryIntentState.UNINITIALIZED)
    var accessState by mutableStateOf(LibraryAccessState.AVAILABLE)
    var sourceState by mutableStateOf(LibrarySourceState())
    var configFingerprint by mutableStateOf(LibraryScanSettings.configFingerprint(context))

    private val songsById = HashMap<String, Song>()
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var songIds by mutableStateOf<List<String>>(emptyList())
    var catalogRevision by mutableLongStateOf(0L)
    var shadowAuthorityRevision by mutableLongStateOf(0L)
    var presentationRevision by mutableLongStateOf(0L)
    var queueMetadataRevision by mutableLongStateOf(0L)
    var libraryChangeRevision by mutableLongStateOf(0L)
    var lastLibraryChangeSet by mutableStateOf<LibraryChangeSet?>(null)
    var lyricsDataVersion by mutableIntStateOf(scanEnvironment.lyricsParserVersion())
    var sortField by mutableStateOf(SongSortField.TITLE)
    var sortDirection by mutableStateOf(SortDirection.ASC)
    var customSongOrderLocked by mutableStateOf(false)
    var isLoadingCachedLibrary by mutableStateOf(false)
    var isScanning by mutableStateOf(false)
    var isUserVisibleScanning by mutableStateOf(false)
    @Volatile
    var isAutoSyncForeground: Boolean = false
    var hasScanned by mutableStateOf(false)
    var totalSizeMb by mutableIntStateOf(0)
    var lastScanAtMs by mutableStateOf<Long?>(null)
    var permissionGranted by mutableStateOf(false)
    var libraryFolderUri by mutableStateOf<String?>(null)
    var libraryFolderLabel by mutableStateOf<String?>(null)
    var pendingLibraryFolderUri by mutableStateOf<String?>(null)
    var pendingLibraryFolderLabel by mutableStateOf<String?>(null)
    var lastScanSource by mutableStateOf(ScanSource.DEVICE)
    var lastScanError by mutableStateOf<String?>(null)
    var lastScanSyncSummary by mutableStateOf<String?>(null)
    var scanProgressLabel by mutableStateOf<String?>(null)
    var songFastScrollLabels by mutableStateOf<List<String>?>(null)
    var songFastScrollSectionTargets by mutableStateOf<Map<String, Int>?>(null)

    val catalog = LibraryCatalogPublisher(this)
    val browse = LibraryBrowseCoordinator(this)
    val folder = LibraryFolderBinding(this)
    val cacheLoader = LibraryCacheLoader(this)
    val lyricsHydrator = LibraryLyricsHydrator(this)
    val operationExecutor = LibraryOperationExecutor(this)
    val syncScheduler = LibrarySyncScheduler(
        backing = this,
        execute = operationExecutor::executeScheduled,
        timing = syncSchedulerTiming,
        nowMs = syncSchedulerNowMs,
    )
    val dirtySignalObserver = LibraryDirtySignalObserver(
        context = context,
        scope = scanScope,
        markDirty = syncScheduler::markDirty,
        activeSource = { sourceState.active?.sourceIdentity?.source },
        activeSafTreeUri = {
            if (sourceState.active?.sourceIdentity?.source == ScanSource.FOLDER) {
                libraryFolderUri?.let { android.net.Uri.parse(it) }
            } else {
                null
            }
        },
    )

    fun songById(id: String): Song? = songsById[id]

    fun setPlaybackIoSnapshotProvider(provider: () -> LibraryPlaybackIoSnapshot) {
        playbackIoSnapshotProvider = provider
    }

    fun playbackIoSnapshot(): LibraryPlaybackIoSnapshot =
        runCatching(playbackIoSnapshotProvider).getOrDefault(LibraryPlaybackIoSnapshot.Idle)

    fun onPlaybackIoLeaseChanged() {
        if (!hasPlaybackDeferredAutoWork || released || releaseRequested) return
        syncScheduler.markDirty(LibraryOperationCause.PLAYBACK_IO_RELEASE)
    }

    fun replaceSongs(value: List<Song>) {
        songs = value
        songsById.clear()
        value.associateByTo(songsById, Song::id)
    }

    fun replaceSongAt(index: Int, value: Song) {
        val previousId = songs[index].id
        songs = songs.toMutableList().also { it[index] = value }
        if (previousId != value.id) songsById.remove(previousId)
        songsById[value.id] = value
    }

    fun isActiveGeneration(generation: Int): Boolean =
        !released && !releaseRequested && generation == scanGeneration

    fun sourceIdentityFor(source: ScanSource): SourceIdentityKey? = when (source) {
        ScanSource.DEVICE -> SourceIdentityKey.device()
        ScanSource.FOLDER -> (pendingLibraryFolderUri ?: libraryFolderUri)
            ?.takeIf(String::isNotBlank)
            ?.let(SourceIdentityKey::folder)
    }

    suspend fun captureShadowObservationStamp(
        expectedSource: ScanSource,
    ): LibraryShadowObservationStamp? = publicationMutex.withLock {
        if (released || releaseRequested) return@withLock null
        if (intentState != LibraryIntentState.ACTIVE) return@withLock null
        if (accessState != LibraryAccessState.AVAILABLE) return@withLock null
        val active = sourceState.active ?: return@withLock null
        if (active.sourceIdentity.source != expectedSource) return@withLock null
        LibraryShadowObservationStamp(
            libraryGeneration = scanGeneration,
            sourceActivation = active,
            configFingerprint = LibraryScanSettings.configFingerprint(context),
            shadowAuthorityRevision = shadowAuthorityRevision,
            intent = intentState,
            access = accessState,
        )
    }
    suspend fun beginActiveAutoSyncOperationToken(
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        cause: LibraryOperationCause,
        enforceAutoSyncGate: Boolean = true,
    ): LibraryOperationToken? = publicationMutex.withLock {
        if (released || releaseRequested) return@withLock null
        if (intentState != LibraryIntentState.ACTIVE) return@withLock null
        if (accessState != LibraryAccessState.AVAILABLE) return@withLock null
        val active = sourceState.active ?: return@withLock null
        if (enforceAutoSyncGate && !autoSyncEnabled(active.sourceIdentity.source)) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "feature-gate disabled source=${active.sourceIdentity.source} " +
                    "request=$requestSequence cause=$cause",
            )
            return@withLock null
        }
        val currentFingerprint = LibraryScanSettings.configFingerprint(context)
        val generation = ++scanGeneration
        configFingerprint = currentFingerprint
        LibraryOperationToken(
            libraryGeneration = generation,
            requestSequence = requestSequence,
            dirtySequenceAtStart = dirtySequenceAtStart,
            mode = LibraryOperationMode.AUTO_SYNC,
            cause = cause,
            sourceIdentity = active.sourceIdentity,
            activationEpoch = active.activationEpoch,
            configFingerprint = currentFingerprint,
            catalogRevisionAtStart = catalogRevision,
            presentationRevisionAtStart = presentationRevision,
            autoSyncGateEnforced = enforceAutoSyncGate,
        )
    }

    suspend fun beginOperationToken(
        source: ScanSource,
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        mode: LibraryOperationMode,
        cause: LibraryOperationCause,
    ): LibraryOperationToken? = publicationMutex.withLock {
        if (released || releaseRequested) return@withLock null
        val sourceIdentity = sourceIdentityFor(source) ?: return@withLock null
        val currentFingerprint = LibraryScanSettings.configFingerprint(context)
        val active = sourceState.active
        val activation = if (active?.sourceIdentity == sourceIdentity) {
            active
        } else {
            val pending = sourceState.pendingTransition
                ?.takeIf { it.sourceIdentity == sourceIdentity }
                ?: SourceActivation(
                    sourceIdentity = sourceIdentity,
                    activationEpoch = ++nextActivationEpoch,
                )
            sourceState = sourceState.copy(pendingTransition = pending)
            pending
        }

        val generation = ++scanGeneration
        configFingerprint = currentFingerprint
        LibraryOperationToken(
            libraryGeneration = generation,
            requestSequence = requestSequence,
            dirtySequenceAtStart = dirtySequenceAtStart,
            mode = mode,
            cause = cause,
            sourceIdentity = sourceIdentity,
            activationEpoch = activation.activationEpoch,
            configFingerprint = currentFingerprint,
            catalogRevisionAtStart = catalogRevision,
            presentationRevisionAtStart = presentationRevision,
        )
    }

    fun isCurrentOperationToken(token: LibraryOperationToken): Boolean {
        if (!isActiveGeneration(token.libraryGeneration)) return false
        if (
            token.autoSyncGateEnforced &&
            !autoSyncEnabled(token.sourceIdentity.source)
        ) {
            return false
        }
        if (LibraryScanSettings.configFingerprint(context) != token.configFingerprint) return false
        val matchingActivation = sequenceOf(
            sourceState.active,
            sourceState.pendingTransition,
        ).filterNotNull().any { activation ->
            activation.sourceIdentity == token.sourceIdentity &&
                activation.activationEpoch == token.activationEpoch
        }
        return matchingActivation
    }

    fun isPendingSourceOperation(token: LibraryOperationToken): Boolean {
        val pending = sourceState.pendingTransition
        return pending?.sourceIdentity == token.sourceIdentity &&
            pending.activationEpoch == token.activationEpoch &&
            sourceState.active?.sourceIdentity != token.sourceIdentity
    }

    fun operationStagingId(token: LibraryOperationToken): String =
        "op-${token.libraryGeneration}-${token.requestSequence}-${token.activationEpoch}"

    suspend fun discardOperationStaging(stagingId: String) {
        if (stagingId.isBlank()) return
        withContext(NonCancellable) {
            storeSyncMutex.withLock {
                withContext(ioDispatcher) {
                    libraryStore.discardStagedLyrics(stagingId)
                }
            }
        }
    }

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
        access = accessState,
        sourceState = LibrarySourceState(),
        configFingerprint = LibraryScanSettings.configFingerprint(context),
    )

    /**
     * Completes source activation after a final publication gate has already validated [token].
     *
     * The Room transaction and memory adopt are one linearized section under [publicationMutex].
     * A lifecycle release may set releaseRequested while the Room IO is in flight; that later
     * cancellation must not turn a committed Room snapshot into a memory split-brain. Operations
     * that could change generation/source activation are themselves ordered behind the same gate.
     */
    fun activateOperationSourceAfterFinalCommit(token: LibraryOperationToken) {
        check(scanGeneration == token.libraryGeneration) {
            "Final publication generation changed while publication gate was held"
        }
        val matchingActivation = sequenceOf(
            sourceState.active,
            sourceState.pendingTransition,
        ).filterNotNull().any { activation ->
            activation.sourceIdentity == token.sourceIdentity &&
                activation.activationEpoch == token.activationEpoch
        }
        check(matchingActivation) {
            "Final publication source activation changed while publication gate was held"
        }
        restorePersistedState(persistedStateAfterActivation(token))
    }

    suspend fun abandonPendingTransition(token: LibraryOperationToken) {
        withContext(NonCancellable) {
            publicationMutex.withLock {
                val pending = sourceState.pendingTransition
                if (
                    pending?.sourceIdentity == token.sourceIdentity &&
                    pending.activationEpoch == token.activationEpoch &&
                    sourceState.active?.sourceIdentity != token.sourceIdentity
                ) {
                    sourceState = sourceState.copy(pendingTransition = null)
                }
            }
        }
    }

    fun restorePersistedState(state: PersistedLibraryState) {
        intentState = state.intent
        accessState = state.access
        sourceState = state.sourceState
        configFingerprint = state.configFingerprint.ifBlank {
            LibraryScanSettings.configFingerprint(context)
        }
        nextActivationEpoch = maxOf(
            nextActivationEpoch,
            state.sourceState.active?.activationEpoch ?: 0L,
            state.sourceState.pendingTransition?.activationEpoch ?: 0L,
        )
        dirtySignalObserver.onActiveSourceChanged()
    }

    fun persistedState(): PersistedLibraryState = PersistedLibraryState(
        intent = intentState,
        access = accessState,
        sourceState = sourceState,
        configFingerprint = configFingerprint,
    )

    fun launchAccessStateUpdate(state: LibraryAccessState) {
        if (released || releaseRequested) return
        scanScope.launch {
            updateAccessState(state)
        }
    }

    private suspend fun updateAccessState(state: LibraryAccessState) {
        publicationMutex.withLock {
            if (released || releaseRequested) return@withLock
            if (accessState == state) return@withLock
            accessState = state
            syncScheduler.onEligibilityChanged()
            val snapshot = persistedState()
            val storeRevision = nextStoreRevision()
            storeSyncMutex.withLock {
                if (!isLatestStoreRevision(storeRevision)) return@withLock
                withContext(ioDispatcher) {
                    libraryStore.saveLibraryState(snapshot)
                }
            }
        }
    }

    private fun nextStoreRevision(): Long = latestStoreRevision.incrementAndGet()

    private fun isLatestStoreRevision(revision: Long): Boolean =
        revision == latestStoreRevision.get()

    /**
     * Serializes a complete-snapshot store mutation while the supplied library generation is
     * still eligible. This intentionally preserves the existing scan/clear semantics: callers
     * decide whether to publish memory after the Room transaction succeeds.
     */
    suspend fun <T : Any> snapshotStoreWriteIfCurrent(
        generation: Int,
        block: suspend () -> T,
    ): T? {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!isActiveGeneration(generation)) return@withLock null
            if (!isLatestStoreRevision(storeRevision)) return@withLock null
            withContext(ioDispatcher) { block() }
        }
    }

    /**
     * Final complete-snapshot publication seam.
     *
     * Cancellation is observed while waiting for [publicationMutex]. Once final validation passes,
     * the Room mutation and in-memory adopt run as one short non-cancellable publication section.
     * Business invalidation that happens after this point is ordered after this publication.
     */
    suspend fun <T : Any> commitSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T) -> Unit,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false) return@withLock null
                if (!isCurrentOperationToken(token)) return@withLock null
                if (
                    expectedCatalogRevision != null &&
                    catalogRevision != expectedCatalogRevision
                ) {
                    return@withLock null
                }
                if (
                    expectedPresentationRevision != null &&
                    presentationRevision != expectedPresentationRevision
                ) {
                    return@withLock null
                }

                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        null
                    } else {
                        val result = withContext(ioDispatcher) { storeBlock() }
                        // Do not re-check Job/generation here: final validation already linearized
                        // this short commit section ahead of later cancellation/invalidation.
                        publishBlock(result)
                        result
                    }
                }
            }
        }
    }

    suspend fun <T : Any> commitAutoSyncSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        changeSetForRevision: (Long) -> LibraryChangeSet,
        storeBlock: suspend (LibraryChangeSet) -> T,
        publishBlock: (T, LibraryChangeSet) -> Unit,
    ): T? {
        require(token.mode == LibraryOperationMode.AUTO_SYNC) {
            "Visible AUTO publication requires an AUTO_SYNC operation token"
        }
        val waitStartedNs = SystemClock.elapsedRealtimeNanos()
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                val gateAcquiredNs = SystemClock.elapsedRealtimeNanos()
                if (callerJob?.isActive == false) return@withLock null
                if (!isCurrentOperationToken(token)) return@withLock null
                if (expectedCatalogRevision != null && catalogRevision != expectedCatalogRevision) {
                    return@withLock null
                }
                if (
                    expectedPresentationRevision != null &&
                    presentationRevision != expectedPresentationRevision
                ) {
                    return@withLock null
                }

                val revision = libraryChangeRevision + 1L
                val changeSet = changeSetForRevision(revision)
                require(changeSet.libraryRevision == revision)
                require(changeSet.cause == token.cause)
                require(
                    changeSet.addedIds.isNotEmpty() ||
                        changeSet.updatedIds.isNotEmpty() ||
                        changeSet.membershipChanges.isNotEmpty(),
                ) {
                    "No-change AUTO operations must use checkpoint-only publication"
                }
                require(
                    changeSet.membershipChanges.all { it.sourceIdentity == token.sourceIdentity },
                ) {
                    "AUTO membership evidence must belong to the operation source"
                }

                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        null
                    } else {
                        val storeStartedNs = SystemClock.elapsedRealtimeNanos()
                        val result = withContext(ioDispatcher) { storeBlock(changeSet) }
                        val storeFinishedNs = SystemClock.elapsedRealtimeNanos()
                        val memoryAdoptStartedNs = storeFinishedNs
                        publishBlock(result, changeSet)
                        libraryChangeRevision = revision
                        lastLibraryChangeSet = changeSet
                        val finishedNs = SystemClock.elapsedRealtimeNanos()
                        val timing = AutoPublicationTiming(
                            waitMs = nanosToMillis(gateAcquiredNs - waitStartedNs),
                            holdMs = nanosToMillis(finishedNs - gateAcquiredNs),
                            storeMs = nanosToMillis(storeFinishedNs - storeStartedNs),
                            memoryAdoptMs = nanosToMillis(finishedNs - memoryAdoptStartedNs),
                        )
                        lastAutoPublicationTiming = timing
                        result
                    }
                }
            }
        }
    }

    private fun nanosToMillis(nanos: Long): Double = nanos.coerceAtLeast(0L) / 1_000_000.0

    suspend fun <T> withPublicationGenerationIfCurrent(
        generation: Int,
        block: () -> T,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || !isActiveGeneration(generation)) {
                    null
                } else {
                    block()
                }
            }
        }
    }

    /**
     * Runs synchronous AUTO bookkeeping only while the complete operation token is current.
     * Generation, source activation and config are validated while [publicationMutex] prevents a
     * clear or source switch from linearizing around the bookkeeping update.
     */
    suspend fun <T> withCurrentOperationIfCurrent(
        token: LibraryOperationToken,
        block: () -> T,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || !isCurrentOperationToken(token)) {
                    null
                } else {
                    block()
                }
            }
        }
    }

    /**
     * Replaces the current complete-snapshot authority (for example user clear) while ordered
     * against any in-flight final publication.
     */
    suspend fun <T : Any> replaceSnapshotAuthority(
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        expectedSourceIdentity: SourceIdentityKey? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T, Int) -> Unit,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || released || releaseRequested) return@withLock null
                if (
                    expectedCatalogRevision != null &&
                    catalogRevision != expectedCatalogRevision
                ) {
                    return@withLock null
                }
                if (
                    expectedPresentationRevision != null &&
                    presentationRevision != expectedPresentationRevision
                ) {
                    return@withLock null
                }
                if (
                    expectedSourceIdentity != null &&
                    sourceState.active?.sourceIdentity != expectedSourceIdentity
                ) {
                    return@withLock null
                }
                val generation = ++scanGeneration
                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        null
                    } else {
                        val result = withContext(ioDispatcher) { storeBlock() }
                        publishBlock(result, generation)
                        result
                    }
                }
            }
        }
    }

    /** Runs maintenance after any in-flight scan has settled, using the snapshot at lock time. */
    fun launchAlbumArtCacheMaintenance() {
        ioScope.launch {
            operationExecutionMutex.withLock {
                synchronized(lifecycleLock) {
                    if (!released) scanEnvironment.pruneAlbumArtCache(songs.toList())
                }
            }
        }
    }

    /**
     * Runs a catalog-dependent publication while no complete scan can adopt a newer snapshot.
     * Partial derived state must use this seam before publishing to memory or the store.
     */
    suspend fun <T> withCurrentCatalogPublication(
        expectedCatalogRevision: Long,
        block: suspend () -> T,
    ): T? = publicationMutex.withLock {
        if (released || catalogRevision != expectedCatalogRevision) return@withLock null
        block()
    }

    /**
     * Writes derived state for the current catalog under the store revision protocol.
     * The store transaction itself owns [publicationMutex]; callers may reacquire that seam
     * afterward for a short in-memory publication guarded by the same catalog revision.
     */
    suspend fun storeWriteIfCurrentCatalog(
        expectedCatalogRevision: Long,
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (
                released ||
                catalogRevision != expectedCatalogRevision ||
                !isCurrent() ||
                !isLatestStoreRevision(storeRevision)
            ) {
                return@withLock false
            }
            withContext(ioDispatcher) { block() }
            !released &&
                catalogRevision == expectedCatalogRevision &&
                isCurrent() &&
                isLatestStoreRevision(storeRevision)
        }
    }

    /**
     * Serializes an asynchronous store mutation derived from the current in-memory catalog.
     * Lock order is always publicationMutex -> storeSyncMutex so a complete snapshot cannot
     * commit Room and publish memory around a stale local write.
     */
    suspend fun storeWriteIfCurrentGeneration(
        expectedGeneration: Int,
        isCurrent: () -> Boolean = { true },
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (
                !isActiveGeneration(expectedGeneration) ||
                !isCurrent() ||
                !isLatestStoreRevision(storeRevision)
            ) {
                return@withLock false
            }
            withContext(ioDispatcher) { block() }
            isActiveGeneration(expectedGeneration) &&
                isCurrent() &&
                isLatestStoreRevision(storeRevision)
        }
    }

    /**
     * Persists an object-derived value whose validity is owned by the current object state rather
     * than by a scan generation. Unrelated AUTO/FULL generation bumps must not discard such a
     * write after the value has already been adopted in memory; the caller predicate is rechecked
     * under the publication gate so a real object/artwork replacement still fails closed.
     */
    suspend fun storeWriteIfCurrentObjectState(
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (
                released ||
                releaseRequested ||
                !isCurrent() ||
                !isLatestStoreRevision(storeRevision)
            ) {
                return@withLock false
            }
            withContext(ioDispatcher) { block() }
            !released &&
                !releaseRequested &&
                isCurrent() &&
                isLatestStoreRevision(storeRevision)
        }
    }

    /**
     * Runs a store side effect only while the complete-snapshot generation and store revision
     * remain current. This is for scan-internal work whose caller already owns
     * [publicationMutex]; asynchronous catalog mutations must use
     * [storeWriteIfCurrentGeneration] instead. The store lock intentionally covers the whole IO
     * transaction so clear/commit cannot finish before an older transaction and then be followed
     * by that older write.
     */
    suspend fun commitAutoSyncStateIfCurrent(
        token: LibraryOperationToken,
        mutation: LibraryAutoSyncStateMutation,
    ): Boolean {
        require(mutation.sourceIdentity == token.sourceIdentity)
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false) return@withLock false
                if (!isCurrentOperationToken(token)) return@withLock false

                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        false
                    } else {
                        withContext(ioDispatcher) {
                            libraryStore.applyAutoSyncState(mutation)
                        }
                        // This is a durable checkpoint/retry linearization point. Do not re-check
                        // cancellation/generation after the Room transaction: once final
                        // validation passed under publicationMutex, later invalidation is ordered
                        // after this short non-cancellable commit just like visible AUTO
                        // publication.
                        true
                    }
                }
            }
        }
    }

    suspend fun commitAutoSyncCheckpointOnlyIfCurrent(
        token: LibraryOperationToken,
        visibleDelta: AutoSyncVisibleDelta,
        mutation: LibraryAutoSyncStateMutation,
    ): Boolean {
        require(token.mode == LibraryOperationMode.AUTO_SYNC) {
            "Checkpoint-only auto state writes require an AUTO_SYNC operation token"
        }
        require(visibleDelta.publicationDecision() == AutoSyncPublicationDecision.CHECKPOINT_ONLY) {
            "Visible AUTO changes must use snapshot publication"
        }
        return commitAutoSyncStateIfCurrent(token, mutation)
    }

    suspend fun upsertUserExclusionIfCurrentGeneration(
        expectedGeneration: Int,
        exclusion: LibraryUserExclusion,
    ): Boolean = storeWriteIfCurrentGeneration(expectedGeneration) {
        libraryStore.upsertUserExclusion(exclusion)
    }

    suspend fun removeUserExclusionIfCurrentGeneration(
        expectedGeneration: Int,
        sourceIdentity: SourceIdentityKey,
        stableObjectKey: String,
    ): Boolean = storeWriteIfCurrentGeneration(expectedGeneration) {
        libraryStore.removeUserExclusion(sourceIdentity, stableObjectKey)
    }

    suspend fun enqueueFollowupOutboxIfCurrentGeneration(
        expectedGeneration: Int,
        item: LibraryFollowupOutboxItem,
    ): Boolean = storeWriteIfCurrentGeneration(expectedGeneration) {
        libraryStore.enqueueFollowupOutbox(item)
    }

    suspend fun storeWriteIfCurrentOperation(
        token: LibraryOperationToken,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!isCurrentOperationToken(token)) return@withLock false
            if (!isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(ioDispatcher) { block() }
            isCurrentOperationToken(token) && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun storeWriteIfCurrent(
        generation: Int,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!isActiveGeneration(generation)) return@withLock false
            if (!isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(ioDispatcher) { block() }
            isActiveGeneration(generation) && isLatestStoreRevision(storeRevision)
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            if (released || releaseRequested) return
            releaseRequested = true
            dirtySignalObserver.release()
            syncScheduler.cancelAll()
            scanJob?.cancel()
            scanJob = null
        }

        // Do not let lifecycle cancellation split an already-linearized Room + memory publication.
        // This cleanup waits behind that short publication section, then invalidates everything else.
        scanScope.launch(NonCancellable) {
            publicationMutex.withLock {
                synchronized(lifecycleLock) {
                    if (!released) {
                        released = true
                        scanGeneration++
                        isScanning = false
                        isUserVisibleScanning = false
                        isLoadingCachedLibrary = false
                        scanProgressLabel = null
                        hasPlaybackDeferredAutoWork = false
                        playbackIoSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle }
                    }
                }
            }
            scanScope.cancel()
            ioScope.cancel()
        }
    }
}
