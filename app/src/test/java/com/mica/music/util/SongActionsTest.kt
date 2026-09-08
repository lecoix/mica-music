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
        var updatedQueue: List<Song>? = null
        var persistExclusion: Boolean? = null

        val result = deleteSongEverywhere(
            context = context,
            song = current,
            currentQueue = listOf(current, next),
            removeFromLibrary = { _, persist -> persistExclusion = persist; true },
            removeFromAllPlaylists = {},
            setQueue = { updatedQueue = it },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.fileDeleted)
        assertFalse(persistExclusion!!)
        assertTrue(result.queueChanged)
        assertEquals("已从设备删除", result.message)
        assertEquals(listOf(next), updatedQueue)
    }

    @Test
    fun deleteSongEverywhereRemovesNonCurrentSongFromQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val current = SongFixtures.song("current")
        val middle = SongFixtures.song("middle")
        val tail = SongFixtures.song("tail")
        var updatedQueue: List<Song>? = null

        val result = deleteSongEverywhere(
            context = context,
            song = middle,
            currentQueue = listOf(current, middle, tail),
            removeFromLibrary = { _, persist -> assertFalse(persist); true },
            removeFromAllPlaylists = {},
            setQueue = { updatedQueue = it },
            deleteFile = { _, _ -> true },
        )

        assertTrue(result.fileDeleted)
        assertTrue(result.queueChanged)
        assertEquals(listOf(current, tail), updatedQueue)
    }

    @Test
    fun deleteSongEverywhereKeepsRemovalFlowWhenFileDeleteFails() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        val other = SongFixtures.song("other")
        var removedFromLibrary: String? = null
        var removedFromPlaylists: String? = null
        var updatedQueue: List<Song>? = null

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            currentQueue = listOf(other, target),
            removeFromLibrary = { removed, persist ->
                removedFromLibrary = removed.id
                assertTrue(persist)
                true
            },
            removeFromAllPlaylists = { removedFromPlaylists = it },
            setQueue = { updatedQueue = it },
            deleteFile = { _, _ -> false },
        )

        assertFalse(result.fileDeleted)
        assertTrue(result.queueChanged)
        assertEquals("已从曲库移除（无法删除文件）", result.message)
        assertEquals("target", removedFromLibrary)
        assertEquals("target", removedFromPlaylists)
        assertEquals(listOf(other), updatedQueue)
    }

    @Test
    fun deleteSongEverywhereKeepsReferencesWhenBothDeletePathsFail() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = SongFixtures.song("target")
        val other = SongFixtures.song("other")
        var playlistRemovalCalls = 0
        var queueSetCalls = 0

        val result = deleteSongEverywhere(
            context = context,
            song = target,
            currentQueue = listOf(other, target),
            removeFromLibrary = { _, persist -> assertTrue(persist); false },
            removeFromAllPlaylists = { playlistRemovalCalls++ },
            setQueue = { queueSetCalls++ },
            deleteFile = { _, _ -> false },
        )

        assertFalse(result.fileDeleted)
        assertFalse(result.libraryRemoved)
        assertFalse(result.queueChanged)
        assertEquals("无法删除文件或从曲库移除", result.message)
        assertEquals(0, playlistRemovalCalls)
        assertEquals(0, queueSetCalls)
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
