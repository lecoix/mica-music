package com.mica.music.data.lx

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LxOnlineOwnerTest {
    private val row = LxOnlineResult("002tjmVF1Ce09U", "卡农", "失重", "专辑", 120,
        """{"source":"tx","songmid":"002tjmVF1Ce09U","strMediaMid":"000aJEne4IpPYR","name":"卡农"}""", listOf("128k"))
    private class Scripts : LxScriptStore {
        var value: LxStoredScript? = null
        override fun read() = value
        override fun write(value: LxStoredScript?) { this.value = value }
    }
    private fun owner(scripts: Scripts = Scripts(), songs: LxOnlineStore = MemoryLxOnlineStore()) =
        LxSourceOwner(scripts, songs) { object : LxRuntime {
            override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("128k"))
            override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String {
                assertTrue(musicInfo.contains("strMediaMid"))
                return "https://example.org/fresh.mp3"
            }
            override fun close() = Unit
        } }

    @Test fun `late search cannot publish or admit after newer query source mutation or exit`() = runBlocking {
        for (change in 0..2) {
            val disk = MemoryLxOnlineStore()
            val owner = owner(songs = disk)
            owner.importScript { "/**\n * @name Test\n */" }
            val entered = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<List<LxOnlineResult>>()
            val pending = async { runCatching { owner.searchOnline("old", 1) { _, _ ->
                entered.complete(Unit); answer.await()
            } } }
            entered.await()
            when (change) {
                0 -> owner.searchOnline("new", 1) { _, _ -> emptyList() }
                1 -> owner.delete()
                else -> owner.leaveOnline()
            }
            answer.complete(listOf(row))
            pending.await()
            assertTrue(owner.onlineSearch.value.results.isEmpty())
            assertTrue(runCatching { owner.admitOnline(row, "128k") }.isFailure)
            assertTrue(disk.summaries().isEmpty())
        }
    }

    @Test fun `later search pages append deduplicate and preserve prior row identity`() = runBlocking {
        val owner = owner()
        owner.importScript { "/**\n * @name Test\n */" }
        val firstPage = (1..20).map { index ->
            row.copy(id = "A${index.toString().padStart(2, '0')}")
        }
        owner.searchOnline("tx", "卡农", 1) { _, _ -> firstPage }
        assertEquals(20, owner.onlineSearch.value.results.size)
        assertTrue(owner.onlineSearch.value.hasMore)

        val extra = row.copy(id = "B21")
        owner.searchOnline("tx", "卡农", 2, append = true) { _, _ -> listOf(firstPage.last(), extra) }
        val state = owner.onlineSearch.value
        assertEquals(2, state.page)
        assertEquals(21, state.results.size)
        assertSame(firstPage.first(), state.results.first())
        assertSame(extra, state.results.last())
        assertFalse(state.hasMore)
    }
    @Test fun `durable identity survives owner restart and source replacement without persisting URL`() = runBlocking {
        val scripts = Scripts()
        val disk = MemoryLxOnlineStore()
        val first = owner(scripts, disk)
        first.importScript { "/**\n * @name One\n */" }
        first.searchOnline("卡农", 1) { _, _ -> listOf(row) }
        val selected = first.admitOnline(row, "128k")
        first.close()
        val second = owner(scripts, disk)
        second.restore()
        assertEquals(selected, second.findOnline(selected.ref))
        second.importScript { "/**\n * @name Two\n */" }
        assertNotNull(second.resolve(selected.mediaId))
        assertFalse(disk.read(selected.ref)!!.musicInfo.contains("fresh.mp3"))
        second.setEnabled(false)
        assertTrue(runCatching { second.resolve(selected.mediaId) }.isFailure)
        assertEquals(selected, second.findOnline(selected.ref))
    }

    @Test fun `failed durable admission cannot publish a song or run playlist side effect`() = runBlocking {
        val disk = object : LxOnlineStore {
            override fun summaries() = emptyList<com.mica.music.data.remote.RemoteTrackSummary>()
            override fun read(ref: com.mica.music.data.remote.RemoteTrackRef): LxOnlineRecord? = null
            override fun write(record: LxOnlineRecord) { throw java.io.IOException("full") }
        }
        val owner = owner(songs = disk)
        owner.importScript { "/**\n * @name One\n */" }
        owner.searchOnline("卡农", 1) { _, _ -> listOf(row) }
        assertTrue(runCatching { owner.admitOnline(row, "128k") }.isFailure)
        assertTrue(owner.onlineTracks.value.isEmpty())
    }
    @Test fun `online resolve lazily rebuilds failed runtime once after process restart`() = runBlocking {
        val scripts = Scripts().apply {
            value = LxStoredScript("persisted", "/**\n * @name Persisted\n */", true)
        }
        val disk = MemoryLxOnlineStore()
        val persisted = row.record("128k")
        disk.write(persisted)
        var created = 0
        val owner = LxSourceOwner(scripts, disk) {
            val index = ++created
            object : LxRuntime {
                override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("128k"))
                override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String {
                    assertEquals("tx", source)
                    assertEquals("128k", quality)
                    assertTrue(musicInfo.contains("strMediaMid"))
                    if (index == 1) throw java.io.IOException("stale runtime")
                    return "https://example.org/recovered.mp3"
                }
                override fun close() = Unit
            }
        }

        val request = owner.resolve(persisted.track.mediaId)

        assertNotNull(request)
        assertEquals("https://example.org/recovered.mp3", request!!.url)
        assertEquals(2, created)
        assertEquals(persisted.track, owner.findOnline(persisted.track.ref))
    }

    @Test fun `runtime recovery does not invalidate independent online search`() = runBlocking {
        val scripts = Scripts().apply {
            value = LxStoredScript("persisted", "/**\n * @name Persisted\n */", true)
        }
        val disk = MemoryLxOnlineStore().apply { write(row.record("128k")) }
        var created = 0
        val owner = LxSourceOwner(scripts, disk) {
            val index = ++created
            object : LxRuntime {
                override suspend fun initialize(script: LxStoredScript) = mapOf("tx" to listOf("128k"))
                override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String {
                    if (index == 1) throw java.io.IOException("retire this runtime")
                    return "https://example.org/recovered.mp3"
                }
                override fun close() = Unit
            }
        }
        owner.restore()
        val searchEntered = CompletableDeferred<Unit>()
        val searchAnswer = CompletableDeferred<List<LxOnlineResult>>()
        val searching = async {
            owner.searchOnline("same generation", 1) { _, _ ->
                searchEntered.complete(Unit)
                searchAnswer.await()
            }
        }
        searchEntered.await()
        val persisted = disk.summaries().single()
        assertNotNull(owner.resolve(persisted.mediaId))
        searchAnswer.complete(listOf(row))
        searching.await()
        assertFalse(owner.onlineSearch.value.busy)
        assertNull(owner.onlineSearch.value.error)
        assertEquals(listOf(row), owner.onlineSearch.value.results)
        assertEquals(2, created)
    }}
