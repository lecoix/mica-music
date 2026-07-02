package com.mica.music.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.util.DiagnosticLog
import org.json.JSONObject

data class CachedLibrary(
    val songs: List<Song>,
    val lastScanAtMs: Long,
    val lastScanSource: ScanSource,
    val totalSizeMb: Int,
    val sortField: SongSortField? = null,
    val sortDirection: SortDirection? = null,
    val fastScrollSectionTargets: Map<String, Int>? = null,
)

class LibraryRepository internal constructor(
    private val db: MicaDatabase,
) {
    constructor(context: Context) : this(MicaDatabase.get(context))

    private val songDao = db.songDao()
    private val metaDao = db.libraryMetaDao()

    suspend fun loadCached(): CachedLibrary? {
        val startedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event("LibraryDb", "loadCached begin")
        val metaStartedMs = SystemClock.elapsedRealtime()
        val meta = metaDao.get()
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached meta durMs=${SystemClock.elapsedRealtime() - metaStartedMs} found=${meta != null}",
        )
        if (meta == null) {
            DiagnosticLog.event("LibraryDb", "loadCached empty-meta durMs=${SystemClock.elapsedRealtime() - startedMs}")
            return null
        }
        val queryStartedMs = SystemClock.elapsedRealtime()
        val entities = songDao.getAllOrdered()
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached songsQuery durMs=${SystemClock.elapsedRealtime() - queryStartedMs} rows=${entities.size}",
        )
        if (entities.isEmpty()) {
            DiagnosticLog.event("LibraryDb", "loadCached empty-songs durMs=${SystemClock.elapsedRealtime() - startedMs}")
            return null
        }
        val payloadStartedMs = SystemClock.elapsedRealtime()
        val lyricsChars = entities.sumOf { it.lyricsJson.length }
        val maxLyricsChars = entities.maxOfOrNull { it.lyricsJson.length } ?: 0
        val songsWithLyrics = entities.count { it.lyricsJson.length > 2 }
        val albumArtUris = entities.count { !it.albumArtUri.isNullOrBlank() }
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached payload durMs=${SystemClock.elapsedRealtime() - payloadStartedMs} " +
                "rows=${entities.size} lyricsChars=$lyricsChars maxLyricsChars=$maxLyricsChars " +
                "songsWithLyrics=$songsWithLyrics albumArtUris=$albumArtUris",
        )
        val mapStartedMs = SystemClock.elapsedRealtime()
        val songs = entities.map { it.toSong() }
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached toSong durMs=${SystemClock.elapsedRealtime() - mapStartedMs} rows=${songs.size}",
        )
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached end durMs=${SystemClock.elapsedRealtime() - startedMs} rows=${songs.size}",
        )
        return CachedLibrary(
            songs = songs,
            lastScanAtMs = meta.lastScanAtMs,
            lastScanSource = runCatching {
                ScanSource.valueOf(meta.lastScanSource)
            }.getOrDefault(ScanSource.DEVICE),
            totalSizeMb = meta.totalSizeMb,
            sortField = meta.sortField.takeIf { it.isNotBlank() }?.let(SongSortField::fromStorage),
            sortDirection = meta.sortDirection.takeIf { it.isNotBlank() }?.let(SortDirection::fromStorage),
            fastScrollSectionTargets = decodeSectionTargets(meta.fastScrollSectionsJson),
        )
    }

    suspend fun songById(id: String): Song? =
        songDao.getById(id)?.toSong()

    suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult = syncIncremental(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
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

        db.withTransaction {
            songDao.syncIncremental(incoming, removeIds)
            metaDao.upsert(
                LibraryMetaEntity(
                    lastScanAtMs = lastScanAtMs,
                    lastScanSource = lastScanSource.name,
                    totalSizeMb = totalSizeMb,
                    songCount = songs.size,
                    sortField = sortField?.storageValue.orEmpty(),
                    sortDirection = sortDirection?.storageValue.orEmpty(),
                    fastScrollSectionsJson = encodeSectionTargets(fastScrollSectionTargets),
                ),
            )
        }
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

    private fun encodeSectionTargets(targets: Map<String, Int>?): String {
        if (targets.isNullOrEmpty()) return ""
        val json = JSONObject()
        targets.forEach { (section, index) -> json.put(section, index) }
        return json.toString()
    }

    private fun decodeSectionTargets(json: String): Map<String, Int>? {
        if (json.isBlank()) return null
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    put(key, obj.getInt(key))
                }
            }
        }.getOrNull()
    }
}
