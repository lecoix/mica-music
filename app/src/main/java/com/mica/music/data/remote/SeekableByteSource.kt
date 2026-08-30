package com.mica.music.data.remote

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.scanner.EmbeddedLyricsReader
import com.mica.music.data.scanner.EmbeddedLyricsResolver
import com.mica.music.data.scanner.TagLibReader
import java.io.Closeable
import java.io.IOException
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

        val target = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        var total = 0
        while (total < target) {
            val cursor = fileOffset + total
            val remaining = target - total

            if (cursor >= cacheStart && cursor < cacheStart + cacheLength) {
                val cachedOffset = (cursor - cacheStart).toInt()
                val copied = minOf(remaining, cacheLength - cachedOffset)
                cache.copyInto(
                    destination = buffer,
                    destinationOffset = bufferOffset + total,
                    startIndex = cachedOffset,
                    endIndex = cachedOffset + copied,
                )
                total += copied
                continue
            }

            if (remaining > cache.size) {
                val fetched = delegate.readAt(cursor, buffer, bufferOffset + total, remaining)
                if (fetched < 0) {
                    throw IOException("Remote metadata read ended before the known file boundary")
                }
                if (fetched == 0) {
                    throw IOException("Remote metadata read made no progress")
                }
                total += fetched
                continue
            }

            val requested = minOf(cache.size.toLong(), sizeBytes - cursor).toInt()
            val fetched = delegate.readAt(cursor, cache, 0, requested)
            if (fetched < 0) {
                cacheStart = -1L
                cacheLength = 0
                throw IOException("Remote metadata read ended before the known file boundary")
            }
            if (fetched == 0) {
                cacheStart = -1L
                cacheLength = 0
                throw IOException("Remote metadata read made no progress")
            }
            cacheStart = cursor
            cacheLength = fetched
        }
        total
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
    val hasEmbeddedArtwork: Boolean = false,
)

// Revision 2 re-probes catalogs that may have cached metadata from the pre-fix read-ahead path.
internal const val REMOTE_METADATA_PROBE_REVISION = 2

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
                hasEmbeddedArtwork = tags.hasPictures,
            )
        }
    }
}

/** Reads the front embedded picture only when an artwork URI is actually opened. */
internal class AndroidTagLibEmbeddedArtworkLoader(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun load(source: SeekableByteSource): ByteArray {
        val bufferedSource = ReadAheadSeekableByteSource(source)
        val proxy = RemoteProxyFileDescriptor.open(appContext, bufferedSource)
        val result = proxy.descriptor.use { descriptor ->
            TagLibReader.read(descriptor, readPictures = true)?.frontCoverBytes
        }
        proxy.readFailure.get()?.let { throw it }
        return result?.takeIf(ByteArray::isNotEmpty)
            ?: throw java.io.IOException("Remote track has no readable embedded artwork")
    }
}

internal fun interface RemoteEmbeddedLyricsLoader {
    fun load(source: SeekableByteSource): LyricsDocument
}

/** Reads only TagLib text lyric properties on demand; binary SYLT fallback remains a separate seam. */
internal class AndroidTagLibEmbeddedLyricsLoader(
    context: Context,
) : RemoteEmbeddedLyricsLoader {
    private val appContext = context.applicationContext

    override fun load(source: SeekableByteSource): LyricsDocument {
        val bufferedSource = ReadAheadSeekableByteSource(source)
        val proxy = RemoteProxyFileDescriptor.open(appContext, bufferedSource)
        val result = proxy.descriptor.use { descriptor ->
            TagLibReader.read(descriptor, readPictures = false)
        }
        proxy.readFailure.get()?.let { throw it }
        return result?.let { tags ->
            EmbeddedLyricsResolver.selectTagLibCandidate(
                candidates = tags.lyricsCandidates,
                parse = EmbeddedLyricsReader::parseTagLibTextDocument,
            )
        } ?: LyricsDocument(origin = LyricsOrigin.EMBEDDED)
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
