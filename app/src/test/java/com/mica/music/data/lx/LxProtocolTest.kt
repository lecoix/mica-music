package com.mica.music.data.lx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import okio.Buffer

@RunWith(RobolectricTestRunner::class)
class LxProtocolTest {
    @Test fun `metadata follows leading block and last value wins`() {
        val info = LxMetadata.parse("/**\n * @name First\n * @name Last\n * @version 1\n */\n// @name Ignored")
        assertEquals("Last", info.name)
        assertEquals("1", info.version)
        assertTrue(runCatching { LxMetadata.parse("alert(1)") }.isFailure)
        assertTrue(runCatching { LxMetadata.parse("/** x */" + "a".repeat(512 * 1024)) }.isFailure)
    }

    @Test fun `bounded reader rejects oversize before publishing output`() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBytesBounded(2))
        assertTrue(runCatching { ByteArrayInputStream(ByteArray(3)).readBytesBounded(2) }.isFailure)
    }

    @Test fun `HTTP JSON form and multipart retain explicit body and headers`() {
        val json = QuickJsLxRuntime.buildHttpRequest("https://example.org", JSONObject("""{"method":"post","body":{"id":1},"headers":{"X-Test":"yes"}}"""))
        assertEquals("yes", json.header("X-Test"))
        assertEquals("{\"id\":1}", Buffer().also { json.body!!.writeTo(it) }.readUtf8())
        val form = QuickJsLxRuntime.buildHttpRequest("https://example.org", JSONObject("""{"method":"post","form":{"a":"b c"}}"""))
        assertEquals("a=b%20c", Buffer().also { form.body!!.writeTo(it) }.readUtf8())
        val multipart = QuickJsLxRuntime.buildHttpRequest("https://example.org", JSONObject("""{"method":"post","formData":{"a":"b"}}"""))
        assertTrue(multipart.body!!.contentType().toString().startsWith("multipart/form-data; boundary="))
        assertTrue(runCatching { QuickJsLxRuntime.buildHttpRequest("file:///etc/passwd", JSONObject()) }.isFailure)
    }

    @Test fun `script store replacement survives a fresh reader`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidLxScriptStore(context)
        store.write(LxStoredScript("one", "/**\n * @name One\n */", true))
        store.write(LxStoredScript("two", "/**\n * @name Two\n */", true))
        val reopened = AndroidLxScriptStore(context).read()
        assertEquals("two", reopened!!.id)
        assertEquals("Two", reopened.metadata.name)
    }

    @Test fun `disk deletion wins over late provider import and stays deleted on restart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidLxScriptStore(context)
        store.write(LxStoredScript("old", "/**\n * @name Existing\n */", false))
        val owner = LxSourceOwner(store) { error("No runtime should execute") }
        owner.restore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async { runCatching { owner.importScript { entered.complete(Unit); release.await(); "/**\n * @name Stale\n */" } } }
        entered.await()
        owner.delete()
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(AndroidLxScriptStore(context).read())
        assertNull(owner.state.value.script)
        owner.close()
    }
}
