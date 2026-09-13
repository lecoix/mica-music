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
    internal fun recordAutoPublicationTiming(timing: AutoPublicationTiming) {
        lastAutoPublicationTiming = timing
    }
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
    private val lifecycleLock = Any()
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

    val publicationAuthority = LibraryPublicationAuthority(this)
    val operationAuthority = LibraryOperationAuthority(this)
    internal val publicationMutex get() = publicationAuthority.publicationMutex
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
        markDirty = { cause -> syncScheduler.markDirty(cause) },
        markDirtyWithMediaStoreHint = { cause, uri -> syncScheduler.markDirty(cause, uri) },
        markMediaStoreGenerationDirty = { cause ->
            syncScheduler.markMediaStoreGenerationDirty(cause)
        },
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
        operationAuthority.isActiveGeneration(generation)

    fun sourceIdentityFor(source: ScanSource): SourceIdentityKey? =
        operationAuthority.sourceIdentityFor(source)

    suspend fun captureShadowObservationStamp(
        expectedSource: ScanSource,
    ): LibraryShadowObservationStamp? =
        operationAuthority.captureShadowObservationStamp(expectedSource)

    suspend fun beginActiveAutoSyncOperationToken(
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        cause: LibraryOperationCause,
        enforceAutoSyncGate: Boolean = true,
    ): LibraryOperationToken? = operationAuthority.beginActiveAutoSyncOperationToken(
        requestSequence = requestSequence,
        dirtySequenceAtStart = dirtySequenceAtStart,
        cause = cause,
        enforceAutoSyncGate = enforceAutoSyncGate,
    )

    suspend fun beginOperationToken(
        source: ScanSource,
        requestSequence: Long,
        dirtySequenceAtStart: Long,
        mode: LibraryOperationMode,
        cause: LibraryOperationCause,
    ): LibraryOperationToken? = operationAuthority.beginOperationToken(
        source = source,
        requestSequence = requestSequence,
        dirtySequenceAtStart = dirtySequenceAtStart,
        mode = mode,
        cause = cause,
    )

    fun isCurrentOperationToken(token: LibraryOperationToken): Boolean =
        operationAuthority.isCurrentOperationToken(token)

    fun isPendingSourceOperation(token: LibraryOperationToken): Boolean =
        operationAuthority.isPendingSourceOperation(token)

    fun operationStagingId(token: LibraryOperationToken): String =
        operationAuthority.operationStagingId(token)
    suspend fun discardOperationStaging(stagingId: String) =
        publicationAuthority.discardOperationStaging(stagingId)

    fun persistedStateAfterActivation(token: LibraryOperationToken): PersistedLibraryState =
        operationAuthority.persistedStateAfterActivation(token)

    fun clearedPersistedState(): PersistedLibraryState =
        operationAuthority.clearedPersistedState()

    fun activateOperationSourceAfterFinalCommit(token: LibraryOperationToken) =
        operationAuthority.activateOperationSourceAfterFinalCommit(token)

    suspend fun abandonPendingTransition(token: LibraryOperationToken) =
        operationAuthority.abandonPendingTransition(token)
    fun restorePersistedState(state: PersistedLibraryState) {
        val previousActiveSourceIdentity = sourceState.active?.sourceIdentity
        intentState = state.intent
        accessState = state.access
        sourceState = state.sourceState
        configFingerprint = state.configFingerprint.ifBlank {
            LibraryScanSettings.configFingerprint(context)
        }
        operationAuthority.observeRestoredState(state)

        if (previousActiveSourceIdentity != sourceState.active?.sourceIdentity) {
            dirtySignalObserver.onActiveSourceChanged()
        }
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
            publicationAuthority.updateAccessState(state)
        }
    }

    suspend fun <T : Any> snapshotStoreWriteIfCurrent(
        generation: Int,
        block: suspend () -> T,
    ): T? = publicationAuthority.snapshotStoreWriteIfCurrent(generation, block)

    suspend fun <T : Any> commitSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T) -> Unit,
    ): T? = publicationAuthority.commitSnapshotAndPublishIfCurrent(
        token = token,
        expectedCatalogRevision = expectedCatalogRevision,
        expectedPresentationRevision = expectedPresentationRevision,
        storeBlock = storeBlock,
        publishBlock = publishBlock,
    )

    suspend fun <T : Any> commitAutoSyncSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        changeSetForRevision: (Long) -> LibraryChangeSet,
        storeBlock: suspend (LibraryChangeSet) -> T,
        publishBlock: (T, LibraryChangeSet) -> Unit,
    ): T? = publicationAuthority.commitAutoSyncSnapshotAndPublishIfCurrent(
        token = token,
        expectedCatalogRevision = expectedCatalogRevision,
        expectedPresentationRevision = expectedPresentationRevision,
        changeSetForRevision = changeSetForRevision,
        storeBlock = storeBlock,
        publishBlock = publishBlock,
    )
    suspend fun <T> withPublicationGenerationIfCurrent(
        generation: Int,
        block: () -> T,
    ): T? = publicationAuthority.withPublicationGenerationIfCurrent(generation, block)

    suspend fun <T> withCurrentOperationIfCurrent(
        token: LibraryOperationToken,
        block: () -> T,
    ): T? = publicationAuthority.withCurrentOperationIfCurrent(token, block)

    suspend fun <T : Any> replaceSnapshotAuthority(
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        expectedSourceIdentity: SourceIdentityKey? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T, Int) -> Unit,
    ): T? = publicationAuthority.replaceSnapshotAuthority(
        expectedCatalogRevision = expectedCatalogRevision,
        expectedPresentationRevision = expectedPresentationRevision,
        expectedSourceIdentity = expectedSourceIdentity,
        storeBlock = storeBlock,
        publishBlock = publishBlock,
    )

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

    suspend fun <T> withCurrentCatalogPublication(
        expectedCatalogRevision: Long,
        block: suspend () -> T,
    ): T? = publicationAuthority.withCurrentCatalogPublication(expectedCatalogRevision, block)

    suspend fun storeWriteIfCurrentCatalog(
        expectedCatalogRevision: Long,
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationAuthority.storeWriteIfCurrentCatalog(
        expectedCatalogRevision = expectedCatalogRevision,
        isCurrent = isCurrent,
        block = block,
    )

    suspend fun storeWriteIfCurrentGeneration(
        expectedGeneration: Int,
        isCurrent: () -> Boolean = { true },
        block: suspend () -> Unit,
    ): Boolean = publicationAuthority.storeWriteIfCurrentGeneration(
        expectedGeneration = expectedGeneration,
        isCurrent = isCurrent,
        block = block,
    )

    suspend fun storeWriteIfCurrentObjectState(
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationAuthority.storeWriteIfCurrentObjectState(isCurrent, block)

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
    ): Boolean = publicationAuthority.commitAutoSyncStateIfCurrent(token, mutation)

    suspend fun commitAutoSyncCheckpointOnlyIfCurrent(
        token: LibraryOperationToken,
        visibleDelta: AutoSyncVisibleDelta,
        mutation: LibraryAutoSyncStateMutation,
    ): Boolean = publicationAuthority.commitAutoSyncCheckpointOnlyIfCurrent(
        token = token,
        visibleDelta = visibleDelta,
        mutation = mutation,
    )

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
    ): Boolean = publicationAuthority.storeWriteIfCurrentOperation(token, block)

    suspend fun storeWriteIfCurrent(
        generation: Int,
        block: suspend () -> Unit,
    ): Boolean = publicationAuthority.storeWriteIfCurrent(generation, block)

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
