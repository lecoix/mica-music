package com.mica.music.data

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.mica.music.MicaApp
import com.mica.music.data.library.LibraryOperationCause
import com.mica.music.data.library.LibraryPlaybackIoSnapshot
import com.mica.music.playback.PlaybackExecutionState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only S3/S4 automatic-library-sync QA gates.
 *
 * The S4 scenario action keeps the original shadow oracle flow. The S3 DEVICE authority action
 * deliberately exercises the ordinary production observer/scheduler/publication path against an
 * isolated app-owned MediaStore fixture, then restores the fixture without deleting it.
 */
class LibraryAutoSyncQaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val scenarioAction = appContext.packageName + ".debug.LIBRARY_S4_RUN_SCENARIO"
        val profileAction = appContext.packageName + ".debug.LIBRARY_S4_PROFILE_METADATA"
        val externalProviderProbeAction =
            appContext.packageName + ".debug.LIBRARY_S4_EXTERNAL_PROVIDER_PROBE"
        val externalProviderPayloadGateAction =
            appContext.packageName + ".debug.LIBRARY_S4_EXTERNAL_PROVIDER_PAYLOAD_GATE"
        val preparePlaybackAction =
            appContext.packageName + ".debug.LIBRARY_S4_PREPARE_PLAYBACK_BASELINE"
        val playbackGateAction =
            appContext.packageName + ".debug.LIBRARY_S4_PLAYBACK_DEFER_GATE"
        val dumpEvidenceAction =
            appContext.packageName + ".debug.LIBRARY_S4_DUMP_PROFILE_EVIDENCE"
        if (intent.action == preparePlaybackAction) {
            val pending = goAsync()
            Thread {
                try {
                    runPreparePlaybackBaseline(appContext)
                } catch (error: Throwable) {
                    Log.e(TAG, "playback-prepare-failed", error)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        if (intent.action == dumpEvidenceAction) {
            val evidenceFile = File(
                appContext.cacheDir,
                "mica-s4-qa-profile-evidence.log",
            )
            if (!evidenceFile.exists()) {
                Log.i(TAG, "profile-evidence missing")
            } else {
                evidenceFile.useLines { lines ->
                    lines.forEach { line -> Log.i(TAG, "profile-evidence " + line) }
                }
            }
            return
        }
        if (intent.action == playbackGateAction) {
            val pending = goAsync()
            Thread {
                try {
                    runPlaybackDeferGate(
                        appContext = appContext,
                        scenario = intent.getStringExtra("scenario")
                            ?.trim()
                            ?.uppercase()
                            ?.let(TestDocumentsProvider.FixtureScenario::valueOf)
                            ?: TestDocumentsProvider.FixtureScenario.CHANGED,
                    )
                } catch (error: Throwable) {
                    Log.e(TAG, "playback-gate-failed", error)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        if (intent.action == externalProviderProbeAction) {
            val treeUri = intent.data
            if (treeUri == null) {
                Log.e(VENDOR_TAG, "external-provider-probe-failed missing tree URI")
                return
            }
            val pending = goAsync()
            Thread {
                try {
                    val permission = appContext.checkUriPermission(
                        treeUri,
                        android.os.Process.myPid(),
                        android.os.Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    Log.i(
                        VENDOR_TAG,
                        "grant-check uri=" + treeUri +
                            " permission=" + permission,
                    )
                    val startedMs = SystemClock.elapsedRealtime()
                    val snapshot = runBlocking {
                        AndroidLibraryScanner(appContext).observeFolderMetadata(treeUri)
                    }
                    Log.i(
                        VENDOR_TAG,
                        "metadata-complete uri=" + treeUri +
                            " entries=" + snapshot.entries.size +
                            " completeness=" + snapshot.discoveryReport.aggregate +
                            " wallMs=" + snapshot.observationStats.wallTimeMs +
                            " externalWallMs=" + (SystemClock.elapsedRealtime() - startedMs) +
                            " providerQueries=" + snapshot.observationStats.providerQueryCount +
                            " directQueries=" + snapshot.observationStats.directQueryCount +
                            " fallbackListings=" + snapshot.observationStats.fallbackListingCount +
                            " details=" + snapshot.discoveryReport.partitions.values.joinToString("|") {
                                status -> status.partitionKey + ":" + status.completeness + ":" + status.detail
                            },
                    )
                } catch (error: Throwable) {
                    Log.e(VENDOR_TAG, "external-provider-probe-failed uri=" + treeUri, error)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        if (intent.action == externalProviderPayloadGateAction) {
            if (!externalPayloadGateRunning.compareAndSet(false, true)) {
                Log.w(VENDOR_TAG, "external-payload-gate-rejected reason=already-running")
                return
            }
            val treeUri = intent.data
            if (treeUri == null) {
                externalPayloadGateRunning.set(false)
                Log.e(VENDOR_TAG, "external-payload-gate-failed missing tree URI")
                return
            }
            val pending = goAsync()
            Thread {
                try {
                    runExternalProviderPayloadGate(
                        appContext = appContext,
                        treeUri = treeUri,
                        targetFileName = intent.getStringExtra("targetFileName")
                            ?.trim()
                            .orEmpty()
                            .ifBlank { "alpha.wav" },
                        isolatedFixtureConfirmed =
                            intent.getBooleanExtra("isolatedFixtureConfirmed", false),
                        publishAuthority =
                            intent.getBooleanExtra("publishAuthority", false),
                        useOrdinaryScheduler =
                            intent.getBooleanExtra("useOrdinaryScheduler", false),
                    )
                } catch (error: Throwable) {
                    Log.e(VENDOR_TAG, "external-payload-gate-failed uri=" + treeUri, error)
                } finally {
                    externalPayloadGateRunning.set(false)
                    pending.finish()
                }
            }.start()
            return
        }
        if (intent.action == profileAction) {
            val repeatCount = intent.getIntExtra("repeat", 3).coerceIn(1, 20)
            if (intent.getBooleanExtra("background", false)) {
                val pending = goAsync()
                Thread {
                    try {
                        runMetadataProfile(appContext, repeatCount)
                    } catch (error: Throwable) {
                        Log.e(TAG, "metadata-profile-failed", error)
                    } finally {
                        pending.finish()
                    }
                }.start()
            } else {
                try {
                    runMetadataProfile(appContext, repeatCount)
                } catch (error: Throwable) {
                    Log.e(TAG, "metadata-profile-failed", error)
                }
            }
            return
        }
        if (intent.action != scenarioAction) return

        val scenarioName = intent.getStringExtra("scenario")
            ?.trim()
            ?.uppercase()
            .orEmpty()
            .ifBlank { TestDocumentsProvider.FixtureScenario.CHANGED.name }

        var library: MusicLibrary? = null
        try {
                val scenario = TestDocumentsProvider.FixtureScenario.valueOf(scenarioName)
                val authority = TestDocumentsProvider.authorityForPackage(appContext.packageName)
                val treeUri = DocumentsContract.buildTreeDocumentUri(
                    authority,
                    TestDocumentsProvider.ROOT_ID,
                )
                val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    TestDocumentsProvider.ROOT_ID,
                )
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

                appContext.grantUriPermission(appContext.packageName, treeUri, flags)
                appContext.grantUriPermission(appContext.packageName, rootDocumentUri, flags)

                TestDocumentsProvider.resetScenario()
                library = MusicLibrary(appContext)
                library.setLibraryFolder(treeUri)

                Log.i(TAG, "baseline-start scenario=" + scenario + " tree=" + treeUri)
                runBlocking {
                    library.scanLibraryFolder()
                }
                Log.i(
                    TAG,
                    "baseline-complete scenario=" + scenario +
                        " songs=" + library.songs.size +
                        " source=" + library.lastScanSource +
                        " error=" + library.lastScanError,
                )

                TestDocumentsProvider.setScenario(scenario.name)
                Log.i(TAG, "shadow-start scenario=" + scenario)
                runBlocking {
                    library.runAutoSyncShadowForDiagnostics(
                        cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                    )
                }
                Log.i(
                    TAG,
                    "shadow-complete scenario=" + scenario +
                        " songs=" + library.songs.size +
                        " error=" + library.lastScanError,
                )

                Log.i(TAG, "oracle-start scenario=" + scenario)
                runBlocking {
                    library.scanLibraryFolder()
                }
                Log.i(
                    TAG,
                    "oracle-complete scenario=" + scenario +
                        " songs=" + library.songs.size +
                        " source=" + library.lastScanSource +
                        " error=" + library.lastScanError,
                )
        } catch (error: Throwable) {
            Log.e(TAG, "gate-failed scenario=" + scenarioName, error)
        } finally {
            library?.release()
        }
    }

    internal fun runDeviceAuthorityGate(appContext: Context) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "DEVICE real-authority Gate requires Android 11+ MediaStore semantics"
        }
        val target = ensureDeviceGateTarget(appContext)
        require(
            target.row.ownerPackageName == null ||
                target.row.ownerPackageName == appContext.packageName,
        ) {
            "Refusing to mutate DEVICE Gate row owned by ${target.row.ownerPackageName}"
        }

        val originalBackup = if (!target.created && target.row.sizeBytes > 0L) {
            File.createTempFile("mica-s3-device-authority-", ".bak", appContext.cacheDir).also {
                copyUriToFile(appContext, target.uri, it)
            }
        } else {
            null
        }
        val originalDigest = originalBackup?.let(::sha256)
        var baselineDigest: String? = null
        var baselineSong: Song? = null
        var library: MusicLibrary? = null
        var foreground = false
        var restoreRequired = false
        var restoreSucceeded = false

        try {
            TestDocumentsProvider.resetScenario()
            val baselineFixture = deviceGateFixtureAudioUri(appContext)
            writeDeviceGatePayload(appContext, target.uri, baselineFixture)
            restoreRequired = originalBackup != null
            val baselineRow = waitForDeviceGateRow(appContext, target.uri) {
                !it.isPending && it.sizeBytes > 0L
            }
            baselineDigest = sha256(appContext, target.uri)

            library = MusicLibrary(appContext)
            val fullStartedMs = SystemClock.elapsedRealtime()
            runBlocking { library.scanDeviceWide() }
            require(library.lastScanSource == ScanSource.DEVICE && library.lastScanError == null) {
                "DEVICE Gate baseline Full failed: source=${library.lastScanSource} " +
                    "error=${library.lastScanError}"
            }
            val stableKey = "ms_${ContentUris.parseId(target.uri)}"
            baselineSong = library.songs.singleOrNull { it.id == stableKey }
                ?: error("DEVICE Gate baseline row not present in Full catalog: $stableKey")
            require(baselineSong.sizeBytes == baselineRow.sizeBytes && baselineSong.durationSec > 0) {
                "DEVICE Gate baseline payload did not resolve canonically: " +
                    "rowSize=${baselineRow.sizeBytes} songSize=${baselineSong.sizeBytes} " +
                    "durationSec=${baselineSong.durationSec}"
            }
            Log.i(
                DEVICE_TAG,
                "device-authority-baseline-complete stableKey=$stableKey " +
                    "created=${target.created} songs=${library.songs.size} " +
                    "durationSec=${baselineSong.durationSec} size=${baselineSong.sizeBytes} " +
                    "sha256=$baselineDigest fullMs=" +
                    (SystemClock.elapsedRealtime() - fullStartedMs),
            )

            library.onForegroundChanged(true)
            foreground = true

            TestDocumentsProvider.setScenario(TestDocumentsProvider.FixtureScenario.CHANGED.name)
            val changedFixture = deviceGateFixtureAudioUri(appContext)
            val mutationStartedMs = SystemClock.elapsedRealtime()
            writeDeviceGatePayload(appContext, target.uri, changedFixture)
            restoreRequired = true
            TestDocumentsProvider.resetScenario()

            val changedRow = waitForDeviceGateRow(appContext, target.uri) {
                !it.isPending && it.sizeBytes > 0L && it.sizeBytes != baselineRow.sizeBytes
            }
            val changedDigest = sha256(appContext, target.uri)
            require(changedDigest != baselineDigest) {
                "DEVICE Gate mutation did not change payload digest"
            }
            require(
                waitUntil(DEVICE_SCHEDULER_GATE_TIMEOUT_MS) {
                    library.songs.singleOrNull { it.id == stableKey }?.let { current ->
                        current.sizeBytes == changedRow.sizeBytes &&
                            current.durationSec != baselineSong.durationSec
                    } == true
                },
            ) {
                "Ordinary DEVICE scheduler did not publish changed payload before timeout"
            }

            val autoTarget = library.songs.single { it.id == stableKey }
            val changeSet = library.lastLibraryChangeSet
            Log.i(
                DEVICE_TAG,
                "device-authority-scheduler-complete stableKey=$stableKey " +
                    "elapsedMs=" + (SystemClock.elapsedRealtime() - mutationStartedMs) +
                    " duration=${baselineSong.durationSec}->${autoTarget.durationSec} " +
                    " size=${baselineSong.sizeBytes}->${autoTarget.sizeBytes} " +
                    " revision=${changeSet?.libraryRevision} cause=${changeSet?.cause} " +
                    "sha256=$changedDigest",
            )

            val reloaded = MusicLibrary(appContext)
            try {
                runBlocking { reloaded.loadCachedLibrary() }
                val cachedTarget = reloaded.songs.singleOrNull { it.id == stableKey }
                    ?: error("DEVICE real AUTO row missing after cold cache reload")
                require(
                    cachedTarget.durationSec == autoTarget.durationSec &&
                        cachedTarget.sizeBytes == autoTarget.sizeBytes
                ) {
                    "DEVICE real AUTO memory/cache authority diverged"
                }
                Log.i(
                    DEVICE_TAG,
                    "device-authority-cache-reload-complete songs=${reloaded.songs.size} " +
                        "durationSec=${cachedTarget.durationSec} size=${cachedTarget.sizeBytes}",
                )
            } finally {
                reloaded.release()
            }

            val oracleStartedMs = SystemClock.elapsedRealtime()
            runBlocking { library.scanDeviceWide() }
            require(library.lastScanError == null && library.lastScanSource == ScanSource.DEVICE) {
                "DEVICE Full oracle failed: error=${library.lastScanError}"
            }
            val oracleTarget = library.songs.single { it.id == stableKey }
            require(
                oracleTarget.durationSec == autoTarget.durationSec &&
                    oracleTarget.sizeBytes == autoTarget.sizeBytes &&
                    oracleTarget.dateModifiedMs == autoTarget.dateModifiedMs &&
                    oracleTarget.title == autoTarget.title
            ) {
                "DEVICE AUTO target diverged from Full oracle"
            }
            Log.i(
                DEVICE_TAG,
                "device-authority-oracle-complete songs=${library.songs.size} " +
                    "durationSec=${oracleTarget.durationSec} size=${oracleTarget.sizeBytes} " +
                    "fullMs=" + (SystemClock.elapsedRealtime() - oracleStartedMs),
            )
        } finally {
            TestDocumentsProvider.resetScenario()
            if (restoreRequired) {
                try {
                    val expectedDigest = if (originalBackup != null) {
                        setDeviceGatePending(appContext, target.uri, true)
                        copyFileToUri(appContext, originalBackup, target.uri)
                        setDeviceGatePending(appContext, target.uri, false)
                        originalDigest
                    } else {
                        val baselineFixture = deviceGateFixtureAudioUri(appContext)
                        writeDeviceGatePayload(appContext, target.uri, baselineFixture)
                        baselineDigest
                    }
                    val restoredRow = waitForDeviceGateRow(appContext, target.uri) {
                        !it.isPending && it.sizeBytes > 0L
                    }
                    if (expectedDigest != null) {
                        check(sha256(appContext, target.uri) == expectedDigest) {
                            "Restored DEVICE fixture digest does not match expected content"
                        }
                    }
                    val activeLibrary = library
                    val initialBaselineSong = baselineSong
                    if (activeLibrary != null && foreground) {
                        check(
                            waitUntil(DEVICE_SCHEDULER_GATE_TIMEOUT_MS) {
                                activeLibrary.songs.singleOrNull {
                                    it.id == "ms_${ContentUris.parseId(target.uri)}"
                                }?.let { current ->
                                    current.sizeBytes == restoredRow.sizeBytes &&
                                        (
                                            originalBackup != null ||
                                                initialBaselineSong == null ||
                                                current.durationSec == initialBaselineSong.durationSec
                                            )
                                } == true
                            },
                        ) {
                            "DEVICE scheduler did not restore catalog authority after fixture restore"
                        }
                    }
                    restoreSucceeded = true
                    Log.i(
                        DEVICE_TAG,
                        "device-authority-fixture-restored created=${target.created} " +
                            "size=${restoredRow.sizeBytes} sha256=$expectedDigest",
                    )
                } catch (restoreError: Throwable) {
                    Log.e(
                        DEVICE_TAG,
                        "device-authority-restore-failed backup=${originalBackup?.absolutePath}",
                        restoreError,
                    )
                }
            }
            if (foreground) {
                library?.onForegroundChanged(false)
            }
            library?.release()
            if (originalBackup != null && (!restoreRequired || restoreSucceeded)) {
                recycleQaBackup(appContext, originalBackup, DEVICE_TAG)
            }
        }
    }

    private data class DeviceGateTarget(
        val uri: Uri,
        val created: Boolean,
        val row: DeviceGateMediaRow,
    )

    private data class DeviceGateMediaRow(
        val uri: Uri,
        val sizeBytes: Long,
        val dateModifiedMs: Long,
        val isPending: Boolean,
        val ownerPackageName: String?,
    )

    private fun ensureDeviceGateTarget(context: Context): DeviceGateTarget {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_MUSIC + "/MicaDeviceAutoSyncGate/"
        val displayName = "device-authority.wav"
        val rows = mutableListOf<DeviceGateMediaRow>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.IS_PENDING,
                MediaStore.Audio.Media.OWNER_PACKAGE_NAME,
            ),
            "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)
            val ownerCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                rows += DeviceGateMediaRow(
                    uri = ContentUris.withAppendedId(collection, id),
                    sizeBytes = cursor.getLong(sizeCol),
                    dateModifiedMs = cursor.getLong(modifiedCol) * 1000L,
                    isPending = cursor.getInt(pendingCol) != 0,
                    ownerPackageName = cursor.getString(ownerCol),
                )
            }
        }
        require(rows.size <= 1) {
            "DEVICE Gate path is not isolated; duplicate rows=${rows.size}"
        }
        rows.singleOrNull()?.let { return DeviceGateTarget(it.uri, false, it) }

        val createdUri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: error("Failed to create isolated DEVICE Gate MediaStore row")
        val row = queryDeviceGateRow(context, createdUri)
        return DeviceGateTarget(createdUri, true, row)
    }

    private fun queryDeviceGateRow(context: Context, uri: Uri): DeviceGateMediaRow {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.IS_PENDING,
                MediaStore.Audio.Media.OWNER_PACKAGE_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "DEVICE Gate MediaStore row disappeared: $uri" }
            return DeviceGateMediaRow(
                uri = uri,
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)),
                dateModifiedMs =
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)) *
                        1000L,
                isPending =
                    cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)) != 0,
                ownerPackageName =
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.OWNER_PACKAGE_NAME),
                    ),
            )
        }
        error("Cannot query DEVICE Gate MediaStore row: $uri")
    }

    private fun waitForDeviceGateRow(
        context: Context,
        uri: Uri,
        predicate: (DeviceGateMediaRow) -> Boolean,
    ): DeviceGateMediaRow {
        val deadline = SystemClock.elapsedRealtime() + DEVICE_MEDIASTORE_SETTLE_TIMEOUT_MS
        var latest = queryDeviceGateRow(context, uri)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(latest)) return latest
            Thread.sleep(50L)
            latest = queryDeviceGateRow(context, uri)
        }
        check(predicate(latest)) {
            "DEVICE Gate MediaStore row did not settle: $latest"
        }
        return latest
    }

    private fun setDeviceGatePending(
        context: Context,
        uri: Uri,
        pending: Boolean,
    ) {
        val updated = context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, if (pending) 1 else 0)
            },
            null,
            null,
        )
        check(updated == 1) {
            "Failed to set DEVICE Gate pending=$pending for $uri; updated=$updated"
        }
    }

    private fun writeDeviceGatePayload(
        context: Context,
        target: Uri,
        source: Uri,
    ) {
        setDeviceGatePending(context, target, true)
        copyUriToUri(context, source, target)
        setDeviceGatePending(context, target, false)
    }

    private fun deviceGateFixtureAudioUri(context: Context): Uri {
        val authority = TestDocumentsProvider.authorityForPackage(context.packageName)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
            TestDocumentsProvider.ROOT_ID,
        )
        context.grantUriPermission(
            context.packageName,
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "root/music/contract.wav",
        )
    }

    private fun recycleQaBackup(
        context: Context,
        backup: File,
        tag: String,
    ) {
        val recycleDir = File(context.cacheDir, ".MicaRecycle").apply { mkdirs() }
        val recycledBackup = File(
            recycleDir,
            "${SystemClock.elapsedRealtime()}-${backup.name}",
        )
        if (backup.renameTo(recycledBackup)) {
            Log.i(tag, "qa-backup-recycled path=${recycledBackup.absolutePath}")
        } else {
            Log.w(tag, "qa-backup-recycle-failed; retained=${backup.absolutePath}")
        }
    }

    private fun runMetadataProfile(
        appContext: Context,
        repeatCount: Int,
    ) {
        val authority = TestDocumentsProvider.authorityForPackage(appContext.packageName)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
            TestDocumentsProvider.ROOT_ID,
        )
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        appContext.grantUriPermission(appContext.packageName, treeUri, flags)
        appContext.grantUriPermission(appContext.packageName, rootDocumentUri, flags)
        TestDocumentsProvider.setScenario(
            TestDocumentsProvider.FixtureScenario.TEN_K_METADATA.name,
        )
        val scanner = AndroidLibraryScanner(appContext)
        repeat(repeatCount) { index ->
            val memoryBefore = processMemorySnapshot()
            val startedMs = SystemClock.elapsedRealtime()
            val snapshot = runBlocking {
                scanner.observeFolderMetadata(treeUri)
            }
            val memoryAfter = processMemorySnapshot()
            Log.i(
                TAG,
                "metadata-profile iteration=" + (index + 1) +
                    " entries=" + snapshot.entries.size +
                    " completeness=" + snapshot.discoveryReport.aggregate +
                    " observedWallMs=" + snapshot.observationStats.wallTimeMs +
                    " externalWallMs=" + (SystemClock.elapsedRealtime() - startedMs) +
                    " queries=" + snapshot.observationStats.providerQueryCount +
                    " directQueries=" + snapshot.observationStats.directQueryCount +
                    " fallbackListings=" + snapshot.observationStats.fallbackListingCount +
                    " pssBeforeKb=" + memoryBefore.pssKb +
                    " pssAfterKb=" + memoryAfter.pssKb +
                    " rssBeforeKb=" + memoryBefore.rssKb +
                    " rssAfterKb=" + memoryAfter.rssKb +
                    " rssHwmKb=" + memoryAfter.rssHwmKb +
                    " javaHeapBeforeKb=" + memoryBefore.javaHeapUsedKb +
                    " javaHeapAfterKb=" + memoryAfter.javaHeapUsedKb +
                    " nativeHeapAfterKb=" + memoryAfter.nativeHeapAllocatedKb,
            )
        }
    }

    private fun runExternalProviderPayloadGate(
        appContext: Context,
        treeUri: android.net.Uri,
        targetFileName: String,
        isolatedFixtureConfirmed: Boolean,
        publishAuthority: Boolean,
        useOrdinaryScheduler: Boolean,
    ) {
        require(isolatedFixtureConfirmed) {
            "External payload Gate may only mutate an explicitly confirmed isolated fixture"
        }
        require(treeUri.authority != TestDocumentsProvider.authorityForPackage(appContext.packageName)) {
            "External payload Gate requires a third-party provider"
        }
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        require(treeDocumentId.endsWith("/MicaSafVendorGate/Music")) {
            "Refusing to mutate an unrecognised isolated music tree: $treeDocumentId"
        }
        val requiredPermission = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        require(
            appContext.checkUriPermission(
                treeUri,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                requiredPermission,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        ) {
            "Persisted read/write grant is required for $treeUri"
        }

        val scanner = AndroidLibraryScanner(appContext)
        val initialSnapshot = runBlocking { scanner.observeFolderMetadata(treeUri) }
        require(initialSnapshot.discoveryReport.aggregate.name == "COMPLETE") {
            "Initial external provider inventory is not COMPLETE: " +
                initialSnapshot.discoveryReport.partitions.values.joinToString("|") { status ->
                    status.partitionKey + ":" + status.completeness + ":" + status.detail
                }
        }
        val targetEntry = initialSnapshot.entries.singleOrNull { it.fileName == targetFileName }
            ?: error(
                "Expected exactly one $targetFileName entry; observed=" +
                    initialSnapshot.entries.joinToString { it.fileName },
            )
        val expectedSongCount = initialSnapshot.entries.size
        require(expectedSongCount > 0) { "External fixture contains no audio entries" }
        val targetUri = android.net.Uri.parse(targetEntry.mediaUri)
        val backup = File.createTempFile("mica-s4-external-payload-", ".bak", appContext.cacheDir)
        copyUriToFile(appContext, targetUri, backup)
        val originalDigest = sha256(backup)
        var restoreRequired = false
        var restoreSucceeded = false
        val library = MusicLibrary(appContext)
        try {
            library.setLibraryFolder(treeUri)
            runBlocking { library.scanLibraryFolder() }
            require(library.songs.size == expectedSongCount && library.lastScanError == null) {
                "External provider baseline Full failed: songs=${library.songs.size} " +
                    "error=${library.lastScanError}"
            }
            val baselineTarget = library.songs.single { it.id == targetEntry.stableObjectKey }
            Log.i(
                VENDOR_TAG,
                "external-payload-baseline-complete tree=" + treeUri +
                    " target=" + targetEntry.stableObjectKey +
                    " size=" + backup.length() +
                    " sha256=" + originalDigest,
            )

            val fixtureAuthority = TestDocumentsProvider.authorityForPackage(appContext.packageName)
            val fixtureTreeUri = DocumentsContract.buildTreeDocumentUri(
                fixtureAuthority,
                TestDocumentsProvider.ROOT_ID,
            )
            val fixtureAudioUri = DocumentsContract.buildDocumentUriUsingTree(
                fixtureTreeUri,
                "root/music/contract.wav",
            )
            appContext.grantUriPermission(
                appContext.packageName,
                fixtureTreeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            TestDocumentsProvider.setScenario(TestDocumentsProvider.FixtureScenario.CHANGED.name)
            restoreRequired = true
            copyUriToUri(appContext, fixtureAudioUri, targetUri)

            val changedSnapshot = runBlocking { scanner.observeFolderMetadata(treeUri) }
            val changedEntry = changedSnapshot.entries.singleOrNull {
                it.stableObjectKey == targetEntry.stableObjectKey
            } ?: error("Mutated external object disappeared from the COMPLETE inventory")
            require(changedSnapshot.discoveryReport.aggregate.name == "COMPLETE") {
                "Mutated external provider inventory is not COMPLETE"
            }
            require(
                changedEntry.sizeBytes != targetEntry.sizeBytes ||
                    changedEntry.lastModifiedMs != targetEntry.lastModifiedMs,
            ) {
                "Third-party provider did not expose a changed size/mtime fingerprint"
            }
            Log.i(
                VENDOR_TAG,
                "external-payload-mutation-visible beforeSize=${targetEntry.sizeBytes} " +
                    "afterSize=${changedEntry.sizeBytes} beforeMtime=${targetEntry.lastModifiedMs} " +
                    "afterMtime=${changedEntry.lastModifiedMs}",
            )

            if (useOrdinaryScheduler) {
                require(publishAuthority) {
                    "Ordinary scheduler Gate is only valid for real authority publication"
                }
                val schedulerStartedMs = SystemClock.elapsedRealtime()
                library.onForegroundChanged(true)
                try {
                    require(
                        waitUntil(SCHEDULER_GATE_TIMEOUT_MS) {
                            library.songs.singleOrNull { it.id == targetEntry.stableObjectKey }
                                ?.let { current ->
                                    current.sizeBytes == changedEntry.sizeBytes &&
                                        current.dateModifiedMs == changedEntry.lastModifiedMs &&
                                        current.durationSec != baselineTarget.durationSec
                                } == true
                        },
                    ) {
                        "Ordinary FOLDER scheduler did not publish changed payload before timeout"
                    }
                } finally {
                    library.onForegroundChanged(false)
                }
                Log.i(
                    VENDOR_TAG,
                    "external-payload-scheduler-auto-complete elapsedMs=" +
                        (SystemClock.elapsedRealtime() - schedulerStartedMs),
                )
            } else {
                runBlocking {
                    library.runAutoSyncShadowForDiagnostics(
                        cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                        publishSafAuthority = publishAuthority,
                    )
                }
            }
            val autoTarget = library.songs.single { it.id == targetEntry.stableObjectKey }
            if (publishAuthority) {
                require(
                    autoTarget.sizeBytes == changedEntry.sizeBytes &&
                        autoTarget.dateModifiedMs == changedEntry.lastModifiedMs &&
                        autoTarget.durationSec != baselineTarget.durationSec
                ) {
                    "Real SAF AUTO did not adopt changed payload metadata: " +
                        "beforeDuration=${baselineTarget.durationSec} " +
                        "afterDuration=${autoTarget.durationSec} " +
                        "expectedSize=${changedEntry.sizeBytes} actualSize=${autoTarget.sizeBytes} " +
                        "expectedMtime=${changedEntry.lastModifiedMs} " +
                        "actualMtime=${autoTarget.dateModifiedMs}"
                }
                val reloaded = MusicLibrary(appContext)
                try {
                    runBlocking { reloaded.loadCachedLibrary() }
                    val cachedTarget = reloaded.songs.single {
                        it.id == targetEntry.stableObjectKey
                    }
                    require(cachedTarget.durationSec == autoTarget.durationSec) {
                        "Restart/cache authority diverged from real SAF AUTO"
                    }
                    Log.i(
                        VENDOR_TAG,
                        "external-payload-auto-cache-reload-complete songs=" +
                            reloaded.songs.size + " targetDurationSec=" + cachedTarget.durationSec,
                    )
                } finally {
                    reloaded.release()
                }
            }
            Log.i(
                VENDOR_TAG,
                "external-payload-${if (publishAuthority) "auto" else "shadow"}-complete " +
                    "songs=" + library.songs.size +
                    " targetDurationSec=" + autoTarget.durationSec,
            )

            runBlocking { library.scanLibraryFolder() }
            require(library.songs.size == expectedSongCount && library.lastScanError == null) {
                "External provider Full oracle failed: songs=${library.songs.size} " +
                    "error=${library.lastScanError}"
            }
            Log.i(
                VENDOR_TAG,
                "external-payload-oracle-complete songs=" + library.songs.size +
                    " targetDurationSec=" +
                    library.songs.single { it.id == targetEntry.stableObjectKey }.durationSec,
            )
            val oracleTarget = library.songs.single { it.id == targetEntry.stableObjectKey }
            require(
                oracleTarget.durationSec == autoTarget.durationSec &&
                    oracleTarget.sizeBytes == autoTarget.sizeBytes &&
                    oracleTarget.dateModifiedMs == autoTarget.dateModifiedMs &&
                    oracleTarget.title == autoTarget.title,
            ) {
                "SAF AUTO target diverged from Full oracle"
            }
        } finally {
            TestDocumentsProvider.resetScenario()
            if (restoreRequired) {
                try {
                    copyFileToUri(appContext, backup, targetUri)
                    check(sha256(appContext, targetUri) == originalDigest) {
                        "Restored external fixture digest does not match its backup"
                    }
                    restoreSucceeded = true
                    runBlocking { library.scanLibraryFolder() }
                    Log.i(VENDOR_TAG, "external-payload-fixture-restored sha256=" + originalDigest)
                } catch (restoreError: Throwable) {
                    Log.e(
                        VENDOR_TAG,
                        "external-payload-restore-failed backup=" + backup.absolutePath,
                        restoreError,
                    )
                }
            }
            library.release()
            if (!restoreRequired || restoreSucceeded) {
                val recycleDir = File(appContext.cacheDir, ".MicaRecycle").apply { mkdirs() }
                val recycledBackup = File(
                    recycleDir,
                    "${SystemClock.elapsedRealtime()}-${backup.name}",
                )
                if (backup.renameTo(recycledBackup)) {
                    Log.i(
                        VENDOR_TAG,
                        "external-payload-backup-recycled path=" + recycledBackup.absolutePath,
                    )
                } else {
                    Log.w(
                        VENDOR_TAG,
                        "external-payload-backup-recycle-failed; retained=" + backup.absolutePath,
                    )
                }
            }
        }
    }

    private fun copyUriToFile(
        context: Context,
        source: android.net.Uri,
        target: File,
    ) {
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot open source URI: $source" }
            FileOutputStream(target).use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
    }

    private fun copyUriToUri(
        context: Context,
        source: android.net.Uri,
        target: android.net.Uri,
    ) {
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot open source URI: $source" }
            context.contentResolver.openOutputStream(target, "rwt").use { output ->
                requireNotNull(output) { "Cannot open target URI for replacement: $target" }
                input.copyTo(output, COPY_BUFFER_BYTES)
            }
        }
    }

    private fun copyFileToUri(
        context: Context,
        source: File,
        target: android.net.Uri,
    ) {
        FileInputStream(source).use { input ->
            context.contentResolver.openOutputStream(target, "rwt").use { output ->
                requireNotNull(output) { "Cannot open target URI for restore: $target" }
                input.copyTo(output, COPY_BUFFER_BYTES)
            }
        }
    }

    private fun sha256(file: File): String =
        FileInputStream(file).use { input -> sha256(input) }

    private fun sha256(
        context: Context,
        uri: android.net.Uri,
    ): String = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Cannot reopen URI for digest: $uri" }
        sha256(input)
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun runPreparePlaybackBaseline(appContext: Context) {
        val authority = TestDocumentsProvider.authorityForPackage(appContext.packageName)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
            TestDocumentsProvider.ROOT_ID,
        )
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        appContext.grantUriPermission(appContext.packageName, treeUri, flags)
        appContext.grantUriPermission(appContext.packageName, rootDocumentUri, flags)
        TestDocumentsProvider.resetScenario()

        val library = MusicLibrary(appContext)
        try {
            library.setLibraryFolder(treeUri)
            val startedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.scanLibraryFolder()
            }
            require(library.songs.isNotEmpty()) {
                "Baseline SAF scan produced no songs"
            }
            val firstSong = library.songs.first()
            val app = appContext as MicaApp
            runOnMainBlocking {
                app.playerController.playQueueSong(
                    newQueue = library.songs,
                    songId = firstSong.id,
                )
            }
            require(
                waitUntil(10_000L) {
                    playbackIoSnapshot(app).hasActivePlaybackInstance
                },
            ) {
                "QA playback did not become active"
            }
            val snapshot = playbackIoSnapshot(app)
            Log.i(
                TAG,
                "playback-prepare-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - startedMs) +
                    " songs=" + library.songs.size +
                    " current=" + snapshot.currentStableObjectKey +
                    " active=" + snapshot.hasActivePlaybackInstance,
            )
        } finally {
            library.release()
        }
    }

    private fun runPlaybackDeferGate(
        appContext: Context,
        scenario: TestDocumentsProvider.FixtureScenario,
    ) {
        val app = appContext as MicaApp
        val player = app.playerController
        val library = MusicLibrary(appContext)
        try {
            val authority = TestDocumentsProvider.authorityForPackage(appContext.packageName)
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
            appContext.grantUriPermission(appContext.packageName, treeUri, grantFlags)
            appContext.grantUriPermission(appContext.packageName, rootDocumentUri, grantFlags)

            TestDocumentsProvider.resetScenario()
            library.setLibraryFolder(treeUri)
            runBlocking {
                library.scanLibraryFolder()
            }
            require(library.songs.isNotEmpty()) {
                "QA baseline FOLDER Full produced no songs"
            }

            runOnMainBlocking {
                player.connectIfNeeded()
            }
            require(waitUntil(5_000L) { player.isConnected }) {
                "QA MediaController did not connect"
            }
            if (!playbackIoSnapshot(app).hasActivePlaybackInstance) {
                val target = library.songs.first()
                runOnMainBlocking {
                    player.playSingleSong(target)
                }
            }
            require(
                waitUntil(5_000L) {
                    playbackIoSnapshot(app).hasActivePlaybackInstance
                },
            ) {
                "Playback did not become active for the S4 playback gate"
            }

            library.setPlaybackIoSnapshotProvider {
                playbackIoSnapshot(app)
            }

            val activeBefore = playbackIoSnapshot(app)
            Log.i(
                TAG,
                "playback-gate baseline-loaded songs=" + library.songs.size +
                    " current=" + activeBefore.currentStableObjectKey +
                    " active=" + activeBefore.hasActivePlaybackInstance,
            )

            TestDocumentsProvider.setScenario(scenario.name)
            Log.i(TAG, "playback-gate mutation scenario=" + scenario)
            val deferStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
                )
            }
            Log.i(
                TAG,
                "playback-gate defer-shadow-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - deferStartedMs) +
                    " active=" + playbackIoSnapshot(app).hasActivePlaybackInstance,
            )

            runOnMainBlocking {
                player.setQueue(emptyList())
            }
            require(
                waitUntil(5_000L) {
                    !playbackIoSnapshot(app).hasActivePlaybackInstance
                },
            ) {
                "Playback instance did not release after clearing QA queue"
            }

            val releaseStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.runAutoSyncShadowForDiagnostics(
                    cause = LibraryOperationCause.PLAYBACK_IO_RELEASE,
                )
            }
            Log.i(
                TAG,
                "playback-gate release-shadow-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - releaseStartedMs) +
                    " active=" + playbackIoSnapshot(app).hasActivePlaybackInstance,
            )

            val oracleStartedMs = SystemClock.elapsedRealtime()
            runBlocking {
                library.scanLibraryFolder()
            }
            Log.i(
                TAG,
                "playback-gate oracle-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - oracleStartedMs) +
                    " songs=" + library.songs.size +
                    " error=" + library.lastScanError,
            )
        } finally {
            library.release()
        }
    }

    private fun playbackIoSnapshot(app: MicaApp): LibraryPlaybackIoSnapshot {
        val surface = app.playerController.playbackSurfaceState
        val current = surface.currentSong
        val activeInstance = current != null && when (surface.playbackStatus.execution) {
            PlaybackExecutionState.PAUSED,
            PlaybackExecutionState.PREPARING,
            PlaybackExecutionState.BUFFERING,
            PlaybackExecutionState.PLAYING,
            PlaybackExecutionState.SUPPRESSED,
            -> true

            PlaybackExecutionState.UNAVAILABLE,
            PlaybackExecutionState.IDLE,
            PlaybackExecutionState.ENDED,
            PlaybackExecutionState.ERROR,
            -> false
        }
        return LibraryPlaybackIoSnapshot(
            currentStableObjectKey = current?.id,
            currentMediaUri = current?.mediaUri,
            hasActivePlaybackInstance = activeInstance,
        )
    }

    private fun runOnMainBlocking(
        timeoutMs: Long = 5_000L,
        action: () -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                action()
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for QA playback command on main thread"
        }
        error.get()?.let { throw it }
    }

    private data class ProcessMemorySnapshot(
        val pssKb: Long,
        val rssKb: Long,
        val rssHwmKb: Long,
        val javaHeapUsedKb: Long,
        val nativeHeapAllocatedKb: Long,
    )

    private fun processMemorySnapshot(): ProcessMemorySnapshot {
        val runtime = Runtime.getRuntime()
        val status = runCatching {
            File("/proc/self/status").readLines()
        }.getOrDefault(emptyList())
        fun statusKb(name: String): Long = status.firstOrNull { it.startsWith(name) }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?: 0L
        return ProcessMemorySnapshot(
            pssKb = Debug.getPss(),
            rssKb = statusKb("VmRSS"),
            rssHwmKb = statusKb("VmHWM"),
            javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / 1024L,
        )
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50L)
        }
        return condition()
    }

    private companion object {
        val externalPayloadGateRunning = AtomicBoolean(false)
        const val DEVICE_TAG = "MICA_S3_DEVICE_GATE"
        const val TAG = "MICA_S4_QA"
        const val VENDOR_TAG = "MICA_S4_VENDOR"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DEVICE_MEDIASTORE_SETTLE_TIMEOUT_MS = 15_000L
        const val DEVICE_SCHEDULER_GATE_TIMEOUT_MS = 60_000L
        const val SCHEDULER_GATE_TIMEOUT_MS = 15_000L
    }
}
