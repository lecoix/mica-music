package com.mica.music.data

import android.net.Uri

internal const val CURRENT_LYRICS_PARSER_VERSION = 12

internal interface ScanEnvironment {
    fun hasAudioReadPermission(): Boolean
    fun canReadTree(treeUri: Uri): Boolean
    /**
     * Persisted grant presence is only a recovery diagnostic. It never makes discovery COMPLETE.
     */
    fun hasPersistedTreeReadAccess(treeUri: Uri): Boolean = false
    /**
     * Best-effort provider reacquire used only after [canReadTree] already failed.
     * A false result remains fail-closed and must never trigger direct-file fallback.
     */
    fun canAcquireTreeProvider(treeUri: Uri): Boolean = true
    fun currentTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    fun playStats(songId: String): PlayStats
    fun playStatsSnapshot(songIds: Collection<String>): PlayStatsSnapshot =
        PlayStatsSnapshot.from(songIds.associateWith { songId -> playStats(songId) })
    fun clearTransientCache()
    /** Background cache maintenance against a snapshot that has already been committed. */
    fun pruneAlbumArtCache(songs: List<Song>)
    /** Folder-scan only: background first-frame posters for matched video covers. */
    fun enqueueVideoCoverPosterPrefetch(videoCoverRefs: Collection<com.mica.music.data.scanner.VideoCoverPosterRef>) = Unit
    fun persistLastScanSource(source: ScanSource)
    fun lyricsParserVersion(): Int = CURRENT_LYRICS_PARSER_VERSION
    fun persistLyricsParserVersion(version: Int) = Unit
    fun lyricsRetryRequired(): Boolean = false
    fun persistLyricsRetryRequired(required: Boolean) = Unit
}
