package com.mica.music.data.scanner

import android.os.SystemClock
import android.util.Log
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ScanPerfTag = "ScanPerf"

internal class ScanProfiler(private val source: String) {
    private val startedAtNs = SystemClock.elapsedRealtimeNanos()
    private val stages = ConcurrentHashMap<String, Stage>()

    fun <T> measure(stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            record(stage, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    fun record(stage: String, elapsedNs: Long) {
        val item = stages.computeIfAbsent(stage) { Stage() }
        item.count.incrementAndGet()
        item.totalNs.addAndGet(elapsedNs)
    }

    fun finish(total: Int, reused: Int, probed: Int): String {
        val totalMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs).nanosToMs()
        val stageSummary = stages.entries
            .sortedByDescending { it.value.totalNs.get() }
            .joinToString(" | ") { (name, stage) ->
                val count = stage.count.get()
                val totalStageMs = stage.totalNs.get().nanosToMs()
                val avgMs = if (count > 0) totalStageMs / count else 0
                "$name=${totalStageMs}ms/${count}x(avg ${avgMs}ms)"
            }
        return "source=$source wall=${totalMs}ms tracks=$total reused=$reused probed=$probed stages(cumulative): $stageSummary"
            .also { Log.i(ScanPerfTag, it) }
    }

    private class Stage {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
    }
}

internal fun TrackDraft.scanSongId(): String =
    if (mediaStoreId > 0) "ms_$mediaStoreId" else "doc_${mediaUri.hashCode()}"

internal fun TrackDraft.reusableCachedSong(
    cachedById: Map<String, Song>,
    requireDeepMetadata: Boolean = false,
    requireDirectLyrics: Boolean = false,
    requireFreshEmbeddedLyrics: Boolean = false,
): Song? {
    val cached = unchangedCachedSong(cachedById) ?: return null
    return cached.takeIf {
        (!requireDeepMetadata || it.hasDeepMetadata()) &&
            (!requireDeepMetadata || !isDsdDraft() || DsdSupport.isDsdMetadata(it.metadata)) &&
            (!requireDirectLyrics || it.lyrics.isNotEmpty()) &&
            (!requireFreshEmbeddedLyrics || it.lyrics.isNotEmpty())
    }
}

internal fun TrackDraft.unchangedCachedSong(cachedById: Map<String, Song>): Song? {
    val cached = cachedById[scanSongId()] ?: return null
    return cached.takeIf {
        it.mediaUri == mediaUri &&
            it.sizeBytes == sizeBytes &&
            it.dateModifiedMs == dateModifiedMs
    }
}

private fun Song.hasDeepMetadata(): Boolean =
    metadata.sampleRateHz > 0 ||
        metadata.bitsPerSample != null ||
        codecLabel.isNotBlank()

internal fun TrackDraft.mayContainMp4EmbeddedLyrics(): Boolean {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val mime = mimeType.lowercase()
    return ext in setOf("m4a", "m4b", "mp4", "aac", "alac") ||
        mime.contains("mp4") ||
        mime.contains("alac")
}

internal fun TrackDraft.isDsdDraft(): Boolean {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return DsdSupport.isDsdExtension(ext) || DsdSupport.isDsdMime(mimeType)
}

private fun Long.nanosToMs(): Long = this / 1_000_000L
