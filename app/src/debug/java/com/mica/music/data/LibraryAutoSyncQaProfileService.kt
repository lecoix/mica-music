package com.mica.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.Room
import com.mica.music.data.library.AndroidSafShadowProbeRuntime
import com.mica.music.data.library.LibraryAccessState
import com.mica.music.data.library.LibraryAutoSyncStateMutation
import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryRetryCursor
import com.mica.music.data.library.LibraryRetryPaging
import com.mica.music.data.library.LibraryIntentState
import com.mica.music.data.library.LibraryOperationCause
import com.mica.music.data.library.LibraryPlaybackIoSnapshot
import com.mica.music.data.library.LibraryRetryItem
import com.mica.music.data.library.LibraryRetryKind
import com.mica.music.data.library.LibrarySourceState
import com.mica.music.data.library.LibrarySyncCheckpoint
import com.mica.music.data.library.MusicLibraryBacking
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SafAutoSyncPublicationPlan
import com.mica.music.data.library.SafAutoSyncPublicationPlanner
import com.mica.music.data.library.SourceActivation
import com.mica.music.data.library.SourceIdentityKey
import com.mica.music.data.library.SafAutoProbePlanner
import com.mica.music.data.library.SafShadowPostProbeValidator
import com.mica.music.data.library.SafShadowProbeRequest
import com.mica.music.data.library.SafProviderDiscoveryBackoff
import com.mica.music.data.library.SAF_PROVIDER_RESELECT_REQUIRED_ERROR
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafFingerprintReliability
import com.mica.music.data.library.SafProviderDiscoveryPermit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only long-running S3/S4 automatic-library-sync profiler/gate host.
 *
 * This exists because the current MIUI build silently short-circuits `am instrument`, while
 * BroadcastReceiver/goAsync is still bounded by broadcast lifecycle timeouts. Production builds do
 * not include this service.
 */
class LibraryAutoSyncQaProfileService : Service() {

    private val workerRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mica library auto-sync QA",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Mica auto-sync QA")
                .setContentText("Running debug library synchronization gate")
                .setOngoing(true)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repeatCount = intent?.getIntExtra("repeat", 3)?.coerceIn(1, 20) ?: 3
        val mode = intent?.getStringExtra("mode")?.trim()?.uppercase().orEmpty()
        val unknownTarget = intent
            ?.getIntExtra("unknownTarget", TEN_K_UNKNOWN_OBJECT_COUNT)
            ?.coerceIn(1, TEN_K_UNKNOWN_OBJECT_COUNT)
            ?: TEN_K_UNKNOWN_OBJECT_COUNT
        val requestedTreeUri = intent?.data
        if (!workerRunning.compareAndSet(false, true)) {
            appendEvidence("duplicate-start ignored startId=$startId mode=$mode flags=$flags")
            return START_NOT_STICKY
        }
        if (mode == MODE_TEN_K_HEAVY || mode == MODE_TEN_K_UNKNOWN) {
            runCatching { File(filesDir, EVIDENCE_FILE_NAME).writeText("") }
        }
        appendEvidence("run-start startId=$startId mode=$mode flags=$flags")
        Log.i(TAG, "run-start startId=$startId mode=$mode flags=$flags")
        Thread {
            try {
                when (mode) {
                    MODE_DEVICE_AUTHORITY ->
                        LibraryAutoSyncQaReceiver().runDeviceAuthorityGate(applicationContext)
                    MODE_PROVIDER_BACKOFF -> runProviderBackoffGate()
                    MODE_EXTERNAL_PROVIDER_BASELINE -> {
                        val treeUri = requireNotNull(requestedTreeUri) {
                            "external provider baseline mode requires intent data tree URI"
                        }
                        runExternalProviderBaselineGate(treeUri)
                    }
                    MODE_EXTERNAL_PROVIDER_UNAVAILABLE -> {
                        val treeUri = requireNotNull(requestedTreeUri) {
                            "external provider unavailable mode requires intent data tree URI"
                        }
                        runExternalProviderUnavailableGate(treeUri)
                    }
                    MODE_EXTERNAL_PROVIDER_REARM -> {
                        val treeUri = requireNotNull(requestedTreeUri) {
                            "external provider rearm mode requires intent data tree URI"
                        }
                        runExternalProviderRearmGate(treeUri)
                    }
                    MODE_AUTO_ARTWORK_GATE -> {
                        val treeUri = requireNotNull(requestedTreeUri) {
                            "auto artwork mode requires intent data tree URI"
                        }
                        LibraryAutoSyncQaReceiver().runAutoArtworkGate(
                            applicationContext,
                            treeUri,
                        )
                    }
                    MODE_TEN_K_HEAVY -> runTenKHeavyProbeGate()
                    MODE_TEN_K_UNKNOWN -> runTenKUnknownVerifyGate(unknownTarget)
                    MODE_AUTO_QUERY_LANE -> runAutoQueryLaneGate()
                    MODE_ROOM_ATOMICITY -> runRoomAtomicityGate()
                    MODE_ROOM_PUBLICATION_10K -> runRoomPublicationTenKGate(unicodeTitles = false)
                    MODE_ROOM_PUBLICATION_10K_UNICODE -> runRoomPublicationTenKGate(unicodeTitles = true)
                    MODE_EXTERNAL_PROVIDER_CADENCE -> {
                        val treeUri = requireNotNull(requestedTreeUri) {
                            "external provider cadence mode requires intent data tree URI"
                        }
                        runExternalProviderCadenceGate(treeUri)
                    }
                    else -> runTenKMetadataProfile(repeatCount)
                }
                appendEvidence("run-complete startId=$startId mode=$mode")
            } catch (error: Throwable) {
                val failureTag = when (mode) {
                    MODE_DEVICE_AUTHORITY -> DEVICE_AUTHORITY_TAG
                    MODE_PROVIDER_BACKOFF,
                    MODE_EXTERNAL_PROVIDER_BASELINE,
                    MODE_EXTERNAL_PROVIDER_UNAVAILABLE,
                    MODE_EXTERNAL_PROVIDER_REARM,
                    MODE_AUTO_ARTWORK_GATE,
                    -> PROVIDER_TAG
                    MODE_TEN_K_HEAVY -> HEAVY_TAG
                    MODE_TEN_K_UNKNOWN -> UNKNOWN_TAG
                    MODE_AUTO_QUERY_LANE -> QUERY_LANE_TAG
                    MODE_ROOM_ATOMICITY -> ROOM_ATOMICITY_TAG
                    MODE_ROOM_PUBLICATION_10K,
                    MODE_ROOM_PUBLICATION_10K_UNICODE,
                    -> ROOM_PUBLICATION_TAG
                    else -> TAG
                }
                val failureMessage = when (mode) {
                    MODE_DEVICE_AUTHORITY -> "device-authority-gate-failed"
                    MODE_PROVIDER_BACKOFF -> "provider-gate-failed"
                    MODE_EXTERNAL_PROVIDER_BASELINE ->
                        "external-provider-baseline-gate-failed"
                    MODE_EXTERNAL_PROVIDER_UNAVAILABLE ->
                        "external-provider-unavailable-gate-failed"
                    MODE_EXTERNAL_PROVIDER_REARM ->
                        "external-provider-rearm-gate-failed"
                    MODE_AUTO_ARTWORK_GATE -> "auto-artwork-gate-failed"
                    MODE_TEN_K_HEAVY -> "heavy-gate-failed"
                    MODE_TEN_K_UNKNOWN -> "unknown-gate-failed"
                    MODE_AUTO_QUERY_LANE -> "query-lane-gate-failed"
                    MODE_ROOM_ATOMICITY -> "room-atomicity-gate-failed"
                    MODE_ROOM_PUBLICATION_10K,
                    MODE_ROOM_PUBLICATION_10K_UNICODE,
                    -> "room-publication-gate-failed"
                    else -> "profile-failed"
                }
                appendEvidence(
                    "run-failed startId=$startId mode=$mode error=" +
                        error.stackTraceToString(),
                )
                Log.e(failureTag, failureMessage, error)
            } finally {
                appendEvidence("run-finally startId=$startId mode=$mode")
                TestDocumentsProvider.resetScenario()
                TestDocumentsProvider.resetChildQueryBehavior()
                workerRunning.set(false)
                stopForeground(true)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun prepareTreeUri(): android.net.Uri {
        val authority = TestDocumentsProvider.authorityForPackage(packageName)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
            TestDocumentsProvider.ROOT_ID,
        )
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        grantUriPermission(packageName, treeUri, grantFlags)
        grantUriPermission(packageName, rootDocumentUri, grantFlags)
        return treeUri
    }

    private fun runTenKMetadataProfile(repeatCount: Int) {
        val treeUri = prepareTreeUri()
        TestDocumentsProvider.resetChildQueryBehavior()
        TestDocumentsProvider.setScenario(
            TestDocumentsProvider.FixtureScenario.TEN_K_METADATA.name,
        )

        val scanner = AndroidLibraryScanner(applicationContext)
        repeat(repeatCount) { index ->
            val startedMs = SystemClock.elapsedRealtime()
            val snapshot = runBlocking {
                scanner.observeFolderMetadata(treeUri)
            }
            val runtime = Runtime.getRuntime()
            Log.i(
                TAG,
                "iteration=" + (index + 1) +
                    " entries=" + snapshot.entries.size +
                    " completeness=" + snapshot.discoveryReport.aggregate +
                    " observedWallMs=" + snapshot.observationStats.wallTimeMs +
                    " externalWallMs=" + (SystemClock.elapsedRealtime() - startedMs) +
                    " queries=" + snapshot.observationStats.providerQueryCount +
                    " directQueries=" + snapshot.observationStats.directQueryCount +
                    " fallbackListings=" + snapshot.observationStats.fallbackListingCount +
                    " pssKb=" + Debug.getPss() +
                    " rssKb=" + readSelfVmRssKb() +
                    " javaUsedKb=" + ((runtime.totalMemory() - runtime.freeMemory()) / 1024L) +
                    " nativeAllocatedKb=" + (Debug.getNativeHeapAllocatedSize() / 1024L),
            )
        }
        Log.i(TAG, "profile-complete repeats=" + repeatCount)
    }

    private fun runTenKHeavyProbeGate() {
        val treeUri = prepareTreeUri()
        val library = MusicLibrary(applicationContext)
        var peakPssKb = 0L
        var peakRssKb = 0L
        fun sampleMemory() {
            peakPssKb = maxOf(peakPssKb, Debug.getPss().toLong())
            peakRssKb = maxOf(peakRssKb, readSelfVmRssKb())
        }

        try {
            TestDocumentsProvider.beginExclusiveLease(
                token = TEN_K_HEAVY_LEASE_TOKEN,
                scenario = TestDocumentsProvider.FixtureScenario.TEN_K_HEAVY_BASELINE.name,
            )
            library.setLibraryFolder(treeUri)
            library.setPlaybackIoSnapshotProvider { LibraryPlaybackIoSnapshot.Idle }

            val baselineStartedMs = SystemClock.elapsedRealtime()
            appendEvidence("heavy-baseline-start")
            runBlocking {
                library.scanLibraryFolder()
            }
            sampleMemory()
            require(library.songs.size == TEN_K_HEAVY_OBJECT_COUNT) {
                "10k heavy baseline expected $TEN_K_HEAVY_OBJECT_COUNT songs, got " +
                    library.songs.size
            }
            val baselineElapsedMs = SystemClock.elapsedRealtime() - baselineStartedMs
            appendEvidence(
                "heavy-baseline-complete elapsedMs=$baselineElapsedMs songs=" +
                    library.songs.size +
                    " pssKb=$peakPssKb rssKb=$peakRssKb error=" + library.lastScanError,
            )
            Log.i(
                HEAVY_TAG,
                "baseline-complete elapsedMs=$baselineElapsedMs" +
                    " songs=" + library.songs.size +
                    " pssKb=$peakPssKb rssKb=$peakRssKb error=" + library.lastScanError,
            )

            TestDocumentsProvider.setScenarioWithinLease(
                token = TEN_K_HEAVY_LEASE_TOKEN,
                value = TestDocumentsProvider.FixtureScenario.TEN_K_HEAVY_CHANGED.name,
            )
            val shadowStartedMs = SystemClock.elapsedRealtime()
            appendEvidence(
                "heavy-shadow-start passes=$TEN_K_HEAVY_SHADOW_PASS_COUNT " +
                    "requiredBudgetPasses=$TEN_K_HEAVY_REQUIRED_BUDGET_PASSES",
            )
            repeat(TEN_K_HEAVY_SHADOW_PASS_COUNT) { index ->
                val passNumber = index + 1
                val passStartedMs = SystemClock.elapsedRealtime()
                appendEvidence("heavy-pass-start pass=$passNumber")
                runBlocking {
                    library.runAutoSyncShadowForDiagnostics(
                        cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                    )
                }
                sampleMemory()
                val passElapsedMs = SystemClock.elapsedRealtime() - passStartedMs
                appendEvidence(
                    "heavy-pass-complete pass=$passNumber elapsedMs=$passElapsedMs " +
                        "pssKb=" + Debug.getPss() +
                        " rssKb=" + readSelfVmRssKb() +
                        " peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
                )
                if (
                    passNumber == 1 ||
                    passNumber % TEN_K_HEAVY_PROGRESS_INTERVAL == 0 ||
                    passNumber >= TEN_K_HEAVY_REQUIRED_BUDGET_PASSES
                ) {
                    Log.i(
                        HEAVY_TAG,
                        "shadow-pass pass=$passNumber/" + TEN_K_HEAVY_SHADOW_PASS_COUNT +
                            " elapsedMs=$passElapsedMs" +
                            " totalElapsedMs=" +
                            (SystemClock.elapsedRealtime() - shadowStartedMs) +
                            " pssKb=" + Debug.getPss() +
                            " rssKb=" + readSelfVmRssKb() +
                            " peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
                    )
                }
            }
            val shadowElapsedMs = SystemClock.elapsedRealtime() - shadowStartedMs
            appendEvidence(
                "heavy-shadow-complete passes=$TEN_K_HEAVY_SHADOW_PASS_COUNT " +
                    "elapsedMs=$shadowElapsedMs peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
            )
            Log.i(
                HEAVY_TAG,
                "shadow-series-complete passes=$TEN_K_HEAVY_SHADOW_PASS_COUNT " +
                    "elapsedMs=$shadowElapsedMs" +
                    " peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
            )

            val oracleStartedMs = SystemClock.elapsedRealtime()
            appendEvidence("heavy-oracle-start")
            runBlocking {
                library.scanLibraryFolder()
            }
            sampleMemory()
            require(library.songs.size == TEN_K_HEAVY_OBJECT_COUNT) {
                "10k heavy oracle expected $TEN_K_HEAVY_OBJECT_COUNT songs, got " +
                    library.songs.size
            }
            val oracleElapsedMs = SystemClock.elapsedRealtime() - oracleStartedMs
            appendEvidence(
                "heavy-oracle-complete elapsedMs=$oracleElapsedMs songs=" +
                    library.songs.size +
                    " peakPssKb=$peakPssKb peakRssKb=$peakRssKb error=" +
                    library.lastScanError,
            )
            Log.i(
                HEAVY_TAG,
                "oracle-complete elapsedMs=$oracleElapsedMs" +
                    " songs=" + library.songs.size +
                    " peakPssKb=$peakPssKb peakRssKb=$peakRssKb error=" +
                    library.lastScanError,
            )
        } finally {
            library.release()
            TestDocumentsProvider.endExclusiveLease(TEN_K_HEAVY_LEASE_TOKEN)
        }
    }

    private fun runTenKUnknownVerifyGate(targetResolvedCount: Int) {
        val treeUri = prepareTreeUri()
        val scanner = AndroidLibraryScanner(applicationContext)
        val runtime = AndroidSafShadowProbeRuntime(applicationContext)
        val scanOptions = LibraryScanSettings.scanOptions(applicationContext)
        val resolvedKeys = linkedSetOf<String>()
        val passElapsedMs = mutableListOf<Long>()
        val unknownVerifyWallMs = mutableListOf<Long>()
        var totalAttempted = 0
        var totalBudgetDeferredIssues = 0
        var peakPssKb = 0L
        var peakRssKb = 0L

        fun sampleMemory() {
            peakPssKb = maxOf(peakPssKb, Debug.getPss().toLong())
            peakRssKb = maxOf(peakRssKb, readSelfVmRssKb())
        }

        fun percentile(values: List<Long>, percentile: Int): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val rank = ((sorted.size * percentile + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        try {
            TestDocumentsProvider.beginExclusiveLease(
                token = TEN_K_UNKNOWN_LEASE_TOKEN,
                scenario = TestDocumentsProvider.FixtureScenario.TEN_K_UNKNOWN.name,
            )
            val metadataStartedMs = SystemClock.elapsedRealtime()
            val snapshot = runBlocking {
                scanner.observeFolderMetadata(treeUri)
            }
            val metadataElapsedMs = SystemClock.elapsedRealtime() - metadataStartedMs
            require(
                snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE),
            ) {
                "10k UNKNOWN metadata inventory is not COMPLETE: " +
                    snapshot.discoveryReport.aggregate
            }
            require(snapshot.entries.size == TEN_K_UNKNOWN_OBJECT_COUNT) {
                "10k UNKNOWN metadata expected $TEN_K_UNKNOWN_OBJECT_COUNT entries, got " +
                    snapshot.entries.size
            }
            require(
                snapshot.entries.all {
                    it.fingerprintReliability == SafFingerprintReliability.UNKNOWN
                },
            ) {
                "10k UNKNOWN fixture exposed a reliable fingerprint"
            }
            appendEvidence(
                "unknown-metadata-complete elapsedMs=$metadataElapsedMs " +
                    "observedWallMs=${snapshot.observationStats.wallTimeMs} " +
                    "entries=${snapshot.entries.size} queries=" +
                    snapshot.observationStats.providerQueryCount,
            )
            Log.i(
                UNKNOWN_TAG,
                "metadata-complete elapsedMs=$metadataElapsedMs " +
                    "observedWallMs=${snapshot.observationStats.wallTimeMs} " +
                    "entries=${snapshot.entries.size} queries=" +
                    snapshot.observationStats.providerQueryCount,
            )

            val seriesStartedMs = SystemClock.elapsedRealtime()
            var pass = 0
            while (resolvedKeys.size < targetResolvedCount) {
                pass += 1
                require(pass <= TEN_K_UNKNOWN_MAX_PASSES) {
                    "10k UNKNOWN did not converge within $TEN_K_UNKNOWN_MAX_PASSES passes; " +
                        "resolved=${resolvedKeys.size}"
                }
                val remaining = snapshot.entries.filterNot {
                    it.stableObjectKey in resolvedKeys
                }
                val verifyPlan = SafFastVerifyPlan(
                    added = emptyList(),
                    changed = emptyList(),
                    unknownFingerprint = remaining,
                    removedStableObjectKeys = emptySet(),
                    unchangedCount = resolvedKeys.size,
                    removalSuppressedCount = 0,
                    discoveryReport = snapshot.discoveryReport,
                )
                val probePlan = SafAutoProbePlanner.plan(
                    verifyPlan = verifyPlan,
                    playback = LibraryPlaybackIoSnapshot.Idle,
                    observedEntries = snapshot.entries,
                    nowMs = 0L,
                    allowUnknownFingerprintVerify = true,
                    heavyProbeBudget = SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET,
                    unknownVerifyBudget = SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET,
                    alreadyResolvedStableObjectKeys = resolvedKeys,
                )
                val startedMs = SystemClock.elapsedRealtime()
                val execution = runtime.execute(
                    SafShadowProbeRequest(
                        probePlan = probePlan,
                        scanOptions = scanOptions,
                        currentSongs = emptyList(),
                        playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
                    ),
                )
                val validation = SafShadowPostProbeValidator.validate(
                    initialSnapshot = snapshot,
                    postSnapshot = snapshot,
                    execution = execution,
                )
                val passMs = SystemClock.elapsedRealtime() - startedMs
                val newlyResolved = execution.strongValidatedFingerprintsByStableObjectKey.keys
                    .filterTo(linkedSetOf()) {
                        it in validation.resolvedSongsByStableObjectKey
                    }
                require(newlyResolved.isNotEmpty()) {
                    "10k UNKNOWN made no progress at pass $pass; " +
                        "selected=${probePlan.unknownFingerprintSelectedCount} " +
                        "attempted=${execution.attemptedCount} issues=${validation.issues}"
                }
                resolvedKeys += newlyResolved
                totalAttempted += execution.attemptedCount
                totalBudgetDeferredIssues += validation.issues.count {
                    it.kind ==
                        com.mica.music.data.library.SafShadowProbeIssueKind
                            .UNKNOWN_VERIFY_BUDGET_DEFERRED
                }
                passElapsedMs += passMs
                unknownVerifyWallMs += execution.unknownVerifyWallTimeMs
                sampleMemory()

                val issueKinds = validation.issues
                    .groupingBy { it.kind }
                    .eachCount()
                    .entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { (kind, count) -> "${kind.name}:$count" }
                    .ifBlank { "none" }
                appendEvidence(
                    "unknown-pass pass=$pass elapsedMs=$passMs " +
                        "selected=${probePlan.unknownFingerprintSelectedCount} " +
                        "attempted=${execution.attemptedCount} " +
                        "strong=${newlyResolved.size} resolved=${resolvedKeys.size}/" +
                        "$targetResolvedCount candidatePool=$TEN_K_UNKNOWN_OBJECT_COUNT verifyWallMs=" +
                        "${execution.unknownVerifyWallTimeMs} issues=$issueKinds " +
                        "pssKb=${Debug.getPss()} rssKb=${readSelfVmRssKb()}",
                )
                if (
                    pass == 1 ||
                    pass % TEN_K_UNKNOWN_PROGRESS_INTERVAL == 0 ||
                    resolvedKeys.size >= targetResolvedCount
                ) {
                    Log.i(
                        UNKNOWN_TAG,
                        "pass=$pass resolved=${resolvedKeys.size}/" +
                            "$targetResolvedCount candidatePool=$TEN_K_UNKNOWN_OBJECT_COUNT " +
                            "elapsedMs=$passMs " +
                            "verifyWallMs=${execution.unknownVerifyWallTimeMs} " +
                            "selected=${probePlan.unknownFingerprintSelectedCount} " +
                            "attempted=${execution.attemptedCount} strong=${newlyResolved.size} " +
                            "issues=$issueKinds peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
                    )
                }
            }

            val totalElapsedMs = SystemClock.elapsedRealtime() - seriesStartedMs
            val medianPassMs = percentile(passElapsedMs, 50)
            val p95PassMs = percentile(passElapsedMs, 95)
            val maxPassMs = passElapsedMs.maxOrNull() ?: 0L
            val medianVerifyMs = percentile(unknownVerifyWallMs, 50)
            val p95VerifyMs = percentile(unknownVerifyWallMs, 95)
            val maxVerifyMs = unknownVerifyWallMs.maxOrNull() ?: 0L
            val totalVerifyWallMs = unknownVerifyWallMs.sum()
            val logicalDoubleShaBytes =
                resolvedKeys.size.toLong() * TEN_K_UNKNOWN_AUDIO_BYTES * 2L
            val avgVerifyUsPerResolvedObject = if (resolvedKeys.isEmpty()) {
                0L
            } else {
                totalVerifyWallMs * 1_000L / resolvedKeys.size
            }
            appendEvidence(
                "unknown-series-complete passes=$pass totalElapsedMs=$totalElapsedMs " +
                    "target=$targetResolvedCount candidatePool=$TEN_K_UNKNOWN_OBJECT_COUNT " +
                    "resolved=${resolvedKeys.size} attempted=$totalAttempted " +
                    "budgetDeferredIssues=$totalBudgetDeferredIssues " +
                    "totalVerifyWallMs=$totalVerifyWallMs " +
                    "avgVerifyUsPerResolvedObject=$avgVerifyUsPerResolvedObject " +
                    "logicalDoubleShaBytes=$logicalDoubleShaBytes " +
                    "medianPassMs=$medianPassMs p95PassMs=$p95PassMs maxPassMs=$maxPassMs " +
                    "medianVerifyMs=$medianVerifyMs p95VerifyMs=$p95VerifyMs " +
                    "maxVerifyMs=$maxVerifyMs peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
            )
            Log.i(
                UNKNOWN_TAG,
                "series-complete passes=$pass totalElapsedMs=$totalElapsedMs " +
                    "target=$targetResolvedCount candidatePool=$TEN_K_UNKNOWN_OBJECT_COUNT " +
                    "resolved=${resolvedKeys.size} attempted=$totalAttempted " +
                    "budgetDeferredIssues=$totalBudgetDeferredIssues " +
                    "totalVerifyWallMs=$totalVerifyWallMs " +
                    "avgVerifyUsPerResolvedObject=$avgVerifyUsPerResolvedObject " +
                    "logicalDoubleShaBytes=$logicalDoubleShaBytes " +
                    "medianPassMs=$medianPassMs p95PassMs=$p95PassMs maxPassMs=$maxPassMs " +
                    "medianVerifyMs=$medianVerifyMs p95VerifyMs=$p95VerifyMs " +
                    "maxVerifyMs=$maxVerifyMs peakPssKb=$peakPssKb peakRssKb=$peakRssKb",
            )
        } finally {
            TestDocumentsProvider.endExclusiveLease(TEN_K_UNKNOWN_LEASE_TOKEN)
        }
    }

    private fun runRoomAtomicityGate() {
        val database = Room.inMemoryDatabaseBuilder(
            applicationContext,
            MicaDatabase::class.java,
        ).build()
        val repository = LibraryRepository(database)
        val source = SourceIdentityKey.folder("content://qa/tree/room-atomicity")
        val state = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(source, activationEpoch = 17L),
            ),
            configFingerprint = "qa-room-atomicity",
        )
        try {
            runBlocking {
                val oldLyrics = roomQaLyrics("old", LyricsFormat.LRC)
                val oldSong = roomQaSong(
                    id = "room-success",
                    title = "Before",
                    modifiedMs = 1L,
                    lyrics = oldLyrics,
                )
                repository.commitScanAuthority(
                    songs = listOf(oldSong),
                    lastScanAtMs = 100L,
                    lastScanSource = ScanSource.FOLDER,
                    totalSizeMb = 1,
                    state = state,
                )
                val newSong = oldSong.copy(
                    title = "After",
                    dateModifiedMs = 2L,
                    lyricsDocument = LyricsDocument(),
                    lyricsLoaded = false,
                )
                val newLyrics = roomQaLyrics("new", LyricsFormat.TTML)
                val successScanId = "room-auto-success"
                repository.stageLyrics(
                    successScanId,
                    listOf(
                        ScannedSongLyrics(
                            newSong.id,
                            newSong.lyricsCacheRevision,
                            LyricsSlots(externalTtml = newLyrics),
                        ),
                    ),
                )
                require(roomPendingLyricsCount(database, successScanId) == 1)
                val successCheckpoint = LibrarySyncCheckpoint(
                    sourceIdentity = source,
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    providerVersion = "saf-full-walk-v1",
                    generation = 0L,
                    configFingerprint = state.configFingerprint,
                    lastSuccessfulAutoSyncAtMs = 1_000L,
                )
                val successRetry = LibraryRetryItem(
                    sourceIdentity = source,
                    retryKey = "saf-object-probe:" + newSong.id,
                    activationEpoch = 17L,
                    stableObjectKey = newSong.id,
                    observedFingerprint = "fp-success",
                    retryKind = LibraryRetryKind.OBJECT_PROBE,
                    failureKind = "qa-success",
                    attemptCount = 1,
                    nextRetryAtMs = 2_000L,
                )
                repository.commitAutoSyncDeltaAuthority(
                    delta = LibraryAutoSyncStoreDelta(
                        upsertRows = listOf(LibraryAutoSyncStoreRow(newSong, queueOrderHint = 0)),
                        removedSongIds = emptyList(),
                        snapshotSongCount = 1,
                        addedCount = 0,
                        updatedCount = 1,
                    ),
                    lastScanAtMs = 100L,
                    lastScanSource = ScanSource.FOLDER,
                    totalSizeMb = 1,
                    state = state,
                    autoSyncStateMutation = LibraryAutoSyncStateMutation(
                        sourceIdentity = source,
                        checkpoints = listOf(successCheckpoint),
                        retryUpserts = listOf(successRetry),
                    ),
                    followupOutboxItems = emptyList(),
                    stagedLyricsId = successScanId,
                )
                val successCached = requireNotNull(repository.loadCached())
                require(successCached.songs.single().title == "After")
                require(successCached.sortField == null && successCached.sortDirection == null) {
                    "AUTO delta commit must invalidate persisted queue-order presentation"
                }
                require(successCached.fastScrollSectionTargets == null)
                require(successCached.browseArtistConfigKey.isBlank()) {
                    "AUTO delta commit must invalidate persisted browse-group cache"
                }
                require(repository.lyricsById(newSong.id) == newLyrics)
                require(repository.loadSyncCheckpoints(source) == listOf(successCheckpoint))
                require(loadAllRetryItems(repository, source) == listOf(successRetry))
                require(roomPendingLyricsCount(database, successScanId) == 0)

                val rollbackOldLyrics = roomQaLyrics("rollback-old", LyricsFormat.LRC)
                val rollbackOldSong = roomQaSong(
                    id = "room-rollback",
                    title = "Rollback Before",
                    modifiedMs = 10L,
                    lyrics = rollbackOldLyrics,
                )
                repository.commitAutoSyncSnapshotAuthority(
                    songs = listOf(newSong, rollbackOldSong),
                    lastScanAtMs = 100L,
                    lastScanSource = ScanSource.FOLDER,
                    totalSizeMb = 2,
                    state = state,
                    autoSyncStateMutation = LibraryAutoSyncStateMutation(sourceIdentity = source),
                    followupOutboxItems = emptyList(),
                )
                val rollbackNewSong = rollbackOldSong.copy(
                    title = "Rollback After",
                    dateModifiedMs = 11L,
                    lyricsDocument = LyricsDocument(),
                    lyricsLoaded = false,
                )
                val rollbackNewLyrics = roomQaLyrics("rollback-new", LyricsFormat.TTML)
                val rollbackScanId = "room-auto-rollback"
                repository.stageLyrics(
                    rollbackScanId,
                    listOf(
                        ScannedSongLyrics(
                            rollbackNewSong.id,
                            rollbackNewSong.lyricsCacheRevision,
                            LyricsSlots(externalTtml = rollbackNewLyrics),
                        ),
                    ),
                )
                require(roomPendingLyricsCount(database, rollbackScanId) == 1)
                val beforeCheckpoints = repository.loadSyncCheckpoints(source)
                val beforeRetries = loadAllRetryItems(repository, source)
                val rollbackCheckpoint = successCheckpoint.copy(
                    lastSuccessfulAutoSyncAtMs = 3_000L,
                )
                val rollbackRetry = successRetry.copy(
                    retryKey = "saf-object-probe:" + rollbackNewSong.id,
                    stableObjectKey = rollbackNewSong.id,
                    observedFingerprint = "fp-rollback",
                )
                database.openHelper.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER fail_room_atomicity_song_insert
                    BEFORE INSERT ON songs
                    WHEN NEW.id = 'room-rollback'
                    BEGIN
                        SELECT RAISE(ABORT, 'forced room atomicity failure');
                    END
                    """.trimIndent(),
                )

                val rollbackFollowup = LibraryFollowupOutboxItem(
                    eventId = "room-atomicity-rollback-followup",
                    libraryRevision = 99L,
                    action = "PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP",
                    sourceIdentity = source,
                    activationEpoch = 17L,
                    stableObjectKey = rollbackNewSong.id,
                    payload = "songId=" + rollbackNewSong.id,
                    createdAtMs = 3_000L,
                )
                val beforeOutbox = loadAllFollowupOutbox(repository)
                val failure = runCatching {
                    repository.commitAutoSyncDeltaAuthority(
                        delta = LibraryAutoSyncStoreDelta(
                            upsertRows = listOf(
                                LibraryAutoSyncStoreRow(rollbackNewSong, queueOrderHint = 1),
                            ),
                            removedSongIds = emptyList(),
                            snapshotSongCount = 2,
                            addedCount = 0,
                            updatedCount = 1,
                        ),
                        lastScanAtMs = 100L,
                        lastScanSource = ScanSource.FOLDER,
                        totalSizeMb = 2,
                        state = state,
                        autoSyncStateMutation = LibraryAutoSyncStateMutation(
                            sourceIdentity = source,
                            checkpoints = listOf(rollbackCheckpoint),
                            retryUpserts = listOf(rollbackRetry),
                        ),
                        followupOutboxItems = listOf(rollbackFollowup),
                        stagedLyricsId = rollbackScanId,
                    )
                }.exceptionOrNull()
                require(failure != null) {
                    "forced Room transaction failure did not abort"
                }

                val rollbackStored = repository.loadCached()!!.songs
                    .single { it.id == rollbackOldSong.id }
                require(rollbackStored.title == "Rollback Before")
                require(repository.lyricsById(rollbackOldSong.id) == rollbackOldLyrics)
                require(repository.loadSyncCheckpoints(source) == beforeCheckpoints)
                require(loadAllRetryItems(repository, source) == beforeRetries)
                require(loadAllFollowupOutbox(repository) == beforeOutbox)
                require(roomPendingLyricsCount(database, rollbackScanId) == 1)
                require(repository.loadLibraryState() == state)

                val message =
                    "room-atomicity-complete deltaPath=true successPromoted=true successPending=0 " +
                        "rollbackTriggered=true rollbackSnapshotPreserved=true " +
                        "rollbackLyricsPreserved=true rollbackPending=1 " +
                        "rollbackCheckpointPreserved=true rollbackRetryPreserved=true " +
                        "rollbackOutboxPreserved=true"
                Log.i(ROOM_ATOMICITY_TAG, message)
                appendEvidence(message)
            }
        } finally {
            database.close()
        }
    }


    private fun runRoomPublicationTenKGate(unicodeTitles: Boolean) {
        val profile = if (unicodeTitles) "unicode" else "ascii"
        val databaseName = "mica-s4-publication-gate-" + profile + "-" + System.currentTimeMillis() + ".db"
        val database = Room.databaseBuilder(
            applicationContext,
            MicaDatabase::class.java,
            databaseName,
        ).build()
        val repository = LibraryRepository(database)
        val source = SourceIdentityKey.folder("content://qa/tree/publication-10k-" + profile)
        val configFingerprint = LibraryScanSettings.configFingerprint(applicationContext)
        val state = PersistedLibraryState(
            intent = LibraryIntentState.ACTIVE,
            access = LibraryAccessState.AVAILABLE,
            sourceState = LibrarySourceState(
                active = SourceActivation(source, activationEpoch = 23L),
            ),
            configFingerprint = configFingerprint,
        )
        val baseline = List(ROOM_PUBLICATION_OBJECT_COUNT) { index ->
            roomQaSong(
                id = "publication-" + index,
                title = if (unicodeTitles) "歌曲 " + index else "Track " + index,
                modifiedMs = 1L,
                lyrics = LyricsDocument(),
            ).copy(
                artist = if (unicodeTitles) "艺术家 " + index else "Artist " + index,
                album = if (unicodeTitles) "专辑 " + index else "Album " + index,
                lyricsLoaded = false,
            )
        }
        val backing = MusicLibraryBacking(
            context = applicationContext,
            libraryScanner = AndroidLibraryScanner(applicationContext),
            libraryStore = RoomLibraryStore(repository),
            scanEnvironment = AndroidScanEnvironment(applicationContext),
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.IO,
        )

        try {
            runBlocking {
                backing.libraryFolderUri = "content://qa/tree/publication-10k-" + profile
                backing.lastScanAtMs = 100L
                backing.lastScanSource = ScanSource.FOLDER
                backing.totalSizeMb = 10
                backing.hasScanned = true
                backing.restorePersistedState(state)
                val baselinePrepareStartedMs = SystemClock.elapsedRealtime()
                val preparedBaseline = backing.catalog.prepareLibrarySongs(
                    raw = baseline,
                    field = backing.sortField,
                    direction = backing.sortDirection,
                    diagnosticTag = ROOM_PUBLICATION_TAG,
                    diagnosticReason = "10k-" + profile + "-baseline",
                    releaseLoadedLyrics = true,
                )
                val baselinePrepareMs = SystemClock.elapsedRealtime() - baselinePrepareStartedMs
                if (unicodeTitles) {
                    require(baselinePrepareMs <= ROOM_PUBLICATION_COLD_UNICODE_PREPARE_GATE_MS) {
                        "10k Unicode cold prepare exceeded gate: " + baselinePrepareMs + "ms > " +
                            ROOM_PUBLICATION_COLD_UNICODE_PREPARE_GATE_MS + "ms"
                    }
                }

                val baselineStartedMs = SystemClock.elapsedRealtime()
                repository.commitScanAuthority(
                    songs = preparedBaseline.visible,
                    lastScanAtMs = 100L,
                    lastScanSource = ScanSource.FOLDER,
                    totalSizeMb = 10,
                    state = state,
                    sortField = backing.sortField,
                    sortDirection = backing.sortDirection,
                    fastScrollSectionTargets = preparedBaseline.fastScrollIndex?.sectionTargets,
                )
                val baselineRoomMs = SystemClock.elapsedRealtime() - baselineStartedMs
                backing.catalog.adoptPrepared(preparedBaseline)

                val timings = mutableListOf<MusicLibraryBacking.AutoPublicationTiming>()
                val walls = mutableListOf<Long>()
                repeat(ROOM_PUBLICATION_PASS_COUNT) { passIndex ->
                    val current = backing.songs
                    require(current.size == ROOM_PUBLICATION_OBJECT_COUNT)
                    val updateIds = current.take(ROOM_PUBLICATION_DELTA_COUNT)
                        .mapTo(linkedSetOf(), Song::id)
                    val next = current.map { song ->
                        if (song.id in updateIds) {
                            song.copy(
                                comment = "publication-pass-" + (passIndex + 1),
                                dateModifiedMs = song.dateModifiedMs + 1L,
                            )
                        } else {
                            song
                        }
                    }
                    val token = requireNotNull(
                        backing.beginActiveAutoSyncOperationToken(
                            requestSequence = 50_000L + passIndex,
                            dirtySequenceAtStart = passIndex.toLong(),
                            cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                        ),
                    )
                    val checkpoint = LibrarySyncCheckpoint(
                        sourceIdentity = source,
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        providerVersion =
                            SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_PROVIDER_VERSION,
                        generation = SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_GENERATION,
                        configFingerprint = token.configFingerprint,
                        lastSuccessfulAutoSyncAtMs = 1_000L + passIndex,
                    )
                    val plan = SafAutoSyncPublicationPlan(
                        nextSnapshot = next,
                        visibleDelta = AutoSyncVisibleDelta(updatedIds = updateIds),
                        membershipChanges = emptyList(),
                        autoSyncStateMutation = LibraryAutoSyncStateMutation(
                            sourceIdentity = source,
                            checkpoints = listOf(checkpoint),
                        ),
                        lyricsToStage = emptyList(),
                        checkpointIncluded = true,
                    )
                    val startedMs = SystemClock.elapsedRealtime()
                    val result = backing.scanOrchestrator.publishSafAutoSyncPlanForReadiness(
                        token = token,
                        scanStartSnapshot = current,
                        plan = plan,
                    )
                    val wallMs = SystemClock.elapsedRealtime() - startedMs
                    require(result != null)
                    require(result.updated >= ROOM_PUBLICATION_DELTA_COUNT) {
                        "10k publication expected at least " + ROOM_PUBLICATION_DELTA_COUNT +
                            " updates, got " + result.updated
                    }
                    val timing = requireNotNull(backing.lastAutoPublicationTiming)
                    require(wallMs <= ROOM_PUBLICATION_WARM_SMALL_DELTA_GATE_MS) {
                        "10k small-delta AUTO wall exceeded gate: " + wallMs + "ms > " +
                            ROOM_PUBLICATION_WARM_SMALL_DELTA_GATE_MS + "ms"
                    }
                    require(timing.holdMs <= ROOM_PUBLICATION_HOLD_GATE_MS) {
                        "AUTO publication hold exceeded gate: " + timing.holdMs + "ms > " +
                            ROOM_PUBLICATION_HOLD_GATE_MS + "ms"
                    }
                    require(timing.storeMs <= ROOM_PUBLICATION_STORE_GATE_MS) {
                        "AUTO Room transaction exceeded gate: " + timing.storeMs + "ms > " +
                            ROOM_PUBLICATION_STORE_GATE_MS + "ms"
                    }
                    timings += timing
                    walls += wallMs
                    val dbFiles = publicationDbBytes(databaseName)
                    val message =
                        "publication-10k-pass profile=" + profile +
                            " pass=" + (passIndex + 1) +
                            " objects=" + current.size +
                            " delta=" + updateIds.size +
                            " wallMs=" + wallMs +
                            " waitMs=" + String.format("%.3f", timing.waitMs) +
                            " holdMs=" + String.format("%.3f", timing.holdMs) +
                            " storeMs=" + String.format("%.3f", timing.storeMs) +
                            " memoryAdoptMs=" + String.format("%.3f", timing.memoryAdoptMs) +
                            " dbBytes=" + dbFiles.first +
                            " walBytes=" + dbFiles.second
                    Log.i(ROOM_PUBLICATION_TAG, message)
                    appendEvidence(message)
                }

                val summary =
                    "publication-10k-complete profile=" + profile +
                        " baselinePrepareMs=" + baselinePrepareMs +
                        " baselineRoomMs=" + baselineRoomMs +
                        " passes=" + timings.size +
                        " objects=" + ROOM_PUBLICATION_OBJECT_COUNT +
                        " delta=" + ROOM_PUBLICATION_DELTA_COUNT +
                        " wallMedianMs=" + medianLong(walls) +
                        " waitMedianMs=" +
                        String.format("%.3f", medianDouble(timings.map { it.waitMs })) +
                        " holdMedianMs=" +
                        String.format("%.3f", medianDouble(timings.map { it.holdMs })) +
                        " holdMaxMs=" +
                        String.format("%.3f", timings.maxOf { it.holdMs }) +
                        " storeMedianMs=" +
                        String.format("%.3f", medianDouble(timings.map { it.storeMs })) +
                        " memoryAdoptMedianMs=" +
                        String.format("%.3f", medianDouble(timings.map { it.memoryAdoptMs })) +
                        " memoryAdoptMaxMs=" +
                        String.format("%.3f", timings.maxOf { it.memoryAdoptMs }) +
                        " database=" + databaseName
                Log.i(ROOM_PUBLICATION_TAG, summary)
                appendEvidence(summary)
            }
        } finally {
            backing.release()
            database.close()
        }
    }

    private fun publicationDbBytes(databaseName: String): Pair<Long, Long> {
        val dbFile = getDatabasePath(databaseName)
        val walFile = File(dbFile.path + "-wal")
        return dbFile.length() to walFile.length()
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2L
        }
    }

    private fun roomQaSong(
        id: String,
        title: String,
        modifiedMs: Long,
        lyrics: LyricsDocument,
    ): Song = Song(
        id = id,
        title = title,
        artist = "QA",
        album = "QA",
        durationSec = 1,
        metadata = TrackMetadata(
            containerName = "WAV",
            sampleRateHz = 44_100,
            bitsPerSample = 16,
            bitrateKbps = 1_411,
            channelCount = 2,
            playbackMimeType = "audio/wav",
        ),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "content://qa/document/" + id,
        fileName = id + ".wav",
        sizeBytes = 1_000L,
        folderPath = "QA",
        filePath = "QA/" + id + ".wav",
        dateModifiedMs = modifiedMs,
        lyricsDocument = lyrics,
        lyricsLoaded = true,
    )

    private fun roomQaLyrics(
        text: String,
        format: LyricsFormat,
    ): LyricsDocument = LyricsDocument(
        format = format,
        origin = LyricsOrigin.EXTERNAL,
        lines = listOf(
            LyricLineNode(
                id = "qa-0",
                startMs = 0,
                parts = listOf(
                    LyricTextPart(
                        role = LyricTextRole.ORIGINAL,
                        text = text,
                    ),
                ),
            ),
        ),
    )

    private suspend fun loadAllRetryItems(
        repository: LibraryRepository,
        sourceIdentity: SourceIdentityKey,
    ): List<LibraryRetryItem> {
        val items = mutableListOf<LibraryRetryItem>()
        var cursor = LibraryRetryCursor.Start
        while (true) {
            val page = repository.loadRetryItemsPage(
                sourceIdentity = sourceIdentity,
                cursor = cursor,
                limit = LibraryRetryPaging.PAGE_SIZE,
            )
            items += page.items
            val next = page.nextCursor ?: break
            if (next == cursor || page.items.size < LibraryRetryPaging.PAGE_SIZE) break
            cursor = next
        }
        return items
    }

    private suspend fun loadAllFollowupOutbox(
        repository: LibraryRepository,
    ): List<LibraryFollowupOutboxItem> {
        val items = mutableListOf<LibraryFollowupOutboxItem>()
        var cursor = LibraryFollowupOutboxCursor.Start
        while (true) {
            val page = repository.loadFollowupOutboxPage(cursor = cursor, limit = 64)
            items += page.items
            val next = page.nextCursor ?: break
            if (next == cursor || page.items.size < 64) break
            cursor = next
        }
        return items
    }

    private fun roomPendingLyricsCount(
        database: MicaDatabase,
        scanId: String,
    ): Int = database.openHelper.writableDatabase
        .query(
            "SELECT COUNT(*) FROM song_lyrics_pending WHERE scanId = ?",
            arrayOf(scanId),
        )
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun runAutoQueryLaneGate() {
        val treeUri = prepareTreeUri()
        TestDocumentsProvider.resetScenario()
        TestDocumentsProvider.configureChildQueryBehavior(
            delayMs = LANE_GATE_QUERY_DELAY_MS,
            serializeQueries = false,
            ignoreInterrupts = true,
        )
        val scanner = AndroidLibraryScanner(applicationContext)

        var cancelJoinMs = -1L
        var busyWalkMs = -1L
        var busyDetail = ""
        var recoveryWalkMs = -1L
        var recoveryEntries = -1
        runBlocking {
            val first = launch {
                scanner.observeFolderMetadata(treeUri)
            }
            while (TestDocumentsProvider.observedChildQueryCount() == 0) {
                delay(10L)
            }

            val cancelStartedMs = SystemClock.elapsedRealtime()
            first.cancelAndJoin()
            cancelJoinMs = SystemClock.elapsedRealtime() - cancelStartedMs

            val busyStartedMs = SystemClock.elapsedRealtime()
            val busy = scanner.observeFolderMetadata(treeUri)
            busyWalkMs = SystemClock.elapsedRealtime() - busyStartedMs
            require(
                !busy.discoveryReport.isComplete(
                    com.mica.music.data.scanner.DiscoveryPartitions.SAF_TREE,
                ),
            ) {
                "busy AUTO metadata lane must fail closed instead of queueing"
            }
            busyDetail = busy.discoveryReport.partitions[
                com.mica.music.data.scanner.DiscoveryPartitions.SAF_TREE
            ]?.detail.orEmpty()
            require(busyDetail.contains("query lane busy", ignoreCase = true)) {
                "busy AUTO metadata lane must expose a diagnosable PARTIAL detail: $busyDetail"
            }
            require(TestDocumentsProvider.observedChildQueryCount() == 1) {
                "busy fail-fast must not enter the provider again"
            }

            delay(LANE_GATE_QUERY_DELAY_MS + LANE_GATE_RECOVERY_SETTLE_MS)

            val recoveryStartedMs = SystemClock.elapsedRealtime()
            val recovery = scanner.observeFolderMetadata(treeUri)
            recoveryWalkMs = SystemClock.elapsedRealtime() - recoveryStartedMs
            recoveryEntries = recovery.entries.size
            require(
                recovery.discoveryReport.isComplete(
                    com.mica.music.data.scanner.DiscoveryPartitions.SAF_TREE,
                ),
            ) {
                "metadata lane must recover after old provider query exits: " +
                    recovery.discoveryReport.aggregate
            }
        }

        val observedQueries = TestDocumentsProvider.observedChildQueryCount()
        val maxConcurrent = TestDocumentsProvider.maxObservedConcurrentChildQueries()
        require(maxConcurrent == 1) {
            "AUTO metadata lane allowed concurrent provider queries: max=$maxConcurrent"
        }
        require(observedQueries >= 3) {
            "expected cancelled root query plus complete two-query walk, got $observedQueries"
        }

        Log.i(
            QUERY_LANE_TAG,
            "auto-query-lane-complete cancelJoinMs=$cancelJoinMs " +
                "busyWalkMs=$busyWalkMs busyDetail=$busyDetail " +
                "recoveryWalkMs=$recoveryWalkMs recoveryEntries=$recoveryEntries " +
                "providerQueries=$observedQueries maxConcurrent=$maxConcurrent " +
                "delayMs=$LANE_GATE_QUERY_DELAY_MS providerSerialization=false " +
                "providerIgnoresInterrupts=true",
        )
        appendEvidence(
            "auto-query-lane cancelJoinMs=$cancelJoinMs busyWalkMs=$busyWalkMs " +
                "recoveryWalkMs=$recoveryWalkMs recoveryEntries=$recoveryEntries " +
                "providerQueries=$observedQueries " +
                "maxConcurrent=$maxConcurrent delayMs=$LANE_GATE_QUERY_DELAY_MS",
        )
    }

    private fun runExternalProviderCadenceGate(treeUri: Uri) {
        val scanner = AndroidLibraryScanner(applicationContext)
        val startedMs = SystemClock.elapsedRealtime()
        val snapshot = runBlocking {
            scanner.observeFolderMetadata(treeUri)
        }
        val externalWallMs = (SystemClock.elapsedRealtime() - startedMs).coerceAtLeast(0L)
        require(snapshot.discoveryReport.isComplete(com.mica.music.data.scanner.DiscoveryPartitions.SAF_TREE)) {
            "external provider metadata walk must be COMPLETE: " +
                snapshot.discoveryReport.aggregate
        }

        val guard = SafProviderDiscoveryBackoff()
        val completedAtMs = SystemClock.elapsedRealtime()
        val completeState = guard.recordComplete(
            scopeKey = treeUri.toString(),
            nowMs = completedAtMs,
            wallTimeMs = snapshot.observationStats.wallTimeMs,
        )
        val immediatePermit = guard.permit(
            scopeKey = treeUri.toString(),
            nowMs = completedAtMs,
        )
        val playbackReleasePermit = guard.permit(
            scopeKey = treeUri.toString(),
            nowMs = completedAtMs,
            bypassSlowSuccessCadence = true,
        )

        if (
            snapshot.observationStats.wallTimeMs >=
                SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS
        ) {
            require(immediatePermit is SafProviderDiscoveryPermit.SlowSuccessCadence) {
                "slow COMPLETE provider walk did not enter cadence guard"
            }
        }
        require(playbackReleasePermit is SafProviderDiscoveryPermit.Allowed) {
            "PLAYBACK_IO_RELEASE must bypass only the slow-success cadence guard"
        }

        val immediateKind = when (immediatePermit) {
            SafProviderDiscoveryPermit.Allowed -> "ALLOWED"
            is SafProviderDiscoveryPermit.BackedOff -> "FAILURE_BACKOFF"
            is SafProviderDiscoveryPermit.SlowSuccessCadence -> "SLOW_SUCCESS_CADENCE"
        }
        Log.i(
            CADENCE_TAG,
            "external-provider-cadence-complete uri=$treeUri " +
                "entries=${snapshot.entries.size} " +
                "completeness=${snapshot.discoveryReport.aggregate} " +
                "metadataWallMs=${snapshot.observationStats.wallTimeMs} " +
                "externalWallMs=$externalWallMs " +
                "providerQueries=${snapshot.observationStats.providerQueryCount} " +
                "slowSuccess=${completeState.slowSuccess} " +
                "thresholdMs=${completeState.slowSuccessThresholdMs} " +
                "cadenceMs=${completeState.cadenceMs} " +
                "immediatePermit=$immediateKind " +
                "playbackReleasePermit=ALLOWED",
        )
        appendEvidence(
            "external-provider-cadence uri=$treeUri entries=${snapshot.entries.size} " +
                "metadataWallMs=${snapshot.observationStats.wallTimeMs} " +
                "externalWallMs=$externalWallMs slowSuccess=${completeState.slowSuccess} " +
                "immediatePermit=$immediateKind playbackReleasePermit=ALLOWED",
        )
    }

    private fun runProviderBackoffGate() {
        val treeUri = prepareTreeUri()
        val library = MusicLibrary(applicationContext)
        try {
            TestDocumentsProvider.resetScenario()
            TestDocumentsProvider.resetChildQueryBehavior()
            library.setLibraryFolder(treeUri)
            runBlocking {
                library.scanLibraryFolder()
            }
            Log.i(
                PROVIDER_TAG,
                "baseline-complete songs=" + library.songs.size +
                    " source=" + library.lastScanSource +
                    " error=" + library.lastScanError,
            )

            TestDocumentsProvider.setScenario(
                TestDocumentsProvider.FixtureScenario.CHANGED.name,
            )
            TestDocumentsProvider.configureChildQueryBehavior(delayMs = SLOW_QUERY_DELAY_MS)
            val slowStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            Log.i(
                PROVIDER_TAG,
                "slow-complete elapsedMs=" + (SystemClock.elapsedRealtime() - slowStartedMs) +
                    " providerChildQueries=" + TestDocumentsProvider.observedChildQueryCount(),
            )

            TestDocumentsProvider.configureChildQueryBehavior(
                failNextQueries = INJECTED_FAILURE_QUERY_BUDGET,
            )
            val failureStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            Log.i(
                PROVIDER_TAG,
                "failure-pass-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - failureStartedMs) +
                    " providerChildQueries=" + TestDocumentsProvider.observedChildQueryCount(),
            )

            TestDocumentsProvider.configureChildQueryBehavior()
            val immediateStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            Log.i(
                PROVIDER_TAG,
                "immediate-backoff-pass elapsedMs=" +
                    (SystemClock.elapsedRealtime() - immediateStartedMs) +
                    " providerChildQueries=" + TestDocumentsProvider.observedChildQueryCount(),
            )

            val waitMs = SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS + BACKOFF_SETTLE_MS
            Log.i(PROVIDER_TAG, "backoff-wait-start waitMs=$waitMs")
            Thread.sleep(waitMs)
            Log.i(PROVIDER_TAG, "backoff-wait-complete")

            val recoveryStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            val queriesAfterRecovery = TestDocumentsProvider.observedChildQueryCount()
            Log.i(
                PROVIDER_TAG,
                "recovery-pass-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - recoveryStartedMs) +
                    " providerChildQueries=$queriesAfterRecovery",
            )

            val resetCheckStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            Log.i(
                PROVIDER_TAG,
                "post-success-pass-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - resetCheckStartedMs) +
                    " providerChildQueries=" + TestDocumentsProvider.observedChildQueryCount(),
            )

            val oracleStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.scanLibraryFolder()
            }
            Log.i(
                PROVIDER_TAG,
                "oracle-complete elapsedMs=" + (SystemClock.elapsedRealtime() - oracleStartedMs) +
                    " songs=" + library.songs.size +
                    " error=" + library.lastScanError,
            )
        } finally {
            library.release()
        }
    }


    private fun runExternalProviderBaselineGate(treeUri: Uri) {
        val scanner = AndroidLibraryScanner(applicationContext)
        val preflight = runBlocking { scanner.observeFolderMetadata(treeUri) }
        require(preflight.discoveryReport.aggregate.name == "COMPLETE") {
            "External provider baseline requires COMPLETE preflight"
        }
        require(preflight.entries.isNotEmpty()) {
            "External provider baseline tree contains no entries"
        }

        val store = RoomLibraryStore(applicationContext)
        val source = SourceIdentityKey.folder(treeUri.toString())
        val library = MusicLibrary(applicationContext)
        try {
            library.setLibraryFolder(treeUri)
            runBlocking { library.scanLibraryFolder() }
            require(library.lastScanError == null) {
                "External provider baseline Full failed: ${library.lastScanError}"
            }
            require(library.songs.isNotEmpty()) {
                "External provider baseline Full produced an empty library"
            }

            val persisted = requireNotNull(runBlocking { store.loadLibraryState() }) {
                "External provider baseline did not persist library authority"
            }
            require(persisted.intent == LibraryIntentState.ACTIVE) {
                "Expected ACTIVE baseline intent, got ${persisted.intent}"
            }
            require(persisted.access == LibraryAccessState.AVAILABLE) {
                "Expected AVAILABLE baseline access, got ${persisted.access}"
            }
            require(persisted.sourceState.active?.sourceIdentity == source) {
                "External provider baseline persisted the wrong active source"
            }

            val checkpoint = runBlocking { store.loadSyncCheckpoints(source) }
                .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE }
            Log.i(
                PROVIDER_TAG,
                "external-baseline-complete tree=$treeUri entries=${preflight.entries.size} " +
                    "songs=${library.songs.size} intent=${persisted.intent} " +
                    "access=${persisted.access} checkpointMs=" +
                    (checkpoint?.lastSuccessfulAutoSyncAtMs ?: -1L),
            )
            appendEvidence(
                "external-baseline-complete tree=$treeUri songs=${library.songs.size} " +
                    "intent=${persisted.intent} access=${persisted.access}",
            )
        } finally {
            library.release()
        }
    }
    private fun runExternalProviderUnavailableGate(treeUri: Uri) {
        val store = RoomLibraryStore(applicationContext)
        val source = SourceIdentityKey.folder(treeUri.toString())
        val beforeState = requireNotNull(runBlocking { store.loadLibraryState() }) {
            "Missing persisted library state before external provider unavailable Gate"
        }
        // Older QA snapshots can still carry legacy UNINITIALIZED/PERMISSION_REQUIRED durable
        // state; loadCached() migrates the valid cached FOLDER snapshot to active in-memory
        // authority before scheduler use, so the Gate validates authority after load instead.
        val beforeCheckpoint = runBlocking { store.loadSyncCheckpoints(source) }
            .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE }
        val beforeRows = runBlocking { store.loadCached() }
            ?.songs
            .orEmpty()
            .map { song -> Triple(song.id, song.durationSec, song.sizeBytes) }

        val library = MusicLibrary(applicationContext)
        try {
            runBlocking { library.loadCachedLibrary() }
            require(library.lastScanSource == ScanSource.FOLDER)
            require(library.libraryFolderUri == treeUri.toString()) {
                "Loaded FOLDER binding does not match requested tree"
            }
            require(library.songs.isNotEmpty()) {
                "Provider-unavailable Gate requires a previously committed FOLDER snapshot"
            }

            Log.i(
                PROVIDER_TAG,
                "external-unavailable-start tree=$treeUri songs=${library.songs.size} " +
                    "checkpointMs=${beforeCheckpoint?.lastSuccessfulAutoSyncAtMs ?: -1L}",
            )
            val startedMs = SystemClock.elapsedRealtime()
            library.onForegroundChanged(true)
            require(
                waitForQa(EXTERNAL_PROVIDER_UNAVAILABLE_TIMEOUT_MS) {
                    library.lastScanError == SAF_PROVIDER_RESELECT_REQUIRED_ERROR
                },
            ) {
                "Ordinary FOLDER scheduler did not surface provider reselect recovery state"
            }
            require(
                waitForQa(PERSISTED_STATE_SETTLE_TIMEOUT_MS) {
                    runBlocking { store.loadLibraryState() }
                        ?.access == LibraryAccessState.TEMP_UNAVAILABLE
                },
            ) {
                "TEMP_UNAVAILABLE was not persisted after provider acquisition failures"
            }

            val afterState = requireNotNull(runBlocking { store.loadLibraryState() })
            val afterCheckpoint = runBlocking { store.loadSyncCheckpoints(source) }
                .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE }
            val afterRows = runBlocking { store.loadCached() }
                ?.songs
                .orEmpty()
                .map { song -> Triple(song.id, song.durationSec, song.sizeBytes) }

            require(afterState.access == LibraryAccessState.TEMP_UNAVAILABLE)
            require(
                beforeCheckpoint?.lastSuccessfulAutoSyncAtMs ==
                    afterCheckpoint?.lastSuccessfulAutoSyncAtMs,
            ) {
                "Provider-unavailable pass advanced the SAF checkpoint"
            }
            require(beforeRows == afterRows) {
                "Provider-unavailable pass changed the committed library snapshot"
            }

            Log.i(
                PROVIDER_TAG,
                "external-unavailable-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - startedMs) +
                    " access=${afterState.access} songs=${afterRows.size} " +
                    "checkpointBefore=${beforeCheckpoint?.lastSuccessfulAutoSyncAtMs ?: -1L} " +
                    "checkpointAfter=${afterCheckpoint?.lastSuccessfulAutoSyncAtMs ?: -1L} " +
                    "error=${library.lastScanError}",
            )
            appendEvidence(
                "external-unavailable-complete tree=$treeUri access=${afterState.access} " +
                    "songs=${afterRows.size} checkpointUnchanged=true",
            )
        } finally {
            library.onForegroundChanged(false)
            library.release()
        }
    }

    private fun runExternalProviderRearmGate(treeUri: Uri) {
        val store = RoomLibraryStore(applicationContext)
        val source = SourceIdentityKey.folder(treeUri.toString())
        val beforeState = requireNotNull(runBlocking { store.loadLibraryState() }) {
            "Missing persisted library state before external provider rearm Gate"
        }
        require(beforeState.intent == LibraryIntentState.ACTIVE)
        require(beforeState.access == LibraryAccessState.TEMP_UNAVAILABLE) {
            "Expected TEMP_UNAVAILABLE before rearm Gate, got ${beforeState.access}"
        }
        require(beforeState.sourceState.active?.sourceIdentity == source) {
            "Persisted active source does not match requested third-party tree"
        }

        val preflight = runBlocking {
            AndroidLibraryScanner(applicationContext).observeFolderMetadata(treeUri)
        }
        require(preflight.discoveryReport.aggregate.name == "COMPLETE") {
            "Provider must be queryable after user re-selection before rearm Gate"
        }

        val beforeCheckpoint = runBlocking { store.loadSyncCheckpoints(source) }
            .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE }
        val beforeCheckpointMs = beforeCheckpoint?.lastSuccessfulAutoSyncAtMs ?: -1L
        val library = MusicLibrary(applicationContext)
        try {
            runBlocking { library.loadCachedLibrary() }
            require(library.lastScanSource == ScanSource.FOLDER)
            require(library.libraryFolderUri == treeUri.toString())

            library.updatePermission(false)
            require(
                waitForQa(PERSISTED_STATE_SETTLE_TIMEOUT_MS) {
                    runBlocking { store.loadLibraryState() }
                        ?.access == LibraryAccessState.AVAILABLE
                },
            ) {
                "FOLDER access refresh did not re-arm AVAILABLE"
            }

            Log.i(
                PROVIDER_TAG,
                "external-rearm-start tree=$treeUri entries=${preflight.entries.size} " +
                    "checkpointBefore=$beforeCheckpointMs",
            )
            val startedMs = SystemClock.elapsedRealtime()
            library.onForegroundChanged(true)
            require(
                waitForQa(EXTERNAL_PROVIDER_REARM_TIMEOUT_MS) {
                    runBlocking {
                        store.loadSyncCheckpoints(source)
                            .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE }
                            ?.lastSuccessfulAutoSyncAtMs
                    }?.let { checkpointMs -> checkpointMs > beforeCheckpointMs } == true
                },
            ) {
                "Ordinary FOLDER scheduler did not advance checkpoint after provider rearm"
            }

            val afterState = requireNotNull(runBlocking { store.loadLibraryState() })
            val afterCheckpoint = requireNotNull(
                runBlocking { store.loadSyncCheckpoints(source) }
                    .firstOrNull { it.partitionKey == DiscoveryPartitions.SAF_TREE },
            )
            require(afterState.access == LibraryAccessState.AVAILABLE)
            require(library.lastScanError == null) {
                "Provider rearm left a scan error: ${library.lastScanError}"
            }
            require(afterCheckpoint.lastSuccessfulAutoSyncAtMs > beforeCheckpointMs)

            Log.i(
                PROVIDER_TAG,
                "external-rearm-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - startedMs) +
                    " access=${afterState.access} songs=${library.songs.size} " +
                    "checkpointBefore=$beforeCheckpointMs " +
                    "checkpointAfter=${afterCheckpoint.lastSuccessfulAutoSyncAtMs}",
            )
            appendEvidence(
                "external-rearm-complete tree=$treeUri access=${afterState.access} " +
                    "checkpointAdvanced=true",
            )
        } finally {
            library.onForegroundChanged(false)
            library.release()
        }
    }

    private fun waitForQa(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            Thread.sleep(QA_POLL_MS)
        }
        return predicate()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun appendEvidence(message: String) {
        runCatching {
            File(cacheDir, EVIDENCE_FILE_NAME).appendText(
                "wallMs=" + System.currentTimeMillis() +
                    " elapsedMs=" + SystemClock.elapsedRealtime() +
                    " pid=" + android.os.Process.myPid() +
                    " " + message + "\n",
            )
        }
    }

    private fun readSelfVmRssKb(): Long =
        runCatching {
            java.io.File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            } ?: -1L
        }.getOrDefault(-1L)

    private companion object {
        const val DEVICE_AUTHORITY_TAG = "MICA_S3_DEVICE_GATE"
        const val TAG = "MICA_S4_10K"
        const val HEAVY_TAG = "MICA_S4_HEAVY"
        const val UNKNOWN_TAG = "MICA_S4_UNKNOWN"
        const val EVIDENCE_FILE_NAME = "mica-s4-qa-profile-evidence.log"
        const val PROVIDER_TAG = "MICA_S4_PROVIDER"
        const val CADENCE_TAG = "MICA_S4_CADENCE"
        const val QUERY_LANE_TAG = "MICA_S4_QUERY_LANE"
        const val ROOM_ATOMICITY_TAG = "MICA_S4_ROOM_ATOMICITY"
        const val ROOM_PUBLICATION_TAG = "MICA_S4_ROOM_PUBLICATION"
        const val MODE_DEVICE_AUTHORITY = "DEVICE_AUTHORITY"
        const val MODE_PROVIDER_BACKOFF = "PROVIDER_BACKOFF"
        const val MODE_EXTERNAL_PROVIDER_BASELINE = "EXTERNAL_PROVIDER_BASELINE"
        const val MODE_EXTERNAL_PROVIDER_UNAVAILABLE = "EXTERNAL_PROVIDER_UNAVAILABLE"
        const val MODE_EXTERNAL_PROVIDER_REARM = "EXTERNAL_PROVIDER_REARM"
        const val MODE_AUTO_ARTWORK_GATE = "AUTO_ARTWORK_GATE"
        const val MODE_AUTO_QUERY_LANE = "AUTO_QUERY_LANE"
        const val MODE_ROOM_ATOMICITY = "ROOM_ATOMICITY"
        const val MODE_ROOM_PUBLICATION_10K = "ROOM_PUBLICATION_10K"
        const val MODE_ROOM_PUBLICATION_10K_UNICODE = "ROOM_PUBLICATION_10K_UNICODE"
        const val MODE_EXTERNAL_PROVIDER_CADENCE = "EXTERNAL_PROVIDER_CADENCE"
        const val MODE_TEN_K_HEAVY = "TEN_K_HEAVY"
        const val MODE_TEN_K_UNKNOWN = "TEN_K_UNKNOWN"
        const val TEN_K_HEAVY_LEASE_TOKEN = "ten-k-heavy"
        const val TEN_K_HEAVY_OBJECT_COUNT = 10_000
        const val ROOM_PUBLICATION_OBJECT_COUNT = 10_000
        const val ROOM_PUBLICATION_DELTA_COUNT = 32
        const val ROOM_PUBLICATION_PASS_COUNT = 3
        const val ROOM_PUBLICATION_COLD_UNICODE_PREPARE_GATE_MS = 5_000L
        const val ROOM_PUBLICATION_WARM_SMALL_DELTA_GATE_MS = 500L
        const val ROOM_PUBLICATION_HOLD_GATE_MS = 100.0
        const val ROOM_PUBLICATION_STORE_GATE_MS = 100.0
        val TEN_K_HEAVY_REQUIRED_BUDGET_PASSES =
            (TEN_K_HEAVY_OBJECT_COUNT + SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET - 1) /
                SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET
        val TEN_K_HEAVY_SHADOW_PASS_COUNT = TEN_K_HEAVY_REQUIRED_BUDGET_PASSES + 1
        const val TEN_K_HEAVY_PROGRESS_INTERVAL = 32
        const val TEN_K_UNKNOWN_LEASE_TOKEN = "ten-k-unknown"
        const val TEN_K_UNKNOWN_OBJECT_COUNT = 10_000
        const val TEN_K_UNKNOWN_MAX_PASSES = 1_000
        const val TEN_K_UNKNOWN_PROGRESS_INTERVAL = 16
        const val TEN_K_UNKNOWN_AUDIO_BYTES = 1_040_044L
        const val SLOW_QUERY_DELAY_MS = 250L
        const val LANE_GATE_QUERY_DELAY_MS = 1_500L
        const val LANE_GATE_RECOVERY_SETTLE_MS = 250L
        const val INJECTED_FAILURE_QUERY_BUDGET = 32
        const val BACKOFF_SETTLE_MS = 1_000L
        const val EXTERNAL_PROVIDER_UNAVAILABLE_TIMEOUT_MS = 130_000L
        const val EXTERNAL_PROVIDER_REARM_TIMEOUT_MS = 20_000L
        const val PERSISTED_STATE_SETTLE_TIMEOUT_MS = 5_000L
        const val QA_POLL_MS = 100L
        const val CHANNEL_ID = "mica_s4_qa_profile"
        const val NOTIFICATION_ID = 0x5344
    }
}
