package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalLyricsReaderTest {

    @Test
    fun externalLyricsInputIsBoundedAtTenMebibytes() {
        assertEquals(10 * 1024 * 1024, ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES)

        val exact = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            exact,
            ExternalLyricsReader.readBoundedLyricsBytes(ByteArrayInputStream(exact), maxBytes = 4),
        )
        assertNull(
            ExternalLyricsReader.readBoundedLyricsBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                maxBytes = 4,
            ),
        )
    }

    @Test
    fun strictProbeDistinguishesAbsentFoundAndFailed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val valid = File.createTempFile("lyrics-valid", ".lrc").apply {
            writeText("[00:01.00]line")
            deleteOnExit()
        }
        val blank = File.createTempFile("lyrics-blank", ".lrc").apply { deleteOnExit() }
        val validUri = Uri.fromFile(valid)
        val blankUri = Uri.fromFile(blank)

        val absent = ExternalLyricsReader.probeDirectDocuments(context, emptyList())
        val found = ExternalLyricsReader.probeDirectDocuments(context, listOf(validUri.toString()))
        val failed = ExternalLyricsReader.probeDirectDocuments(context, listOf(blankUri.toString()))

        assertTrue(absent is ProbeResult.Ok && absent.value == null)
        assertTrue(found is ProbeResult.Ok && found.value?.lines?.isNotEmpty() == true)
        assertTrue(failed is ProbeResult.Failed)
    }

    @Test
    fun oneUnreadableExternalCandidateMakesWholeSlotFail() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val valid = File.createTempFile("lyrics-conservative", ".lrc").apply {
            writeText("[00:01.00]line")
            deleteOnExit()
        }
        val validUri = Uri.fromFile(valid)
        val missingUri = Uri.fromFile(File(valid.parentFile, "missing-${System.nanoTime()}.lrc"))

        val result = ExternalLyricsReader.probeDirectDocuments(
            context,
            listOf(validUri.toString(), missingUri.toString()),
        )

        assertTrue(result is ProbeResult.Failed)
    }
}
