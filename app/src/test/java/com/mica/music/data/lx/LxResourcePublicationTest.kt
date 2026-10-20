package com.mica.music.data.lx

import com.mica.music.data.LyricsDocument
import com.mica.music.data.scanner.LrcParser
import com.mica.music.data.remote.RemoteArtworkRef
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LxResourcePublicationTest {
    private val row = LxOnlineResult("A01", "Song", "Artist", "Album", 10,
        """{"source":"tx","songmid":"A01","img":"https://y.gtimg.cn/old.jpg"}""", listOf("flac"))
    private fun owner(): LxSourceOwner = LxSourceOwner(object : LxScriptStore {
        override fun read(): LxStoredScript? = null
        override fun write(value: LxStoredScript?) = Unit
    }) { object : LxRuntime {
        override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("flac"))
        override suspend fun musicUrl(source: String, quality: String, musicInfo: String) = "https://example.org/audio.flac"
        override fun close() = Unit
    } }
    private suspend fun admit(owner: LxSourceOwner, value: LxOnlineResult = row) = run {
        owner.searchOnline("song", 1) { _, _ -> listOf(value) }
        owner.admitOnline(value, "flac")
    }

    @Test fun staleLyricsCannotRepopulateCacheAfterSourceReplacement() = runBlocking {
        val owner = owner()
        owner.importScript { "/**\n * @name One\n */" }
        val track = admit(owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async { runCatching { owner.onlineLyrics(track.ref, loader = {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
            LrcParser.parseDocument("[00:01.00]old")
        }) } }
        entered.await()
        owner.importScript { "/**\n * @name Two\n */" }
        val fresh = owner.onlineLyrics(track.ref, loader = { LrcParser.parseDocument("[00:01.00]new") })
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertSame(fresh, owner.onlineLyrics(track.ref, loader = { error("fresh cache was lost") }))
        owner.close()
    }

    @Test fun invalidatedArtworkCannotWriteToObserver() = runBlocking {
        val owner = owner()
        owner.importScript { "/**\n * @name One\n */" }
        val track = admit(owner)
        val ref = RemoteArtworkRef(track.ref.sourceInstanceId, track.ref.opaqueTrackId)
        val oldBytes = requireNotNull(owner.onlineArtwork(ref) { byteArrayOf(1) })
        val output = java.io.ByteArrayOutputStream()
        owner.importScript { "/**\n * @name Two\n */" }
        val freshBytes = requireNotNull(owner.onlineArtwork(ref) { byteArrayOf(2) })
        owner.publishOnlineArtwork(ref, freshBytes) { output.write(freshBytes) }
        assertTrue(runCatching { owner.publishOnlineArtwork(ref, oldBytes) { output.write(oldBytes) } }.isFailure)
        assertArrayEquals(byteArrayOf(2), output.toByteArray())
        owner.close()
    }

    @Test fun staleArtworkCannotRepopulateCacheAfterRecordRefreshOrClose() = runBlocking {
        for (close in listOf(false, true)) {
            val owner = owner()
            owner.importScript { "/**\n * @name One\n */" }
            val track = admit(owner)
            val ref = RemoteArtworkRef(track.ref.sourceInstanceId, track.ref.opaqueTrackId)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = async { runCatching { owner.onlineArtwork(ref) {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; byteArrayOf(1)
            } } }
            entered.await()
            if (close) owner.close() else {
                admit(owner, row.copy(musicInfo = row.musicInfo.replace("old.jpg", "new.jpg")))
                assertArrayEquals(byteArrayOf(2), owner.onlineArtwork(ref) { url ->
                    assertTrue(url.endsWith("new.jpg")); byteArrayOf(2)
                })
            }
            release.complete(Unit)
            assertTrue(old.await().isFailure)
            if (!close) {
                assertArrayEquals(byteArrayOf(2), owner.onlineArtwork(ref) { error("fresh cache was lost") })
                owner.close()
            }
        }
    }
}
