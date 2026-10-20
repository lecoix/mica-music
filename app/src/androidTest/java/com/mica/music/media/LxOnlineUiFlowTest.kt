package com.mica.music.media

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.BuildConfig
import com.mica.music.MicaMainActivity
import com.mica.music.MicaApp
import com.mica.music.data.PlaylistStore
import com.mica.music.data.lx.*
import com.mica.music.data.preferences.UsageTutorialPreferences
import com.mica.music.data.preferences.OnlineMusicDefaultQuality
import com.mica.music.data.preferences.OnlineMusicPreferences
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.onMain
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in: real UI text input -> real QQ search -> real third-party script -> MediaSession. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LxOnlineUiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MicaApp>()
    private fun isolated() { check(BuildConfig.APPLICATION_ID == "com.mica.music.lx") }
    private fun stage(message: String) { android.util.Log.i("LX_ONLINE_STAGE", message) }
    private fun assertOnlineArtwork(mediaId: String, expectedBitrateKbps: Int = 128): String {
        val track = app.lxSourceOwner.onlineTracks.value.getValue(mediaId)
        assertEquals(expectedBitrateKbps, track.bitrateKbps)
        assertEquals("mp3", track.suffix)
        assertEquals("audio/mpeg", track.mimeTypeHint)
        val artworkUri = requireNotNull(track.toPlaybackSong().albumArtUri)
        val bytes = app.contentResolver.openInputStream(Uri.parse(artworkUri))!!.use { it.readBytes() }
        assertTrue("artwork payload is empty", bytes.isNotEmpty())
        assertNotNull("artwork payload is not decodable", BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return artworkUri
    }
    private suspend fun assertControllerMetadata(
        controller: MediaController,
        mediaId: String,
        artworkUri: String,
    ) {
        val track = app.lxSourceOwner.onlineTracks.value.getValue(mediaId)
        val metadata = onMain { controller.mediaMetadata }
        assertEquals(track.title, metadata.title?.toString())
        assertEquals(track.artist, metadata.artist?.toString())
        assertEquals(track.album, metadata.albumTitle?.toString())
        if (track.durationSec > 0) assertEquals(track.durationSec * 1000L, metadata.durationMs)
        assertEquals(artworkUri, metadata.artworkUri?.toString())
    }
    private lateinit var activity: MicaMainActivity
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    @Before fun launchExperimentActivity() {
        isolated()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.mica.music.lx")
        UsageTutorialPreferences.complete(context)
        val application = context.applicationContext as Application
        val resumed = AtomicReference<MicaMainActivity?>()
        val latch = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is MicaMainActivity) { resumed.set(activity); latch.countDown() }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
        val component = "${context.packageName}/${MicaMainActivity::class.java.name}"
        val shell = instrumentation.uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n $component").let {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        assertTrue("LX MicaMainActivity shell launch failed: $shell", shell.contains("Status: ok"))
        assertTrue("LX MicaMainActivity did not reach RESUMED: $shell", latch.await(10, TimeUnit.SECONDS))
        activity = checkNotNull(resumed.get())
        compose.waitForIdle()
    }

    @After fun closeExperimentActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (::activity.isInitialized) {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            instrumentation.waitForIdleSync()
        }
        lifecycleCallbacks?.let { (instrumentation.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
    }

    @Test fun searchChoosePlayQueueAndPlaylist(): Unit = runBlocking {
        stage("01-test-enter")
        isolated()
        val script = InstrumentationRegistry.getInstrumentation().context.assets.open("lx-real/freelisten.js").bufferedReader().use { it.readText() }
        stage("02-script-loaded")
        app.lxSourceOwner.importScript { script }
        stage("03-source-imported platforms=${app.lxSourceOwner.state.value.platforms}")
        val playlist = app.playlistStore.createPlaylist("LX 在线流程 ${System.currentTimeMillis()}")
        stage("04-playlist-created id=${playlist.id}")
        compose.onNodeWithContentDescription("菜单").performClick()
        stage("05-drawer-opened")
        compose.onNodeWithText("在线音乐").performClick()
        stage("06-online-opened")
        compose.onNodeWithTag("online-query").performTextInput("卡农 失重")
        compose.onNodeWithTag("online-search-submit").performClick()
        stage("07-search-submitted")
        compose.waitUntil(25_000) {
            val state = app.lxSourceOwner.onlineSearch.value
            state.query == "卡农 失重" && !state.busy
        }
        val searchState = app.lxSourceOwner.onlineSearch.value
        stage("08-search-finished count=${searchState.results.size} error=${searchState.error}")
        assertNull("QQ search failed: ${searchState.error}", searchState.error)
        val rows = searchState.results
        assertTrue("QQ search returned no rows", rows.isNotEmpty())
        val row = rows.first()
        assertTrue(row.musicInfo.contains("strMediaMid"))
        assertTrue(row.qualities.contains("128k"))
        val expectedId = row.record("128k").track.mediaId
        compose.onNodeWithTag("online-song-${row.id}").performClick()
        compose.onNodeWithTag("online-quality-128k").performClick()
        compose.onNodeWithTag("online-confirm").performClick()
        stage("09-play-confirmed id=${row.id} expectedMediaId=$expectedId")
        compose.waitUntil(10_000) {
            app.playerController.playbackQueueState.queue.any { it.id == expectedId }
        }
        stage(
            "09b-app-queue-ready connected=${app.playerController.isConnected} " +
                "current=${app.playerController.playbackSurfaceState.currentSong?.id} " +
                "queue=${app.playerController.playbackQueueState.queue.joinToString { it.id }}",
        )
        val controller = connectMediaService(app)
        stage("10-media-controller-connected current=${onMain { controller.currentMediaItem?.mediaId }} error=${onMain { controller.playerError?.errorCodeName }}")
        try {
            await("real online audio", 45_000) {
                onMain {
                    val currentId = controller.currentMediaItem?.mediaId
                    if (currentId == expectedId) {
                        controller.playerError?.let { error ->
                            throw AssertionError("online playerError=${error.errorCodeName}: ${error.message}", error)
                        }
                    }
                    currentId == expectedId && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val id = onMain { controller.currentMediaItem!!.mediaId }
            stage("11-audio-ready mediaId=$id position=${onMain { controller.currentPosition }}")
            assertEquals(expectedId, id)
            val artworkUri = assertOnlineArtwork(expectedId)
            assertControllerMetadata(controller, expectedId, artworkUri)
            compose.onNodeWithTag("online-song-${row.id}").performTouchInput { longClick() }
            compose.onNodeWithText("加入歌单").performClick()
            compose.onNodeWithTag("online-quality-128k").performClick()
            compose.onNodeWithTag("online-action-dialog-list").performScrollToNode(hasTestTag("online-playlist-${playlist.id}"))
            compose.onNodeWithTag("online-playlist-${playlist.id}").performClick()
            compose.onNodeWithTag("online-confirm").performClick()
            compose.waitUntil(10_000) { app.playlistStore.playlists.any { it.id == playlist.id && id in it.songIds } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("online-confirm").fetchSemanticsNodes().isEmpty() }
            stage("12-playlist-added")
            val next = rows[1]
            val nextId = next.record("128k").track.mediaId
            stage("12b-next-target searchId=${next.id} mediaId=$nextId sameAsCurrent=${nextId == id}")
            compose.onNodeWithTag("online-song-${next.id}").performTouchInput { longClick() }
            compose.onNodeWithText("下一首播放").performClick()
            compose.onNodeWithTag("online-quality-128k").performClick()
            compose.onNodeWithTag("online-confirm").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("online-confirm").fetchSemanticsNodes().isEmpty() }
            stage(
                "12c-next-action-finished admitted=${app.lxSourceOwner.onlineTracks.value.containsKey(nextId)} " +
                    "current=${app.playerController.playbackSurfaceState.currentSong?.id} " +
                    "queue=${app.playerController.playbackQueueState.queue.joinToString { it.id }}",
            )
            compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == nextId } }
            stage("13-play-next-added")
            val third = rows[2]
            compose.onNodeWithTag("online-song-${third.id}").performTouchInput { longClick() }
            compose.onNodeWithText("加入队列").performClick()
            compose.onNodeWithTag("online-quality-128k").performClick()
            compose.onNodeWithTag("online-confirm").performClick()
            compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == third.record("128k").track.mediaId } }
            stage("14-append-added")
            assertEquals(listOf(id, next.record("128k").track.mediaId, third.record("128k").track.mediaId), onMain { app.playerController.playbackQueueState.queue.map { it.id } })
            val freshPlaylists = onMain { PlaylistStore(app) }
            assertTrue(freshPlaylists.playlists.first { it.id == playlist.id }.songIds.contains(id))
            check(app.getSharedPreferences("lx-flow-evidence", 0).edit().putString("id", id).putString("playlist", playlist.id).commit())
            stage("15-evidence-committed")
            android.util.Log.i("LX_ONLINE_FLOW", "searchId=${row.id} playlist=${playlist.id} queue=3 position=${onMain { controller.currentPosition }}")
        } finally { onMain { controller.pause(); controller.release() } }
    }

    /** Run in a separate instrumentation invocation after am force-stop, without clearing data. */
    @Test fun replayPersistedPlaylistAfterProcessRestart(): Unit = runBlocking {
        isolated()
        val prefs = app.getSharedPreferences("lx-flow-evidence", 0)
        val id = requireNotNull(prefs.getString("id", null))
        val playlistId = requireNotNull(prefs.getString("playlist", null))
        app.lxSourceOwner.loadOnline()
        assertNotNull(app.lxSourceOwner.findOnline(requireNotNull(RemoteMediaIdCodec.decode(id))))
        val playlist = onMain { app.playlistStore.playlists.first { it.id == playlistId } }
        assertTrue(id in playlist.songIds)
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasText(playlist.name) and hasClickAction()).performScrollTo().performClick()
        val title = app.lxSourceOwner.onlineTracks.value.getValue(id).title
        compose.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag("playlist-content-$playlistId")),
            useUnmergedTree = true,
        ).performClick()
        val controller = connectMediaService(app)
        try {
            await("persisted online playlist replay", 45_000) {
                onMain {
                    controller.playerError?.let { error ->
                        throw AssertionError("restart playerError=${error.errorCodeName}: ${error.message}", error)
                    }
                    controller.currentMediaItem?.mediaId == id && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val artworkUri = assertOnlineArtwork(id)
            assertControllerMetadata(controller, id, artworkUri)
            android.util.Log.i("LX_ONLINE_RESTART", "id=$id position=${onMain { controller.currentPosition }} artwork=ok")
        } finally { onMain { controller.pause(); controller.release() } }
    }

    @Test fun searchChoosePlayKuwoAndPersistPlaylist(): Unit = runBlocking {
        stage("KW-01-test-enter")
        isolated()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
        app.lxSourceOwner.importScript { script }
        stage("KW-02-source-imported platforms=${app.lxSourceOwner.state.value.platforms}")
        assertTrue("XingHai did not expose kw", app.lxSourceOwner.state.value.platforms["kw"].orEmpty().isNotEmpty())
        val playlist = app.playlistStore.createPlaylist("LX 酷我流程 ${System.currentTimeMillis()}")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("在线音乐").performClick()
        compose.onNodeWithTag("online-platform-selector").performClick()
        compose.onNodeWithTag("online-platform-kw").performClick()
        compose.onNodeWithTag("online-query").performTextInput("周杰伦 晴天")
        compose.onNodeWithTag("online-search-submit").performClick()
        compose.waitUntil(25_000) {
            val state = app.lxSourceOwner.onlineSearch.value
            state.platform == "kw" && state.query == "周杰伦 晴天" && !state.busy
        }
        val search = app.lxSourceOwner.onlineSearch.value
        assertNull("Kuwo search failed: ${search.error}", search.error)
        val row = search.results.firstOrNull { it.id == "228908" } ?: search.results.first()
        assertEquals("kw", row.platform)
        assertTrue("128k" in row.qualities)
        val expectedId = row.record("128k").track.mediaId
        stage("KW-03-search-ready id=${row.id} mediaId=$expectedId")
        compose.onNodeWithTag("online-song-${row.id}").performClick()
        compose.onNodeWithTag("online-quality-128k").performClick()
        compose.onNodeWithTag("online-confirm").performClick()
        compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == expectedId } }
        val controller = connectMediaService(app)
        try {
            await("real Kuwo online audio", 45_000) {
                onMain {
                    val currentId = controller.currentMediaItem?.mediaId
                    if (currentId == expectedId) controller.playerError?.let { error ->
                        throw AssertionError("Kuwo playerError=${error.errorCodeName}: ${error.message}", error)
                    }
                    currentId == expectedId && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val artworkUri = assertOnlineArtwork(expectedId)
            assertControllerMetadata(controller, expectedId, artworkUri)
            stage("KW-04-audio-ready id=$expectedId position=${onMain { controller.currentPosition }} artwork=ok")
            compose.onNodeWithTag("online-song-${row.id}").performTouchInput { longClick() }
            compose.onNodeWithText("加入歌单").performClick()
            compose.onNodeWithTag("online-quality-128k").performClick()
            compose.onNodeWithTag("online-action-dialog-list").performScrollToNode(hasTestTag("online-playlist-${playlist.id}"))
            compose.onNodeWithTag("online-playlist-${playlist.id}").performClick()
            compose.onNodeWithTag("online-confirm").performClick()
            compose.waitUntil(10_000) { app.playlistStore.playlists.any { it.id == playlist.id && expectedId in it.songIds } }
            check(app.getSharedPreferences("lx-kw-flow-evidence", 0).edit()
                .putString("id", expectedId).putString("playlist", playlist.id).commit())
            stage("KW-05-playlist-persisted")
        } finally { onMain { controller.pause(); controller.release() } }
    }

    /** Run after force-stop without clearing data. */
    @Test fun replayPersistedKuwoAfterProcessRestart(): Unit = runBlocking {
        isolated()
        val prefs = app.getSharedPreferences("lx-kw-flow-evidence", 0)
        val id = requireNotNull(prefs.getString("id", null))
        val playlistId = requireNotNull(prefs.getString("playlist", null))
        app.lxSourceOwner.loadOnline()
        val ref = requireNotNull(RemoteMediaIdCodec.decode(id))
        assertEquals("kw", lxOnlinePlatformForSource(ref.sourceInstanceId))
        assertNotNull(app.lxSourceOwner.findOnline(ref))
        val playlist = onMain { app.playlistStore.playlists.first { it.id == playlistId } }
        assertTrue(id in playlist.songIds)
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasText(playlist.name) and hasClickAction()).performScrollTo().performClick()
        val title = app.lxSourceOwner.onlineTracks.value.getValue(id).title
        compose.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag("playlist-content-$playlistId")),
            useUnmergedTree = true,
        ).performClick()
        val controller = connectMediaService(app)
        try {
            await("persisted Kuwo playlist replay", 45_000) {
                onMain {
                    controller.playerError?.let { error ->
                        throw AssertionError("Kuwo restart playerError=${error.errorCodeName}: ${error.message}", error)
                    }
                    controller.currentMediaItem?.mediaId == id && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val artworkUri = assertOnlineArtwork(id)
            assertControllerMetadata(controller, id, artworkUri)
            stage("KW-06-restart-ready id=$id position=${onMain { controller.currentPosition }} artwork=ok")
        } finally { onMain { controller.pause(); controller.release() } }
    }
    private suspend fun runXingHaiPlatformFlow(
        platform: String,
        label: String,
        query: String,
        prefsName: String,
        stagePrefix: String,
    ) {
        stage("$stagePrefix-01-test-enter")
        isolated()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
        app.lxSourceOwner.importScript { script }
        stage("$stagePrefix-02-source-imported platforms=${app.lxSourceOwner.state.value.platforms}")
        assertTrue("XingHai did not expose $platform", app.lxSourceOwner.state.value.platforms[platform].orEmpty().isNotEmpty())
        val playlist = app.playlistStore.createPlaylist("LX $label 流程 ${System.currentTimeMillis()}")
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("在线音乐").performClick()
        compose.onNodeWithTag("online-platform-selector").performClick()
        compose.onNodeWithTag("online-platform-$platform").performClick()
        compose.onNodeWithTag("online-query").performTextInput(query)
        compose.onNodeWithTag("online-search-submit").performClick()
        compose.waitUntil(25_000) {
            val state = app.lxSourceOwner.onlineSearch.value
            state.platform == platform && state.query == query && !state.busy
        }
        val search = app.lxSourceOwner.onlineSearch.value
        assertNull("$label search failed: ${search.error}", search.error)
        val row = search.results.first { "128k" in it.qualities }
        assertEquals(platform, row.platform)
        val expectedId = row.record("128k").track.mediaId
        stage("$stagePrefix-03-search-ready id=${row.id} mediaId=$expectedId")
        compose.onNodeWithTag("online-song-${row.id}").performClick()
        compose.onNodeWithTag("online-quality-128k").performClick()
        compose.onNodeWithTag("online-confirm").performClick()
        compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == expectedId } }
        val controller = connectMediaService(app)
        try {
            await("real $label online audio", 45_000) {
                onMain {
                    val currentId = controller.currentMediaItem?.mediaId
                    if (currentId == expectedId) controller.playerError?.let { error ->
                        throw AssertionError("$label playerError=${error.errorCodeName}: ${error.message}", error)
                    }
                    currentId == expectedId && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val artworkUri = assertOnlineArtwork(expectedId)
            assertControllerMetadata(controller, expectedId, artworkUri)
            stage("$stagePrefix-04-audio-ready id=$expectedId position=${onMain { controller.currentPosition }} artwork=ok")
            compose.onNodeWithTag("online-song-${row.id}").performTouchInput { longClick() }
            compose.onNodeWithText("加入歌单").performClick()
            compose.onNodeWithTag("online-quality-128k").performClick()
            compose.onNodeWithTag("online-action-dialog-list").performScrollToNode(hasTestTag("online-playlist-${playlist.id}"))
            compose.onNodeWithTag("online-playlist-${playlist.id}").performClick()
            compose.onNodeWithTag("online-confirm").performClick()
            compose.waitUntil(10_000) { app.playlistStore.playlists.any { it.id == playlist.id && expectedId in it.songIds } }
            check(app.getSharedPreferences(prefsName, 0).edit()
                .putString("id", expectedId).putString("playlist", playlist.id).commit())
            stage("$stagePrefix-05-playlist-persisted")
        } finally {
            onMain { controller.pause(); controller.release() }
        }
    }

    private suspend fun replayXingHaiPlatform(
        platform: String,
        label: String,
        prefsName: String,
        stagePrefix: String,
    ) {
        isolated()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val id = requireNotNull(prefs.getString("id", null))
        val playlistId = requireNotNull(prefs.getString("playlist", null))
        app.lxSourceOwner.loadOnline()
        val ref = requireNotNull(RemoteMediaIdCodec.decode(id))
        assertEquals(platform, lxOnlinePlatformForSource(ref.sourceInstanceId))
        assertNotNull(app.lxSourceOwner.findOnline(ref))
        val playlist = onMain { app.playlistStore.playlists.first { it.id == playlistId } }
        assertTrue(id in playlist.songIds)
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasText(playlist.name) and hasClickAction()).performScrollTo().performClick()
        val title = app.lxSourceOwner.onlineTracks.value.getValue(id).title
        compose.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag("playlist-content-$playlistId")),
            useUnmergedTree = true,
        ).performClick()
        val controller = connectMediaService(app)
        try {
            await("persisted $label playlist replay", 45_000) {
                onMain {
                    controller.playerError?.let { error ->
                        throw AssertionError("$label restart playerError=${error.errorCodeName}: ${error.message}", error)
                    }
                    controller.currentMediaItem?.mediaId == id && controller.playbackState == Player.STATE_READY && controller.currentPosition > 250
                }
            }
            val artworkUri = assertOnlineArtwork(id)
            assertControllerMetadata(controller, id, artworkUri)
            stage("$stagePrefix-06-restart-ready id=$id position=${onMain { controller.currentPosition }} artwork=ok")
        } finally {
            onMain { controller.pause(); controller.release() }
        }
    }

    @Test fun searchChoosePlayNeteaseAndPersistPlaylist(): Unit = runBlocking {
        runXingHaiPlatformFlow("wy", "网易", "周杰伦 晴天", "lx-wy-flow-evidence", "WY")
    }

    @Test fun replayPersistedNeteaseAfterProcessRestart(): Unit = runBlocking {
        replayXingHaiPlatform("wy", "网易", "lx-wy-flow-evidence", "WY")
    }

    @Test fun searchChoosePlayKugouAndPersistPlaylist(): Unit = runBlocking {
        runXingHaiPlatformFlow("kg", "酷狗", "周杰伦 晴天", "lx-kg-flow-evidence", "KG")
    }

    @Test fun replayPersistedKugouAfterProcessRestart(): Unit = runBlocking {
        replayXingHaiPlatform("kg", "酷狗", "lx-kg-flow-evidence", "KG")
    }
    @Test fun searchAutoLoadsNextPageAndKeepsEarlierRows(): Unit = runBlocking {
        isolated()
        OnlineMusicPreferences.setDefaultQuality(app, OnlineMusicDefaultQuality.K128)
        try {
            val script = InstrumentationRegistry.getInstrumentation().context.assets
                .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
            app.lxSourceOwner.importScript { script }
            compose.onNodeWithContentDescription("菜单").performClick()
            compose.onNodeWithText("在线音乐").performClick()
            compose.onNodeWithTag("online-platform-selector").performClick()
            compose.onNodeWithTag("online-platform-kw").performClick()
            compose.onNodeWithTag("online-query").performTextInput("周杰伦")
            compose.onNodeWithTag("online-search-submit").performClick()
            compose.waitUntil(25_000) {
                val state = app.lxSourceOwner.onlineSearch.value
                state.platform == "kw" && state.query == "周杰伦" && !state.busy && state.page == 1 && state.results.size == 20 && state.hasMore
            }
            val firstPage = app.lxSourceOwner.onlineSearch.value.results.toList()
            val firstIds = firstPage.mapTo(linkedSetOf()) { it.id }
            compose.onNodeWithTag("online-results-list").performScrollToNode(hasTestTag("online-song-${firstPage.last().id}"))
            compose.waitUntil(25_000) {
                val state = app.lxSourceOwner.onlineSearch.value
                !state.busy && state.page >= 2 && state.results.size > firstPage.size
            }
            val appended = app.lxSourceOwner.onlineSearch.value.results.first { it.id !in firstIds && "128k" in it.qualities }
            assertTrue(firstIds.all { id -> app.lxSourceOwner.onlineSearch.value.results.any { it.id == id } })
            compose.onNodeWithTag("online-results-list").performScrollToNode(hasTestTag("online-song-${appended.id}"))
            compose.onNodeWithTag("online-song-${appended.id}").performClick()
            val expectedId = appended.record("128k").track.mediaId
            compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == expectedId } }
            stage("PAGING-page=${app.lxSourceOwner.onlineSearch.value.page} rows=${app.lxSourceOwner.onlineSearch.value.results.size} appended=${appended.id}")
        } finally {
            OnlineMusicPreferences.setDefaultQuality(app, OnlineMusicDefaultQuality.ASK)
        }
    }
    @Test fun fixed128kDefaultSkipsQualityPromptAndPlays(): Unit = runBlocking {
        isolated()
        OnlineMusicPreferences.setDefaultQuality(app, OnlineMusicDefaultQuality.K128)
        var controller: androidx.media3.session.MediaController? = null
        try {
            val script = InstrumentationRegistry.getInstrumentation().context.assets
                .open("lx-real/xinghai-music-source.js").bufferedReader().use { it.readText() }
            app.lxSourceOwner.importScript { script }
            compose.onNodeWithContentDescription("菜单").performClick()
            compose.onNodeWithText("在线音乐").performClick()
            compose.onNodeWithTag("online-platform-selector").performClick()
            compose.onNodeWithTag("online-platform-kw").performClick()
            compose.onNodeWithTag("online-query").performTextInput("周杰伦 晴天")
            compose.onNodeWithTag("online-search-submit").performClick()
            compose.waitUntil(25_000) {
                val state = app.lxSourceOwner.onlineSearch.value
                state.platform == "kw" && state.query == "周杰伦 晴天" && !state.busy && state.results.isNotEmpty()
            }
            val row = app.lxSourceOwner.onlineSearch.value.results.first { "128k" in it.qualities }
            val expectedId = row.record("128k").track.mediaId
            compose.onNodeWithTag("online-song-${row.id}").performClick()
            compose.waitUntil(10_000) { app.playerController.playbackQueueState.queue.any { it.id == expectedId } }
            assertTrue("fixed default should skip quality dialog", compose.onAllNodesWithTag("online-confirm").fetchSemanticsNodes().isEmpty())
            controller = connectMediaService(app)
            await("fixed default audio", 45_000) {
                onMain {
                    controller!!.currentMediaItem?.mediaId == expectedId &&
                        controller!!.playbackState == Player.STATE_READY && controller!!.currentPosition > 250
                }
            }
            stage("DEFAULT-128-direct-play id=$expectedId position=${onMain { controller!!.currentPosition }}")
        } finally {
            controller?.let { onMain { it.pause(); it.release() } }
            OnlineMusicPreferences.setDefaultQuality(app, OnlineMusicDefaultQuality.ASK)
        }
    }

}
