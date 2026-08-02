package com.mica.music.data.scanner

/**
 * 曲库扫描参数（来自 [com.mica.music.data.preferences.LibraryScanSettings]）。
 */
data class ScanOptions(
    val minDurationMs: Long = 60_000L,
    val includeNonMusicByMime: Boolean = true,
    val deepMetadataProbe: Boolean = true,
    val excludedDirectories: List<String> = emptyList(),
    val forceRefreshLyrics: Boolean = false,
    val forceRefreshArtwork: Boolean = false,
    /** One-off metadata refresh targets, normally used after an external tag editor returns. */
    val forceRefreshSongIds: Set<String> = emptySet(),
)

object ExcludedScanDirectories {
    fun normalize(path: String): String =
        path.replace('\\', '/')
            .trim()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/")

    fun normalizeAll(paths: List<String>): List<String> =
        paths.map(::normalize)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    fun isExcluded(path: String, excludedDirectories: List<String>): Boolean {
        val normalizedPath = normalize(path)
        if (normalizedPath.isBlank()) return false
        return normalizeAll(excludedDirectories).any { excluded ->
            normalizedPath.equals(excluded, ignoreCase = true) ||
                normalizedPath.startsWith("$excluded/", ignoreCase = true)
        }
    }
}
