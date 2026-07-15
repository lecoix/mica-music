package com.mica.music.data.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
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

    suspend fun <T> measureSuspend(stage: String, block: suspend () -> T): T {
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
            .also {
                Log.i(ScanPerfTag, it)
                DiagnosticLog.event(ScanPerfTag, it)
            }
    }

    private class Stage {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
    }
}

internal fun TrackDraft.scanSongId(): String =
    if (mediaStoreId > 0) "ms_$mediaStoreId" else "doc_${mediaUri.hashCode()}"

internal fun TrackDraft.reusableCachedSong(
    context: Context,
    cachedById: Map<String, Song>,
    requireDeepMetadata: Boolean = false,
    requireDirectLyrics: Boolean = false,
    requireFreshEmbeddedLyrics: Boolean = false,
    forceRefreshLyrics: Boolean = false,
    forceRefreshArtwork: Boolean = false,
): Song? {
    val cached = unchangedCachedSong(cachedById) ?: return null
    if (forceRefreshLyrics) return null
    if (forceRefreshArtwork && cached.hasRefreshableArtwork(context)) return null
    return cached.takeIf {
        AlbumArtCache.hasReadableCachedArt(context, it) &&
        (!requireDeepMetadata || it.hasDeepMetadata()) &&
            (!requireDeepMetadata || it.discNumber >= 0) &&
            (!requireDeepMetadata || !isDsdDraft() || DsdSupport.isDsdMetadata(it.metadata)) &&
            (!requireDirectLyrics || it.lyricsDocument.lines.isNotEmpty()) &&
            (!requireFreshEmbeddedLyrics || it.lyricsDocument.lines.isNotEmpty())
    }
}

internal fun TrackDraft.unchangedCachedSong(cachedById: Map<String, Song>): Song? {
    val cached = cachedById[scanSongId()] ?: return null
    return cached.takeIf {
            it.mediaUri == mediaUri &&
            it.sizeBytes == sizeBytes &&
            it.dateModifiedMs == dateModifiedMs &&
            it.externalLyricsSignature == externalLyricsSignature
    }
}

internal fun TrackDraft.unchangedCachedSongForProbe(
    cachedById: Map<String, Song>,
    forceRefreshLyrics: Boolean,
): Song? =
    unchangedCachedSong(cachedById)?.let { cached ->
        if (forceRefreshLyrics) cached.copy(lyricsDocument = com.mica.music.data.LyricsDocument()) else cached
    }

private fun Song.hasDeepMetadata(): Boolean =
    metadata.sampleRateHz > 0 ||
        metadata.bitsPerSample != null ||
        codecLabel.isNotBlank()

private fun Song.hasRefreshableArtwork(context: Context): Boolean =
    albumArtUri.isNullOrBlank() ||
        AlbumArtCache.isCachedArtUri(context, albumArtUri)

internal fun TrackDraft.mayContainMp4EmbeddedLyrics(): Boolean {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val mime = mimeType.lowercase()
    return ext in setOf("m4a", "m4b", "mp4", "aac", "alac") ||
        mime.contains("mp4") ||
        mime.contains("alac")
}

internal fun TrackDraft.requiresAudioTrackProbe(): Boolean {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    if (ext in setOf("m4a", "m4b", "m4p", "mp4", "aac", "alac")) return true
    if (DsdSupport.isDsdExtension(ext)) return false
    if (ext in setOf("mp3", "flac", "wav", "wave", "ogg", "oga", "opus", "wma")) return false

    val mime = mimeType.lowercase()
    if (mime.isBlank() || mime == "audio/*" || mime == "application/octet-stream") return true
    if (
        mime.contains("mp4") ||
        mime.contains("m4a") ||
        mime.contains("aac") ||
        mime.contains("alac")
    ) return true
    if (DsdSupport.isDsdMime(mime)) return false
    return when {
        mime == "audio/mpeg" || mime.contains("mp3") -> false
        mime.contains("flac") -> false
        mime.contains("wav") -> false
        mime.contains("ogg") -> false
        mime.contains("opus") -> false
        mime.contains("wma") -> false
        else -> true
    }
}

internal fun TrackDraft.isDsdDraft(): Boolean {
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return DsdSupport.isDsdExtension(ext) || DsdSupport.isDsdMime(mimeType)
}

private fun Long.nanosToMs(): Long = this / 1_000_000L
