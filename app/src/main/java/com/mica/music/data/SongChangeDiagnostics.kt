package com.mica.music.data

internal object SongChangeDiagnostics {
    fun changedFields(old: Song, new: Song): List<String> = buildList {
        if (old.title != new.title) add("title")
        if (old.artist != new.artist) add("artist")
        if (old.album != new.album) add("album")
        if (old.albumArtist != new.albumArtist) add("albumArtist")
        if (old.durationSec != new.durationSec) add("durationSec")
        if (old.metadata != new.metadata) add("metadata")
        if (old.albumArtUri != new.albumArtUri) add("albumArtUri")
        if (old.videoCoverUri != new.videoCoverUri) add("videoCoverUri")
        if (old.coverColorArgb != new.coverColorArgb) add("coverColorArgb")
        if (old.mediaUri != new.mediaUri) add("mediaUri")
        if (old.playbackUri != new.playbackUri) add("playbackUri")
        if (old.fileName != new.fileName) add("fileName")
        if (old.sizeBytes != new.sizeBytes) add("sizeBytes")
        if (old.year != new.year) add("year")
        if (old.releaseDate != new.releaseDate) add("releaseDate")
        if (old.folderPath != new.folderPath) add("folderPath")
        if (old.filePath != new.filePath) add("filePath")
        if (old.copyright != new.copyright) add("copyright")
        if (old.codecLabel != new.codecLabel) add("codecLabel")
        if (old.dateAddedMs != new.dateAddedMs) add("dateAddedMs")
        if (old.dateModifiedMs != new.dateModifiedMs) add("dateModifiedMs")
        if (old.externalLyricsSignature != new.externalLyricsSignature) add("externalLyricsSignature")
        if (old.playCount != new.playCount) add("playCount")
        if (old.totalListenSeconds != new.totalListenSeconds) add("totalListenSeconds")
        if (old.lastPlayedAtMs != new.lastPlayedAtMs) add("lastPlayedAtMs")
        if (old.lyricsDocument != new.lyricsDocument) add("lyrics")
    }

    fun summarizeChangedFields(old: Song, new: Song): String =
        changedFields(old, new).takeIf { it.isNotEmpty() }?.joinToString("+") ?: "none"
}
