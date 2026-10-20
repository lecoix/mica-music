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

@RunWith(AndroidJUnit4::class)
class LxWyKgOnlineSearchSmokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<MicaApp>()
    private fun isolated() { check(BuildConfig.APPLICATION_ID == "com.mica.music.lx") }
    private fun xingHaiScript(): String = InstrumentationRegistry.getInstrumentation().context.assets
        .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }

    @Test fun realWySearchReturnsRows(): Unit = runBlocking {
        isolated()
        val rows = WyOnlineSearch().search("周杰伦 晴天", 1)
        Log.i("LX_WY_SEARCH", "rows=${rows.size} first=${rows.firstOrNull()?.id} qualities=${rows.firstOrNull()?.qualities}")
        assertTrue("NetEase search returned no accepted rows", rows.isNotEmpty())
        assertEquals("wy", rows.first().platform)
        assertTrue("first NetEase result has no 128k", "128k" in rows.first().qualities)
    }

    @Test fun xingHaiResolvesRealWy128k(): Unit = runBlocking {
        isolated()
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { xingHaiScript() } }
            val api = WyOnlineSearch()
            app.lxSourceOwner.searchOnline("wy", "周杰伦 晴天", 1, api::search)
            val row = app.lxSourceOwner.onlineSearch.value.results.first { "128k" in it.qualities }
            val track = app.lxSourceOwner.admitOnline(row, "128k")
            val request = withTimeout(25_000) { app.lxSourceOwner.resolve(track.mediaId) }
            val uri = URI(request!!.url)
            Log.i("LX_WY_RESOLVE", "id=${row.id} scheme=${uri.scheme} host=${uri.host} length=${request.url.length}")
            assertTrue(uri.scheme == "http" || uri.scheme == "https")
            assertTrue(!uri.host.isNullOrBlank())
        } finally {
            runCatching { app.lxSourceOwner.delete() }
        }
    }

    @Test fun realKgSearchReturnsRows(): Unit = runBlocking {
        isolated()
        val rows = KgOnlineSearch().search("周杰伦 晴天", 1)
        Log.i("LX_KG_SEARCH", "rows=${rows.size} first=${rows.firstOrNull()?.id} qualities=${rows.firstOrNull()?.qualities}")
        assertTrue("Kugou search returned no accepted rows", rows.isNotEmpty())
        assertEquals("kg", rows.first().platform)
        assertTrue("first Kugou result has no 128k", "128k" in rows.first().qualities)
        assertTrue("Kugou MusicInfo did not preserve hashes", rows.first().musicInfo.contains("_types") && rows.first().musicInfo.contains("hash"))
    }

    @Test fun xingHaiResolvesRealKg128k(): Unit = runBlocking {
        isolated()
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { xingHaiScript() } }
            val api = KgOnlineSearch()
            app.lxSourceOwner.searchOnline("kg", "周杰伦 晴天", 1, api::search)
            val row = app.lxSourceOwner.onlineSearch.value.results.first { "128k" in it.qualities }
            val track = app.lxSourceOwner.admitOnline(row, "128k")
            val request = withTimeout(25_000) { app.lxSourceOwner.resolve(track.mediaId) }
            val uri = URI(request!!.url)
            Log.i("LX_KG_RESOLVE", "id=${row.id} scheme=${uri.scheme} host=${uri.host} length=${request.url.length}")
            assertTrue(uri.scheme == "http" || uri.scheme == "https")
            assertTrue(!uri.host.isNullOrBlank())
        } finally {
            runCatching { app.lxSourceOwner.delete() }
        }
    }
}