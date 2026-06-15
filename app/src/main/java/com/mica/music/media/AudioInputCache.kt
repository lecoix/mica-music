package com.mica.music.media

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Bounded, atomic cache for seekable FFmpeg inputs copied from content URIs.
 *
 * Copying is serialized so rapid track changes cannot saturate storage with overlapping reads.
 */
internal class AudioInputCache(
    cacheDir: File,
    private val maxEntries: Int = 4,
    private val maxBytes: Long = 512L * 1024L * 1024L,
) {
    private val directory = File(cacheDir, DIRECTORY_NAME)
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val activeLeases = ConcurrentHashMap<String, AtomicInteger>()
    private val trimLock = Any()

    data class Result(
        val file: File,
        val reused: Boolean,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        override fun close() = releaseAction()
    }

    fun getOrCopy(
        identity: String,
        extension: String,
        expectedBytes: Long,
        isCancelled: () -> Boolean,
        openInput: () -> InputStream?,
    ): Result? {
        if (isCancelled()) return null
        directory.mkdirs()
        val target = File(directory, "${sha256(identity)}.$extension")
        val lock = keyLocks.getOrPut(target.name) { Any() }
        return synchronized(lock) {
            getOrCopyLocked(target, expectedBytes, isCancelled, openInput)
        }
    }

    private fun getOrCopyLocked(
        target: File,
        expectedBytes: Long,
        isCancelled: () -> Boolean,
        openInput: () -> InputStream?,
    ): Result? {
        if (isCancelled()) return null
        val validCachedFile = target.exists() &&
            target.length() > 0L &&
            (expectedBytes <= 0L || target.length() == expectedBytes)
        if (validCachedFile) {
            target.setLastModified(System.currentTimeMillis())
            return lease(target, reused = true)
        }
        target.delete()

        val partial = File(directory, "${target.name}.part")
        partial.delete()
        var cancelled = false
        val finished = AtomicBoolean(false)
        var activeInput: InputStream? = null
        val copied = try {
            val input = openInput() ?: return null
            activeInput = input
            thread(name = "mica-audio-cache-cancel", isDaemon = true) {
                while (!finished.get()) {
                    if (isCancelled()) {
                        runCatching { activeInput?.close() }
                        break
                    }
                    Thread.sleep(CANCEL_POLL_MS)
                }
            }
            input.use {
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                        val count = it.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            !cancelled &&
                !isCancelled() &&
                partial.length() > 0L &&
                (expectedBytes <= 0L || partial.length() == expectedBytes)
        } catch (_: Exception) {
            false
        } finally {
            finished.set(true)
            runCatching { activeInput?.close() }
        }
        if (!copied) {
            partial.delete()
            return null
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        val result = lease(target, reused = false)
        synchronized(trimLock) {
            trim(exclude = target)
        }
        return result
    }

    private fun trim(exclude: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var kept = 0
        var bytes = 0L
        files.forEach { file ->
            val active = activeLeases[file.absolutePath]?.get()?.let { it > 0 } == true
            if (file == exclude || active || (kept < maxEntries && bytes + file.length() <= maxBytes)) {
                kept++
                bytes += file.length()
            } else {
                file.delete()
            }
        }
    }

    private fun lease(file: File, reused: Boolean): Result {
        val path = file.absolutePath
        val counter = activeLeases.computeIfAbsent(path) { AtomicInteger() }
        counter.incrementAndGet()
        val released = AtomicBoolean(false)
        return Result(file, reused) {
            if (released.compareAndSet(false, true) && counter.decrementAndGet() <= 0) {
                activeLeases.remove(path, counter)
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
        const val CANCEL_POLL_MS = 25L
        const val DIRECTORY_NAME = "audio_input"
    }
}
