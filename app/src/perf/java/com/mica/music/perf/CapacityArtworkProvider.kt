package com.mica.music.perf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor

/** Generates unique, decodable artwork without placing 10,000 image files on the device. */
class CapacityArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Capacity artwork is read-only" }
        val index = uri.lastPathSegment?.toIntOrNull() ?: 0
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(colorFor(index))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = 72f
                    typeface = Typeface.DEFAULT_BOLD
                }
                canvas.drawText((index + 1).toString(), 256f, 282f, paint)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                bitmap.recycle()
            }
        }, "capacity-artwork-$index").start()
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun colorFor(index: Int): Int = Color.rgb(
        48 + index * 31 % 176,
        48 + index * 61 % 176,
        48 + index * 89 % 176,
    )
}
