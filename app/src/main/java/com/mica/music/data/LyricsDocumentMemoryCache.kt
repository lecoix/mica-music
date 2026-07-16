package com.mica.music.data

import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class LyricsCacheKey(
    val songId: String,
    val revision: String,
    val lyricsDataVersion: Int,
    val contentGeneration: Long = 0L,
)

internal class LyricsDocumentMemoryCache(
    maxBytes: Int = MAX_BYTES,
) {
    private val minimumEntryBytes = (maxBytes / MAX_ENTRIES).coerceAtLeast(1)
    private val cache = object : LruCache<LyricsCacheKey, LyricsDocument>(maxBytes) {
        override fun sizeOf(key: LyricsCacheKey, value: LyricsDocument): Int =
            value.estimatedRetainedBytes().coerceAtLeast(minimumEntryBytes)
    }

    fun get(key: LyricsCacheKey): LyricsDocument? = cache.get(key)

    fun put(key: LyricsCacheKey, document: LyricsDocument) {
        cache.put(key, document)
    }

    fun removeSongs(songIds: Set<String>) {
        cache.snapshot().keys
            .filter { it.songId in songIds }
            .forEach(cache::remove)
    }

    fun songIds(): Set<String> = cache.snapshot().keys.mapTo(mutableSetOf(), LyricsCacheKey::songId)

    fun clear() = cache.evictAll()

    internal fun sizeBytes(): Int = cache.size()

    internal fun entryCount(): Int = cache.snapshot().size

    companion object {
        const val MAX_BYTES: Int = 16 * 1024 * 1024
        const val MAX_ENTRIES: Int = 12
    }
}

internal class LyricsCacheCoordinator(
    maxBytes: Int = LyricsDocumentMemoryCache.MAX_BYTES,
    maxConcurrentLoads: Int = 2,
) {
    private sealed interface LoadResult {
        data class Fresh(val document: LyricsDocument) : LoadResult
        data object Stale : LoadResult
    }

    private data class LoadDecision(
        val key: LyricsCacheKey? = null,
        val cached: LyricsDocument? = null,
        val deferred: CompletableDeferred<LoadResult>? = null,
        val isLeader: Boolean = false,
    )

    private val lock = Any()
    private val cache = LyricsDocumentMemoryCache(maxBytes)
    private val generations = mutableMapOf<String, Long>()
    private val inFlight = mutableMapOf<LyricsCacheKey, CompletableDeferred<LoadResult>>()
    private val invalidationEvents = MutableSharedFlow<Set<String>>()
    private val loadPermits = Semaphore(maxConcurrentLoads)
    private val prefetchPermits = Semaphore(1)

    val invalidations = invalidationEvents.asSharedFlow()

    fun get(songId: String, revision: String, lyricsDataVersion: Int): LyricsDocument? = synchronized(lock) {
        cache.get(key(songId, revision, lyricsDataVersion))
    }

    suspend fun load(
        songId: String,
        revision: String,
        lyricsDataVersion: Int,
        isPrefetch: Boolean = false,
        loader: suspend () -> LyricsDocument,
    ): LyricsDocument {
        while (true) {
            val decision = synchronized(lock) {
                val key = key(songId, revision, lyricsDataVersion)
                cache.get(key)?.let { LoadDecision(cached = it) } ?: run {
                    val existing = inFlight[key]
                    if (existing != null) {
                        LoadDecision(deferred = existing)
                    } else {
                        val created = CompletableDeferred<LoadResult>()
                        inFlight[key] = created
                        LoadDecision(key = key, deferred = created, isLeader = true)
                    }
                }
            }
            decision.cached?.let { return it }
            val deferred = checkNotNull(decision.deferred)
            if (!decision.isLeader) {
                when (val result = deferred.await()) {
                    is LoadResult.Fresh -> return result.document
                    LoadResult.Stale -> continue
                }
            }
            val document = try {
                if (isPrefetch) {
                    prefetchPermits.withPermit { loadPermits.withPermit { loader() } }
                } else {
                    loadPermits.withPermit { loader() }
                }
            } catch (error: Throwable) {
                failLoad(checkNotNull(decision.key), deferred, error)
                throw error
            }
            when (val result = finishLoad(checkNotNull(decision.key), deferred, document)) {
                is LoadResult.Fresh -> return result.document
                LoadResult.Stale -> continue
            }
        }
    }

    suspend fun invalidateSongs(songIds: Collection<String>) {
        val affected = songIds.toSet()
        if (affected.isEmpty()) return
        synchronized(lock) {
            val relevant = cache.songIds() + inFlight.keys.map(LyricsCacheKey::songId)
            affected.filterTo(mutableSetOf()) { it in relevant }.forEach { songId ->
                generations[songId] = generations.getOrDefault(songId, 0L) + 1L
            }
            cache.removeSongs(affected)
            pruneGenerations()
        }
        invalidationEvents.emit(affected)
    }

    fun clear() = synchronized(lock) {
        inFlight.keys.map(LyricsCacheKey::songId).forEach { songId ->
            generations[songId] = generations.getOrDefault(songId, 0L) + 1L
        }
        cache.clear()
        pruneGenerations()
    }

    internal fun sizeBytes(): Int = synchronized(lock) { cache.sizeBytes() }

    internal fun entryCount(): Int = synchronized(lock) { cache.entryCount() }

    private fun key(songId: String, revision: String, lyricsDataVersion: Int) = LyricsCacheKey(
        songId = songId,
        revision = revision,
        lyricsDataVersion = lyricsDataVersion,
        contentGeneration = generations.getOrDefault(songId, 0L),
    )

    private fun finishLoad(
        key: LyricsCacheKey,
        deferred: CompletableDeferred<LoadResult>,
        document: LyricsDocument,
    ): LoadResult = synchronized(lock) {
        inFlight.remove(key)
        val result = if (key.contentGeneration == generations.getOrDefault(key.songId, 0L)) {
            cache.put(key, document)
            LoadResult.Fresh(document)
        } else {
            LoadResult.Stale
        }
        deferred.complete(result)
        pruneGenerations()
        result
    }

    private fun failLoad(
        key: LyricsCacheKey,
        deferred: CompletableDeferred<LoadResult>,
        error: Throwable,
    ) = synchronized(lock) {
        inFlight.remove(key)
        deferred.completeExceptionally(error)
        pruneGenerations()
    }

    private fun pruneGenerations() {
        val retained = cache.songIds() + inFlight.keys.map(LyricsCacheKey::songId)
        generations.keys.retainAll(retained)
    }
}

internal val SharedLyricsMemoryCache = LyricsCacheCoordinator()

private fun LyricsDocument.estimatedRetainedBytes(): Int {
    var bytes = 256L
    lines.forEach { line ->
        bytes += 128L + line.id.length * 2L
        line.parts.forEach { part -> bytes += 64L + part.text.length * 2L }
        line.tokens.forEach { token -> bytes += 80L + token.text.length * 2L }
    }
    return bytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
