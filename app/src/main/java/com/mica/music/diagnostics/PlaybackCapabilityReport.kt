package com.mica.music.diagnostics

data class PlaybackCapabilityLine(val title: String, val detail: String)

data class PlaybackCapabilityReport(val lines: List<PlaybackCapabilityLine>) {
    fun asLogText(): String = lines.joinToString(" | ") { "${it.title}=${it.detail}" }
}

interface PlaybackCapabilityReportProvider {
    fun report(): PlaybackCapabilityReport
}