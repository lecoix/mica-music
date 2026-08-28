package com.mica.music.data.scanner

import com.mica.music.data.Song
import java.text.Normalizer
import java.util.Locale

/** Linear-time, directory-scoped matcher for audio files and MP4 sidecars. */
internal object MusicVideoMatcher {
    private val whitespace = Regex("\\s+")

    fun attach(songs: List<Song>, videos: List<VideoCoverFile>): List<Song> {
        if (songs.isEmpty()) return songs
        if (videos.isEmpty()) return songs.map(::withoutMusicVideo)

        val matches = HashMap<String, VideoCoverFile>()
        val unmatchedSongIds = songs.mapTo(linkedSetOf(), Song::id)
        val unmatchedVideoUris = videos.mapTo(linkedSetOf(), VideoCoverFile::uri)

        matchUniqueGroups(
            songs = songs,
            videos = videos,
            songKey = { song -> Key(song.folderPath, song.baseName()) },
            videoKey = { video -> Key(video.folderPath, video.baseName) },
            unmatchedSongIds = unmatchedSongIds,
            unmatchedVideoUris = unmatchedVideoUris,
            matches = matches,
        )
        matchUniqueGroups(
            songs = songs.filter { it.id in unmatchedSongIds },
            videos = videos.filter { it.uri in unmatchedVideoUris },
            songKey = { song -> Key(song.folderPath, normalize(song.baseName())) },
            videoKey = { video -> Key(video.folderPath, normalize(video.baseName)) },
            unmatchedSongIds = unmatchedSongIds,
            unmatchedVideoUris = unmatchedVideoUris,
            matches = matches,
        )

        return songs.map { song ->
            matches[song.id]?.let { video ->
                song.copy(
                    musicVideoUri = video.uri,
                    musicVideoRevision = "${video.uri}|${video.sizeBytes}|${video.lastModifiedMs}",
                )
            } ?: withoutMusicVideo(song)
        }
    }

    private fun matchUniqueGroups(
        songs: List<Song>,
        videos: List<VideoCoverFile>,
        songKey: (Song) -> Key,
        videoKey: (VideoCoverFile) -> Key,
        unmatchedSongIds: MutableSet<String>,
        unmatchedVideoUris: MutableSet<String>,
        matches: MutableMap<String, VideoCoverFile>,
    ) {
        val songsByKey = songs.groupBy(songKey)
        val videosByKey = videos.groupBy(videoKey)
        songsByKey.forEach { (key, candidates) ->
            val videoCandidates = videosByKey[key].orEmpty()
            if (candidates.size == 1 && videoCandidates.size == 1) {
                val song = candidates.single()
                val video = videoCandidates.single()
                matches[song.id] = video
                unmatchedSongIds -= song.id
                unmatchedVideoUris -= video.uri
            }
        }
    }

    private fun Song.baseName(): String = fileName.substringBeforeLast('.', fileName)

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT)

    private fun withoutMusicVideo(song: Song): Song =
        song.copy(musicVideoUri = null, musicVideoRevision = "")

    private data class Key(val folderPath: String, val baseName: String)
}
