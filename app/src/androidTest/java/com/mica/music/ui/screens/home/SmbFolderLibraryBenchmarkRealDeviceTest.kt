package com.mica.music.ui.screens.home

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.remote.AndroidTagLibRemoteTrackMetadataProbe
import com.mica.music.data.remote.smb.SmbFolderLibraryIndexer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary implementation-verification benchmark for real SMB folder-to-library loading.
 * Uses the same metadata probe as the production SMB browser.
 */
@RunWith(AndroidJUnit4::class)
class SmbFolderLibraryBenchmarkRealDeviceTest {
    @Test
    fun benchmarkColdFolderAddsOverRealSmb() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the isolated SMB QA package", app.packageName == "com.mica.music.qa")
        assumeTrue("Explicit device gate required", args.getString("smbGate") == "true")

        val endpoint = checkNotNull(args.getString("smbEndpoint"))
        val username = args.getString("smbUsername") ?: "mica"
        val password = args.getString("smbPassword") ?: "mica"
        val benchmarkRoot = args.getString("benchmarkRoot") ?: "__mica_bench_20260930"
        val counts = (args.getString("benchmarkCounts") ?: "10,50,100,200,500")
            .split(',')
            .map { it.trim().toInt() }
        val reps = (args.getString("benchmarkReps") ?: "2").toInt()

        val source = app.remoteSourceManager.createSmb(
            displayName = "SMB benchmark",
            endpoint = endpoint,
            username = username,
            password = password,
        )
        val repo = app.remoteCatalogRepository
        val indexer = SmbFolderLibraryIndexer(
            repository = repo,
            credentials = app.remoteCredentialStore,
            metadataProbe = AndroidTagLibRemoteTrackMetadataProbe(app),
        )

        try {
            for (count in counts) {
                for (rep in 1..reps) {
                    val directory = benchmarkRoot + "/n" + count.toString().padStart(4, '0') + "_r" + rep
                    val startNs = SystemClock.elapsedRealtimeNanos()
                    val result = indexer.index(
                        sourceId = source.id,
                        directory = directory,
                        includeSubdirectories = false,
                    )
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
                    val line = buildString {
                        append("count=").append(count)
                        append(" rep=").append(rep)
                        append(" ms=").append(String.format(java.util.Locale.US, "%.1f", elapsedMs))
                        append(" tracks=").append(result.trackCount)
                        append(" complete=").append(result.complete)
                        append(" published=").append(result.published)
                        append(" probed=").append(result.metadataProbedCount)
                        append(" reused=").append(result.metadataReusedCount)
                    }
                    Log.i("MicaSmbBench", line)

                    assertTrue("index incomplete for $directory", result.complete)
                    assertTrue("index not published for $directory", result.published)
                    assertEquals(count, result.trackCount)
                    assertEquals(count, result.metadataProbedCount)
                    assertEquals(0, result.metadataReusedCount)

                    val scope = repo.smbFolderScopes(source.id)
                        .single { it.relativeDirectory == directory }
                    assertTrue(repo.removeSmbFolderScope(source.id, scope.id))
                    assertTrue(repo.tracksForSource(source.id).isEmpty())
                    delay(250)
                }
            }
        } finally {
            app.remoteSourceManager.deleteSource(source.id)
        }
    }
}
