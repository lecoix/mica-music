package com.mica.music.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemMediaArtworkResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val affectedHuawei = SystemMediaArtworkResolver.DeviceProfile(
        manufacturer = "HUAWEI",
        model = "OXF-AN10",
        sdkInt = 31,
    )

    @Test
    fun affectedHuaweiPublishesResidentManagedArtworkAsBackingFile() {
        val raw = AlbumArtCache.storeManagedArtwork(
            context,
            "huawei-resident",
            "resident-cover-${System.nanoTime()}".toByteArray(),
        )
        val backing = AlbumArtCache.fileForManagedArtwork(context, raw)!!

        try {
            val resolved = SystemMediaArtworkResolver.resolve(context, raw, affectedHuawei)

            assertEquals("file", resolved?.scheme)
            assertEquals(Uri.fromFile(backing), resolved)
        } finally {
            backing.delete()
        }
    }

    @Test
    fun affectedHuaweiNeverFallsBackToMissingManagedContentUri() {
        val raw = AlbumArtCache.buildManagedArtworkUri(
            context,
            "huawei-missing",
            "content_v1_${"f".repeat(64)}",
        )
        AlbumArtCache.fileForManagedArtwork(context, raw)?.delete()

        assertNull(SystemMediaArtworkResolver.resolve(context, raw, affectedHuawei))
    }

    @Test
    fun unaffectedDevicesKeepManagedContentUri() {
        val raw = AlbumArtCache.buildManagedArtworkUri(
            context,
            "pixel",
            "content_v1_${"a".repeat(64)}",
        )
        val pixel = affectedHuawei.copy(manufacturer = "Google", model = "Pixel 8")

        assertEquals(raw, SystemMediaArtworkResolver.resolve(context, raw, pixel)?.toString())
    }

    @Test
    fun sessionCodecKeepsCanonicalManagedUriInExtras() {
        val raw = AlbumArtCache.storeManagedArtwork(
            context,
            "codec",
            "codec-cover-${System.nanoTime()}".toByteArray(),
        )
        val backing = AlbumArtCache.fileForManagedArtwork(context, raw)!!
        val song = SongFixtures.song("codec").copy(albumArtUri = raw)

        try {
            val item = SongMediaItemCodec.encodeForSession(context, song, affectedHuawei)

            assertEquals("file", item.mediaMetadata.artworkUri?.scheme)
            assertEquals(raw, SongMediaItemCodec.decode(item)?.albumArtUri)
        } finally {
            backing.delete()
        }
    }
}
