package com.mica.music.data.lx

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.BuildConfig
import com.mica.music.MicaApp
import com.mica.music.data.remote.toPlaybackSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxOnlineArtworkSmokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<MicaApp>()

    private suspend fun assertArtwork(
        platform: String,
        search: suspend (String, Int) -> List<LxOnlineResult>,
    ) {
        check(BuildConfig.APPLICATION_ID == "com.mica.music.lx")
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
        app.lxSourceOwner.importScript { script }
        app.lxSourceOwner.searchOnline(platform, "周杰伦 晴天", 1, search)
        val row = app.lxSourceOwner.onlineSearch.value.results.firstOrNull {
            lxOnlineArtworkUrl(platform, it.musicInfo) != null && "128k" in it.qualities
        } ?: error("$platform search returned no row with artwork")
        val direct = requireNotNull(lxOnlineArtworkUrl(platform, row.musicInfo))
        val track = app.lxSourceOwner.admitOnline(row, "128k")
        assertTrue(track.artworkOpaqueId.isNotBlank())
        val song = track.toPlaybackSong()
        val stable = requireNotNull(song.albumArtUri)
        assertTrue(stable.startsWith("content://${BuildConfig.APPLICATION_ID}.remoteart/"))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(Uri.parse(stable)).use { input ->
            requireNotNull(input)
            BitmapFactory.decodeStream(input, null, options)
        }
        assertTrue("$platform artwork width=${options.outWidth}", options.outWidth > 0)
        assertTrue("$platform artwork height=${options.outHeight}", options.outHeight > 0)
        android.util.Log.i("LX_ARTWORK", "platform=$platform directHost=${Uri.parse(direct).host} stable=$stable size=${options.outWidth}x${options.outHeight}")
    }

    @Test fun qqArtworkBridgeReadsImage() = runBlocking { assertArtwork("tx", QqOnlineSearch()::search) }
    @Test fun kuwoArtworkBridgeReadsImage() = runBlocking { assertArtwork("kw", KwOnlineSearch()::search) }
    @Test fun neteaseArtworkBridgeReadsImage() = runBlocking { assertArtwork("wy", WyOnlineSearch()::search) }
    @Test fun kugouArtworkBridgeReadsImage() = runBlocking { assertArtwork("kg", KgOnlineSearch()::search) }
}