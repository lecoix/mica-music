package com.mica.music.data.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.data.SongIdentity
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ScanPerfTag = "ScanPerf"
internal const val CURRENT_EMBEDDED_LYRICS_PROBE_VERSION = 1

private fun embeddedLyricsProbeRevision(
    songId: String,
    sizeBytes: Long,
    dateModifiedMs: Long,
): String = buildString {
    append(CURRENT_EMBEDDED_LYRICS_PROBE_VERSION)
    append('\u0001')
    append(songId)
    append('\u0001')
    append(sizeBytes.coerceAtLeast(0L))
    append('\u0001')
    append(dateModifiedMs.coerceAtLeast(0L))
}

internal class ScanProfiler(private val source: String) {
    private val startedAtNs = SystemClock.elapsedRealtimeNanos()
    private val stages = ConcurrentHashMap<String, Stage>()
    private val byteStages = ConcurrentHashMap<String, ByteStage>()
    private val reuseMisses = ConcurrentHashMap<String, AtomicInteger>()

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

    fun recordBytes(stage: String, byteCount: Long) {
        val item = byteStages.computeIfAbsent(stage) { ByteStage() }
        item.count.incrementAndGet()
        item.totalBytes.addAndGet(byteCount.coerceAtLeast(0L))
    }

    fun recordReuseMiss(reason: String) {
        reuseMisses.computeIfAbsent(reason) { AtomicInteger(0) }.incrementAndGet()
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
        val byteSummary = byteStages.entries
            .sortedByDescending { it.value.totalBytes.get() }
            .joinToString(" | ") { (name, stage) ->
                val count = stage.count.get()
                val totalBytes = stage.totalBytes.get()
                val totalMiB = totalBytes / (1024L * 1024L)
                val avgKiB = if (count > 0) totalBytes / count / 1024L else 0L
                "$name=${totalMiB}MiB/${count}x(avg ${avgKiB}KiB)"
            }
        val reuseMissSummary = reuseMisses.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { (reason, count) -> "$reason=${count.get()}" }
        return buildString {
            append("source=$source wall=${totalMs}ms tracks=$total reused=$reused probed=$probed")
            append(" stages(cumulative): $stageSummary")
            if (byteSummary.isNotEmpty()) append(" bytes(cumulative): $byteSummary")
            if (reuseMissSummary.isNotEmpty()) append(" reuseMisses=$reuseMissSummary")
        }
            .also {
                Log.i(ScanPerfTag, it)
                DiagnosticLog.event(ScanPerfTag, it)
            }
    }

    private class Stage {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
    }


    private class ByteStage {
        val count = AtomicInteger(0)
        val totalBytes = AtomicLong(0)
    }
}

internal fun TrackDraft.scanSongId(): String =
    if (mediaStoreId > 0) "ms_$mediaStoreId" else SongIdentity.documentId(mediaUri)

internal fun TrackDraft.embeddedLyricsProbeRevisionForCurrentFile(): String =
    embeddedLyricsProbeRevision(scanSongId(), sizeBytes, dateModifiedMs)

internal fun Song.embeddedLyricsProbeRevisionForCurrentFile(): String =
    embeddedLyricsProbeRevision(id, sizeBytes, dateModifiedMs)

/** Preserve the original library-add time when an existing song is re-probed. */
internal fun TrackDraft.dateAddedMsFor(cachedSong: Song?): Long =
    cachedSong?.dateAddedMs ?: dateAddedMs

internal fun TrackDraft.reusableCachedSong(
    context: Context,
    cachedById: Map<String, Song>,
    requireDeepMetadata: Boolean = false,
    requireDirectLyrics: Boolean = false,
    requireFreshEmbeddedLyrics: Boolean = false,
    forceRefreshLyrics: Boolean = false,
    forceRefreshArtwork: Boolean = false,
    onReuseMiss: ((String) -> Unit)? = null,
): Song? {
    fun miss(reason: String): Song? {
        onReuseMiss?.invoke(reason)
        return null
    }

    val cached = cachedById[scanSongId()] ?: return miss("cache-missing")
    if (cached.mediaUri != mediaUri) return miss("media-uri-changed")
    if (cached.sizeBytes != sizeBytes) return miss("size-changed")
    if (cached.dateModifiedMs != dateModifiedMs) return miss("date-modified-changed")
    if (cached.externalLyricsSignature != externalLyricsSignature) {
        return miss("external-lyrics-changed")
    }
    if (forceRefreshLyrics) return miss("force-lyrics")
    if (forceRefreshArtwork && cached.hasRefreshableArtwork(context)) return miss("force-artwork")
    if (!AlbumArtCache.hasReadableCachedArt(context, cached)) return miss("art-cache-unreadable")
    if (requireDeepMetadata && !cached.hasDeepMetadata()) return miss("deep-metadata-missing")
    if (
        requireDeepMetadata &&
        cached.metadataScanVersion < AudioMetadataProbe.CURRENT_METADATA_SCAN_VERSION
    ) {
        return miss("metadata-scan-version-stale")
    }
    if (requireDeepMetadata && cached.discNumber < 0) return miss("disc-number-missing")
    if (requireDeepMetadata && isDsdDraft() && !DsdSupport.isDsdMetadata(cached.metadata)) {
        return miss("dsd-metadata-invalid")
    }
    if (requireDirectLyrics && cached.lyricsDocument.lines.isEmpty()) {
        return miss("direct-lyrics-missing")
    }
    if (requireFreshEmbeddedLyrics &&
        (!hasStableEmbeddedLyricsFingerprint() ||
            cached.embeddedLyricsProbeRevision != embeddedLyricsProbeRevisionForCurrentFile())
    ) {
        return miss(
            if (hasStableEmbeddedLyricsFingerprint()) {
                "embedded-lyrics-probe-stale"
            } else {
                "embedded-lyrics-fingerprint-unreliable"
            },
        )
    }
    return cached
}

private fun TrackDraft.hasStableEmbeddedLyricsFingerprint(): Boolean =
    sizeBytes > 0L && dateModifiedMs > 0L

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

internal fun TrackDraft.forceRefreshLyricsFor(options: ScanOptions): Boolean =
    options.forceRefreshLyrics || scanSongId() in options.forceRefreshSongIds

internal fun TrackDraft.forceRefreshArtworkFor(options: ScanOptions): Boolean =
    options.forceRefreshArtwork || scanSongId() in options.forceRefreshSongIds

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
