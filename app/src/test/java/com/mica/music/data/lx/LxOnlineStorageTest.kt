package com.mica.music.data.lx

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.mica.music.data.PlaylistStore
import com.mica.music.data.remote.toPlaybackSong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LxOnlineStorageTest {
    @Test fun `source replacement between durable admission and playlist handoff fences actual playlist write`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disk = AndroidLxOnlineStore(context)
        val scripts = AndroidLxScriptStore(context)
        val owner = LxSourceOwner(scripts, disk) { object : LxRuntime {
            override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("128k"))
            override suspend fun musicUrl(source: String, quality: String, musicInfo: String) = "https://example.org/audio"
            override fun close() = Unit
        } }
        val playlistStore = PlaylistStore(context)
        val playlist = playlistStore.createPlaylist("Interleaving")
        owner.importScript { "/**\n * @name Old\n */" }
        val row = LxOnlineResult("abcd", "Song", "Artist", "Album", 180,
            """{"source":"tx","songmid":"abcd","strMediaMid":"media"}""", listOf("128k"))
        owner.searchOnline("Song", 1) { _, _ -> listOf(row) }
        val durable = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            owner.admitOnline(row, "128k")
            durable.complete(Unit)
            release.await() // Main dispatcher handoff after durable admission.
            runCatching {
                var admittedId: String? = null
                owner.useOnline(row, "128k") { admittedId = it.mediaId }
                playlistStore.addSongToPlaylist(playlist.id, requireNotNull(admittedId))
            }
        }
        durable.await()
        owner.importScript { "/**\n * @name New\n */" }
        assertEquals("New", owner.state.value.script!!.metadata.name)
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        val reloadedPlaylists = PlaylistStore(context)
        reloadedPlaylists.awaitReady()
        assertTrue(reloadedPlaylists.playlists.first { it.id == playlist.id }.songIds.isEmpty())
        disk.close()
        val reopened = AndroidLxOnlineStore(context)
        assertEquals(row.musicInfo, reopened.read(row.record("128k").track.ref)!!.musicInfo)
        assertEquals("New", AndroidLxScriptStore(context).read()!!.metadata.name)
        reopened.close(); owner.close()
    }

    @Test fun `late noncancelable search cannot write SQLite after a newer query`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disk = AndroidLxOnlineStore(context)
        val owner = LxSourceOwner(AndroidLxScriptStore(context), disk) { object : LxRuntime {
            override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("128k"))
            override suspend fun musicUrl(source: String, quality: String, musicInfo: String) = ""
            override fun close() = Unit
        } }
        owner.importScript { "/**\n * @name One\n */" }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val row = LxOnlineResult("old", "Old", "", "", 1, "{}", listOf("128k"))
        val old = async { runCatching {
            owner.searchOnline("old", 1) { _, _ -> entered.complete(Unit); withContext(NonCancellable) { release.await() }; listOf(row) }
            owner.admitOnline(row, "128k")
        } }
        entered.await()
        owner.searchOnline("new", 1) { _, _ -> emptyList() }
        release.complete(Unit); old.await()
        disk.close()
        AndroidLxOnlineStore(context).use { assertNull(it.read(row.record("128k").track.ref)) }
        assertEquals("new", owner.onlineSearch.value.query)
        owner.close()
    }
    @Test fun `online metadata refresh keeps stable identity and derives safe artwork plus quality`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disk = AndroidLxOnlineStore(context)
        val old = LxOnlineResult(
            "metaRefresh123", "Song", "Artist", "Album", 180,
            """{"source":"tx","songmid":"metaRefresh123","img":"https://evil.example/cover.jpg"}""",
            listOf("128k"),
        ).record("128k")
        val freshInfo = """{"source":"tx","songmid":"metaRefresh123","albumId":"album-1","img":"https://y.gtimg.cn/music/photo_new/cover.jpg"}"""
        val fresh = LxOnlineResult(
            "metaRefresh123", "Song", "Artist", "Album", 180,
            freshInfo, listOf("128k"),
        ).record("128k")

        assertNull(lxOnlineArtworkUrl("tx", old.musicInfo))
        assertEquals("https://y.gtimg.cn/music/photo_new/cover.jpg", lxOnlineArtworkUrl("tx", freshInfo))
        assertNull(lxOnlineArtworkUrl("tx", """{"img":"https://y.gtimg.cn.evil.example/cover.jpg"}"""))
        assertEquals(old.track.mediaId, fresh.track.mediaId)

        disk.write(old)
        disk.write(fresh)
        val stored = requireNotNull(disk.read(fresh.track.ref))
        assertEquals(fresh.track.mediaId, stored.track.mediaId)
        assertEquals(freshInfo, stored.musicInfo)
        assertEquals(fresh.track.ref.opaqueTrackId, stored.track.artworkOpaqueId)
        assertEquals("album-1", stored.track.albumOpaqueId)
        assertEquals("audio/mpeg", stored.track.mimeTypeHint)
        assertEquals("mp3", stored.track.suffix)
        assertEquals(128, stored.track.bitrateKbps)
        assertNull(stored.track.bitsPerSample)
        disk.close()

        AndroidLxOnlineStore(context).use { reopened ->
            val restored = requireNotNull(reopened.read(fresh.track.ref))
            assertEquals(freshInfo, restored.musicInfo)
            assertEquals(fresh.track.ref.opaqueTrackId, restored.track.artworkOpaqueId)
            assertEquals("audio/mpeg", restored.track.mimeTypeHint)
            assertEquals(128, restored.track.bitrateKbps)
        }
    }


    @Test fun `online quality projects only metadata we can prove`() {
        fun track(quality: String) = LxOnlineResult(
            "qualityMeta123", "Song", "Artist", "Album", 180,
            """{"source":"wy","songmid":"qualityMeta123"}""",
            listOf(quality), platform = "wy",
        ).record(quality).track

        val low = track("128k")
        assertEquals("audio/mpeg", low.mimeTypeHint)
        assertEquals("mp3", low.suffix)
        assertEquals(128, low.bitrateKbps)
        assertNull(low.bitsPerSample)

        val high = track("320k")
        assertEquals("audio/mpeg", high.mimeTypeHint)
        assertEquals(320, high.bitrateKbps)
        assertNull(high.bitsPerSample)

        val lossless = track("flac")
        assertEquals("audio/flac", lossless.mimeTypeHint)
        assertEquals("flac", lossless.suffix)
        assertEquals(0, lossless.bitrateKbps)
        assertNull(lossless.bitsPerSample)

        val hiRes = track("flac24bit")
        assertEquals("audio/flac", hiRes.mimeTypeHint)
        assertEquals("flac", hiRes.suffix)
        assertEquals(0, hiRes.bitrateKbps)
        assertEquals(24, hiRes.bitsPerSample)
        assertEquals(0, hiRes.sampleRateHz)
    }

    @Test fun tenThousandSavedSongsStayMetadataOnlyAndEnforceCapacity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidLxOnlineStore(context).use { store ->
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM tracks")
                val info = "{\"source\":\"tx\",\"songmid\":\"capacity\",\"padding\":\"" + "x".repeat(8000) + "\"}"
                db.compileStatement("INSERT INTO tracks VALUES(?,?,?,?,?,?,?)").use { statement ->
                    repeat(10_000) { index ->
                        statement.bindString(1, "C" + index + ":flac")
                        statement.bindString(2, "T".repeat(256))
                        statement.bindString(3, "A".repeat(256))
                        statement.bindString(4, "B".repeat(256))
                        statement.bindLong(5, 180)
                        statement.bindString(6, "flac")
                        statement.bindString(7, info)
                        statement.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val summaries = store.summaries()
            assertEquals(10_000, summaries.size)
            summaries.forEach { summary ->
                val song = summary.toPlaybackSong()
                assertFalse(song.lyricsLoaded)
                assertTrue(song.lyricsDocument.lines.isEmpty())
            }
            val extra = LxOnlineResult("overflow", "Song", "", "", 1, "{}", listOf("flac"))
            assertTrue(runCatching { store.write(extra.record("flac")) }.isFailure)
            assertEquals(10_000, store.summaries().size)
        }
    }
}
