package com.mica.music.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.LyricsDocument
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.LyricsSlot
import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
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
    private val lyricsDao = db.songLyricsDao()
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
        val entities = songDao.getAllSummariesOrdered()
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached songsQuery durMs=${SystemClock.elapsedRealtime() - queryStartedMs} rows=${entities.size}",
        )
        if (entities.isEmpty()) {
            DiagnosticLog.event("LibraryDb", "loadCached empty-songs durMs=${SystemClock.elapsedRealtime() - startedMs}")
            return null
        }
        val albumArtUris = entities.count { !it.albumArtUri.isNullOrBlank() }
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached summaries rows=${entities.size} lyricsPayloads=0 albumArtUris=$albumArtUris",
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

    suspend fun songById(
        id: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ): Song? = songDao.getById(id)?.toSong()?.let { song ->
        song.copy(
            lyricsDocument = lyricsById(id, priority, song.lyricsCacheRevision),
            lyricsLoaded = true,
        )
    }

    suspend fun lyricsById(
        id: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        revision: String? = null,
    ): LyricsDocument {
        val startedMs = SystemClock.elapsedRealtime()
        val rows = lyricsDao.getBySongId(id, revision)
        val queryMs = SystemClock.elapsedRealtime() - startedMs
        val bySlot = rows.associateBy { runCatching { LyricsSlot.valueOf(it.slot) }.getOrNull() }
        val slots = LyricsSlots(
            embedded = bySlot[LyricsSlot.EMBEDDED]?.lyricsJson?.let(LyricsDocumentCodec::decode),
            externalLrc = bySlot[LyricsSlot.EXTERNAL_LRC]?.lyricsJson?.let(LyricsDocumentCodec::decode),
            externalTtml = bySlot[LyricsSlot.EXTERNAL_TTML]?.lyricsJson?.let(LyricsDocumentCodec::decode),
        )
        val document = slots.selected(priority)
        DiagnosticLog.event(
            "LyricsLoad",
            "song=${id.takeLast(12)} queryMs=$queryMs totalMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "slots=${rows.size} jsonChars=${rows.sumOf { it.lyricsJson.length }} lines=${document.lines.size}",
        )
        return document
    }

    suspend fun replaceLyricsBatch(batch: List<ScannedSongLyrics>) {
        if (batch.isEmpty()) return
        db.withTransaction {
            val songIds = batch.map { it.songId }
            lyricsDao.deleteBySongIds(songIds)
            lyricsDao.insertAll(batch.flatMap { payload ->
                payload.slots.entries().map { (slot, document) ->
                    SongLyricsEntity(
                        songId = payload.songId,
                        slot = slot.name,
                        revision = payload.revision,
                        lyricsJson = LyricsDocumentCodec.encode(document),
                    )
                }
            })
        }
    }

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
        val existing = songDao.getAllSummariesOrdered().associateBy { it.id }
        val incomingIds = songs.mapTo(HashSet(songs.size), Song::id)
        val removeIds = (existing.keys - incomingIds).toList()
        val upserts = ArrayList<SongEntity>()
        val directlyLoadedLyrics = songs.mapNotNull { song ->
            song.lyricsDocument.takeIf { song.lyricsLoaded && it.lines.isNotEmpty() }?.let { document ->
                SongLyricsEntity(
                    songId = song.id,
                    slot = when {
                        document.origin != LyricsOrigin.EXTERNAL -> LyricsSlot.EMBEDDED
                        document.format == LyricsFormat.TTML -> LyricsSlot.EXTERNAL_TTML
                        else -> LyricsSlot.EXTERNAL_LRC
                    }.name,
                    revision = song.lyricsCacheRevision,
                    lyricsJson = LyricsDocumentCodec.encode(document),
                )
            }
        }

        var added = 0
        var updated = 0
        var unchanged = 0
        songs.forEachIndexed { index, song ->
            val old = existing[song.id]
            when {
                old == null -> {
                    added++
                    upserts += song.copy(lyricsLoaded = false).toEntity(index)
                }
                else -> {
                    val oldComparable = old.toSong().toEntity(old.queueOrder, "")
                    val incomingComparable = song.copy(
                        lyricsDocument = LyricsDocument(),
                        lyricsLoaded = false,
                    ).toEntity(index, "")
                    val metadataChanged = oldComparable.copy(queueOrder = 0) !=
                        incomingComparable.copy(queueOrder = 0)
                    val orderChanged = old.queueOrder != index
                    if (metadataChanged) {
                        upserts += song.copy(lyricsLoaded = false).toEntity(index)
                    }
                    if (metadataChanged || orderChanged) updated++ else unchanged++
                }
            }
        }

        db.withTransaction {
            if (removeIds.isNotEmpty()) lyricsDao.deleteBySongIds(removeIds)
            if (directlyLoadedLyrics.isNotEmpty()) lyricsDao.insertAll(directlyLoadedLyrics)
            songDao.syncIncremental(upserts, removeIds, songs.map(Song::id))
            lyricsDao.deleteOrphans()
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

    suspend fun updatePresentation(
        songIds: List<String>,
        sortField: SongSortField,
        sortDirection: SortDirection,
        fastScrollSectionTargets: Map<String, Int>?,
    ) {
        db.withTransaction {
            val meta = metaDao.get() ?: return@withTransaction
            songDao.updateQueueOrders(songIds)
            metaDao.upsert(
                meta.copy(
                    sortField = sortField.storageValue,
                    sortDirection = sortDirection.storageValue,
                    fastScrollSectionsJson = encodeSectionTargets(fastScrollSectionTargets),
                ),
            )
        }
    }

    suspend fun clear() {
        db.withTransaction {
            lyricsDao.deleteAll()
            songDao.deleteAll()
        }
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
