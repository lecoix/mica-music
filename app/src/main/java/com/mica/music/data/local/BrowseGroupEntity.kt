package com.mica.music.data.local

import androidx.room.Entity
import com.mica.music.data.BrowseGroup

internal const val BROWSE_GROUP_KIND_ARTIST = "artist"
internal const val BROWSE_GROUP_KIND_ALBUM = "album"

@Entity(tableName = "browse_groups", primaryKeys = ["kind", "groupKey"])
data class BrowseGroupEntity(
    val kind: String,
    val groupKey: String,
    val title: String,
    val subtitle: String,
    val songCount: Int,
    val artist: String,
    val year: Int,
    val releaseDate: String,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val position: Int,
)

internal fun BrowseGroupEntity.toBrowseGroup(): BrowseGroup = BrowseGroup(
    title = title,
    subtitle = subtitle,
    songCount = songCount,
    artist = artist,
    year = year,
    releaseDate = releaseDate,
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    key = groupKey,
)

internal fun BrowseGroup.toEntity(kind: String, position: Int): BrowseGroupEntity = BrowseGroupEntity(
    kind = kind,
    groupKey = key,
    title = title,
    subtitle = subtitle,
    songCount = songCount,
    artist = artist,
    year = year,
    releaseDate = releaseDate,
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    position = position,
)
