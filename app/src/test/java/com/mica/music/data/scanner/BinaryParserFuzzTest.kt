package com.mica.music.data.scanner

import kotlin.random.Random
import org.junit.Test

class BinaryParserFuzzTest {

    @Test
    fun binaryParsersDoNotThrowOnDeterministicRandomInput() {
        fuzz(iterations = 3_000, seed = 0x4D494341)
    }

    @Test(timeout = 60_000)
    fun nightlyBinaryParsersRemainBounded() {
        org.junit.Assume.assumeTrue(System.getProperty("mica.nightly") == "true")
        fuzz(iterations = 10_000, seed = 0x4D494341)
    }

    private fun fuzz(iterations: Int, seed: Int) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val bytes = ByteArray(random.nextInt(0, 512))
            random.nextBytes(bytes)
            try {
                Id3FrameLister.listAll(bytes)
                VorbisCommentLister.listAll(bytes)
                Mp4LyricsReader.read(bytes)
                Mp4LyricsReader.listIlstItems(bytes)
                Mp4AtomTextReader.read(bytes, listOf("data".toByteArray()))
                EncoderSettingsReader.fromBytes(bytes)
                listOf("mp3", "flac", "ape", "m4a", "ogg").forEach { ext ->
                    EmbeddedLyricsReader.readDocumentFromBinaryForTest(bytes, ext = ext)
                }
                LyricsEncoding.decodeBytes(bytes)
            } catch (error: Throwable) {
                throw AssertionError(
                    "parser fuzz failed seed=$seed iteration=$iteration length=${bytes.size} " +
                        "hex=${bytes.joinToString("") { "%02x".format(it) }}",
                    error,
                )
            }
        }
    }
}
