package com.mica.music.data.lx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxOnlineLyricsSmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private suspend fun verify(platform: String, label: String, requireTokens: Boolean) {
        val store = AndroidLxOnlineStore(context)
        val source = lxOnlineSourceForPlatform(platform)
        val summary = store.summaries()
            .filter { it.ref.sourceInstanceId == source }
            .maxByOrNull { if (it.title.contains("晴天")) 1 else 0 }
            ?: error("$label has no persisted online record; run platform playback E2E first")
        val record = store.read(summary.ref) ?: error("$label persisted online record unreadable")
        val document = LxOnlineLyrics.load(record)
        val tokenCount = document.lines.sumOf { it.tokens.size }
        val originalCount = document.lines.count { line -> line.parts.any { it.role.name == "ORIGINAL" && it.text.isNotBlank() } }
        val translationCount = document.lines.count { line -> line.parts.any { it.role.name == "TRANSLATION" && it.text.isNotBlank() } }
        val readingCount = document.lines.count { line -> line.parts.any { it.role.name == "READING" && it.text.isNotBlank() } }
        android.util.Log.i(
            "LX_LYRIC_STAGE",
            "$label media=${summary.mediaId.takeLast(24)} title=${summary.title} lines=${document.lines.size} tokens=$tokenCount originals=$originalCount translation=$translationCount reading=$readingCount",
        )
        assertTrue("$label lyric lines=${document.lines.size}", document.lines.size >= 8)
        assertTrue("$label original lines=$originalCount", originalCount >= 8)
        if (requireTokens) assertTrue("$label expected word timing, tokens=$tokenCount", tokenCount >= 8)
    }

    @Test fun persistedTxLyrics(): Unit = runBlocking { verify("tx", "TX", false) }
    @Test fun persistedKwLyrics(): Unit = runBlocking { verify("kw", "KW", true) }
    @Test fun persistedWyLyrics(): Unit = runBlocking { verify("wy", "WY", false) }
    @Test fun persistedKgLyrics(): Unit = runBlocking { verify("kg", "KG", true) }
}