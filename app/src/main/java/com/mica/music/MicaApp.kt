package com.mica.music

import android.app.Application
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.data.PlaybackStatisticsRepository
import com.mica.music.data.ProcessPlaybackSongResolver
import com.mica.music.data.PlaylistStore
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.media.DesktopLyricsOverlayStateStore
import com.mica.music.media.ServicePlaybackStateStore
import com.mica.music.util.BluetoothAudioDiagnostics
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.AudioEnvironmentDiagnostics
import com.mica.music.util.SpatialAudioMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MicaApp : Application() {
    /** Process-lifetime scope for playback behavior that must outlive Activity/ViewModel owners. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Process-lifetime catalog for the current external queue. */
    val transientPlaybackCatalog = TransientPlaybackCatalog()

    /** Process-lifetime playback lookup; it must not capture a ViewModel or Activity callback. */
    val playbackSongResolver = ProcessPlaybackSongResolver(transientPlaybackCatalog)

    /** Process-lifetime lyric snapshot shared by the media service and desktop overlay. */
    val desktopLyricsOverlayStateStore = DesktopLyricsOverlayStateStore()

    /**
     * Process-lifetime playback facade. Activity/ViewModel destruction must never tear down
     * the service-backed playback session.
     */
    val playerController: PlayerController by lazy(LazyThreadSafetyMode.NONE) {
        PlayerController(this, playbackSongResolver)
    }

    /** Process-lifetime sleep timer; Activity/ViewModel teardown must not cancel active playback policy. */
    val sleepTimer: SleepTimerController by lazy(LazyThreadSafetyMode.NONE) {
        SleepTimerController(processScope, playerController, this)
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
        ServicePlaybackStateStore(this).load()?.externalSongs
            ?.map { it.toSong() }
            ?.let(transientPlaybackCatalog::replaceAll)
        // Bind stats persistence before any MediaSession playback can publish sessions.
        playbackStatistics
    }
}
