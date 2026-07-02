package com.mica.music.data

data class FastScrollIndex(
    val labels: List<String>,
    val sectionTargets: Map<String, Int>,
)

object LibraryFastScrollIndex {
    fun forSongs(songs: List<Song>, field: SongSortField): FastScrollIndex? {
        val labels = labelsForSongs(songs, field) ?: return null
        return FastScrollIndex(
            labels = labels,
            sectionTargets = sectionTargets(labels),
        )
    }

    fun labelsForSongs(songs: List<Song>, field: SongSortField): List<String>? = when (field) {
        SongSortField.TITLE -> songs.map { it.title }
        SongSortField.FILE_NAME -> songs.map { it.fileName }
        SongSortField.ALBUM -> songs.map { it.album }
        SongSortField.ARTIST -> songs.map { ArtistNames.primary(it.artist) }
        SongSortField.FOLDER -> songs.map { it.folderPath.ifBlank { it.filePath } }
        SongSortField.SIZE,
        SongSortField.YEAR,
        SongSortField.PLAY_COUNT,
        SongSortField.LAST_PLAYED,
        SongSortField.DURATION,
        SongSortField.DATE_MODIFIED,
        SongSortField.DATE_ADDED,
        SongSortField.CUSTOM,
        -> null
    }

    fun sectionTargets(labels: List<String>): Map<String, Int> {
        val targets = linkedMapOf<String, Int>()
        labels.forEachIndexed { index, label ->
            targets.putIfAbsent(AlphabeticalText.sectionFor(label), index)
        }
        return targets
    }
}
