package com.mica.music.data.lx

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.MicaApp
import com.mica.music.data.remote.toPlaybackSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxOnlineLyricsHydrationTest {
    @Test fun persistedOnlineTracksHydrateThroughMicaRepositoryAfterProcessStart(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        app.lxSourceOwner.loadOnline()
        for (platform in listOf("tx", "kw", "wy", "kg")) {
            val source = lxOnlineSourceForPlatform(platform)
            val summary = app.lxSourceOwner.onlineTracks.value.values
                .filter { it.ref.sourceInstanceId == source }
                .maxByOrNull { if (it.title.contains("晴天")) 1 else 0 }
                ?: error("$platform persisted online record missing")
            val hydrated = app.remoteLyricsRepository.songWithLyrics(summary.toPlaybackSong())
            val tokenCount = hydrated.lyricsDocument.lines.sumOf { it.tokens.size }
            android.util.Log.i(
                "LX_LYRIC_HYDRATE",
                "$platform loaded=${hydrated.lyricsLoaded} lines=${hydrated.lyricsDocument.lines.size} tokens=$tokenCount",
            )
            assertTrue("$platform lyrics should be marked loaded", hydrated.lyricsLoaded)
            assertTrue("$platform hydrated lyrics should be non-empty", hydrated.lyricsDocument.lines.size >= 8)
        }
    }
}