package com.mica.music.audio.loudness

import com.mica.music.data.LoudnessAnalysis
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import kotlinx.coroutines.flow.StateFlow

data class LoudnessScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentSongId: String? = null,
    val currentTitle: String = "",
    val lastError: String? = null,
) {
    val progressLabel: String
        get() = if (running) "$completed / $total" else if (total > 0) "$completed / $total" else ""
}

interface LoudnessScanPort {
    val state: StateFlow<LoudnessScanState>

    fun startLibraryScan(library: MusicLibrary, missingOnly: Boolean = true): Boolean

    suspend fun analyzeSingle(song: Song, library: MusicLibrary): Result<LoudnessAnalysis>
}