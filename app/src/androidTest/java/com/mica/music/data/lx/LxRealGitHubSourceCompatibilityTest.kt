package com.mica.music.data.lx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real public GitHub LX scripts executed by Mica's Android QuickJS host. */
@RunWith(AndroidJUnit4::class)
class LxRealGitHubSourceCompatibilityTest {
    private fun readAsset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx-real/$name")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun initialize(name: String): Map<String, List<String>> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val runtime = QuickJsLxRuntime(app)
        return try {
            withTimeout(20_000) {
                runtime.initialize(
                    LxStoredScript(
                        id = "github-$name",
                        raw = readAsset(name),
                        enabled = true,
                    ),
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun xinghaiSourceInitializesAndPublishesPlayableCapabilities() = runBlocking {
        val capabilities = initialize("xinghai-music-source.js")
        assertEquals(setOf("wy", "tx", "kg", "kw", "mg"), capabilities.keys)
        for (source in capabilities.keys) {
            assertTrue("$source missing 128k", "128k" in capabilities.getValue(source))
        }
        assertTrue("wy should expose lossless", "flac" in capabilities.getValue("wy"))
        assertTrue("tx should expose lossless", "flac" in capabilities.getValue("tx"))
    }

    @Test
    fun feicaiWebpackSourceInitializesAndPublishesCapabilities() = runBlocking {
        val capabilities = initialize("feicai-internal-source.js")
        assertEquals(setOf("wy", "tx", "kg", "kw", "mg"), capabilities.keys)
        assertEquals(listOf("128k", "320k"), capabilities.getValue("kw"))
        assertTrue("wy should at least expose 128k", "128k" in capabilities.getValue("wy"))
        assertTrue("tx should at least expose 128k", "128k" in capabilities.getValue("tx"))
        assertTrue("kg should at least expose 128k", "128k" in capabilities.getValue("kg"))
        assertTrue("mg should at least expose 128k", "128k" in capabilities.getValue("mg"))
    }

    @Test
    fun freeListenSourceInitializesAndPublishesCapabilities() = runBlocking {
        val capabilities = initialize("freelisten.js")
        assertEquals(setOf("wy", "tx", "kg", "kw", "mg"), capabilities.keys)
        assertEquals(listOf("128k"), capabilities.getValue("wy"))
        assertTrue("kw should expose 320k", "320k" in capabilities.getValue("kw"))
        assertTrue("tx should at least expose 128k", "128k" in capabilities.getValue("tx"))
        assertTrue("kg should at least expose 128k", "128k" in capabilities.getValue("kg"))
        assertTrue("mg should at least expose 128k", "128k" in capabilities.getValue("mg"))
    }}
