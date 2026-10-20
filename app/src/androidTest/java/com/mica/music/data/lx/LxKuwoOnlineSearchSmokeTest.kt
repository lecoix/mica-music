package com.mica.music.data.lx

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.BuildConfig
import com.mica.music.MicaApp
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device diagnostic for Kuwo search and the FreeListen kw musicUrl path. */
@RunWith(AndroidJUnit4::class)
class LxKuwoOnlineSearchSmokeTest {
    @Test fun realKuwoSearchReturnsRows(): Unit = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val rows = KwOnlineSearch().search("周杰伦 晴天", 1)
        val exact = rows.firstOrNull { it.id == "228908" } ?: rows.firstOrNull()
        Log.i("LX_KW_SEARCH", "rows=${rows.size} first=${exact?.id} qualities=${exact?.qualities}")
        assertTrue("Kuwo search returned no accepted rows", rows.isNotEmpty())
        assertEquals("kw", exact!!.platform)
        assertTrue("Kuwo result has no 128k quality", "128k" in exact.qualities)
        assertTrue("Kuwo MusicInfo missing songmid", exact.musicInfo.contains("\"songmid\":\"${exact.id}\""))
    }

    @Test fun freeListenResolvesRealKuwo128k(): Unit = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/freelisten.js").bufferedReader().use { it.readText() }
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { script } }
            val api = KwOnlineSearch()
            app.lxSourceOwner.searchOnline("kw", "周杰伦 晴天", 1, api::search)
            val row = app.lxSourceOwner.onlineSearch.value.results
                .firstOrNull { it.id == "228908" && "128k" in it.qualities }
                ?: app.lxSourceOwner.onlineSearch.value.results.first { "128k" in it.qualities }
            val track = app.lxSourceOwner.admitOnline(row, "128k")
            assertEquals(LX_ONLINE_KW_SOURCE, track.ref.sourceInstanceId)
            val request = withTimeout(30_000) { app.lxSourceOwner.resolve(track.mediaId) }
            val uri = URI(request!!.url)
            Log.i("LX_KW_RESOLVE", "id=${row.id} scheme=${uri.scheme} host=${uri.host} length=${request.url.length}")
            assertTrue(uri.scheme == "http" || uri.scheme == "https")
            assertTrue(!uri.host.isNullOrBlank())
        } finally {
            runCatching { app.lxSourceOwner.delete() }
        }
    }

    @Test fun xingHaiResolvesRealKuwo128k(): Unit = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { script } }
            val api = KwOnlineSearch()
            app.lxSourceOwner.searchOnline("kw", "周杰伦 晴天", 1, api::search)
            val row = app.lxSourceOwner.onlineSearch.value.results
                .firstOrNull { it.id == "228908" && "128k" in it.qualities }
                ?: app.lxSourceOwner.onlineSearch.value.results.first { "128k" in it.qualities }
            val track = app.lxSourceOwner.admitOnline(row, "128k")
            val request = withTimeout(30_000) { app.lxSourceOwner.resolve(track.mediaId) }
            val uri = URI(request!!.url)
            Log.i("LX_KW_RESOLVE", "source=xinghai id=${row.id} scheme=${uri.scheme} host=${uri.host} length=${request.url.length}")
            assertTrue(uri.scheme == "http" || uri.scheme == "https")
            assertTrue(!uri.host.isNullOrBlank())
        } finally {
            runCatching { app.lxSourceOwner.delete() }
        }
    }}
