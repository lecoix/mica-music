package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * First-frame posters for video album covers.
 *
 * Written on first successful playback and/or post-scan background prefetch
 * (matched URIs only; never during the folder walk itself).
 * Memory LRU + disk under cacheDir for process restarts.
 */
internal object VideoCoverPosterStore {
    private const val MemoryEntries = 8
    private const val DiskQueueCapacity = 8
    private val memory = LruCache<String, Bitmap>(MemoryEntries)
    private val diskExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(DiskQueueCapacity),
        ThreadFactory { runnable ->
            Thread(runnable, "video-cover-poster-disk").apply { isDaemon = true }
        },
        RejectedExecutionHandler { runnable, _ ->
            (runnable as? DiskWriteTask)?.discard()
        },
    )

    fun get(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        memory.get(uri)?.takeUnless { it.isRecycled }?.let { return it }
        val file = fileFor(context, uri)
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { memory.put(uri, it) }
        } catch (_: Exception) {
            null
        }
    }

    fun isCached(context: Context, uri: String): Boolean {
        if (uri.isBlank()) return false
        memory.get(uri)?.takeUnless { it.isRecycled }?.let { return true }
        val file = fileFor(context, uri)
        return file.isFile && file.length() > 0L
    }

    fun put(context: Context, uri: String, bitmap: Bitmap) {
        if (uri.isBlank() || bitmap.isRecycled) return
        memory.put(uri, bitmap)
        val appContext = context.applicationContext
        val copy = bitmap.config?.let { bitmap.copy(it, false) } ?: return
        diskExecutor.execute(DiskWriteTask(appContext, uri, copy))
    }

    private class DiskWriteTask(
        private val context: Context,
        private val uri: String,
        private val copy: Bitmap,
    ) : Runnable {
        override fun run() {
            try {
                val file = fileFor(context, uri)
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    copy.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (_: Exception) {
                // Best-effort cache; playback still works without a poster.
            } finally {
                discard()
            }
        }

        fun discard() {
            if (!copy.isRecycled) copy.recycle()
        }
    }

    private fun fileFor(context: Context, uri: String): File {
        val digester = MessageDigest.getInstance("SHA-256")
        val hex = digester.digest(uri.toByteArray()).joinToString("") { b ->
            "%02x".format(b)
        }
        return File(context.cacheDir, "video_cover_posters/$hex.jpg")
    }
}
