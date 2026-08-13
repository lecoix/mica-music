package com.mica.music.media.usbprototype

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.media.dsd.ByteSourceIdentity
import com.mica.music.media.dsd.SeekableByteSource
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only Android boundary adapter; P5's source protocol remains Android-independent. */
internal class AndroidContentSeekableByteSource private constructor(
    private val input: ParcelFileDescriptor.AutoCloseInputStream,
    override val length: Long?,
    override val identity: ByteSourceIdentity,
) : SeekableByteSource {
    private val channel = input.channel
    private val closed = AtomicBoolean(false)

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
        check(!closed.get()) { "source is closed" }
        if (byteCount == 0) return 0
        if (length != null && position >= length) return -1
        val buffer = ByteBuffer.wrap(destination, destinationOffset, byteCount)
        return channel.read(buffer, position)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.close()
        }
    }

    companion object {
        fun open(
            resolver: ContentResolver,
            uri: Uri,
            generation: Long = 0L,
            knownLength: Long? = null,
        ): AndroidContentSeekableByteSource {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: error("Unable to open content source: $uri")
            return fromParcelFileDescriptor(
                pfd = pfd,
                stableId = uri.normalizeScheme().toString(),
                generation = generation,
                knownLength = knownLength,
            )
        }

        internal fun fromParcelFileDescriptor(
            pfd: ParcelFileDescriptor,
            stableId: String,
            generation: Long = 0L,
            knownLength: Long? = null,
        ): AndroidContentSeekableByteSource = try {
            val statLength = pfd.statSize.takeIf { it >= 0L }
            AndroidContentSeekableByteSource(
                input = ParcelFileDescriptor.AutoCloseInputStream(pfd),
                length = knownLength?.takeIf { it >= 0L } ?: statLength,
                identity = ByteSourceIdentity(stableId = stableId, generation = generation),
            )
        } catch (error: Throwable) {
            runCatching { pfd.close() }
            throw error
        }
    }
}
