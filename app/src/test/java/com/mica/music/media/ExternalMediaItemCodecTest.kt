package com.mica.music.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalMediaItemCodecTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val affectedHuawei = SystemMediaArtworkResolver.DeviceProfile(
        manufacturer = "HUAWEI",
        model = "OXF-AN10",
        sdkInt = 31,
    )

    @Test
    fun managedArtworkIsRetainedOnlyAsAControllerGrantableContentUri() {
        val raw = AlbumArtCache.buildManagedArtworkUri(
            context,
            "song-1",
            "content_v1_${"a".repeat(64)}",
        )
        val item = ExternalMediaItemCodec.encode(
            context,
            SongFixtures.song("song-1").copy(albumArtUri = raw),
        )

        assertEquals(raw, item.mediaMetadata.artworkUri?.toString())
        assertTrue(ExternalMediaItemCodec.isExternal(item))
        assertNull(item.mediaMetadata.extras?.getString("mica.song.mediaUri"))
        assertEquals(
            item.localConfiguration?.uri?.toString(),
            ExternalMediaItemCodec.decode(item)?.mediaUri,
        )
    }

    @Test
    fun affectedHuaweiExternalItemUsesTheSameBackingFileBoundary() {
        val raw = AlbumArtCache.storeManagedArtwork(
            context,
            "external-huawei",
            "external-cover-${System.nanoTime()}".toByteArray(),
        )
        val backing = AlbumArtCache.fileForManagedArtwork(context, raw)!!

        try {
            val item = ExternalMediaItemCodec.encode(
                context,
                SongFixtures.song("external-huawei").copy(albumArtUri = raw),
                affectedHuawei,
            )

            assertEquals("file", item.mediaMetadata.artworkUri?.scheme)
            assertEquals(Uri.fromFile(backing), item.mediaMetadata.artworkUri)
        } finally {
            backing.delete()
        }
    }

    @Test
    fun privateAndFileArtworkUrisAreNotPublished() {
        val song = SongFixtures.song("song-2")
        val privateItem = ExternalMediaItemCodec.encode(
            context,
            song.copy(albumArtUri = "content://attacker/private-cover"),
        )
        val fileItem = ExternalMediaItemCodec.encode(
            context,
            song.copy(albumArtUri = "file:///data/user/0/com.mica.music/files/cover.jpg"),
        )

        assertNull(privateItem.mediaMetadata.artworkUri)
        assertNull(fileItem.mediaMetadata.artworkUri)
    }

    @Test
    fun httpArtworkRemainsAvailableToExternalControllers() {
        val item = ExternalMediaItemCodec.encode(
            context,
            SongFixtures.song("song-3").copy(albumArtUri = "https://example.test/cover.jpg"),
        )

        assertEquals(
            "https://example.test/cover.jpg",
            item.mediaMetadata.artworkUri?.toString(),
        )
    }
}
