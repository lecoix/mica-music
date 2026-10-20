package com.mica.music.data.lx

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.remote.toPlaybackSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxOnlineLyricsPresentationTest {
    @Test fun onlineLyricsReachMediaServiceDesktopProjection(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousMode = LyricsPreferences.externalLyricsMode(app)
        try {
            app.lxSourceOwner.restore()
            app.lxSourceOwner.loadOnline()
            val summary = app.lxSourceOwner.onlineTracks.value.values
                .filter { it.ref.sourceInstanceId == lxOnlineSourceForPlatform("kw") }
                .maxByOrNull { if (it.title.contains("晴天")) 1 else 0 }
                ?: error("KW persisted online record missing")
            LyricsPreferences.setExternalLyricsMode(app, ExternalLyricsMode.DESKTOP)
            app.desktopLyricsOverlayStateStore.clear()
            instrumentation.runOnMainSync {
                app.playerController.connectIfNeeded()
                app.playerController.playSingleSong(summary.toPlaybackSong())
            }
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var observed = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                observed = app.desktopLyricsOverlayStateStore.state.value.desktop.line?.original?.text.orEmpty()
                if (observed.isNotBlank()) break
                SystemClock.sleep(100L)
            }
            android.util.Log.i("LX_LYRIC_PRESENT", "media=${summary.mediaId.takeLast(24)} text=${observed.take(60)}")
            assertTrue("desktop lyric projection stayed empty for online KW track", observed.isNotBlank())
        } finally {
            instrumentation.runOnMainSync { app.playerController.pauseIfPlaying() }
            LyricsPreferences.setExternalLyricsMode(app, previousMode)
        }
    }
}