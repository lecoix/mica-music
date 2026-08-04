package com.mica.music.ui.screens.settings

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.CustomWallpaperCrop
import com.mica.music.ui.theme.CustomWallpaperImageState
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.rememberCustomWallpaperImage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomWallpaperFlowRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cropDialogIsDisplayedForPreparedWallpaper() {
        val image = createSolidImage("crop-dialog.jpg", Color.MAGENTA)

        composeRule.setContent {
            MicaTheme {
                CustomWallpaperCropDialog(
                    imagePath = image.absolutePath,
                    initialCrop = CustomWallpaperCrop.Default,
                    overlayPercent = 0,
                    blurDp = 0,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("调整壁纸裁切").assertIsDisplayed()
        composeRule.onNodeWithText("应用").assertIsDisplayed()
    }

    @Test
    fun publishedWallpaperFileLoadsForRendering() {
        val image = createSolidImage("applied-wallpaper.jpg", Color.MAGENTA)
        val imageState = AtomicReference<CustomWallpaperImageState>()

        composeRule.setContent {
            val state = rememberCustomWallpaperImage(image.absolutePath)
            SideEffect { imageState.set(state) }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            imageState.get() is CustomWallpaperImageState.Ready
        }
        val ready = imageState.get() as CustomWallpaperImageState.Ready
        val pixels = ready.image.toPixelMap()
        val pixel = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(
            "expected magenta wallpaper pixel, actual=$pixel",
            pixel.red > 0.8f && pixel.green < 0.35f && pixel.blue > 0.8f,
        )
    }

    private fun createSolidImage(name: String, color: Int): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(40, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()
        return file
    }
}
