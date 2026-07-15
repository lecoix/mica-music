package com.mica.music.data

import android.util.LruCache

internal data class LyricsCacheKey(
    val songId: String,
    val revision: String,
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

    fun clear() = cache.evictAll()

    internal fun sizeBytes(): Int = cache.size()

    internal fun entryCount(): Int = cache.snapshot().size

    companion object {
        const val MAX_BYTES: Int = 16 * 1024 * 1024
        const val MAX_ENTRIES: Int = 12
    }
}

internal val SharedLyricsMemoryCache = LyricsDocumentMemoryCache()

private fun LyricsDocument.estimatedRetainedBytes(): Int {
    var bytes = 256L
    lines.forEach { line ->
        bytes += 128L + line.id.length * 2L
        line.parts.forEach { part -> bytes += 64L + part.text.length * 2L }
        line.tokens.forEach { token -> bytes += 80L + token.text.length * 2L }
    }
    return bytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
