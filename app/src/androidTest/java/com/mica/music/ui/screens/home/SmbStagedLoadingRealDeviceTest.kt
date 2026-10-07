package com.mica.music.ui.screens.home

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.remote.AndroidTagLibRemoteTrackMetadataProbe
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.smb.SmbFolderLibraryIndexer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbStagedLoadingRealDeviceTest {
    @Test fun comparePublicationTimes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(app.packageName == "com.mica.music.qa" && args.getString("smbGate") == "true")
        delay(15_000)
        val source = app.remoteSourceManager.createSmb("SMB staged temporary", checkNotNull(args.getString("smbEndpoint")),
            args.getString("smbUsername") ?: "mica", args.getString("smbPassword") ?: "mica")
        val repo = app.remoteCatalogRepository
        val directory = "__mica_bench_20260930/n0050_r2"
        val loader = app.smbFolderLibraryLoader
        try {
            var expected: List<com.mica.music.data.remote.RemoteTrackSummary>? = null
            for (mode in listOf("full", "staged", "staged", "full")) {
                repo.smbFolderScopes(source.id).forEach { repo.removeSmbFolderScope(source.id, it.id) }
                val start = SystemClock.elapsedRealtime()
                var early = -1L
                if (mode == "full") {
                    val result = SmbFolderLibraryIndexer(repo, app.remoteCredentialStore,
                        metadataProbe = AndroidTagLibRemoteTrackMetadataProbe(app)).index(source.id, directory, false)
                    assertTrue(result.published)
                    assertEquals(0, result.metadataFailedCount)
                } else {
                    val id = loader.start(source.id, directory, false)
                    withTimeout(120_000) { loader.state.first { it.requestId == id && it.entriesReady } }
                    early = SystemClock.elapsedRealtime() - start
                    assertEquals(50, repo.tracksForSource(source.id).size)
                    val end = withTimeout(120_000) { loader.state.first { it.requestId == id && !it.active } }
                    assertTrue(end.message.startsWith("歌曲信息已更新"))
                }
                val total = SystemClock.elapsedRealtime() - start
                val tracks = repo.tracksForSource(source.id).sortedBy { it.fileName }
                assertEquals(50, tracks.size)
                assertTrue(tracks.all { it.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION })
                if (expected == null) expected = tracks else assertEquals(expected, tracks)
                Log.i("MicaSmbPhase", "staged_product mode=$mode entries_ms=$early total_ms=$total count=${tracks.size}")
            }
        } finally {
            loader.cancel()
            app.remoteSourceManager.deleteSource(source.id)
        }
    }
}
