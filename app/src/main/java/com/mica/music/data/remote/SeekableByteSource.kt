package com.mica.music.data.remote

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.mica.music.data.scanner.TagLibReader
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Protocol-neutral, read-only random-access byte source for remote media. */
internal interface SeekableByteSource : Closeable {
    val sizeBytes: Long

    fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int
}

/**
 * One bounded window shared by all proxy-fd reads for a single metadata probe.
 * Tag readers commonly issue many small adjacent reads; collapsing those into one protocol read
 * removes an RTT per small read without turning catalog sync into whole-file download.
 */
internal class ReadAheadSeekableByteSource(
    private val delegate: SeekableByteSource,
    private val readAheadBytes: Int = DEFAULT_REMOTE_METADATA_READ_AHEAD_BYTES,
) : SeekableByteSource {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val cache = ByteArray(readAheadBytes)
    private var cacheStart = -1L
    private var cacheLength = 0

    init {
        require(readAheadBytes > 0) { "readAheadBytes must be positive" }
    }

    override val sizeBytes: Long
        get() = delegate.sizeBytes

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int = synchronized(lock) {
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return@synchronized 0
        if (fileOffset >= sizeBytes) return@synchronized -1

        if (fileOffset >= cacheStart && fileOffset < cacheStart + cacheLength) {
            val cachedOffset = (fileOffset - cacheStart).toInt()
            val copied = minOf(length, cacheLength - cachedOffset)
            cache.copyInto(buffer, bufferOffset, cachedOffset, cachedOffset + copied)
            return@synchronized copied
        }

        if (length > cache.size) {
            return@synchronized delegate.readAt(fileOffset, buffer, bufferOffset, length)
        }

        val requested = minOf(cache.size.toLong(), sizeBytes - fileOffset).toInt()
        val fetched = delegate.readAt(fileOffset, cache, 0, requested)
        if (fetched <= 0) {
            cacheStart = -1L
            cacheLength = 0
            return@synchronized fetched
        }
        cacheStart = fileOffset
        cacheLength = fetched
        val copied = minOf(length, fetched)
        cache.copyInto(buffer, bufferOffset, 0, copied)
        copied
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}

internal const val DEFAULT_REMOTE_METADATA_READ_AHEAD_BYTES = 1024 * 1024
internal const val REMOTE_METADATA_IO_CONCURRENCY = 4

/** Browse metadata that can be populated without downloading artwork or lyric payloads. */
internal data class RemoteTrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val durationSec: Int = 0,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
)

internal fun interface RemoteTrackMetadataProbe {
    fun probe(fileName: String, source: SeekableByteSource): RemoteTrackMetadata?
}

/**
 * Reuses Mica's existing TagLib fd reader over a proxy descriptor backed by protocol random reads.
 * Pictures stay disabled here: catalog sync must remain bounded for large remote libraries.
 */
internal class AndroidTagLibRemoteTrackMetadataProbe(
    context: Context,
) : RemoteTrackMetadataProbe {
    private val appContext = context.applicationContext

    override fun probe(fileName: String, source: SeekableByteSource): RemoteTrackMetadata? {
        val bufferedSource = ReadAheadSeekableByteSource(source)
        val proxy = RemoteProxyFileDescriptor.open(appContext, bufferedSource)
        val result = proxy.descriptor.use { descriptor ->
            TagLibReader.read(descriptor, readPictures = false)
        }
        proxy.readFailure.get()?.let { throw it }
        return result?.let { tags ->
            RemoteTrackMetadata(
                title = tags.title,
                artist = tags.artist,
                album = tags.album,
                albumArtist = tags.albumArtist,
                durationSec = tags.durationSec.coerceAtLeast(0),
                year = tags.year.coerceAtLeast(0),
                trackNumber = tags.trackNumber.coerceAtLeast(0),
                discNumber = tags.discNumber.coerceAtLeast(0),
            )
        }
    }
}

internal data class RemoteProxyFileDescriptor(
    val descriptor: ParcelFileDescriptor,
    val readFailure: AtomicReference<Throwable?>,
) {
    companion object {
        private val callbackHandlers: List<Handler> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            List(REMOTE_METADATA_IO_CONCURRENCY) { lane ->
                HandlerThread("mica-remote-proxy-fd-$lane")
                    .apply { start() }
                    .let { Handler(it.looper) }
            }
        }
        private val nextCallbackLane = AtomicInteger()

        fun open(context: Context, source: SeekableByteSource): RemoteProxyFileDescriptor {
            val handler = callbackHandlers[Math.floorMod(nextCallbackLane.getAndIncrement(), callbackHandlers.size)]
            val failure = AtomicReference<Throwable?>(null)
            val released = AtomicBoolean(false)
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = source.sizeBytes.coerceAtLeast(0L)

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset < 0L || size < 0) throw ErrnoException("remote-read", OsConstants.EINVAL)
                    if (offset >= source.sizeBytes) return 0
                    val requested = minOf(size, data.size)
                    if (requested == 0) return 0
                    return try {
                        source.readAt(offset, data, 0, requested).coerceAtLeast(0)
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                        throw ErrnoException("remote-read", OsConstants.EIO)
                    }
                }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    throw ErrnoException("remote-write", OsConstants.EBADF)

                override fun onFsync() = Unit

                override fun onRelease() {
                    if (!released.compareAndSet(false, true)) return
                    runCatching { source.close() }
                        .onFailure { failure.compareAndSet(null, it) }
                }
            }
            return try {
                val storage = context.getSystemService(StorageManager::class.java)
                RemoteProxyFileDescriptor(
                    descriptor = storage.openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_ONLY,
                        callback,
                        handler,
                    ),
                    readFailure = failure,
                )
            } catch (error: Throwable) {
                if (released.compareAndSet(false, true)) runCatching { source.close() }
                throw error
            }
        }
    }
}
