package com.mica.music.data.remote

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Small revision-keyed disk LRU for rematerializable remote payloads. */
internal class RevisionedDiskCache(
    private val directory: File,
    private val maxBytes: Long,
    private val maxEntries: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    init {
        require(maxBytes >= 0L) { "Disk cache byte budget must be non-negative" }
        require(maxEntries >= 0) { "Disk cache entry budget must be non-negative" }
    }

    fun get(key: String): ByteArray? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.isFile) return@synchronized null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: run {
            file.delete()
            return@synchronized null
        }
        file.setLastModified(nowMs())
        bytes
    }

    fun put(key: String, bytes: ByteArray) = synchronized(lock) {
        runCatching {
            if (maxEntries == 0 || bytes.size.toLong() > maxBytes) return@runCatching
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return@runCatching

            val target = fileFor(key)
            val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (target.exists() && !target.delete()) return@runCatching
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setLastModified(nowMs())
                trimLocked()
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun trimLocked() {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
            .toMutableList()
        var totalBytes = files.sumOf(File::length)
        while (files.isNotEmpty() && (files.size > maxEntries || totalBytes > maxBytes)) {
            val victim = files.removeAt(0)
            val bytes = victim.length()
            if (victim.delete()) totalBytes -= bytes
        }
    }

    private fun fileFor(key: String): File = File(directory, sha256(key) + FILE_SUFFIX)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILE_SUFFIX = ".bin"
    }
}
