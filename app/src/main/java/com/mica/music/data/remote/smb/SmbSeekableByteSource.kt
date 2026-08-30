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
    ): Int = file.read(fileOffset, buffer, bufferOffset, length)

    override fun close() {
        if (closed.compareAndSet(false, true)) file.close()
    }
}
