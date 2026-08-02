package com.mica.music.data.preferences

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.mica.music.data.ScanSource
import com.mica.music.data.scanner.ExcludedScanDirectories
import com.mica.music.data.scanner.ScanOptions

/** 曲库扫描相关偏好：时长过滤、深度探测、排除目录、SAF 目录与扫描来源。 */
internal object LibraryScanSettings {
    private const val KEY_MIN_TRACK_DURATION_SEC = "min_track_duration_sec"
    private const val KEY_INCLUDE_NON_MUSIC_AUDIO = "include_non_music_audio"
    private const val KEY_DEEP_METADATA_PROBE = "deep_metadata_probe"
    private const val KEY_EXCLUDED_SCAN_DIRECTORIES = "excluded_scan_directories"
    private const val KEY_LIBRARY_TREE_URI = "library_tree_uri"
    private const val KEY_LIBRARY_FOLDER_LABEL = "library_folder_label"
    private const val KEY_LAST_SCAN_SOURCE = "last_scan_source"
    private const val KEY_LYRICS_PARSER_VERSION = "lyrics_parser_version"
    private const val KEY_LYRICS_RETRY_REQUIRED = "lyrics_retry_required"

    fun minTrackDurationSec(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_MIN_TRACK_DURATION_SEC, 60)

    fun setMinTrackDurationSec(context: Context, seconds: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_MIN_TRACK_DURATION_SEC, seconds.coerceAtLeast(0))
            .apply()
    }

    fun includeNonMusicAudio(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_INCLUDE_NON_MUSIC_AUDIO, true)

    fun setIncludeNonMusicAudio(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_INCLUDE_NON_MUSIC_AUDIO, enabled)
            .apply()
    }

    fun deepMetadataProbe(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_DEEP_METADATA_PROBE, true)

    fun setDeepMetadataProbe(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_DEEP_METADATA_PROBE, enabled)
            .apply()
    }

    fun excludedScanDirectories(context: Context): List<String> =
        MicaSettingsStore.prefs(context).getString(KEY_EXCLUDED_SCAN_DIRECTORIES, null)
            ?.lineSequence()
            ?.toList()
            ?.let(ExcludedScanDirectories::normalizeAll)
            ?: emptyList()

    fun setExcludedScanDirectories(context: Context, directories: List<String>) {
        val normalized = ExcludedScanDirectories.normalizeAll(directories)
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_EXCLUDED_SCAN_DIRECTORIES, normalized.joinToString("\n"))
            .apply()
    }

    fun libraryTreeUri(context: Context): Uri? =
        MicaSettingsStore.prefs(context).getString(KEY_LIBRARY_TREE_URI, null)?.toUri()

    fun libraryFolderLabel(context: Context): String? =
        MicaSettingsStore.prefs(context).getString(KEY_LIBRARY_FOLDER_LABEL, null)

    fun setLibraryFolder(context: Context, treeUri: Uri, label: String) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LIBRARY_TREE_URI, treeUri.toString())
            .putString(KEY_LIBRARY_FOLDER_LABEL, label)
            .apply()
    }

    fun clearLibraryFolder(context: Context) {
        MicaSettingsStore.prefs(context).edit()
            .remove(KEY_LIBRARY_TREE_URI)
            .remove(KEY_LIBRARY_FOLDER_LABEL)
            .apply()
    }

    fun lastScanSource(context: Context): ScanSource =
        ScanSource.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_LAST_SCAN_SOURCE, null))

    fun setLastScanSource(context: Context, source: ScanSource) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_LAST_SCAN_SOURCE, source.storageValue)
            .apply()
    }

    fun lyricsParserVersion(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_LYRICS_PARSER_VERSION, 0)

    fun setLyricsParserVersion(context: Context, version: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_LYRICS_PARSER_VERSION, version.coerceAtLeast(0))
            .apply()
    }

    fun lyricsRetryRequired(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_LYRICS_RETRY_REQUIRED, false)

    fun setLyricsRetryRequired(context: Context, required: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_LYRICS_RETRY_REQUIRED, required)
            .commit()
    }

    fun scanOptions(context: Context): ScanOptions = ScanOptions(
        minDurationMs = minTrackDurationSec(context).coerceAtLeast(0) * 1000L,
        // MediaStore music compatibility is intentionally always enabled; the old preference
        // key is retained only so existing installations can migrate without data loss.
        includeNonMusicByMime = true,
        deepMetadataProbe = deepMetadataProbe(context),
        excludedDirectories = excludedScanDirectories(context),
    )
}
