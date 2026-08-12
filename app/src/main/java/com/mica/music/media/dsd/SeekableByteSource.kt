package com.mica.music.media.dsd

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Read-only random-access byte source for the P5 DSD container layer.
 *
 * The container reader deliberately depends on this protocol-neutral seam instead of Android Uri,
 * FileDescriptor, HTTP, SMB, or USB. Future remote sources only need another adapter here.
 */
interface SeekableByteSource : Closeable {
    val length: Long?
    val identity: ByteSourceIdentity

    /** Returns bytes read, or -1 at EOF. */
    fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        byteCount: Int = destination.size - destinationOffset,
    ): Int
}

data class ByteSourceIdentity(
    val stableId: String,
    val generation: Long = 0L,
)

class LocalFileByteSource(
    file: File,
    generation: Long = file.lastModified(),
) : SeekableByteSource {
    private val randomAccessFile = RandomAccessFile(file, "r")

    override val length: Long = randomAccessFile.length()
    override val identity: ByteSourceIdentity = ByteSourceIdentity(
        stableId = file.canonicalPath,
        generation = generation,
    )

    @Synchronized
    override fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int {
        require(position >= 0L) { "position must be non-negative" }
        require(destinationOffset >= 0 && byteCount >= 0 && destinationOffset + byteCount <= destination.size) {
            "destination range out of bounds"
        }
        if (byteCount == 0) return 0
        if (position >= length) return -1
        randomAccessFile.seek(position)
        return randomAccessFile.read(destination, destinationOffset, byteCount)
    }

    override fun close() {
        randomAccessFile.close()
    }
}

internal fun SeekableByteSource.readFullyAt(position: Long, byteCount: Int): ByteArray {
    require(position >= 0L) { "position must be non-negative" }
    require(byteCount >= 0) { "byteCount must be non-negative" }
    val result = ByteArray(byteCount)
    var total = 0
    while (total < byteCount) {
        val read = readAt(position + total, result, total, byteCount - total)
        if (read <= 0) {
            throw DsdContainerException(
                DsdContainerFailure.TRUNCATED,
                "Source ended at ${position + total}; needed $byteCount bytes from $position",
            )
        }
        total += read
    }
    return result
}
