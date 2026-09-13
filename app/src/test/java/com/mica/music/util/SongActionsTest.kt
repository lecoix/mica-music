package com.mica.music.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.Song
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongActionsTest {
    @Test
    fun deleteSongEverywhereRemovesCurrentPlayingSongFromQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val current = SongFixtures.song("current")
        val next = SongFixtures.song("next")
        var liveQueue = listOf(current, next)
        var persistExclusion: Boolean? = null

        val result = deleteSongEverywhere(
            context = context,
            song = current,
            removeFromLibrary = { _, persist -> persistExclusion = persist; true },
            removeFromAllPlaylists = { true },
            removeFromQueue = { songId ->
                val before = liveQueue.size
                liveQueue = liveQueue.filterNot { it.id == songId }
                liveQueue.size != before
            },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.fileDeleted)
        assertFalse(persistExclusion!!)
        assertTrue(result.playlistCleanupSucceeded)
        assertTrue(result.queueChanged)
        assertEquals("已从设备删除", result.message)
        assertEquals(listOf(next), liveQueue)
    }

    @Test
    fun deleteSongEverywhereRemovesNonCurrentSongFromQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val current = SongFixtures.song("current")
        val middle = SongFixtures.song("middle")
        val tail = SongFixtures.song("tail")
        var liveQueue = listOf(current, middle, tail)

        val result = deleteSongEverywhere(
            context = context,
            song = middle,
            removeFromLibrary = { _, persist -> assertFalse(persist); true },
            removeFromAllPlaylists = { true },
            removeFromQueue = { songId ->
                val before = liveQueue.size
                liveQueue = liveQueue.filterNot { it.id == songId }
                liveQueue.size != before
            },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.fileDeleted)
        assertTrue(result.queueChanged)
        assertEquals(listOf(current, tail), liveQueue)
    }

    @Test
    fun deleteSongEverywhereTargetsLatestQueueAfterSuspendingCleanup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        val oldOther = SongFixtures.song("old-other")
        val newlyQueued = SongFixtures.song("newly-queued")
        var liveQueue = listOf(target, oldOther)

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            removeFromLibrary = { _, _ -> true },
            removeFromAllPlaylists = {
                liveQueue = listOf(target, oldOther, newlyQueued)
                true
            },
            removeFromQueue = { songId ->
                val before = liveQueue.size
                liveQueue = liveQueue.filterNot { it.id == songId }
                liveQueue.size != before
            },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.queueChanged)
        assertEquals(listOf(oldOther, newlyQueued), liveQueue)
    }

    @Test
    fun deleteSongEverywhereRemovesFromLiveQueueAfterConcurrentMutation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val current = SongFixtures.song("current")
        val target = SongFixtures.song("target")
        val other = SongFixtures.song("other")
        val inserted = SongFixtures.song("inserted")
        var queue = listOf(current, target, other)

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            removeFromLibrary = { _, _ ->
                queue = listOf(current, inserted, target, other)
                true
            },
            removeFromAllPlaylists = { true },
            removeFromQueue = { id ->
                val changed = queue.any { it.id == id }
                queue = queue.filterNot { it.id == id }
                changed
            },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.queueChanged)
        assertEquals(listOf(current, inserted, other), queue)
    }

    @Test
    fun deleteSongEverywhereKeepsRemovalFlowWhenFileDeleteFails() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        val other = SongFixtures.song("other")
        var removedFromLibrary: String? = null
        var removedFromPlaylists: String? = null
        var liveQueue = listOf(other, target)

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            removeFromLibrary = { removed, persist ->
                removedFromLibrary = removed.id
                assertTrue(persist)
                true
            },
            removeFromAllPlaylists = { songId -> removedFromPlaylists = songId; true },
            removeFromQueue = { songId ->
                val before = liveQueue.size
                liveQueue = liveQueue.filterNot { it.id == songId }
                liveQueue.size != before
            },
            deleteFile = { _, _ -> false },
        )

        assertFalse(result.fileDeleted)
        assertTrue(result.queueChanged)
        assertEquals("已从曲库移除（无法删除文件）", result.message)
        assertEquals("target", removedFromLibrary)
        assertEquals("target", removedFromPlaylists)
        assertEquals(listOf(other), liveQueue)
    }

    @Test
    fun deleteSongEverywhereReportsPlaylistCleanupFailureButStillRemovesQueueReference() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        var liveQueue = listOf(target)

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            removeFromLibrary = { _, _ -> true },
            removeFromAllPlaylists = { false },
            removeFromQueue = { songId ->
                val before = liveQueue.size
                liveQueue = liveQueue.filterNot { it.id == songId }
                liveQueue.size != before
            },
            deleteFile = { _, _ -> true },
        )

        assertFalse(result.playlistCleanupSucceeded)
        assertTrue(result.queueChanged)
        assertTrue(liveQueue.isEmpty())
        assertEquals("已从设备删除，但部分歌单引用清理失败", result.message)
    }

    @Test
    fun deleteSongEverywhereKeepsReferencesWhenBothDeletePathsFail() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        var playlistRemovalCalls = 0
        var queueRemovalCalls = 0

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            removeFromLibrary = { _, persist -> assertTrue(persist); false },
            removeFromAllPlaylists = { playlistRemovalCalls++; true },
            removeFromQueue = { queueRemovalCalls++; true },
            deleteFile = { _, _ -> false },
        )

        assertFalse(result.fileDeleted)
        assertFalse(result.libraryRemoved)
        assertTrue(result.playlistCleanupSucceeded)
        assertFalse(result.queueChanged)
        assertEquals("无法删除文件或从曲库移除", result.message)
        assertEquals(0, playlistRemovalCalls)
        assertEquals(0, queueRemovalCalls)
    }

    @Test
    fun lyricoEditTagIntentUsesReadOnlyGrantForMediaStoreUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://media/external/audio/media/42")

        val intent = buildLyricoEditTagIntent(
            context = context,
            title = "Track",
            uri = uri,
        )

        assertEquals(LYRICO_EDIT_TAG_ACTION, intent.action)
        assertEquals(LYRICO_PACKAGE_NAME, intent.`package`)
        assertEquals(uri, intent.data)
        assertEquals("audio/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
    }

    @Test
    fun lyricoEditTagIntentKeepsWriteGrantForDocumentProviderUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FTrack.flac",
        )

        val intent = buildLyricoEditTagIntent(
            context = context,
            title = "Track",
            uri = uri,
        )

        assertEquals(LYRICO_EDIT_TAG_ACTION, intent.action)
        assertEquals(LYRICO_PACKAGE_NAME, intent.`package`)
        assertEquals(uri, intent.data)
        assertEquals("audio/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
    }
}
