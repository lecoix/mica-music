package com.mica.music.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.mica.music.data.ArtistNames
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.BrowseGroup
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LibraryBrowse
import com.mica.music.data.LibraryFastScrollIndex
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsSlot
import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScanSource
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.cacheKey
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
    val artistGroups: List<BrowseGroup>? = null,
    val albumGroups: List<BrowseGroup>? = null,
    val browseArtistConfigKey: String = "",
    val artistBrowseSortField: ArtistBrowseSortField? = null,
    val artistBrowseSortDirection: SortDirection? = null,
    val artistBrowseFastScrollSectionTargets: Map<String, Int>? = null,
    val albumBrowseSortField: AlbumBrowseSortField? = null,
    val albumBrowseSortDirection: SortDirection? = null,
    val albumBrowseFastScrollSectionTargets: Map<String, Int>? = null,
)

class LibraryRepository internal constructor(
    private val db: MicaDatabase,
    private val migrationContext: Context? = null,
) {
    constructor(context: Context) : this(MicaDatabase.get(context), context.applicationContext)

    private val songDao = db.songDao()
    private val lyricsDao = db.songLyricsDao()
    private val metaDao = db.libraryMetaDao()
    private val browseGroupDao = db.browseGroupDao()

    suspend fun loadCached(): CachedLibrary? {
        ensureSongIdentityMigration()
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
        val browseStartedMs = SystemClock.elapsedRealtime()
        val artistGroups = browseGroupDao.getArtists().map(BrowseGroupEntity::toBrowseGroup)
        val albumGroups = browseGroupDao.getAlbums().map(BrowseGroupEntity::toBrowseGroup)
        DiagnosticLog.event(
            "LibraryDb",
            "loadCached browse durMs=${SystemClock.elapsedRealtime() - browseStartedMs} " +
                "artists=${artistGroups.size} albums=${albumGroups.size}",
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
            artistGroups = artistGroups.takeIf(List<BrowseGroup>::isNotEmpty),
            albumGroups = albumGroups.takeIf(List<BrowseGroup>::isNotEmpty),
            browseArtistConfigKey = meta.browseArtistConfigKey,
            artistBrowseSortField = meta.artistBrowseSortField.takeIf(String::isNotBlank)
                ?.let(ArtistBrowseSortField::fromStorage),
            artistBrowseSortDirection = meta.artistBrowseSortDirection.takeIf(String::isNotBlank)
                ?.let(SortDirection::fromStorage),
            artistBrowseFastScrollSectionTargets = decodeSectionTargets(
                meta.artistBrowseFastScrollSectionsJson,
            ),
            albumBrowseSortField = meta.albumBrowseSortField.takeIf(String::isNotBlank)
                ?.let(AlbumBrowseSortField::fromStorage),
            albumBrowseSortDirection = meta.albumBrowseSortDirection.takeIf(String::isNotBlank)
                ?.let(SortDirection::fromStorage),
            albumBrowseFastScrollSectionTargets = decodeSectionTargets(
                meta.albumBrowseFastScrollSectionsJson,
            ),
        )
    }

    suspend fun songById(
        id: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ): Song? {
        ensureSongIdentityMigration()
        return songDao.getById(id)?.toSong()?.let { song ->
            song.copy(
                lyricsDocument = lyricsById(id, priority, song.lyricsCacheRevision),
                lyricsLoaded = true,
            )
        }
    }

    /** Lightweight identity lookup for MediaSession item resolution; never loads lyrics. */
    suspend fun songSummariesByIds(ids: List<String>): Map<String, Song> {
        if (ids.isEmpty()) return emptyMap()
        ensureSongIdentityMigration()
        return songDao.getSummariesByIds(ids)
            .associate { summary -> summary.id to summary.toSong() }
    }

    suspend fun lyricsById(
        id: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        revision: String? = null,
    ): LyricsDocument {
        ensureSongIdentityMigration()
        val startedMs = SystemClock.elapsedRealtime()
        var queryMs = 0L
        var queryStartedMs = SystemClock.elapsedRealtime()
        val available = lyricsDao.getSlots(id)
            .mapNotNullTo(mutableSetOf()) { runCatching { LyricsSlot.valueOf(it) }.getOrNull() }
        queryMs += SystemClock.elapsedRealtime() - queryStartedMs
        var jsonChars = 0
        var document = LyricsDocument()
        for (slot in priority) {
            if (slot !in available) continue
            queryStartedMs = SystemClock.elapsedRealtime()
            val json = lyricsDao.getLyricsJson(id, slot.name)
            queryMs += SystemClock.elapsedRealtime() - queryStartedMs
            if (json == null) continue
            jsonChars += json.length
            val decoded = LyricsDocumentCodec.decode(json)
            if (decoded.lines.isNotEmpty()) {
                document = decoded
                break
            }
        }
        DiagnosticLog.event(
            "LyricsLoad",
            "song=${id.takeLast(12)} queryMs=$queryMs totalMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "slots=${available.size} jsonChars=$jsonChars lines=${document.lines.size}",
        )
        return document
    }

    private suspend fun ensureSongIdentityMigration() {
        migrationContext?.let { context ->
            SongIdentityMigration.migrate(context, db)
        }
    }

    suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) {
        if (batch.isEmpty()) return
        val songIds = batch.map(ScannedSongLyrics::songId)
        val encoded = batch.flatMap { payload ->
            payload.slots.entries().map { (slot, document) ->
                SongLyricsEntity(
                    songId = payload.songId,
                    slot = slot.name,
                    revision = payload.revision,
                    lyricsJson = LyricsDocumentCodec.encode(document),
                )
            }
        }
        db.withTransaction {
            lyricsDao.deleteBySongIds(songIds)
            if (encoded.isNotEmpty()) lyricsDao.insertAll(encoded)
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
    ): LibrarySyncResult = syncIncrementalInternal(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    suspend fun commitScan(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult = syncIncrementalInternal(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    private suspend fun syncIncrementalInternal(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult {
        val browseGroups = buildBrowseGroupEntities(songs)
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
            browseGroupDao.replaceAll(browseGroups.artistGroups, browseGroups.albumGroups)
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
                    browseArtistConfigKey = browseGroups.artistConfigKey,
                    artistBrowseSortField = ArtistBrowseSortField.TITLE.storageValue,
                    artistBrowseSortDirection = SortDirection.ASC.storageValue,
                    artistBrowseFastScrollSectionsJson = encodeSectionTargets(
                        browseGroups.artistFastScrollSectionTargets,
                    ),
                    albumBrowseSortField = AlbumBrowseSortField.TITLE.storageValue,
                    albumBrowseSortDirection = SortDirection.ASC.storageValue,
                    albumBrowseFastScrollSectionsJson = encodeSectionTargets(
                        browseGroups.albumFastScrollSectionTargets,
                    ),
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

    suspend fun updateBrowseGroups(
        artistGroups: List<BrowseGroup>,
        albumGroups: List<BrowseGroup>,
        artistConfigKey: String,
        artistSortField: ArtistBrowseSortField,
        artistSortDirection: SortDirection,
        artistFastScrollSectionTargets: Map<String, Int>?,
        albumSortField: AlbumBrowseSortField,
        albumSortDirection: SortDirection,
        albumFastScrollSectionTargets: Map<String, Int>?,
    ) {
        val artistEntities = artistGroups.mapIndexed { index, group ->
            group.toEntity(BROWSE_GROUP_KIND_ARTIST, index)
        }
        val albumEntities = albumGroups.mapIndexed { index, group ->
            group.toEntity(BROWSE_GROUP_KIND_ALBUM, index)
        }
        db.withTransaction {
            val meta = metaDao.get() ?: return@withTransaction
            browseGroupDao.replaceAll(artistEntities, albumEntities)
            metaDao.upsert(
                meta.copy(
                    browseArtistConfigKey = artistConfigKey,
                    artistBrowseSortField = artistSortField.storageValue,
                    artistBrowseSortDirection = artistSortDirection.storageValue,
                    artistBrowseFastScrollSectionsJson = encodeSectionTargets(artistFastScrollSectionTargets),
                    albumBrowseSortField = albumSortField.storageValue,
                    albumBrowseSortDirection = albumSortDirection.storageValue,
                    albumBrowseFastScrollSectionsJson = encodeSectionTargets(albumFastScrollSectionTargets),
                ),
            )
        }
    }

    suspend fun clear() {
        db.withTransaction {
            lyricsDao.deleteAllPending()
            lyricsDao.deleteAll()
            songDao.deleteAll()
            browseGroupDao.deleteAll()
            metaDao.deleteAll()
        }
    }

    private fun buildBrowseGroupEntities(songs: List<Song>): PersistedBrowseGroups {
        val artists = LibraryBrowse.groupByArtist(songs)
        val albums = LibraryBrowse.groupByAlbum(songs)
        return PersistedBrowseGroups(
            artistGroups = artists.mapIndexed { index, group ->
                group.toEntity(BROWSE_GROUP_KIND_ARTIST, index)
            },
            albumGroups = albums.mapIndexed { index, group ->
                group.toEntity(BROWSE_GROUP_KIND_ALBUM, index)
            },
            artistFastScrollSectionTargets = LibraryFastScrollIndex.sectionTargets(artists.map(BrowseGroup::title)),
            albumFastScrollSectionTargets = LibraryFastScrollIndex.sectionTargets(albums.map(BrowseGroup::title)),
            artistConfigKey = ArtistNames.currentConfig().cacheKey(),
        )
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

private data class PersistedBrowseGroups(
    val artistGroups: List<BrowseGroupEntity>,
    val albumGroups: List<BrowseGroupEntity>,
    val artistFastScrollSectionTargets: Map<String, Int>,
    val albumFastScrollSectionTargets: Map<String, Int>,
    val artistConfigKey: String,
)
