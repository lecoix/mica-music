package com.mica.music.ui.screens.home

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Activity
import com.mica.music.MicaApp
import com.mica.music.data.remote.AndroidTagLibRemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.smb.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, bounded fixture measurement. Decorates existing seams; no production changes. */
@RunWith(AndroidJUnit4::class)
class SmbPhaseTimingRealDeviceTest {
    @Test fun phases() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(app.packageName == "com.mica.music.qa" && args.getString("smbGate") == "true")
        val count = (args.getString("count") ?: "10").toInt()
        require(count in 1..50)
        val directory = "__mica_bench_20260930/n${count.toString().padStart(4, '0')}_r2"
        // Host brings the installed activity to foreground.
        try {
            Log.i("MicaSmbPhase", "settling before measurement")
            // Allow restoration/prebuffer from the existing paused queue to settle before sampling.
            delay(15_000)
            val source = app.remoteSourceManager.createSmb("SMB phase timing temporary",
                checkNotNull(args.getString("smbEndpoint")),
                args.getString("smbUsername") ?: "mica", args.getString("smbPassword") ?: "mica")
            val repo = app.remoteCatalogRepository
            try {
                for (mode in listOf("listing_only", "full", "reuse")) {
                    if (mode == "full") {
                        repo.smbFolderScopes(source.id).forEach { repo.removeSmbFolderScope(source.id, it.id) }
                    }
                    val meter = Meter()
                    val delegate = AndroidTagLibRemoteTrackMetadataProbe(app)
                    val probe = if (mode == "listing_only") null else RemoteTrackMetadataProbe { name, bytes ->
                        val start = now()
                        try { delegate.probe(name, bytes) } finally {
                            meter.files.getValue(name).probeNs.addAndGet(now() - start)
                        }
                    }
                    val indexer = SmbFolderLibraryIndexer(repo, app.remoteCredentialStore, meter.factory(), probe)
                    meter.start = now()
                    val result = indexer.index(source.id, directory, false)
                    val end = now()
                    val row = JSONObject().put("mode", mode).put("count", count)
                        .put("total_ms", ms(end - meter.start))
                        .put("before_connect_ms", ms(meter.connectStart - meter.start))
                        .put("connect_ms", ms(meter.connectEnd - meter.connectStart))
                        .put("enumeration_ms", ms(meter.enumerationNs))
                        .put("probe_phase_wall_ms", if (meter.firstOpen.get() == 0L) 0.0 else ms(meter.closeStart - meter.firstOpen.get()))
                        .put("session_close_ms", ms(meter.closeEnd - meter.closeStart))
                        .put("post_session_publish_ms", ms(end - meter.closeEnd))
                        .put("reads", meter.files.values.sumOf { it.calls.get() })
                        .put("bytes", meter.files.values.sumOf { it.bytes.get() })
                        .put("read_sum_ms", ms(meter.files.values.sumOf { it.readNs.get() }))
                        .put("probed", result.metadataProbedCount).put("reused", result.metadataReusedCount)
                        .put("complete", result.complete).put("published", result.published)
                    Log.i("MicaSmbPhase", row.toString())
                    meter.files.toSortedMap().forEach { (name, f) ->
                        Log.i("MicaSmbFile", JSONObject().put("mode", mode).put("name", name)
                            .put("length", f.size).put("open_ms", ms(f.openNs))
                            .put("probe_ms", ms(f.probeNs.get())).put("read_ms", ms(f.readNs.get()))
                            .put("reads", f.calls.get()).put("bytes", f.bytes.get()).toString())
                    }
                    assertTrue(result.complete && result.published)
                    assertEquals(count, result.trackCount)
                    if (mode == "listing_only" || mode == "reuse") assertEquals(0, meter.files.size)
                    if (mode == "full") assertEquals(count, result.metadataProbedCount)
                    if (mode == "reuse") assertEquals(count, result.metadataReusedCount)
                }
            } finally { app.remoteSourceManager.deleteSource(source.id) }
        } finally { Log.i("MicaSmbPhase", "measurement finished") }
    }

    private class FileMeter(val size: Long, val openNs: Long) {
        val calls = AtomicLong(); val bytes = AtomicLong(); val readNs = AtomicLong(); val probeNs = AtomicLong()
    }
    private class Meter {
        var start = 0L; var connectStart = 0L; var connectEnd = 0L
        var enumerationNs = 0L; var closeStart = 0L; var closeEnd = 0L
        val firstOpen = AtomicLong()
        val files = ConcurrentHashMap<String, FileMeter>()
        fun factory() = SmbSessionFactory { endpoint, login ->
            connectStart = now()
            val session = SmbjSessionFactory().open(endpoint, login)
            connectEnd = now()
            object : SmbSessionHandle {
                override fun list(serverPath: String) = session.list(serverPath)
                override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                    val t = now()
                    try { session.visit(serverPath, consume) } finally { enumerationNs += now() - t }
                }
                override fun openFile(serverPath: String): SmbRandomAccessFile {
                    val t = now(); firstOpen.compareAndSet(0L, t)
                    val file = session.openFile(serverPath)
                    val meter = FileMeter(file.length, now() - t)
                    check(files.put(serverPath.substringAfterLast('/').substringAfterLast('\\'), meter) == null)
                    return object : SmbRandomAccessFile {
                        override val length get() = file.length
                        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                            val readStart = now()
                            try {
                                return file.read(fileOffset, buffer, offset, length).also {
                                    meter.bytes.addAndGet(it.coerceAtLeast(0).toLong())
                                }
                            } finally { meter.calls.incrementAndGet(); meter.readNs.addAndGet(now() - readStart) }
                        }
                        override fun close() = file.close()
                    }
                }
                override fun close() {
                    closeStart = now()
                    try { session.close() } finally { closeEnd = now() }
                }
            }
        }
    }
    companion object {
        private fun now() = SystemClock.elapsedRealtimeNanos()
        private fun ms(ns: Long) = ns / 1_000_000.0
    }
}
