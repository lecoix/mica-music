package com.mica.music.data.lx

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LxSourceOwnerTest {
    private class Store : LxScriptStore {
        var saved: LxStoredScript? = null
        override fun read() = saved
        override fun write(value: LxStoredScript?) { saved = value }
    }
    private class Runtime : LxRuntime {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<String>()
        var closed = false
        override suspend fun initialize(script: LxStoredScript): Map<String, List<String>> {
            started.complete(Unit)
            answer.await()
            return mapOf("wy" to listOf("flac"))
        }
        override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String = answer.await()
        override fun close() { closed = true }
    }

    @Test fun `old initialization cannot publish after deletion or restore script on disk`() = runBlocking {
        val store = Store()
        val runtime = Runtime()
        val owner = LxSourceOwner(store) { runtime }
        val old = async { runCatching { owner.importScript { "/**\n * @name Old\n */\n" } } }
        runtime.started.await()
        owner.delete()
        runtime.answer.complete("done")
        old.await()
        assertNull(store.saved)
        assertNull(owner.state.value.script)
        assertTrue(owner.state.value.platforms.isEmpty())
        assertTrue(runtime.closed)
    }

    @Test fun `late file read cannot overwrite newer import persistence`() = runBlocking {
        val store = Store()
        val runtime = Runtime().apply { answer.complete("done") }
        val owner = LxSourceOwner(store) { runtime }
        val entered = CompletableDeferred<Unit>()
        val read = CompletableDeferred<String>()
        val old = async { runCatching { owner.importScript { entered.complete(Unit); read.await() } } }
        entered.await()
        owner.importScript { "/**\n * @name New\n */" }
        read.complete("/**\n * @name Old\n */")
        old.await()
        assertEquals("New", store.saved!!.metadata.name)
        assertEquals("New", owner.state.value.script!!.metadata.name)
    }

    @Test fun `disable prevents delayed URL from reaching playback publication`() = runBlocking {
        val store = Store()
        val urlStarted = CompletableDeferred<Unit>()
        val url = CompletableDeferred<String>()
        val owner = LxSourceOwner(store) { object : LxRuntime {
            override suspend fun initialize(script: LxStoredScript) = mapOf("wy" to listOf("flac"))
            override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String {
                urlStarted.complete(Unit); return url.await()
            }
            override fun close() = Unit
        } }
        owner.importScript { "/**\n * @name Source\n */" }
        val trial = owner.prepareTrial("wy", "flac", "{\"name\":\"Test\"}")
        val pending = async { runCatching { owner.resolve(trial.mediaId) } }
        urlStarted.await()
        owner.setEnabled(false)
        url.complete("https://example.org/test.flac")
        assertTrue(pending.await().isFailure)
        assertFalse(store.saved!!.enabled)
        assertNull(owner.findTrial(trial.ref))
    }

    @Test fun `release fences an initialization that ignores runtime close`() = runBlocking {
        val store = Store()
        val runtime = Runtime()
        val owner = LxSourceOwner(store) { runtime }
        val pending = async { runCatching { owner.importScript { "/**\n * @name Source\n */" } } }
        runtime.started.await()
        owner.close()
        val durable = store.saved
        runtime.answer.complete("late")
        pending.await()
        assertEquals(durable, store.saved)
        assertNull(owner.state.value.script)
        assertTrue(owner.state.value.platforms.isEmpty())
        assertTrue(runtime.closed)
    }

    @Test fun `new trial invalidates old URL and old error publication`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val late = CompletableDeferred<String>()
        val owner = LxSourceOwner(Store()) { object : LxRuntime {
            override suspend fun initialize(script: LxStoredScript) = mapOf("wy" to listOf("flac"))
            override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String {
                started.complete(Unit); return late.await()
            }
            override fun close() = Unit
        } }
        owner.importScript { "/**\n * @name Source\n */" }
        val old = owner.prepareTrial("wy", "flac", "{}")
        val pending = async { runCatching { owner.resolve(old.mediaId) } }
        started.await()
        val newer = owner.prepareTrial("wy", "flac", "{}")
        late.complete("https://example.org/old.flac")
        assertTrue(pending.await().isFailure)
        assertEquals(newer, owner.findTrial(newer.ref))
        assertEquals("初始化成功", owner.state.value.message)
        owner.close()
    }

    @Test fun `failed durable edit preserves committed config but retires capabilities`() = runBlocking {
        var saved: LxStoredScript? = null
        var failWrite = false
        val runtime = Runtime().apply { answer.complete("ready") }
        val owner = LxSourceOwner(object : LxScriptStore {
            override fun read() = saved
            override fun write(value: LxStoredScript?) {
                if (failWrite) throw java.io.IOException("disk failure")
                saved = value
            }
        }) { runtime }
        owner.importScript { "/**\n * @name Source\n */" }
        failWrite = true
        assertTrue(runCatching { owner.setEnabled(false) }.isFailure)
        assertTrue(saved!!.enabled)
        assertTrue(owner.state.value.script!!.enabled)
        assertTrue(owner.state.value.platforms.isEmpty())
        assertEquals("运行时已停止", owner.state.value.message)
        assertTrue(runtime.closed)
        owner.close()
    }
}
