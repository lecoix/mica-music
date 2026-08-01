package com.mica.music.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class AppLetterSealImporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanup() {
        AppLetterSealImporter.clearSeal(context)
        File(context.cacheDir, "letter-seal-source.png").delete()
    }

    @Test
    fun importedSealIsCenterCroppedBoundedAndKeepsAlpha() {
        val source = File(context.cacheDir, "letter-seal-source.png")
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            setPixel(width / 2, height / 2, Color.RED)
        }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        val result = AppLetterSealImporter.importSeal(context, Uri.fromFile(source))
        val importedFile = result.path?.let(::File)
        val imported = importedFile?.let { BitmapFactory.decodeFile(it.absolutePath) }

        assertNotNull(result.message, importedFile)
        assertTrue(importedFile?.isFile == true)
        assertNotNull(imported)
        assertEquals(512, imported?.width)
        assertEquals(512, imported?.height)
        assertEquals(0, imported?.getPixel(0, 0)?.let(Color::alpha))
        imported?.recycle()
    }
}
