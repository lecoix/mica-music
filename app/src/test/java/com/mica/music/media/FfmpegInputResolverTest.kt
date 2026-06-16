package com.mica.music.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FfmpegInputResolverTest {

    @Test
    fun resolveExternalStorageDocumentId_primaryVolume() {
        val root = File("/storage/emulated/0")
        val resolved = FfmpegInputResolver.resolveExternalStorageDocumentId(
            documentId = "primary:Music/album/song.flac",
            primaryStorageRoot = root,
            volumeRoot = { root },
        )
        assertNotNull(resolved)
        assertEquals(
            File(root, "Music/album/song.flac").path,
            resolved!!.path,
        )
    }

    @Test
    fun resolveExternalStorageDocumentId_rejectsPathTraversal() {
        val root = File("/storage/emulated/0")
        val resolved = FfmpegInputResolver.resolveExternalStorageDocumentId(
            documentId = "primary:../etc/passwd",
            primaryStorageRoot = root,
            volumeRoot = { root },
        )
        assertNull(resolved)
    }

    @Test
    fun resolveDirectFile_fileScheme() {
        val dir = File(System.getProperty("java.io.tmpdir"), "mica-ffmpeg-input")
        dir.mkdirs()
        val song = File(dir, "direct.flac").apply {
            writeBytes(byteArrayOf(0x66, 0x4C, 0x61, 0x43))
        }
        val resolved = FfmpegInputResolver.resolveDirectFile(Uri.fromFile(song))
        assertNotNull(resolved)
        assertEquals(song.absolutePath, resolved!!.absolutePath)
        song.delete()
    }

    @Test
    fun resolveDirectFile_missingFile_returnsNull() {
        val missing = File(System.getProperty("java.io.tmpdir"), "mica-missing-${System.nanoTime()}.flac")
        val resolved = FfmpegInputResolver.resolveDirectFile(Uri.fromFile(missing))
        assertNull(resolved)
    }

    @Test
    fun resolveExternalStorageDocumentId_invalidDocumentId_returnsNull() {
        val root = File("/storage/emulated/0")
        assertNull(
            FfmpegInputResolver.resolveExternalStorageDocumentId(
                documentId = "primary",
                primaryStorageRoot = root,
                volumeRoot = { root },
            ),
        )
        assertNull(
            FfmpegInputResolver.resolveExternalStorageDocumentId(
                documentId = ":Music/song.flac",
                primaryStorageRoot = root,
                volumeRoot = { root },
            ),
        )
    }

    @Test
    fun resolveExternalStorageDocumentId_secondaryVolume() {
        val sdRoot = File("/storage/ABCD-1234")
        val resolved = FfmpegInputResolver.resolveExternalStorageDocumentId(
            documentId = "ABCD-1234:Audio/track.dsf",
            primaryStorageRoot = File("/storage/emulated/0"),
            volumeRoot = { volume ->
                when (volume) {
                    "primary" -> File("/storage/emulated/0")
                    else -> File("/storage", volume)
                }
            },
        )
        assertNotNull(resolved)
        assertTrue(resolved!!.path.endsWith("Audio/track.dsf"))
        assertTrue(resolved.path.startsWith(sdRoot.path))
    }
}
