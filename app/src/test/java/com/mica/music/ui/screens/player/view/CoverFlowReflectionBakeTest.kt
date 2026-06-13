package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowReflectionBakeTest {

    @Test
    fun bake_producesReflectionStrip() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val baked = CoverFlowReflectionBake.bake(cover, dstAspect = 1f)
        assertNotNull(baked)
        baked!!
        assertTrue(baked.width > 0)
        assertTrue(baked.height > 0)
        assertTrue(baked.height <= cover.height)
    }

    @Test
    fun bake_wideCover_respectsAspectCrop() {
        val cover = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val baked = CoverFlowReflectionBake.bake(cover, dstAspect = 1f)
        assertNotNull(baked)
        baked!!
        assertTrue(baked.width <= 200)
    }
}
