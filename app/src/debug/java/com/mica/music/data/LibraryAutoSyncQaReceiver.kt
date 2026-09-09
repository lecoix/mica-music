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
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.data.scanner.AndroidDeviceMediaStoreGenerationApi
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
        val artworkAutoGateAction =
            appContext.packageName + ".debug.LIBRARY_S4_AUTO_ARTWORK_GATE"
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
        if (intent.action == artworkAutoGateAction) {
            val treeUri = intent.data
            if (treeUri == null) {
                Log.e(VENDOR_TAG, "auto-artwork-gate-failed missing tree URI")
                return
            }
            if (!autoArtworkGateRunning.compareAndSet(false, true)) {
                Log.i(VENDOR_TAG, "auto-artwork-gate-skip already-running tree=$treeUri")
                return
            }
            val pending = goAsync()
            Thread {
                try {
                    runAutoArtworkGate(appContext, treeUri)
                } catch (error: Throwable) {
                    Log.e(VENDOR_TAG, "auto-artwork-gate-failed tree=$treeUri", error)
                } finally {
                    autoArtworkGateRunning.set(false)
                    pending.finish()
                }
            }.start()
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
                    val persisted = appContext.contentResolver.persistedUriPermissions
                        .filter { held ->
                            held.uri == treeUri ||
                                treeUri.toString().startsWith(held.uri.toString() + "/")
                        }
                        .joinToString(";") { held ->
                            held.uri.toString() +
                                ":read=" + held.isReadPermission +
                                ":write=" + held.isWritePermission
                        }
                    Log.i(
                        VENDOR_TAG,
                        "grant-check uri=" + treeUri +
                            " permission=" + permission +
                            " persisted=" + persisted.ifBlank { "none" },
                    )
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    repeat(2) { attempt ->
                        val client = appContext.contentResolver
                            .acquireUnstableContentProviderClient(treeUri)
                        try {
                            val rows = client?.query(
                                childUri,
                                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                                null,
                                null,
                                null,
                            )?.use { cursor ->
                                var count = 0
                                while (cursor.moveToNext()) count += 1
                                count
                            }
                            Log.i(
                                VENDOR_TAG,
                                "client-probe attempt=" + (attempt + 1) +
                                    " acquired=" + (client != null) +
                                    " rows=" + (rows ?: -1),
                            )
                        } catch (error: Throwable) {
                            Log.e(
                                VENDOR_TAG,
                                "client-probe attempt=" + (attempt + 1) + " failed",
                                error,
                            )
                        } finally {
                            client?.close()
                        }
                        if (attempt == 0) Thread.sleep(150L)
                    }
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

    private fun autoArtworkRootContainsDisplayName(
        appContext: Context,
        treeUri: Uri,
        displayName: String,
    ): Boolean {
        val resolver = appContext.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        return resolver.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            var found = false
            while (cursor.moveToNext() && !found) {
                found = cursor.getString(nameColumn) == displayName
            }
            found
        } ?: false
    }

    private fun trashAutoArtworkMediaRows(
        appContext: Context,
        treeUri: Uri,
        exactDisplayName: String? = null,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val relativeRoot = documentId.substringAfter(':', "")
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.plus("/")
            ?: return 0
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.IS_TRASHED,
        )
        val selection: String
        val args: Array<String>
        if (exactDisplayName != null) {
            selection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.Files.FileColumns.IS_TRASHED} = 0"
            args = arrayOf("$relativeRoot%", exactDisplayName)
        } else {
            selection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
                    "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?) AND " +
                    "${MediaStore.Files.FileColumns.IS_TRASHED} = 0"
            args = arrayOf(
                "$relativeRoot%",
                "mica-auto-artwork-gate-%",
                "recycled-auto-artwork-%",
            )
        }

        val targets = mutableListOf<Pair<Uri, String>>()
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                targets += ContentUris.withAppendedId(collection, id) to
                    cursor.getString(nameColumn).orEmpty()
            }
        }

        var trashed = 0
        targets.forEach { (uri, name) ->
            val updated = runCatching {
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.IS_TRASHED, 1)
                    },
                    null,
                    null,
                )
            }.onFailure { error ->
                Log.e(
                    VENDOR_TAG,
                    "auto-artwork-system-trash-failed name=$name uri=$uri",
                    error,
                )
            }.getOrDefault(0)
            val leftAuthority = if (exactDisplayName != null) {
                !autoArtworkRootContainsDisplayName(appContext, treeUri, exactDisplayName)
            } else {
                false
            }
            if (updated > 0 || leftAuthority) {
                trashed += 1
                Log.i(
                    VENDOR_TAG,
                    "auto-artwork-system-trashed name=$name uri=$uri " +
                        "updateRows=$updated leftAuthority=$leftAuthority",
                )
            }
        }
        Log.i(
            VENDOR_TAG,
            "auto-artwork-system-trash-summary root=$relativeRoot " +
                "exact=${exactDisplayName ?: "all"} rows=${targets.size} trashed=$trashed",
        )
        return trashed
    }

    private fun isAutoArtworkGateArtifact(song: Song): Boolean =
        song.fileName.startsWith("mica-auto-artwork-gate-") ||
            song.fileName.startsWith("recycled-auto-artwork-")

    private fun recycleStaleAutoArtworkGateDocuments(
        appContext: Context,
        treeUri: Uri,
    ) {
        val resolver = appContext.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        var recycleUri: Uri? = null
        val stale = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            childUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val mimeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn).orEmpty()
                val mime = cursor.getString(mimeColumn).orEmpty()
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (
                    name == ".MicaRecycle" &&
                    mime == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    recycleUri = uri
                } else if (
                    name.startsWith("mica-auto-artwork-gate-") ||
                    name.startsWith("recycled-auto-artwork-")
                ) {
                    stale += uri to name
                }
            }
        }
        if (stale.isEmpty()) return

        if (recycleUri == null) {
            recycleUri = DocumentsContract.createDocument(
                resolver,
                rootDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                ".MicaRecycle",
            )
        }
        val destination = requireNotNull(recycleUri) {
            "Could not create AUTO artwork recycle directory"
        }
        stale.forEachIndexed { index, (uri, name) ->
            val safeName = "recycled-auto-artwork-stale-" +
                System.currentTimeMillis() +
                "-" + index +
                ".bak"
            val renamed = DocumentsContract.renameDocument(
                resolver,
                uri,
                safeName,
            ) ?: uri
            val moved = DocumentsContract.moveDocument(
                resolver,
                renamed,
                rootDocUri,
                destination,
            )
            requireNotNull(moved) {
                "Could not recycle stale AUTO artwork Gate document: $name"
            }
            Log.i(
                VENDOR_TAG,
                "auto-artwork-stale-recycled name=$name moved=$moved",
            )
        }
    }

    internal fun runAutoArtworkGate(
        appContext: Context,
        treeUri: Uri,
    ) {
        val library = MusicLibrary(appContext)
        var foreground = false
        var createdUri: Uri? = null
        var createdDisplayName: String? = null
        var createdStableId: String? = null
        try {
            trashAutoArtworkMediaRows(appContext, treeUri)
            runBlocking { library.loadCachedLibrary() }
            require(library.lastScanSource == ScanSource.FOLDER) {
                "AUTO artwork Gate requires cached FOLDER authority; source=${library.lastScanSource}"
            }
            require(library.libraryFolderUri != null) {
                "AUTO artwork Gate requires a persisted library folder"
            }
            require(
                Uri.decode(library.libraryFolderUri.orEmpty()) ==
                    Uri.decode(treeUri.toString()),
            ) {
                "AUTO artwork Gate tree mismatch cached=${library.libraryFolderUri} requested=$treeUri"
            }
            require(library.songs.isNotEmpty()) {
                "AUTO artwork Gate cached library is empty"
            }

            library.onForegroundChanged(true)
            foreground = true
            require(
                waitUntil(20_000L) {
                    library.songs.none(::isAutoArtworkGateArtifact)
                },
            ) {
                "AUTO artwork stale cleanup did not leave the authoritative library; " +
                    "stale=" + library.songs
                        .filter(::isAutoArtworkGateArtifact)
                        .joinToString(",") { it.fileName }
            }
            Thread.sleep(2_500L)

            val source = library.songs.firstOrNull { song ->
                !song.albumArtUri.isNullOrBlank() &&
                    AlbumArtCache.hasReadableCachedArt(appContext, song)
            } ?: error("AUTO artwork Gate needs at least one source song with readable embedded art")
            val baselineCount = library.songs.size
            Log.i(
                VENDOR_TAG,
                "auto-artwork-clean-baseline songs=$baselineCount source=${source.id}",
            )

            val resolver = appContext.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val extension = source.fileName.substringAfterLast('.', "flac")
            val displayName = "mica-auto-artwork-gate-" +
                System.currentTimeMillis() +
                "." + extension
            createdDisplayName = displayName
            val mime = source.metadata.playbackMimeType.ifBlank { "audio/*" }
            val created = DocumentsContract.createDocument(
                resolver,
                rootDocUri,
                mime,
                displayName,
            ) ?: error("Could not create AUTO artwork Gate document")
            createdUri = created
            val startedMs = SystemClock.elapsedRealtime()
            copyUriToUri(appContext, Uri.parse(source.mediaUri), created)
            Log.i(
                VENDOR_TAG,
                "auto-artwork-created tree=$treeUri source=${source.id} " +
                    "target=$created baseline=$baselineCount sourceArt=${source.albumArtUri}",
            )

            var firstSeenArt: String? = null
            var firstSeenAtMs: Long? = null
            require(
                waitUntil(60_000L) {
                    val target = library.songs.firstOrNull { it.mediaUri == created.toString() }
                    if (target != null && firstSeenAtMs == null) {
                        firstSeenAtMs = SystemClock.elapsedRealtime() - startedMs
                        firstSeenArt = target.albumArtUri
                        Log.i(
                            VENDOR_TAG,
                            "auto-artwork-first-visible elapsedMs=$firstSeenAtMs " +
                                "id=${target.id} art=${target.albumArtUri ?: "none"} " +
                                "cover=0x${target.coverColorArgb.toUInt().toString(16)}",
                        )
                    }
                    target != null &&
                        !target.albumArtUri.isNullOrBlank() &&
                        AlbumArtCache.hasReadableCachedArt(appContext, target)
                },
            ) {
                val current = library.songs.firstOrNull { it.mediaUri == created.toString() }
                "AUTO artwork was not materialized within timeout; " +
                    "target=${current?.id} art=${current?.albumArtUri} " +
                    "songs=${library.songs.size}"
            }

            val hydrated = requireNotNull(
                library.songs.firstOrNull { it.mediaUri == created.toString() },
            )
            createdStableId = hydrated.id
            require(library.songs.size == baselineCount + 1) {
                "AUTO artwork Gate count mismatch: $baselineCount -> ${library.songs.size}"
            }

            val cold = requireNotNull(
                runBlocking { LibraryRepository(appContext).loadCached() }
                    ?.songs
                    ?.firstOrNull { it.id == hydrated.id },
            ) {
                "Cold cache lost AUTO artwork Gate song"
            }
            require(!cold.albumArtUri.isNullOrBlank()) {
                "Cold cache lost hydrated albumArtUri"
            }
            require(AlbumArtCache.hasReadableCachedArt(appContext, cold)) {
                "Cold cache points at unreadable hydrated artwork: ${cold.albumArtUri}"
            }

            Log.i(
                VENDOR_TAG,
                "auto-artwork-gate-complete elapsedMs=" +
                    (SystemClock.elapsedRealtime() - startedMs) +
                    " firstSeenMs=${firstSeenAtMs ?: -1L} " +
                    "firstSeenArt=${firstSeenArt ?: "none"} " +
                    "finalId=${hydrated.id} finalArt=${hydrated.albumArtUri} " +
                    "readable=${AlbumArtCache.hasReadableCachedArt(appContext, hydrated)} " +
                    "cover=0x${hydrated.coverColorArgb.toUInt().toString(16)}",
            )
            // Drain creation/media-scan/artwork signals before exercising Trash. Otherwise a
            // pre-existing dirty request can accidentally remove the row and mask whether the
            // generation accelerator itself observed the missed IS_TRASHED callback.
            Thread.sleep(8_000L)
            Log.i(VENDOR_TAG, "auto-artwork-pre-trash-quiesced")
        } finally {
            try {
                createdDisplayName?.let { displayName ->
                    val generationApi = AndroidDeviceMediaStoreGenerationApi(appContext)
                    Log.i(
                        VENDOR_TAG,
                        "auto-artwork-generation-before-trash ${generationApi.read()}",
                    )
                    var trashed = 0
                    repeat(10) {
                        if (trashed == 0) {
                            trashed = trashAutoArtworkMediaRows(
                                appContext = appContext,
                                treeUri = treeUri,
                                exactDisplayName = displayName,
                            )
                            if (trashed == 0) Thread.sleep(300L)
                        }
                    }
                    Log.i(
                        VENDOR_TAG,
                        "auto-artwork-generation-after-trash-immediate ${generationApi.read()}",
                    )
                    if (trashed == 0) {
                        // Safety fallback: preserve the bytes inside the selected tree recycle
                        // directory rather than deleting them. The Gate still fails because this
                        // fallback remains inside SAF authority and must not be treated as clean.
                        recycleStaleAutoArtworkGateDocuments(appContext, treeUri)
                        error(
                            "AUTO artwork test file could not enter Android system Trash; " +
                                "preserved in .MicaRecycle instead",
                        )
                    }
                    val stableId = createdStableId
                    if (foreground && stableId != null) {
                        require(
                            waitUntilCachedSongAbsent(
                                appContext = appContext,
                                stableId = stableId,
                                timeoutMs = 20_000L,
                            ),
                        ) {
                            "AUTO artwork system-trash cleanup was not committed to cold cache " +
                                "before timeout"
                        }
                    }
                    Log.i(
                        VENDOR_TAG,
                        "auto-artwork-cleanup-complete name=$displayName " +
                            "stableId=${stableId ?: "unknown"} systemTrash=true authority=cold-cache",
                    )
                }
            } finally {
                if (foreground) {
                    library.onForegroundChanged(false)
                }
                library.release()
            }
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

    private fun waitUntilCachedSongAbsent(
        appContext: Context,
        stableId: String,
        timeoutMs: Long,
    ): Boolean {
        val repository = LibraryRepository(appContext)
        fun coldCacheContainsTarget(): Boolean = runBlocking {
            repository.loadCached()?.songs?.any { it.id == stableId } == true
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!coldCacheContainsTarget()) return true
            Thread.sleep(250L)
        }
        return !coldCacheContainsTarget()
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
        val autoArtworkGateRunning = AtomicBoolean(false)
        const val DEVICE_TAG = "MICA_S3_DEVICE_GATE"
        const val TAG = "MICA_S4_QA"
        const val VENDOR_TAG = "MICA_S4_VENDOR"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DEVICE_MEDIASTORE_SETTLE_TIMEOUT_MS = 15_000L
        const val DEVICE_SCHEDULER_GATE_TIMEOUT_MS = 60_000L
        const val SCHEDULER_GATE_TIMEOUT_MS = 15_000L
    }
}
