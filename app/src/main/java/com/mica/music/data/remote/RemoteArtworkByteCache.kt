package com.mica.music.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RemoteArtworkCacheKey(
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val catalogRevision: Long = 0L,
    val credentialRevision: Long,
    val opaqueArtworkId: String,
)

/**
 * Bounded in-process cache for authenticated remote artwork bytes.
 *
 * The key intentionally includes source/config/credential revisions so an edited source can never
 * reuse bytes fetched under stale routing or credentials. Concurrent opens for the same key share a
 * single loader; SystemUI commonly opens the same MediaSession artwork URI several times.
 */
internal class RemoteArtworkByteCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Mutex()
    private val entries = LinkedHashMap<RemoteArtworkCacheKey, ByteArray>(16, 0.75f, true)
    private val inFlight = mutableMapOf<RemoteArtworkCacheKey, CompletableDeferred<ByteArray>>()
    private var residentBytes = 0L

    init {
        require(maxBytes >= 0L) { "Remote artwork cache budget must be non-negative" }
    }

    suspend fun getOrLoad(
        key: RemoteArtworkCacheKey,
        loader: suspend () -> ByteArray,
    ): ByteArray {
        var ownsLoad = false
        val pending = lock.withLock {
            entries[key]?.let { cached ->
                return cached
            }
            inFlight[key]?.let { return@withLock it }
            CompletableDeferred<ByteArray>().also { created ->
                inFlight[key] = created
                ownsLoad = true
            }
        }
        if (!ownsLoad) return pending.await()

        return try {
            val loaded = loader()
            lock.withLock {
                inFlight.remove(key)
                cacheLocked(key, loaded)
                pending.complete(loaded)
            }
            loaded
        } catch (failure: Throwable) {
            lock.withLock {
                inFlight.remove(key)
                pending.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private fun cacheLocked(key: RemoteArtworkCacheKey, bytes: ByteArray) {
        val byteCount = bytes.size.toLong()
        if (byteCount > maxBytes) return

        entries.remove(key)?.let { previous -> residentBytes -= previous.size.toLong() }
        entries[key] = bytes
        residentBytes += byteCount
        while (residentBytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            residentBytes -= eldest.value.size.toLong()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L
    }
}
