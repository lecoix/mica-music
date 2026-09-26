package com.mica.music.ui.screens.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.*
import com.mica.music.data.remote.smb.*
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h900dp-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class SmbBrowserFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MicaDatabase.resetForTests()
        context.deleteDatabase(MicaDatabase.DATABASE_NAME)
        context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { MicaDatabase.resetForTests() }

    @Test fun connectionDirectoryPlaySelectPlaylistAndOfflineRefreshUseRealOwners() {
        val repo = RemoteCatalogRepository(context)
        val store = PlaylistStore(context)
        val playlist = runBlocking {
            store.awaitReady()
            repo.upsertSource(RemoteSourceInstance("smb", RemoteSourceType.SMB, "Router", "smb://router/share", "cred"))
            store.createPlaylist("Travel")
        }
        val listed = Collections.synchronizedList(mutableListOf<String>())
        var offline = false
        var queue = emptyList<Song>()
        val refreshRequest = mutableIntStateOf(0)
        var infoActions = SmbBrowserInfoActions()
        val browser = SmbDirectoryBrowser(repo, SecureRemoteCredentialStore {
            RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
        }, SmbSessionFactory { _, _ -> object : SmbSessionHandle {
            override fun close() = Unit
            override fun openFile(serverPath: String): SmbRandomAccessFile = error("UI browse must not read payloads")
            override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No full list API")
            override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                if (offline) throw SmbException(SmbFailureKind.CONNECT, "Offline")
                listed += serverPath
                val entries = if (serverPath.isEmpty()) listOf(SmbDirectoryEntry("Album", true, 0)) else listOf(
                    SmbDirectoryEntry("Track2.flac", false, 1024, "2"),
                    SmbDirectoryEntry("Track1.flac", false, 1024, "1"),
                    SmbDirectoryEntry("Child", true, 0),
                )
                entries.forEach { consume(it) }
            }
        } })
        compose.setContent { MicaTheme {
            SmbBrowserContent(
                repo,
                store,
                browser,
                onPlay = { songs, _ -> queue = songs },
                bottomPadding = 0.dp,
                onBack = {},
                refreshRequestKey = refreshRequest.intValue,
                onInfoActionsChange = { infoActions = it },
            )
        } }
        waitText("Router")
        compose.runOnIdle {
            assertEquals("返回远程歌曲", infoActions.backLabel)
            assertFalse(infoActions.showRefresh)
        }
        compose.onNodeWithText("返回远程歌曲").assertDoesNotExist()
        compose.onNodeWithText("Router").performClick()
        waitText("Album")
        compose.runOnIdle {
            assertEquals("返回上级", infoActions.backLabel)
            assertTrue(infoActions.showRefresh)
            assertTrue(infoActions.refreshEnabled)
        }
        compose.onNodeWithText("刷新本目录").assertDoesNotExist()
        compose.onNodeWithText("Album").performClick()
        waitText("Track1.flac")
        compose.onRoot().captureRoboImage("build/reports/smb-browser.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("Track1.flac").performClick()
        compose.waitUntil(10_000) { queue.isNotEmpty() }
        assertEquals(listOf("Track1.flac"), queue.map { it.fileName })
        compose.onNodeWithText("全选本层").performClick()
        compose.onNodeWithText("添加 2 首到歌单").performClick()
        compose.onNode(isDialog()).captureRoboImage("build/reports/smb-playlist-dialog.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("Travel").performClick()
        waitText("已添加 2 首歌曲")
        assertEquals(listOf("", "Album"), listed.toList())
        runBlocking {
            val cold = PlaylistStore(context)
            cold.awaitReady()
            assertEquals(2, cold.playlistById(playlist.id)!!.songIds.size)
            assertTrue(repo.tracksForEnabledSources().isEmpty())
            assertEquals(2, repo.find(cold.playlistById(playlist.id)!!.songIds.map { RemoteMediaIdCodec.decode(it)!! }).size)
        }
        compose.onNodeWithText("添加 2 首到歌单").performClick()
        compose.onNodeWithContentDescription("新歌单名称").performTextInput("New SMB playlist")
        compose.onNodeWithText("创建并添加").performClick()
        compose.waitUntil(10_000) { store.playlists.any { it.name == "New SMB playlist" && it.songIds.size == 2 } }
        offline = true
        compose.runOnIdle { refreshRequest.intValue++ }
        waitText("读取失败，请检查网络后重试")
        compose.onNodeWithText("Track1.flac").assertExists()
        compose.onNodeWithText("Track2.flac").assertExists()
        assertEquals(2, store.playlistById(playlist.id)!!.songIds.size)
    }

    @Test fun folderLibraryUiAddsRefreshesAndRemovesCurrentDirectory() {
        val repo = RemoteCatalogRepository(context)
        val store = PlaylistStore(context)
        runBlocking {
            store.awaitReady()
            repo.upsertSource(
                RemoteSourceInstance(
                    "smb-index",
                    RemoteSourceType.SMB,
                    "Router Index",
                    "smb://router/share",
                    "cred-index",
                ),
            )
        }
        val credentialStore = SecureRemoteCredentialStore {
            RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
        }
        val browser = SmbDirectoryBrowser(
            repo,
            credentialStore,
            SmbSessionFactory { _, _ ->
                object : SmbSessionHandle {
                    override fun close() = Unit
                    override fun openFile(serverPath: String): SmbRandomAccessFile =
                        error("Browse must not read payloads")
                    override fun list(serverPath: String): List<SmbDirectoryEntry> =
                        error("No materialized list")
                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        val entries = if (serverPath.isEmpty()) {
                            listOf(SmbDirectoryEntry("Album", true, 0))
                        } else {
                            listOf(
                                SmbDirectoryEntry("Track1.flac", false, 1024, "1"),
                                SmbDirectoryEntry("Track2.flac", false, 1024, "2"),
                            )
                        }
                        entries.forEach { consume(it) }
                    }
                }
            },
        )
        val indexedDirectories = Collections.synchronizedList(mutableListOf<String>())
        val indexer = SmbFolderLibraryIndexer(
            repo,
            credentialStore,
            SmbSessionFactory { _, _ ->
                object : SmbSessionHandle {
                    override fun close() = Unit
                    override fun openFile(serverPath: String): SmbRandomAccessFile =
                        error("Folder indexing must not read payloads")
                    override fun list(serverPath: String): List<SmbDirectoryEntry> =
                        error("Folder indexing must stream")
                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        indexedDirectories += serverPath
                        assertEquals("Album", serverPath)
                        assertTrue(consume(SmbDirectoryEntry("Track1.flac", false, 1024, "1")))
                        assertTrue(consume(SmbDirectoryEntry("Track2.flac", false, 1024, "2")))
                    }
                }
            },
        )

        compose.setContent {
            MicaTheme {
                SmbBrowserContent(
                    repo = repo,
                    playlists = store,
                    browser = browser,
                    onPlay = { _, _ -> },
                    bottomPadding = 0.dp,
                    onBack = {},
                    folderIndexer = indexer,
                )
            }
        }

        waitText("Router Index")
        compose.onNodeWithText("Router Index").performClick()
        waitText("Album")
        compose.onNodeWithText("Album").performClick()
        waitText("Track1.flac")
        compose.onNodeWithText("加入曲库").performClick()
        waitText("加入当前目录到曲库")
        compose.onAllNodesWithText("加入曲库")[1].performClick()
        waitText("已加入曲库，共 2 首")
        waitText("已建库 · 仅本层")
        assertEquals(listOf("Album"), indexedDirectories.toList())
        runBlocking {
            assertEquals(
                setOf("Album/Track1.flac", "Album/Track2.flac"),
                repo.tracksForSource("smb-index").map { it.ref.opaqueTrackId }.toSet(),
            )
        }

        compose.onNodeWithText("刷新曲库").performClick()
        compose.waitUntil(10_000) { indexedDirectories.size == 2 }
        waitText("曲库已刷新，共 2 首")
        assertEquals(listOf("Album", "Album"), indexedDirectories.toList())

        compose.onNodeWithText("移出曲库").performClick()
        waitText("已从曲库移除此目录")
        compose.waitUntil(10_000) {
            runBlocking { repo.tracksForSource("smb-index").isEmpty() }
        }
        waitText("加入曲库")
    }

    private fun waitText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
}
