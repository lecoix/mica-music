package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.scanner.VideoCoverPosterPrefetcher

internal class AndroidScanEnvironment(
    private val context: Context,
) : ScanEnvironment {
    override fun hasAudioReadPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            },
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun canReadTree(treeUri: Uri): Boolean =
        LibraryFolderStore.canReadTree(context, treeUri)

    override fun hasPersistedTreeReadAccess(treeUri: Uri): Boolean =
        LibraryFolderStore.hasPersistedTreeReadAccess(context, treeUri)

    override fun canAcquireTreeProvider(treeUri: Uri): Boolean =
        LibraryFolderStore.canAcquireTreeProvider(context, treeUri)

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun playStats(songId: String): PlayStats =
        PlayHistoryStore.getStats(context, songId)

    override fun playStatsSnapshot(songIds: Collection<String>): PlayStatsSnapshot =
        PlayHistoryStore.snapshotStats(context, songIds)

    override fun clearTransientCache() {
        VideoCoverPosterPrefetcher.cancel()
        ScanCacheManager.clearTransientScanCache(context)
    }

    override fun pruneAlbumArtCache(songs: List<Song>) =
        ScanCacheManager.pruneAlbumArtCache(context, songs)

    override fun enqueueVideoCoverPosterPrefetch(
        videoCoverRefs: Collection<com.mica.music.data.scanner.VideoCoverPosterRef>,
    ) {
        if (!PlaybackUiPreferences.videoAlbumCoverEnabled(context)) {
            VideoCoverPosterPrefetcher.cancel()
            return
        }
        VideoCoverPosterPrefetcher.enqueue(context, videoCoverRefs)
    }

    override fun persistLastScanSource(source: ScanSource) =
        LibraryScanSettings.setLastScanSource(context, source)

    override fun lyricsParserVersion(): Int = LibraryScanSettings.lyricsParserVersion(context)

    override fun persistLyricsParserVersion(version: Int) =
        LibraryScanSettings.setLyricsParserVersion(context, version)

    override fun lyricsRetryRequired(): Boolean = LibraryScanSettings.lyricsRetryRequired(context)

    override fun persistLyricsRetryRequired(required: Boolean) =
        LibraryScanSettings.setLyricsRetryRequired(context, required)
}
