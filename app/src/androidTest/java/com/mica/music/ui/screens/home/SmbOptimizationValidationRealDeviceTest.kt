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
import com.mica.music.data.remote.RemoteTrackMetadata
import kotlinx.coroutines.*
import org.json.JSONArray
import java.util.Collections
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
class SmbOptimizationValidationRealDeviceTest {
    @Test fun phases() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(app.packageName == "com.mica.music.qa" && args.getString("smbGate") == "true")
        val count = (args.getString("count") ?: "50").toInt()
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
                var expected: Map<String, RemoteTrackMetadata>? = null
                val modes = if (args.getString("skipDynamic") == "true") listOf("listing_only", "full", "reuse", "changed", "reuse_changed", "listing_again", "fill_again") else listOf("listing_only", "full", "reuse", "changed", "reuse_changed", "dynamic1", "batch2", "dynamic2", "listing_again", "fill_again")
                for (mode in modes) {
                    val observed = ConcurrentHashMap<String, RemoteTrackMetadata>()
                    if (mode in listOf("full", "dynamic1", "batch2", "dynamic2", "listing_again")) {
                        repo.smbFolderScopes(source.id).forEach { repo.removeSmbFolderScope(source.id, it.id) }
                    }
                    val meter = Meter(mode == "changed" || mode == "reuse_changed")
                    val delegate = AndroidTagLibRemoteTrackMetadataProbe(app)
                    val probe = if (mode.startsWith("listing")) null else RemoteTrackMetadataProbe { name, bytes ->
                        val start = now()
                        try { delegate.probe(name, bytes).also { if (it != null) observed[name] = it } } finally {
                            meter.files.getValue(name).probeNs.addAndGet(now() - start)
                            meter.files.getValue(name).ended = now()
                        }
                    }
                    val indexer = SmbFolderLibraryIndexer(repo, app.remoteCredentialStore, meter.factory(), probe)
                    meter.start = now()
                    val result = if (mode.startsWith("dynamic")) ExperimentalSmbWorkerIndexer(repo, app.remoteCredentialStore, meter.factory(), probe).index(source.id, directory, false) else indexer.index(source.id, directory, false)
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
                            .put("reads", f.calls.get()).put("bytes", f.bytes.get()).put("start_ms", ms(f.started - meter.start)).put("end_ms", ms(f.ended - meter.start)).put("ranges", JSONArray(f.ranges)).toString())
                    }
                    assertTrue(result.complete && result.published)
                    assertEquals(count, result.trackCount)
                    if (mode.startsWith("listing") || mode.startsWith("reuse")) assertEquals(0, meter.files.size)
                    if (mode.startsWith("reuse")) assertEquals(count, result.metadataReusedCount)
                    if (mode == "changed") {
                        assertEquals(1, result.metadataProbedCount)
                        assertEquals(count - 1, result.metadataReusedCount)
                        assertEquals(expected!!["0001_000.flac"], observed["0001_000.flac"])
                    } else if (!mode.startsWith("listing") && !mode.startsWith("reuse")) {
                        assertEquals(count, result.metadataProbedCount)
                        assertEquals(count, observed.size)
                        if (expected == null) expected = observed.toMap() else assertEquals(expected, observed.toMap())
                    }
                    assertEquals(count, repo.tracksForSource(source.id).size)
                    Log.i("MicaSmbPhase", "validated mode=$mode")
                }
            } finally { app.remoteSourceManager.deleteSource(source.id) }
            // Actual publication interleaving: release old TagLib results only after source deletion.
            for (dynamic in listOf(false, true)) {
                val stale = app.remoteSourceManager.createSmb("SMB stale fill temporary", checkNotNull(args.getString("smbEndpoint")), args.getString("smbUsername") ?: "mica", args.getString("smbPassword") ?: "mica")
                var deleted = false
                try {
                    val first = SmbFolderLibraryIndexer(repo, app.remoteCredentialStore).index(stale.id, directory, false)
                    assertTrue(first.published)
                    assertEquals(count, repo.tracksForSource(stale.id).size)
                    val reached = CompletableDeferred<Unit>()
                    val release = java.util.concurrent.CountDownLatch(1)
                    val delegate = AndroidTagLibRemoteTrackMetadataProbe(app)
                    val probe = RemoteTrackMetadataProbe { name, bytes ->
                        val result = delegate.probe(name, bytes)
                        reached.complete(Unit)
                        check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        result
                    }
                    val old = async(Dispatchers.IO) {
                        runCatching {
                            if (dynamic) ExperimentalSmbWorkerIndexer(repo, app.remoteCredentialStore, metadataProbe = probe).index(stale.id, directory, false)
                            else SmbFolderLibraryIndexer(repo, app.remoteCredentialStore, metadataProbe = probe).index(stale.id, directory, false)
                        }
                    }
                    try {
                        withTimeout(20_000) { reached.await() }
                        app.remoteSourceManager.deleteSource(stale.id)
                        deleted = true
                    } finally { release.countDown() }
                    val outcome = old.await()
                    assertTrue(outcome.isFailure || outcome.getOrNull()?.published == false)
                    assertTrue(repo.tracksForSource(stale.id).isEmpty())
                    Log.i("MicaSmbPhase", "stale_fill_rejected dynamic=$dynamic persisted_tracks=0")
                } finally { if (!deleted) app.remoteSourceManager.deleteSource(stale.id) }
            }

        } finally { Log.i("MicaSmbPhase", "measurement finished") }
    }

    private class FileMeter(val size: Long, val openNs: Long, val started: Long) {
        @Volatile var ended = 0L
        val ranges = Collections.synchronizedList(mutableListOf<List<Long>>())
        val calls = AtomicLong(); val bytes = AtomicLong(); val readNs = AtomicLong(); val probeNs = AtomicLong()
    }
    private class Meter(private val changeOne: Boolean) {
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
                    try { session.visit(serverPath) { entry -> consume(if (changeOne && entry.name == "0001_000.flac") entry.copy(contentRevision = entry.contentRevision + "-test-change") else entry) } } finally { enumerationNs += now() - t }
                }
                override fun openFile(serverPath: String): SmbRandomAccessFile {
                    val t = now(); firstOpen.compareAndSet(0L, t)
                    val file = session.openFile(serverPath)
                    val meter = FileMeter(file.length, now() - t, t)
                    check(files.put(serverPath.substringAfterLast('/').substringAfterLast('\\'), meter) == null)
                    return object : SmbRandomAccessFile {
                        override val length get() = file.length
                        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                            val readStart = now()
                            try {
                                return file.read(fileOffset, buffer, offset, length).also {
                                    meter.bytes.addAndGet(it.coerceAtLeast(0).toLong())
                                    if (it > 0) meter.ranges.add(listOf(fileOffset, it.toLong()))
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
