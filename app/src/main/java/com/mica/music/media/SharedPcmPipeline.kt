package com.mica.music.media

import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog

/** Gate 2 unified chain: optional debug format logging only. */
object SharedPcmPipelineDiagnostics {

    fun logSongFormat(song: Song) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        val metadata = song.metadata
        DiagnosticLog.event(
            "AudioPipelineFormat",
            "songId=${song.id} sampleRate=${metadata.sampleRateHz} " +
                "bitDepth=${metadata.bitsPerSample ?: "unknown"} " +
                "isDsd=${isDsdSong(song)}",
        )
    }

    private fun isDsdSong(song: Song): Boolean =
        DsdSupport.isDsdMetadata(song.metadata) ||
            song.fileName.substringAfterLast('.', "").equals("dsf", ignoreCase = true)
}
