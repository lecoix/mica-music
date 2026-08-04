package com.mica.music.data

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.mica.music.data.preferences.AppearancePreferences
import com.mica.music.data.preferences.PreferencesTestFixtures
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUiSettingsWallpaperRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun resetState() {
        PreferencesTestFixtures.clearMicaSettings(context)
        AppWallpaperImporter.wallpaperDirectory(context).deleteRecursively()
    }

    @Test
    fun selectingImagePreparesCropAndOnlyApplyPublishesWallpaper() = runTest {
        val source = createSource("wallpaper-source.png", Color.MAGENTA)
        val settings = AppUiSettings(context)

        val prepared = settings.prepareCustomWallpaper(Uri.fromFile(source))

        assertTrue(prepared.applied)
        assertNull(settings.customWallpaperPath)
        assertNotNull(settings.pendingCustomWallpaperPath)
        assertNull(AppearancePreferences.customWallpaperPath(context))

        val crop = CustomWallpaperCrop(zoom = 2f, offsetX = 0.25f, offsetY = -0.5f)
        assertTrue(settings.applyPendingCustomWallpaper(crop))

        val publishedPath = requireNotNull(settings.customWallpaperPath)
        assertTrue(File(publishedPath).isFile)
        assertEquals(publishedPath, AppearancePreferences.customWallpaperPath(context))
        assertEquals(crop, settings.customWallpaperCrop)
        assertEquals(crop, AppearancePreferences.customWallpaperCrop(context))
        assertNull(settings.pendingCustomWallpaperPath)
    }

    @Test
    fun cancelingReplacementKeepsPublishedWallpaperAndDeletesCandidate() = runTest {
        val settings = AppUiSettings(context)
        assertTrue(settings.prepareCustomWallpaper(Uri.fromFile(createSource("first.png", Color.RED))).applied)
        assertTrue(settings.applyPendingCustomWallpaper(CustomWallpaperCrop.Default))
        val publishedPath = requireNotNull(settings.customWallpaperPath)

        assertTrue(settings.prepareCustomWallpaper(Uri.fromFile(createSource("second.png", Color.BLUE))).applied)
        val candidatePath = requireNotNull(settings.pendingCustomWallpaperPath)
        assertTrue(File(candidatePath).isFile)

        settings.cancelPendingCustomWallpaper()

        assertEquals(publishedPath, settings.customWallpaperPath)
        assertEquals(publishedPath, AppearancePreferences.customWallpaperPath(context))
        assertTrue(File(publishedPath).isFile)
        assertTrue(!File(candidatePath).exists())
        assertNull(settings.pendingCustomWallpaperPath)
    }

    private fun createSource(name: String, color: Int): File {
        val source = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        return source
    }
}
