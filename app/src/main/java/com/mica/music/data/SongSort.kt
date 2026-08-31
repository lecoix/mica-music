package com.mica.music.data

import java.text.Collator
import java.util.Locale

enum class SongSortField(val storageValue: String, val label: String) {
    TITLE("title", "标题"),
    FILE_NAME("file_name", "文件名"),
    ALBUM("album", "专辑"),
    ARTIST("artist", "艺术家"),
    SIZE("size", "大小"),
    YEAR("year", "日期"),
    FOLDER("folder", "文件夹"),
    PLAY_COUNT("play_count", "播放次数"),
    LAST_PLAYED("last_played", "最近播放"),
    DURATION("duration", "时长"),
    DATE_MODIFIED("date_modified", "修改时间"),
    DATE_ADDED("date_added", "添加时间"),
    /** 歌单内按 [UserPlaylist.songIds] 顺序展示，可拖拽调整 */
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        fun fromStorage(value: String?): SongSortField =
            entries.firstOrNull { it.storageValue == value } ?: TITLE
    }
}

enum class SortDirection(val storageValue: String, val label: String) {
    ASC("asc", "升序"),
    DESC("desc", "降序"),
    ;

    companion object {
        fun fromStorage(value: String?): SortDirection =
            entries.firstOrNull { it.storageValue == value } ?: ASC
    }
}

/** 统计栏等展示的排序文案，如「标题 · 升序」；自定义排序不展示方向。 */
fun formatSortLabel(field: SongSortField, direction: SortDirection): String =
    if (field == SongSortField.CUSTOM) field.label else "${field.label} · ${direction.label}"

val REMOTE_SONG_SORT_FIELDS: List<SongSortField> = listOf(
    SongSortField.TITLE,
    SongSortField.ALBUM,
    SongSortField.ARTIST,
    SongSortField.YEAR,
    SongSortField.PLAY_COUNT,
    SongSortField.LAST_PLAYED,
    SongSortField.DURATION,
)

object SongSorter {

    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    fun sort(
        songs: List<Song>,
        field: SongSortField,
        direction: SortDirection,
    ): List<Song> {
        if (field == SongSortField.CUSTOM || songs.size <= 1) return songs
        val comparator = if (field == SongSortField.YEAR) {
            releaseDateComparator(direction)
        } else {
            comparatorFor(field)
        }
        val sorted = songs.sortedWith(comparator)
        return if (direction == SortDirection.DESC && field != SongSortField.YEAR) sorted.reversed() else sorted
    }

    fun customOrder(songs: List<Song>, orderedIds: List<String>): List<Song> {
        if (orderedIds.isEmpty() || songs.size <= 1) return songs
        val byId = songs.associateBy { it.id }
        val used = mutableSetOf<String>()
        val ordered = orderedIds.mapNotNull { id ->
            if (id in used) null else byId[id]?.also { used += id }
        }
        return ordered + songs.filterNot { it.id in used }
    }

    private fun text(selector: (Song) -> String): Comparator<Song> =
        AlphabeticalText.comparator(selector, collator)

    private fun comparatorFor(field: SongSortField): Comparator<Song> = when (field) {
        SongSortField.TITLE -> text { it.title }
        SongSortField.FILE_NAME -> text { it.fileName }
        SongSortField.ALBUM -> text { it.album }.then(text { it.title })
        SongSortField.ARTIST -> text { ArtistNames.primary(it.artist) }.then(text { it.album })
        SongSortField.SIZE -> compareBy<Song>({ it.sizeBytes }).then(text { it.title })
        SongSortField.YEAR -> compareBy<Song>({ it.year }).then(text { it.album })
        SongSortField.FOLDER -> text { it.folderPath }.then(text { it.fileName })
        SongSortField.PLAY_COUNT -> compareBy<Song>({ it.playCount }).then(text { it.title })
        SongSortField.LAST_PLAYED -> compareBy<Song>({ it.lastPlayedAtMs }).then(text { it.title })
        SongSortField.DURATION -> compareBy<Song>({ it.durationSec }).then(text { it.title })
        SongSortField.DATE_MODIFIED -> compareBy<Song>({ it.dateModifiedMs }).then(text { it.title })
        SongSortField.DATE_ADDED -> compareBy<Song>({ it.dateAddedMs }).then(text { it.title })
        SongSortField.CUSTOM -> compareBy<Song> { it.id }
    }

    private fun releaseDateComparator(direction: SortDirection): Comparator<Song> =
        Comparator { left, right ->
            ReleaseDates.compare(
                leftYear = left.year,
                leftFullDate = left.releaseDate,
                rightYear = right.year,
                rightFullDate = right.releaseDate,
                direction = direction,
            ).takeIf { it != 0 }
                ?: text { it.album }.compare(left, right).takeIf { it != 0 }
                ?: text { it.title }.compare(left, right)
        }
}
