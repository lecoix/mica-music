package com.mica.music.data.scanner

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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
    fun boundedLyricsReadUsesSmallStreamingReads() {
        val payload = "[00:01.00]streamed".toByteArray()
        val input = object : InputStream() {
            private var offset = 0

            override fun read(): Int =
                if (offset >= payload.size) -1 else payload[offset++].toInt() and 0xff

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                assertTrue("bulk read should stay bounded, requested=$len", len <= 64 * 1024)
                if (offset >= payload.size) return -1
                val count = minOf(len, payload.size - offset)
                payload.copyInto(buffer, destinationOffset = off, startIndex = offset, endIndex = offset + count)
                offset += count
                return count
            }
        }

        assertArrayEquals(
            payload,
            ExternalLyricsReader.readBoundedLyricsBytes(input),
        )
    }

    @Test
    fun strictProbeDistinguishesAbsentBlankFoundAndIoFailed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val valid = File.createTempFile("lyrics-valid", ".lrc").apply {
            writeText("[00:01.00]line")
            deleteOnExit()
        }
        val blank = File.createTempFile("lyrics-blank", ".lrc").apply { deleteOnExit() }
        val metadataOnly = File.createTempFile("lyrics-meta-only", ".lrc").apply {
            writeText("[ti:Song Title]\n[ar:Artist]\n")
            deleteOnExit()
        }
        val missing = File(valid.parentFile, "lyrics-missing-${System.nanoTime()}.lrc")
        val validUri = Uri.fromFile(valid)
        val blankUri = Uri.fromFile(blank)
        val metadataOnlyUri = Uri.fromFile(metadataOnly)
        val missingUri = Uri.fromFile(missing)

        val absent = ExternalLyricsReader.probeDirectDocuments(context, emptyList())
        val found = ExternalLyricsReader.probeDirectDocuments(context, listOf(validUri.toString()))
        val blankResult = ExternalLyricsReader.probeDirectDocuments(context, listOf(blankUri.toString()))
        val metaOnlyResult = ExternalLyricsReader.probeDirectDocuments(context, listOf(metadataOnlyUri.toString()))
        val failed = ExternalLyricsReader.probeDirectDocuments(context, listOf(missingUri.toString()))

        assertTrue(absent is ProbeResult.Ok && absent.value == null)
        assertTrue(found is ProbeResult.Ok && found.value?.lines?.isNotEmpty() == true)
        assertTrue(blankResult is ProbeResult.Ok && blankResult.value == null)
        assertTrue(metaOnlyResult is ProbeResult.Ok && metaOnlyResult.value == null)
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

    @Test
    fun securityExceptionDistinguishesMediaStorePermissionDeniedFromSafFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.buildContentProvider(SecurityExceptionContentProvider::class.java)
            .create("media")
        Robolectric.buildContentProvider(SecurityExceptionContentProvider::class.java)
            .create("com.android.externalstorage.documents")

        val mediaStoreLrc = "content://media/external/file/42"
        val safLrc = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.lrc"

        val mediaStoreDetailed = ExternalLyricsReader.probeDirectDocumentsDetailed(
            context,
            listOf(mediaStoreLrc),
        )
        val mediaStoreStrict = ExternalLyricsReader.probeDirectDocuments(
            context,
            listOf(mediaStoreLrc),
        )
        val safDetailed = ExternalLyricsReader.probeDirectDocumentsDetailed(
            context,
            listOf(safLrc),
        )

        assertTrue(mediaStoreDetailed is ExternalLyricsProbeResult.PermissionDenied)
        assertTrue(mediaStoreStrict is ProbeResult.Failed)
        assertTrue(safDetailed is ExternalLyricsProbeResult.Failed)
    }

    class SecurityExceptionContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            throw SecurityException("denied for $uri")
        }
    }
}
