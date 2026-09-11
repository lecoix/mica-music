package com.mica.music.util

enum class DiagnosticDetailDomain {
    LIBRARY_SCAN,
    PLAYBACK_MEDIA,
    AUDIO_PIPELINE,
    USB_DEVICE,
    UI_RENDERING,
}

data class DiagnosticDetailConfig(
    val enabled: Boolean = false,
    val libraryScan: Boolean = false,
    val playbackMedia: Boolean = false,
    val audioPipeline: Boolean = false,
    val usbDevice: Boolean = false,
    val uiRendering: Boolean = false,
) {
    fun isEnabled(domain: DiagnosticDetailDomain): Boolean = enabled && when (domain) {
        DiagnosticDetailDomain.LIBRARY_SCAN -> libraryScan
        DiagnosticDetailDomain.PLAYBACK_MEDIA -> playbackMedia
        DiagnosticDetailDomain.AUDIO_PIPELINE -> audioPipeline
        DiagnosticDetailDomain.USB_DEVICE -> usbDevice
        DiagnosticDetailDomain.UI_RENDERING -> uiRendering
    }
}

/**
 * Coarse ownership for verbose runtime traces. Unknown categories intentionally stay outside this
 * policy so new error/breadcrumb categories do not silently disappear before they are classified.
 */
internal fun diagnosticDetailDomainFor(category: String): DiagnosticDetailDomain? = when {
    category.startsWith("Usb", ignoreCase = true) || category == "AudioRoute" ->
        DiagnosticDetailDomain.USB_DEVICE

    category.startsWith("Library", ignoreCase = true) ||
        category.startsWith("Scan", ignoreCase = true) ||
        category.startsWith("AlbumArt", ignoreCase = true) ||
        category.startsWith("Playlist", ignoreCase = true) ||
        category.startsWith("Remote", ignoreCase = true) ||
        category.startsWith("Smb", ignoreCase = true) ||
        category == "SongIdentityMigration" ||
        category == "LyricsLoad" || category == "LyricsCache" -> DiagnosticDetailDomain.LIBRARY_SCAN

    category.startsWith("Audio", ignoreCase = true) ||
        category.startsWith("Spectrum", ignoreCase = true) ||
        category.startsWith("Dsd", ignoreCase = true) ||
        category.startsWith("Pcm", ignoreCase = true) ||
        category.startsWith("FloatDsp", ignoreCase = true) ||
        category == "ReplayGain" || category == "LoudnessScan" ||
        category == "RendererSupportProbe" || category == "ProcessorFormat" ||
        category == "DecoderInput" -> DiagnosticDetailDomain.AUDIO_PIPELINE

    category.startsWith("Playback", ignoreCase = true) ||
        category == "Player" || category == "QueueSync" || category == "MediaSession" ||
        category == "Shuffle" || category == "TrackPerf" || category == "MusicVideo" ||
        category == "ExternalOpen" || category == "DesktopLyrics" || category == "Lyricon" ->
        DiagnosticDetailDomain.PLAYBACK_MEDIA

    category.startsWith("ParticleCover", ignoreCase = true) ||
        category.startsWith("StarMap", ignoreCase = true) ||
        category.startsWith("DynamicLight", ignoreCase = true) ||
        category == "CoverFlow" || category == "WindowTouchTrace" || category == "TagEditor" ||
        category == "VideoCover" || category == "LyricsRenderInput" -> DiagnosticDetailDomain.UI_RENDERING

    else -> null
}
