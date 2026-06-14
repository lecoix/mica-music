package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.MediaStoreScanner
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.scanner.ScanResult

internal interface LibraryScanner {
    suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult

    suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult
}

internal interface LibraryStore {
    suspend fun loadCached(): CachedLibrary?

    suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult

    suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult

    suspend fun clear()
}

internal interface ScanEnvironment {
    fun hasAudioReadPermission(): Boolean
    fun canReadTree(treeUri: Uri): Boolean
    fun currentTimeMillis(): Long
    fun playStats(songId: String): PlayStats
    fun clearTransientCache()
    fun persistLastScanSource(source: ScanSource)
}

internal class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {
    override suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = AppPreferences.scanOptions(context),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
    )

    override suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = AppPreferences.scanOptions(context),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
    )
}

internal class RoomLibraryStore(
    context: Context,
) : LibraryStore {
    private val repository = LibraryRepository(context)

    override suspend fun loadCached(): CachedLibrary? = repository.loadCached()

    override suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult = repository.save(songs, lastScanAtMs, lastScanSource, totalSizeMb)

    override suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult =
        repository.syncIncremental(songs, lastScanAtMs, lastScanSource, totalSizeMb)

    override suspend fun clear() = repository.clear()
}

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

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun playStats(songId: String): PlayStats =
        PlayHistoryStore.getStats(context, songId)

    override fun clearTransientCache() =
        ScanCacheManager.clearTransientScanCache(context)

    override fun persistLastScanSource(source: ScanSource) =
        AppPreferences.setLastScanSource(context, source)
}
