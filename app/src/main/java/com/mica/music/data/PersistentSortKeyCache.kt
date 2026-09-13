package com.mica.music.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap

/**
 * Small versioned disk cache for expensive non-ASCII alphabetical sort keys.
 *
 * The in-memory cache remains authoritative for the current process. This store only makes already
 * computed normalized keys reusable after a cold process start. Corrupt or stale files are ignored
 * and replaced on the next persist.
 */
internal class PersistentSortKeyCache(
    private val algorithmVersion: Int,
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    private var storeFile: File? = null
    private var loaded = false
    private var dirty = false
    private var mutationVersion = 0L

    fun configure(file: File) {
        synchronized(lock) {
            if (storeFile?.absolutePath == file.absolutePath) return
            storeFile = file
            loaded = false
            dirty = false
            mutationVersion = 0L
            values.clear()
        }
    }

    fun preload() {
        synchronized(lock) {
            ensureLoadedLocked()
        }
    }

    operator fun get(key: String): String? = synchronized(lock) {
        ensureLoadedLocked()
        values[key]
    }

    operator fun set(key: String, value: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (values[key] == value) return
            values[key] = value
            dirty = true
            mutationVersion++
        }
    }

    fun persist() {
        val target: File
        val snapshot: List<Pair<String, String>>
        val snapshotVersion: Long
        synchronized(lock) {
            ensureLoadedLocked()
            if (!dirty) return
            target = storeFile ?: return
            snapshot = values.entries.map { it.key to it.value }
            snapshotVersion = mutationVersion
        }

        val parent = target.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, target.name + ".tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { output ->
                output.writeInt(FILE_MAGIC)
                output.writeInt(algorithmVersion)
                output.writeInt(snapshot.size)
                snapshot.forEach { (key, value) ->
                    output.writeUtf8String(key)
                    output.writeUtf8String(value)
                }
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
            synchronized(lock) {
                if (mutationVersion == snapshotVersion) dirty = false
            }
        }
    }

    internal fun inMemorySizeForTest(): Int = synchronized(lock) { values.size }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = storeFile ?: return
        if (!file.isFile) return

        val loadedValues = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != FILE_MAGIC) return@use emptyList()
                if (input.readInt() != algorithmVersion) return@use emptyList()
                val count = input.readInt()
                if (count !in 0..maxEntries) return@use emptyList()
                buildList(count) {
                    repeat(count) {
                        add(input.readUtf8String() to input.readUtf8String())
                    }
                }
            }
        }.getOrElse { emptyList() }

        loadedValues.forEach { (key, value) -> values[key] = value }
    }

    private fun DataOutputStream.writeUtf8String(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf8String(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) throw EOFException("Invalid sort-key string size: $size")
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val FILE_MAGIC = 0x4D534B43 // "MSKC"
        const val MAX_STRING_BYTES = 256 * 1024
    }
}

