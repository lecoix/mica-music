package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
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
    private const val MaxDiskBytes = 64L * 1024L * 1024L
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
        return isReadablePoster(file)
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
            val file = fileFor(context, uri)
            val temporary = File(file.parentFile, "${file.name}.part")
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(temporary).use { out ->
                    copy.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.fd.sync()
                }
                check(temporary.length() > 0L) { "Video cover poster was not encoded" }
                if (file.exists()) check(file.delete()) { "Unable to replace video cover poster" }
                check(temporary.renameTo(file)) { "Unable to publish video cover poster" }
                trimToBudget(file)
            } catch (_: Exception) {
                // Best-effort cache; playback still works without a poster.
            } finally {
                temporary.delete()
                discard()
            }
        }

        fun discard() {
            if (!copy.isRecycled) copy.recycle()
        }
    }

    private fun isReadablePoster(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    internal fun trimToBudgetForTest(
        directory: File,
        protectedFile: File? = null,
        maxBytes: Long = MaxDiskBytes,
    ) = trimToBudget(directory, protectedFile, maxBytes)

    private fun trimToBudget(protectedFile: File) {
        val directory = protectedFile.parentFile ?: return
        trimToBudget(directory, protectedFile, MaxDiskBytes)
    }

    private fun trimToBudget(directory: File, protectedFile: File?, maxBytes: Long) {
        require(maxBytes >= 0L)
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }
            .orEmpty()
        var remaining = files.sumOf(File::length)
        if (remaining <= maxBytes) return
        val trimTargetBytes = maxBytes * 3L / 4L
        files
            .filter { it.absolutePath != protectedFile?.absolutePath }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
            .forEach { file ->
                if (remaining <= trimTargetBytes) return@forEach
                val bytes = file.length()
                if (file.delete()) remaining -= bytes
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
