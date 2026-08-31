package com.mica.music.data.remote.smb

import com.mica.music.data.remote.SeekableByteSource
import java.util.concurrent.atomic.AtomicBoolean

internal class SmbSeekableByteSource(
    private val file: SmbRandomAccessFile,
) : SeekableByteSource {
    private val closed = AtomicBoolean(false)
    override val sizeBytes: Long
        get() = file.length.coerceAtLeast(0L)

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return 0
        if (fileOffset >= sizeBytes) return -1

        val requested = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        var total = 0
        while (total < requested) {
            val read = file.read(
                fileOffset = fileOffset + total,
                buffer = buffer,
                offset = bufferOffset + total,
                length = requested - total,
            )
            if (read < 0) {
                if (total == 0) return -1
                throw SmbException(SmbFailureKind.IO, "SMB random read ended before the known file boundary")
            }
            if (read == 0) {
                throw SmbException(SmbFailureKind.IO, "SMB random read made no progress")
            }
            total += read
        }
        return total
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) file.close()
    }
}
