package com.mica.music.data.lx

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.BuildConfig
import com.mica.music.MicaApp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** Real-device diagnostic for the QQ search transport/parser, independent from Compose and LX playback. */
@RunWith(AndroidJUnit4::class)
class LxQqOnlineSearchSmokeTest {
    @Test fun realQqSearchReturnsRows(): Unit = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val rows = QqOnlineSearch().search("卡农 失重", 1)
        Log.i("LX_QQ_SEARCH", "rows=${rows.size} first=${rows.firstOrNull()?.id} qualities=${rows.firstOrNull()?.qualities}")
        assertTrue("QQ search returned no accepted rows", rows.isNotEmpty())
        assertTrue("first QQ result has no 128k quality", "128k" in rows.first().qualities)
    }

    @Test fun freeListenResolvesRealQq128k(): Unit = runBlocking {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/freelisten.js").bufferedReader().use { it.readText() }
        try {
            withTimeout(25_000) { app.lxSourceOwner.importScript { script } }
            val api = QqOnlineSearch()
            app.lxSourceOwner.searchOnline("卡农 失重", 1, api::search)
            val row = app.lxSourceOwner.onlineSearch.value.results.first()
            val track = app.lxSourceOwner.admitOnline(row, "128k")
            val request = withTimeout(25_000) { app.lxSourceOwner.resolve(track.mediaId) }
            val uri = URI(request!!.url)
            Log.i("LX_QQ_RESOLVE", "id=${row.id} scheme=${uri.scheme} host=${uri.host} length=${request.url.length}")
            assertTrue(uri.scheme == "http" || uri.scheme == "https")
            assertTrue(!uri.host.isNullOrBlank())
        } finally {
            runCatching { app.lxSourceOwner.delete() }
        }
    }
}
