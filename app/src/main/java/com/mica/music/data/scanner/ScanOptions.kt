package com.mica.music.data.scanner

/**
 * 曲库扫描参数（来自 [com.mica.music.data.AppPreferences]）。
 */
data class ScanOptions(
    val minDurationMs: Long = 60_000L,
    val includeNonMusicByMime: Boolean = true,
    val deepMetadataProbe: Boolean = true,
)
