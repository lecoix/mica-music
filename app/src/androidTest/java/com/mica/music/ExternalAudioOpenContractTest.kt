package com.mica.music

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.data.TestDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalAudioOpenContractTest {

    @Test
    fun resolvesReadableDocumentsProviderAudioIntoTransientSong() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = DocumentsContract.buildDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            "root/music/contract.wav",
        )
        val request = parseExternalAudioOpenRequest(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav"),
        )

        assertNotNull(request)
        val song = ExternalAudioSongResolver.resolve(
            context = context,
            request = requireNotNull(request),
            librarySongs = emptyList(),
        )

        assertNotNull(song)
        requireNotNull(song)
        assertEquals(uri.toString(), song.mediaUri)
        assertEquals("audio/wav", song.metadata.playbackMimeType)
        assertTrue(song.fileName.endsWith(".wav"))
        assertTrue(song.durationSec > 0)
    }
}
