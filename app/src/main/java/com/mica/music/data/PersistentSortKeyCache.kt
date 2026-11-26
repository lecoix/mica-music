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
import java.util.concurrent.atomic.AtomicLong

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
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(maxEntries >= 0) { "maxEntries must not be negative" }
        require(maxTotalBytes >= 0) { "maxTotalBytes must not be negative" }
    }

    private data class CacheEntry(
        val value: String,
        val serializedBytes: Int,
    )

    private val lock = Any()
    private val persistLock = Any()
    private val tempFileSequence = AtomicLong(0L)
    private val values = LinkedHashMap<String, CacheEntry>(maxEntries.coerceAtLeast(16), 0.75f, true)

    private var storeFile: File? = null
    private var loaded = false
    private var dirty = false
    private var configureGeneration = 0L
    private var mutationVersion = 0L
    private var lastPublishedVersion = 0L
    private var totalSerializedBytes = 0

    internal var beforePersistMoveForTest: ((snapshotVersion: Long) -> Unit)? = null

    fun configure(file: File) {
        synchronized(lock) {
            if (storeFile?.absolutePath == file.absolutePath) return
            storeFile = file
            loaded = false
            dirty = false
            configureGeneration++
            mutationVersion = 0L
            lastPublishedVersion = 0L
            totalSerializedBytes = 0
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
        values[key]?.value
    }

    operator fun set(key: String, value: String) {
        val entryBytes = serializedEntryBytesOrNull(key, value) ?: return
        synchronized(lock) {
            ensureLoadedLocked()
            val existing = values[key]
            if (existing?.value == value) return
            putLocked(key, value, entryBytes)
            dirty = true
            mutationVersion++
        }
    }

    fun persist() {
        val target: File
        val snapshot: List<Pair<String, String>>
        val snapshotVersion: Long
        val snapshotConfigureGeneration: Long
        synchronized(lock) {
            ensureLoadedLocked()
            if (!dirty) return
            target = storeFile ?: return
            snapshot = values.entries.map { it.key to it.value.value }
            snapshotVersion = mutationVersion
            snapshotConfigureGeneration = configureGeneration
        }

        val parent = target.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, "${target.name}.${tempFileSequence.incrementAndGet()}.tmp")
        try {
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
                beforePersistMoveForTest?.invoke(snapshotVersion)
                synchronized(persistLock) {
                    val stillCurrent = synchronized(lock) {
                        configureGeneration == snapshotConfigureGeneration &&
                            storeFile?.absolutePath == target.absolutePath &&
                            snapshotVersion > lastPublishedVersion
                    }
                    if (!stillCurrent) return@runCatching

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
                        if (configureGeneration == snapshotConfigureGeneration &&
                            storeFile?.absolutePath == target.absolutePath &&
                            snapshotVersion > lastPublishedVersion
                        ) {
                            lastPublishedVersion = snapshotVersion
                            if (mutationVersion == snapshotVersion) dirty = false
                        }
                    }
                }
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    internal fun inMemorySizeForTest(): Int = synchronized(lock) { values.size }

    internal fun inMemoryBytesForTest(): Int = synchronized(lock) { totalSerializedBytes }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = storeFile ?: return
        if (!file.isFile) return
        val maxFileBytes = HEADER_BYTES.toLong() + maxTotalBytes.toLong()
        if (file.length() > maxFileBytes) return

        val loadedValues = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != FILE_MAGIC) return@use emptyList()
                if (input.readInt() != algorithmVersion) return@use emptyList()
                val count = input.readInt()
                if (count !in 0..maxEntries) return@use emptyList()
                var cumulativeBytes = 0L
                buildList(count) {
                    repeat(count) {
                        val key = input.readUtf8String()
                        val value = input.readUtf8String()
                        val entryBytes = serializedEntryBytesOrNull(key, value)
                            ?: throw EOFException("Entry exceeds cache byte budget")
                        cumulativeBytes += entryBytes
                        if (cumulativeBytes > maxTotalBytes) {
                            throw EOFException("Sort-key cache exceeds total byte budget: $cumulativeBytes")
                        }
                        add(Triple(key, value, entryBytes))
                    }
                }
            }
        }.getOrElse { emptyList() }

        loadedValues.forEach { (key, value, entryBytes) ->
            putLocked(key, value, entryBytes)
        }
    }

    private fun putLocked(key: String, value: String, entryBytes: Int) {
        if (maxEntries == 0 || entryBytes > maxTotalBytes) return
        values.remove(key)?.let { previous ->
            totalSerializedBytes -= previous.serializedBytes
        }
        values[key] = CacheEntry(value, entryBytes)
        totalSerializedBytes += entryBytes
        val iterator = values.entries.iterator()
        while ((values.size > maxEntries || totalSerializedBytes > maxTotalBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            totalSerializedBytes -= eldest.value.serializedBytes
            iterator.remove()
        }
    }

    private fun serializedEntryBytesOrNull(key: String, value: String): Int? {
        val keyBytes = key.toByteArray(Charsets.UTF_8).size
        val valueBytes = value.toByteArray(Charsets.UTF_8).size
        if (keyBytes > MAX_STRING_BYTES || valueBytes > MAX_STRING_BYTES) return null
        val total = ENTRY_HEADER_BYTES.toLong() + keyBytes.toLong() + valueBytes.toLong()
        if (total > maxTotalBytes.toLong()) return null
        return total.toInt()
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
        const val HEADER_BYTES = 12
        const val ENTRY_HEADER_BYTES = 8
        const val MAX_STRING_BYTES = 256 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 8 * 1024 * 1024
    }
}

