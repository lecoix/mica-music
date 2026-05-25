package com.mica.music.data.local

import android.content.Context
import com.mica.music.data.ScanSource
import com.mica.music.data.Song

data class CachedLibrary(
    val songs: List<Song>,
    val lastScanAtMs: Long,
    val lastScanSource: ScanSource,
    val totalSizeMb: Int,
)

class LibraryRepository(context: Context) {

    private val db = MicaDatabase.get(context)
    private val songDao = db.songDao()
    private val metaDao = db.libraryMetaDao()

    suspend fun loadCached(): CachedLibrary? {
        val meta = metaDao.get() ?: return null
        val entities = songDao.getAllOrdered()
        if (entities.isEmpty()) return null
        return CachedLibrary(
            songs = entities.map { it.toSong() },
            lastScanAtMs = meta.lastScanAtMs,
            lastScanSource = runCatching {
                ScanSource.valueOf(meta.lastScanSource)
            }.getOrDefault(ScanSource.DEVICE),
            totalSizeMb = meta.totalSizeMb,
        )
    }

    suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult = syncIncremental(songs, lastScanAtMs, lastScanSource, totalSizeMb)

    suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
    ): LibrarySyncResult {
        val existing = songDao.getAllOrdered().associateBy { it.id }
        val incoming = songs.mapIndexed { index, song -> song.toEntity(index) }
        val incomingIds = incoming.map { it.id }.toSet()
        val removeIds = (existing.keys - incomingIds).toList()

        var added = 0
        var updated = 0
        var unchanged = 0
        incoming.forEach { entity ->
            val old = existing[entity.id]
            when {
                old == null -> added++
                old.scanFingerprint() == entity.scanFingerprint() &&
                    old.queueOrder == entity.queueOrder &&
                    old.playCount == entity.playCount -> unchanged++
                else -> updated++
            }
        }

        songDao.syncIncremental(incoming, removeIds)
        metaDao.upsert(
            LibraryMetaEntity(
                lastScanAtMs = lastScanAtMs,
                lastScanSource = lastScanSource.name,
                totalSizeMb = totalSizeMb,
                songCount = songs.size,
            ),
        )
        return LibrarySyncResult(
            added = added,
            updated = updated,
            removed = removeIds.size,
            unchanged = unchanged,
        )
    }

    suspend fun clear() {
        songDao.deleteAll()
    }
}
