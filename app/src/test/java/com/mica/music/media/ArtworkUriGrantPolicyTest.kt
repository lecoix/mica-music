package com.mica.music.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkUriGrantPolicyTest {
    @Test
    fun `temporary grants stay scoped to the requesting controller`() {
        assertEquals(
            setOf("com.mica.music"),
            ArtworkUriGrantPolicy.targetPackages("com.mica.music"),
        )
        assertTrue(ArtworkUriGrantPolicy.targetPackages("").isEmpty())
        assertEquals(
            "android.permission.MEDIA_CONTENT_CONTROL",
            ArtworkUriGrantPolicy.SYSTEM_MEDIA_CONTROL_PERMISSION,
        )
    }

    @Test
    fun `only Mica-owned artwork providers are grantable`() {
        assertTrue(
            ArtworkUriGrantPolicy.isGrantable(
                "com.mica.music",
                Uri.parse("content://com.mica.music.remoteart/source/nav/art/cover"),
            ),
        )
        assertTrue(
            ArtworkUriGrantPolicy.isGrantable(
                "com.mica.music",
                Uri.parse("content://com.mica.music.artwork/song/id"),
            ),
        )
        assertFalse(
            ArtworkUriGrantPolicy.isGrantable(
                "com.mica.music",
                Uri.parse("content://other.provider/cover"),
            ),
        )
        assertFalse(
            ArtworkUriGrantPolicy.isGrantable(
                "com.mica.music",
                Uri.parse("https://music.example/cover.jpg"),
            ),
        )
    }
}
