package com.mica.music.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongActionsTest {
    @Test
    fun lyricoEditTagIntentTargetsExternalEditorContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://media/external/audio/media/42")

        val intent = buildLyricoEditTagIntent(
            context = context,
            title = "Track",
            uri = uri,
        )

        assertEquals(LYRICO_EDIT_TAG_ACTION, intent.action)
        assertEquals(LYRICO_PACKAGE_NAME, intent.`package`)
        assertEquals(uri, intent.data)
        assertEquals("audio/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
    }
}
