package com.mica.music.data.scanner

import com.mica.music.data.Song

fun scanDirectoryCandidates(songs: List<Song>): List<String> =
    ExcludedScanDirectories.normalizeAll(
        songs.flatMap { song ->
            val folder = ExcludedScanDirectories.normalize(song.folderPath)
            if (folder.isBlank()) {
                emptyList()
            } else {
                folder.split('/').runningFold("") { parent, segment ->
                    if (parent.isBlank()) segment else "$parent/$segment"
                }.drop(1)
            }
        },
    )
