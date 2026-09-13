package com.mica.music.data

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.library.*
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Device component profile: real provider/probe/Room; deliberately excludes dirty-signal latency.
 * Timings wrap existing seams and do not change production concurrency or scanning policy.
 */
@RunWith(AndroidJUnit4::class)
class SafAutoStageProfileTest {
    @get:Rule val deviceHost = ScannerDeviceHostRule()

    @Test
    fun profileNoOpAdditionLyricsAndHundredObjectBurst() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa"))
        val tree = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.authorityForPackage(context.packageName), TestDocumentsProvider.ROOT_ID,
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        context.grantUriPermission(context.packageName, tree, flags)
        val lease = "saf-stage-profile"
        TestDocumentsProvider.beginExclusiveLease(lease, "BASELINE")
        val delegate = AndroidLibraryScanner(context)
        var inventoryMs = 0L
        var postMs = 0L
        var probeMs = 0L
        var attempted = 0
        var sequence = 0L
        val scanner = object : LibraryScanner by delegate {
            override suspend fun observeFolderMetadata(treeUri: Uri): SafTreeMetadataSnapshot {
                val start = SystemClock.elapsedRealtime()
                return delegate.observeFolderMetadata(treeUri).also {
                    inventoryMs += SystemClock.elapsedRealtime() - start
                }
            }
            override suspend fun observeFolderMetadataTargets(
                treeUri: Uri, folderPaths: Set<String>,
            ): SafTargetedMetadataSnapshot {
                val start = SystemClock.elapsedRealtime()
                return delegate.observeFolderMetadataTargets(treeUri, folderPaths).also {
                    postMs += SystemClock.elapsedRealtime() - start
                }
            }
        }
        val runtime = AndroidSafShadowProbeRuntime(context)
        val backing = withContext(Dispatchers.Main) {
            MusicLibraryBacking(
                context, scanner, RoomLibraryStore(context), AndroidScanEnvironment(context),
                Dispatchers.Main.immediate, Dispatchers.IO,
                safShadowProbeRuntime = SafShadowProbeRuntime { request ->
                    val start = SystemClock.elapsedRealtime()
                    runtime.execute(request).also {
                        probeMs += SystemClock.elapsedRealtime() - start
                        attempted += it.attemptedCount
                    }
                },
            )
        }
        try {
            withContext(Dispatchers.Main) { backing.folder.setLibraryFolder(tree) }
            backing.operationExecutor.scanLibraryFolder()
            assertNull(backing.lastScanError)
            assertEquals(1, backing.songs.size)
            suspend fun profile(label: String, scenario: String, expected: Int) {
                TestDocumentsProvider.setScenarioWithinLease(lease, scenario)
                inventoryMs = 0; postMs = 0; probeMs = 0; attempted = 0
                val start = SystemClock.elapsedRealtime()
                var passes = 0
                do {
                    backing.operationExecutor.executeAutoSyncForReadiness(
                        ScheduledLibraryOperation(
                            LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_PERIODIC_VERIFY),
                            ++sequence, backing.syncScheduler.dirtySequence,
                        ),
                    )
                    passes++
                } while (backing.songs.size != expected && passes < 5)
                val wall = SystemClock.elapsedRealtime() - start
                assertEquals("$label must publish all expected members", expected, backing.songs.size)
                assertEquals(expected, requireNotNull(backing.libraryStore.loadCached()).songs.size)
                Log.i("MICA_SCANNER_STAGE", "label=$label wallMs=$wall inventoryMs=$inventoryMs " +
                    "postVerifyMs=$postMs probeMs=$probeMs attempted=$attempted passes=$passes " +
                    "lastPublication=${backing.lastAutoPublicationTiming} " +
                    "scope=component excludes=notification,scheduler-wait provider=test-private-wav")
            }
            repeat(3) { profile("noop-${it + 1}", "BASELINE", 1) }
            val beforeLyrics = backing.libraryChangeRevision
            profile("lyrics-only", "LYRICS_CHANGED", 1)
            assertTrue("Lyrics change must publish", backing.libraryChangeRevision > beforeLyrics)
            profile("lyrics-restore", "BASELINE", 1)
            profile("add-one", "ADDED", 2)
            profile("add-hundred", "ADDED_HUNDRED", 102)
        } finally {
            withContext(Dispatchers.Main) { backing.release() }
            LibraryFolderStore.releaseTreeAccess(context, tree)
            context.revokeUriPermission(tree, flags)
            TestDocumentsProvider.endExclusiveLease(lease)
            TestDocumentsProvider.resetScenario()
        }
    }
}
