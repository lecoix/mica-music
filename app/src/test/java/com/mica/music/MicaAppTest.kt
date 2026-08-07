package com.mica.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicaAppTest {

    @Test
    fun playlistStoreIsSharedAcrossConsumers() {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        app.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE).edit().clear().commit()
        MicaDatabase.resetForTests()
        runBlocking { MicaDatabase.get(app).playlistDao().deleteAll() }

        val homeStore = app.playlistStore
        val playerStore = app.playlistStore
        val playlist = homeStore.createPlaylist("Shared")
        playerStore.addSongToPlaylist(playlist.id, "song")

        assertSame(homeStore, playerStore)
        assertEquals(listOf("song"), homeStore.playlistById(playlist.id)?.songIds)
    }
}
