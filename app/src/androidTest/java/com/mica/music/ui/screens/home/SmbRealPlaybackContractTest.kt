package com.mica.music.ui.screens.home

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.PlaylistStore
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.data.remote.smb.SmbDirectoryBrowser
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.onMain
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device gate. Requires the dedicated, synthetic-only SMB fixture server. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SmbRealPlaybackContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsePlaySeekAndPersistOrdinaryPlaylistOverRealSmb() {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use an isolated SMB QA package", app.packageName == "com.mica.music.smbqa")
        assumeTrue("Explicit device gate required", args.getString("smbGate") == "true")
        com.mica.music.util.DiagnosticLog.configureDetailedDiagnostics(
            com.mica.music.util.DiagnosticDetailConfig(enabled = true, playbackMedia = true))
        val endpoint = args.getString("smbEndpoint") ?: "smb://127.0.0.1:1445/music"
        val repo = app.remoteCatalogRepository
        val playlists = app.playlistStore
        val source = runBlocking {
            playlists.awaitReady()
            app.remoteSourceManager.createSmb("SMB device gate", endpoint,
                args.getString("smbUsername").orEmpty(), args.getString("smbPassword").orEmpty())
        }
        val playlist = runBlocking { playlists.createPlaylist("SMB gate ${System.nanoTime()}") }
        onMain { app.playerController.connectIfNeeded() }
        val controller = connectMediaService(app)
        try {
            await("app playback controller connection", 10_000) { onMain { app.playerController.isConnected } }
            onMain { controller.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    Log.i(TAG, "state=$state count=${controller.mediaItemCount} playWhenReady=${controller.playWhenReady}")
                }
                override fun onPlayerError(error: PlaybackException) { Log.e(TAG, "playback-error", error) }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    Log.i(TAG, "transition index=${controller.currentMediaItemIndex} id=${item?.mediaId} reason=$reason")
                }
            }) }
            val browser = SmbDirectoryBrowser(repo, app.remoteCredentialStore)
            val root = runBlocking { browser.list(source.id, "") }
            assertTrue("Real SMB root must contain Album", root.entries.any { it.name == "Album" })
            compose.setContent { MicaTheme {
                SmbBrowserContent(repo, playlists, browser,
                    onPlay = { songs, id ->
                        Log.i(TAG, "play-request count=${songs.size}")
                        // Compose's test dispatcher can resume its scope off the Android looper.
                        onMain { app.playerController.playQueueSong(songs, id) }
                    }, bottomPadding = 0.dp, onBack = {}, initialSourceId = source.id)
            } }
            waitText("Album")
            assertTrue(runBlocking { repo.tracksForSource(source.id).isEmpty() })
            compose.onNodeWithText("Album").performClick()
            waitText("tone1.wav")
            compose.onNodeWithText("tone1.wav").performClick()
            await("real SMB playback", 30_000) { onMain { controller.isPlaying && controller.currentPosition > 1_000 } }
            val firstPosition = onMain { controller.currentPosition }
            await("decoded playback advances", 10_000) { onMain { controller.currentPosition >= firstPosition + 2_000 } }
            onMain { controller.seekTo(60_000) }
            await("nonzero SMB seek", 30_000) { onMain { controller.isPlaying && controller.currentPosition in 60_500..75_000 } }
            Log.i(TAG, "seek-pass positionMs=${onMain { controller.currentPosition }}")
            onMain { controller.pause() }
            await("pause") { onMain { !controller.isPlaying } }
            onMain { controller.play() }
            await("resume", 15_000) { onMain { controller.isPlaying } }

            compose.onNodeWithText("全选本层").performClick()
            compose.onNodeWithText("添加 2 首到歌单").performClick()
            compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
                .performScrollToNode(hasText(playlist.name))
            compose.onNodeWithText(playlist.name).performClick()
            waitText("已添加 2 首歌曲")
            runBlocking {
                val cold = PlaylistStore(app)
                cold.awaitReady()
                assertEquals(2, cold.playlistById(playlist.id)!!.songIds.size)
                assertTrue(repo.tracksForSource(source.id).isEmpty())
                assertEquals(0L, repo.sourceStatus(source.id)!!.lastSyncAtMs)
            }
            Log.i(TAG, "playlist-persisted id=${playlist.id}")
            compose.onNodeWithText("播放本目录").performClick()
            await("directory queue starts at first song", 30_000) { onMain {
                controller.mediaItemCount == 2 && controller.isPlaying && controller.currentMediaItemIndex == 0 &&
                    controller.currentPosition in 1_000..10_000
            } }
            onMain {
                if (args.getString("smbUseAppNext") == "true") app.playerController.next()
                else controller.seekToNextMediaItem()
            }
            await("second SMB song", 30_000) { onMain {
                controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition > 1_000
            } }
            assertNull(onMain { controller.playerError })
            Log.i(TAG, "PASS source=${source.id} playlist=${playlist.id} queue=2 catalog=0 positionMs=${onMain { controller.currentPosition }}")
        } finally {
            onMain {
                Log.i(TAG, "final index=${controller.currentMediaItemIndex} count=${controller.mediaItemCount} position=${controller.currentPosition} playing=${controller.isPlaying} suppression=${controller.playbackSuppressionReason}")
                controller.pause(); controller.release()
            }
        }
    }

    @Test fun coldPlaylistReplayOrOfflineRetention() {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(app.packageName == "com.mica.music.smbqa" && args.getString("smbGate") == "true")
        val playlistId = args.getString("smbPlaylistId")
        assumeTrue("Pass the playlist ID from the completed device gate", playlistId != null)
        val repo = app.remoteCatalogRepository
        val songs = runBlocking {
            app.playlistStore.awaitReady()
            val ids = app.playlistStore.playlistById(playlistId!!)!!.songIds
            assertEquals(2, ids.size)
            val refs = ids.map { RemoteMediaIdCodec.decode(it)!! }
            val known = repo.find(refs)
            assertEquals(2, known.size)
            assertTrue(repo.tracksForSource(refs.first().sourceInstanceId).isEmpty())
            refs.map { known.getValue(it).toPlaybackSong() }
        }
        if (args.getString("smbOffline") == "true") {
            val source = RemoteMediaIdCodec.decode(songs.first().id)!!.sourceInstanceId
            val failure = runCatching { runBlocking { SmbDirectoryBrowser(repo, app.remoteCredentialStore).list(source, "Album") } }.exceptionOrNull()
            assertNotNull("Fixture server must actually be offline", failure)
            assertEquals(2, app.playlistStore.playlistById(playlistId!!)!!.songIds.size)
            Log.i(TAG, "PASS offline-retention playlist=$playlistId descriptions=2")
            return
        }
        onMain { app.playerController.connectIfNeeded() }
        val controller = connectMediaService(app)
        try {
            await("cold controller connection", 10_000) { onMain { app.playerController.isConnected } }
            onMain { app.playerController.playQueueSong(songs, songs.last().id) }
            await("cold FLAC playlist replay", 30_000) { onMain { controller.isPlaying && controller.currentPosition > 1_000 } }
            assertEquals(songs.last().id, onMain { controller.currentMediaItem!!.mediaId })
            onMain { controller.seekTo(60_000) }
            await("cold FLAC seek", 30_000) { onMain { controller.isPlaying && controller.currentPosition in 60_500..75_000 } }
            assertNull(onMain { controller.playerError })
            Log.i(TAG, "PASS cold-replay playlist=$playlistId positionMs=${onMain { controller.currentPosition }}")
        } finally { onMain { controller.pause(); controller.release() } }
    }

    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object { const val TAG = "SmbRealGate" }
}
