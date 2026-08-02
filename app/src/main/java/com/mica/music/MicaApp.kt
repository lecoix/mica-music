package com.mica.music

import android.app.Application
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.data.PlaybackStatisticsRepository
import com.mica.music.data.PlaylistStore
import com.mica.music.data.PlayerController
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.media.DesktopLyricsOverlayStateStore
import com.mica.music.util.BluetoothAudioDiagnostics
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.AudioEnvironmentDiagnostics
import com.mica.music.util.SpatialAudioMonitor

class MicaApp : Application() {
    /** Process-lifetime catalog for external songs; it is intentionally not persisted. */
    val transientPlaybackCatalog = TransientPlaybackCatalog()

    /** Process-lifetime lyric snapshot shared by the media service and desktop overlay. */
    val desktopLyricsOverlayStateStore = DesktopLyricsOverlayStateStore()

    /**
     * Process-lifetime playback facade. Activity/ViewModel destruction must never tear down
     * the service-backed playback session.
     */
    val playerController: PlayerController by lazy(LazyThreadSafetyMode.NONE) {
        PlayerController(this)
    }

    /**
     * Process-lifetime play-count / listen-seconds persistence. Must outlive Activity/ViewModel
     * so background playback after the UI is dismissed still records stats.
     */
    val playbackStatistics: PlaybackStatisticsRepository by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackStatisticsRepository(
            context = this,
            isPersistentSong = { !TransientPlaybackCatalog.isTransientId(it) },
        ).also { it.bind(playerController) }
    }

    val playlistStore: PlaylistStore by lazy(LazyThreadSafetyMode.NONE) {
        PlaylistStore(this)
    }

    override fun onCreate() {
        super.onCreate()
        ScanCacheManager.runStartupCacheCleanup(this)
        SpatialAudioMonitor.install(this)
        DiagnosticLog.install(this)
        BluetoothAudioDiagnostics.install(this)
        AudioEnvironmentDiagnostics.install(this)
        MicaImageLoaders.init(this)
        // Bind stats persistence before any MediaSession playback can publish sessions.
        playbackStatistics
    }
}
