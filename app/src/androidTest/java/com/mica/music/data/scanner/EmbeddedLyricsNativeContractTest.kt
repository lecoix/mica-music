package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedLyricsNativeContractTest {
    @Test
    fun tagLibReadsLyricsWrittenToRealFlacAndAlacContainers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val lyrics = "[00:01.00]native container lyric"

        listOf(
            "media/contract-silence-flac-96k-24bit.flac",
            "media/contract-silence-alac.m4a",
        ).forEach { assetPath ->
            val file = File(context.cacheDir, "lyrics-${assetPath.substringAfterLast('/')}")
            try {
                assets.open(assetPath).use { input -> file.outputStream().use(input::copyTo) }
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tag ?: audioFile.createDefaultTag().also(audioFile::setTag)
                tag.setField(FieldKey.LYRICS, lyrics)
                audioFile.commit()

                val candidates = TagLibReader.read(context, Uri.fromFile(file))?.lyricsCandidates.orEmpty()
                assertTrue(assetPath, candidates.any { it.text == lyrics })
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun dsfMetadataPointerHandsId3LyricsToSharedParser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id3 = id3Uslt("[00:01.00]dsf pointer lyric")
        val file = File(context.cacheDir, "lyrics-pointer.dsf")
        try {
            file.writeBytes(dsfHeader(id3.size) + id3)
            val draft = TrackDraft(
                mediaStoreId = 1,
                title = "DSF fixture",
                artist = "Mica",
                album = "Tests",
                albumId = 1,
                durationSec = 1,
                mimeType = "audio/x-dsf",
                displayName = file.name,
                sizeBytes = file.length(),
                bitrateBpsFromStore = 0,
                mediaUri = Uri.fromFile(file).toString(),
                coverColorArgb = 0,
            )

            val document = DsdMetadataReader.read(context, Uri.fromFile(file), draft)?.embeddedLyricsDocument
            val text = document?.lines?.firstOrNull()?.parts?.firstOrNull()?.text
            assertEquals("dsf pointer lyric", text)
        } finally {
            file.delete()
        }
    }

    private fun dsfHeader(id3Size: Int): ByteArray {
        val metadataOffset = 92L
        return ByteBuffer.allocate(metadataOffset.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("DSD ".toByteArray())
            putLong(28L)
            putLong(metadataOffset + id3Size)
            putLong(metadataOffset)
            put("fmt ".toByteArray())
            putLong(52L)
            putInt(1)
            putInt(0)
            putInt(2)
            putInt(2)
            putInt(2_822_400)
            putInt(1)
            putLong(2_822_400L)
            putInt(4_096)
            putInt(0)
            put("data".toByteArray())
            putLong(12L)
        }.array()
    }

    private fun id3Uslt(text: String): ByteArray {
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray()
        val frame = "USLT".toByteArray() + int32Be(payload.size) + byteArrayOf(0, 0) + payload
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(3, 0, 0))
            write(synchsafe(frame.size))
            write(frame)
        }.toByteArray()
    }

    private fun int32Be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )
}
