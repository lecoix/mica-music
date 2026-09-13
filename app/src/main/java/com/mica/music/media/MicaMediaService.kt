package com.mica.music.media

import com.mica.music.audio.eq.MicaEqualizerManager
import com.mica.music.data.playback.ServicePlaybackSnapshot
import com.mica.music.data.playback.ServicePlaybackStateStore

import com.mica.music.audio.AudioQualityMode

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mica.music.MainActivity
import com.mica.music.MicaApp
import com.mica.music.isExternalAudioUriRestorableNow
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.remote.CompositeRemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.navidrome.NavidromeStreamRequestResolver
import com.mica.music.data.remote.webdav.WebDavStreamRequestResolver
import com.mica.music.data.remote.smb.SmbPlaybackRequestResolver
import com.mica.music.data.remote.smb.SmbStreamRequestResolver
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.ChannelBalancePreferences
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.data.preferences.SoundFxPreferences
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.externallyrics.ExternalLyricsOverlayControl
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding
import com.mica.music.queue.PlaybackShuffleOrder
import com.mica.music.util.DiagnosticLog
import com.mica.music.widget.PlaybackWidgetCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Playback service owns ExoPlayer + MediaSession independently from Activity lifecycle.
 */
@UnstableApi
class MicaMediaService : MediaSessionService() {

    companion object {
        const val ACTION_WIDGET_PREVIOUS = PlaybackWidgetCommandCoordinator.ACTION_PREVIOUS
        const val ACTION_WIDGET_PLAY_PAUSE = PlaybackWidgetCommandCoordinator.ACTION_PLAY_PAUSE
        const val ACTION_WIDGET_NEXT = PlaybackWidgetCommandCoordinator.ACTION_NEXT
    }

    private var mediaSession: MediaSession? = null
    private var sessionPresentationPlayer: MicaSessionPresentationPlayer? = null
    private val playbackStackLifecycle = PlaybackStackLifecycleOwner { player ->
        val publishedPlayer = sessionPresentationPlayer
            ?.takeIf { it.wraps(player) }
            ?: player
        mediaSession?.setPlayer(publishedPlayer)
    }
    private val exoPlayer: ExoPlayer? get() = playbackStackLifecycle.exoPlayer
    private val compositePlayer: MicaCompositePlayer? get() = playbackStackLifecycle.player
    private var replayGainStateOwner: ReplayGainStateOwner? = null
    private var spectrumAnalyzerStateOwner: SpectrumAnalyzerStateOwner? = null
    private var activeOutputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION
    private var playbackStateCoordinator: ServicePlaybackStateCoordinator? = null
    private var playbackWidgetCoordinator: PlaybackWidgetCoordinator? = null
    private var notificationLyricsCoordinator: NotificationLyricsCoordinator? = null
    private var lyriconLyricsSink: LyriconLyricsSink? = null
    private var playbackEngineCoordinator: ServicePlaybackEngineCoordinator? = null
    private var activeAppShuffleRequest: PlaybackShuffleRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionScope: CoroutineScope? = null
    private var trustedMediaItemResolver: TrustedMediaItemResolver? = null
    private var remoteHttpPlaybackResolver: RemoteHttpPlaybackRequestResolver? = null
    private var remoteSmbPlaybackResolver: SmbPlaybackRequestResolver? = null
    private var unregisterLyricsPreferenceListener: (() -> Unit)? = null
    private var unregisterAudioOffloadPreferenceListener: (() -> Unit)? = null
    private var unregisterUsbOutputPreferenceListener: (() -> Unit)? = null
    private var unregisterMusicVideoPreferenceListener: (() -> Unit)? = null
    private lateinit var musicVideoPreferenceOwner: MusicVideoPreferenceOwner
    private val musicVideoFailureRegistry = MusicVideoFailureRegistry()
    private var playbackRouteMonitor: PlaybackRouteMonitor? = null
    private lateinit var externalLyricsOverlayControl: ExternalLyricsOverlayControl
    private var audioOffloadCircuitBreaker: AudioOffloadCircuitBreaker? = null
    private var audioPipelineCoordinator: AudioPipelineCoordinator? = null
    private var usbOutputCoordinator: UsbOutputCoordinator? = null
    private var activeUsbEpoch: Long? = null
    private var usbServiceCreateBootstrapMode: UsbHybridOutputMode? = null
    private var usbBootstrapHandoff: PlaybackStackHandoff? = null
    private var sharedServiceQueueRestoreInFlight = false
    private var sharedServiceQueueRestoreFinished = false
    private lateinit var widgetCommandCoordinator: PlaybackWidgetCommandCoordinator
    @Volatile
    private var usbOutputDestroyed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        activeOutputPath = UsbHostPrototypeOutput.selectedPath(this)
        val micaApp = application as MicaApp
        externalLyricsOverlayControl = micaApp.externalLyricsOverlayControl
        musicVideoPreferenceOwner = MusicVideoPreferenceOwner(
            initialRequested = PlaybackUiPreferences.musicVideoEnabled(this),
        )
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        widgetCommandCoordinator = PlaybackWidgetCommandCoordinator(
            service = this,
            mainHandler = mainHandler,
            playerProvider = { compositePlayer },
            sessionActivityPendingIntent = ::createSessionActivityPendingIntent,
            restoreExhausted = {
                UsbHybridPreferences.outputMode(this) == UsbHybridOutputMode.SharedPcm &&
                    sharedServiceQueueRestoreFinished &&
                    !sharedServiceQueueRestoreInFlight
            },
        )
        val libraryRepository = LibraryRepository(this)
        val remoteMediaItemProvider = TrustedRemoteMediaItemProvider(micaApp.remoteCatalogRepository)
        trustedMediaItemResolver = TrustedMediaItemResolver(
            transientSongById = micaApp.transientPlaybackCatalog::songById,
            librarySongsById = libraryRepository::songSummariesByIds,
            remoteMediaItemsById = remoteMediaItemProvider::resolve,
            mediaItemFactory = { song ->
                decorateResolvedSong(song, ExternalMediaItemCodec.encode(this, song))
            },
        )
        remoteHttpPlaybackResolver = CompositeRemoteHttpPlaybackRequestResolver(
            NavidromeStreamRequestResolver(
                sourceOwnerById = micaApp.remoteCatalogRepository::sourceOwner,
                credentialStore = micaApp.remoteCredentialStore,
            ),
            WebDavStreamRequestResolver(
                sourceOwnerById = micaApp.remoteCatalogRepository::sourceOwner,
                credentialStore = micaApp.remoteCredentialStore,
            ),
        )
        remoteSmbPlaybackResolver = SmbStreamRequestResolver(
            sourceOwnerById = micaApp.remoteCatalogRepository::sourceOwner,
            credentialStore = micaApp.remoteCredentialStore,
        )
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
        })

        PlaybackCapabilityDiagnostics.logStartup(this)
        spectrumAnalyzerStateOwner = SpectrumAnalyzerStateOwner(this).also { it.start() }

        activeOutputPath = AudioOutputPathConfig.PRODUCTION
        val stack = ExoPlaybackStackFactory.build(
            context = this,
            outputPath = activeOutputPath,
            remoteResolver = remoteHttpPlaybackResolver,
            smbResolver = remoteSmbPlaybackResolver,
            isMusicVideoEnabledFor = ::isMusicVideoEnabledForSource,
        )
        installPlaybackStackOwners(stack, micaApp)
        installUsbOutputCoordinator()
        installPlaybackRouteMonitor()

        if (LyricsPreferences.externalLyricsMode(this) != com.mica.music.data.ExternalLyricsMode.OFF &&
            externalLyricsOverlayControl.canDrawOverlays()
        ) {
            externalLyricsOverlayControl.start()
        }

        mediaSession = MediaSession.Builder(this, checkNotNull(sessionPresentationPlayer))
            .setCallback(createMediaSessionCallback())
            .setSessionActivity(createSessionActivityPendingIntent())
            .setMediaButtonPreferences(
                ExternalLyricsSessionCommands.mediaButtonPreferences(
                    context = this,
                    overlayAvailable = externalLyricsOverlayControl.canDrawOverlays(),
                ),
            )
            .build()
        unregisterLyricsPreferenceListener =
            LyricsPreferences.registerNotificationLyricsChangeListener(this) {
                mainHandler.post(::updateMediaButtonPreferences)
            }
        unregisterAudioOffloadPreferenceListener =
            AudioOffloadPreferences.registerChangeListener(this) { state ->
                mainHandler.post {
                    audioPipelineCoordinator?.onOffloadPreferenceChanged(state.enabled)
                }
            }
        unregisterUsbOutputPreferenceListener =
            UsbHybridPreferences.registerChangeListener(this) { mode ->
                mainHandler.post usbPreference@{
                    if (usbOutputDestroyed) return@usbPreference
                    if (usbServiceCreateBootstrapMode != null) {
                        usbServiceCreateBootstrapMode = mode
                    } else {
                        applyUsbOutputMode(mode, "preference")
                    }
                }
            }
        unregisterMusicVideoPreferenceListener =
            PlaybackUiPreferences.registerMusicVideoChangeListener(this) { enabled ->
                mainHandler.post musicVideoPreference@{
                    if (usbOutputDestroyed) return@musicVideoPreference
                    musicVideoPreferenceOwner.updateRequested(enabled)
                }
            }
        val serviceCreateOutputMode = UsbHybridPreferences.outputMode(this)
        applyUsbOutputModeOnServiceCreate(
            serviceCreateOutputMode,
            libraryRepository,
            micaApp,
        )
        if (serviceCreateOutputMode == UsbHybridOutputMode.SharedPcm) {
            restoreSharedServiceQueueIfNeeded(
                stack = stack,
                libraryRepository = libraryRepository,
                micaApp = micaApp,
            )
        }

    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action
            ?.takeIf(widgetCommandCoordinator::isPlaybackAction)
            ?.let(widgetCommandCoordinator::handle)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = compositePlayer ?: mediaSession?.player ?: return
        if (MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = player.playWhenReady,
                mediaItemCount = player.mediaItemCount,
                playbackState = player.playbackState,
            )
        ) {
            stopSelf()
        }
    }

    private fun restoreSharedServiceQueueIfNeeded(
        stack: ExoPlaybackStack,
        libraryRepository: LibraryRepository,
        micaApp: MicaApp,
    ) {
        if (sharedServiceQueueRestoreInFlight || sharedServiceQueueRestoreFinished) return
        if (stack.compositePlayer.mediaItemCount > 0) {
            sharedServiceQueueRestoreFinished = true
            return
        }
        val snapshot = ServicePlaybackStateStore(this).load()
        if (snapshot == null || snapshot.queueSongIds.isEmpty()) {
            sharedServiceQueueRestoreFinished = true
            widgetCommandCoordinator.fail("missing-snapshot")
            return
        }
        sharedServiceQueueRestoreInFlight = true
        sessionScope?.launch {
            val bootstrap = resolveServicePlaybackBootstrap(snapshot, libraryRepository, micaApp)
            mainHandler.post sharedRestore@{
                sharedServiceQueueRestoreInFlight = false
                sharedServiceQueueRestoreFinished = true
                if (usbOutputDestroyed || !playbackStackLifecycle.isActive(stack)) return@sharedRestore
                if (UsbHybridPreferences.outputMode(this@MicaMediaService) != UsbHybridOutputMode.SharedPcm) {
                    return@sharedRestore
                }
                if (stack.compositePlayer.mediaItemCount == 0 && bootstrap != null) {
                    stack.compositePlayer.selectWithoutPlayback(
                        mediaItems = bootstrap.songs.map(::serviceBootstrapMediaItem),
                        startIndex = bootstrap.currentIndex,
                        startPositionMs = bootstrap.positionMs,
                    )
                    DiagnosticLog.event(
                        "PlaybackRestore",
                        "shared service-create bootstrap items=${bootstrap.songs.size} " +
                            "index=${bootstrap.currentIndex} positionMs=${bootstrap.positionMs} resumed=false",
                    )
                }
                if (stack.compositePlayer.mediaItemCount == 0) {
                    widgetCommandCoordinator.fail("bootstrap-empty")
                }
            }
        } ?: run {
            sharedServiceQueueRestoreInFlight = false
            sharedServiceQueueRestoreFinished = true
            widgetCommandCoordinator.fail("scope-unavailable")
        }
    }

    private suspend fun resolveServicePlaybackBootstrap(
        snapshot: ServicePlaybackSnapshot,
        libraryRepository: LibraryRepository,
        micaApp: MicaApp,
    ): ServicePlaybackBootstrap? {
        val libraryIds = snapshot.queueSongIds
            .filterNot(TransientPlaybackCatalog::isTransientId)
            .filterNot(RemoteMediaIdCodec::isRemoteId)
            .distinct()
        val librarySongs = runCatching {
            libraryRepository.songSummariesByIds(libraryIds)
        }.getOrDefault(emptyMap())
        val persistedExternalSongs = snapshot.externalSongs
            .mapNotNull { external ->
                val uri = runCatching { android.net.Uri.parse(external.mediaUri) }.getOrNull()
                if (uri != null && isExternalAudioUriRestorableNow(this, uri)) {
                    external.id to external.toSong()
                } else {
                    null
                }
            }
            .toMap()
        val persistedRemoteSongs = snapshot.remoteSongs.associate { remote ->
            remote.id to remote.toSong()
        }
        val songsById = buildMap {
            putAll(librarySongs)
            putAll(persistedExternalSongs)
            putAll(persistedRemoteSongs)
            snapshot.queueSongIds.forEach { id ->
                micaApp.transientPlaybackCatalog.songById(id)?.let { put(id, it) }
            }
        }
        return ServicePlaybackBootstrapResolver.resolve(snapshot, songsById)
    }

    private fun serviceBootstrapMediaItem(song: com.mica.music.data.Song): MediaItem =
        if (song.isRemote) {
            RemoteMediaItemCodec.encode(song)
        } else {
            decorateResolvedSong(song, SongMediaItemCodec.encode(song))
        }

    private fun installUsbOutputCoordinator() {
        usbOutputCoordinator = DefaultUsbOutputCoordinator(
            context = this,
            mainHandler = mainHandler,
            playback = object : UsbOutputPlaybackPort {
                override fun captureHandoff(): PlaybackStackHandoff? = captureUsbPlaybackHandoff()

                override fun hasPlaybackStack(): Boolean = exoPlayer != null

                override fun isSharedOutputActive(): Boolean =
                    activeOutputPath.outputMode == PlaybackOutputMode.SharedPcm

                override fun currentPlayWhenReady(): Boolean = compositePlayer?.playWhenReady ?: false

                override fun currentAudioSessionId(): Int? = exoPlayer?.audioSessionId

                override fun retireBeforeUsbRequest() {
                    retirePlaybackStackBeforeUsbRequest()
                }

                override fun rebuildShared(handoff: PlaybackStackHandoff?, reason: String) {
                    rebuildPlaybackStack(
                        AudioOutputPathConfig.PRODUCTION,
                        null,
                        reason,
                        handoff,
                    )
                }

                override fun rebuildExclusive(
                    mode: DesiredUsbOutput,
                    binding: UsbHybridPlaybackBinding,
                    handoff: PlaybackStackHandoff?,
                    reason: String,
                ) {
                    rebuildPlaybackStack(mode.toOutputPath(), binding, reason, handoff)
                }

                override fun restorePlaybackIntent(playWhenReady: Boolean) {
                    if (playWhenReady) compositePlayer?.playExoDirect() else compositePlayer?.pauseExoDirect()
                }
            },
        )
    }

    private fun applyUsbOutputModeOnServiceCreate(
        mode: UsbHybridOutputMode,
        libraryRepository: LibraryRepository,
        micaApp: MicaApp,
    ) {
        if (usbOutputDestroyed) return
        if (mode == UsbHybridOutputMode.SharedPcm) {
            usbOutputCoordinator?.start(mode)
            return
        }
        val snapshot = ServicePlaybackStateStore(this).load()
        if (usbOutputDestroyed) return
        if (snapshot == null || snapshot.queueSongIds.isEmpty()) {
            usbOutputCoordinator?.start(mode)
            return
        }

        usbServiceCreateBootstrapMode = mode
        sessionScope?.launch {
            val bootstrap = resolveServicePlaybackBootstrap(snapshot, libraryRepository, micaApp)
            if (usbOutputDestroyed) return@launch
            mainHandler.post usbBootstrap@{
                if (usbOutputDestroyed) return@usbBootstrap
                val selectedMode = usbServiceCreateBootstrapMode
                    ?: UsbHybridPreferences.outputMode(this@MicaMediaService)
                usbServiceCreateBootstrapMode = null
                if (selectedMode != UsbHybridOutputMode.SharedPcm && bootstrap != null) {
                    usbBootstrapHandoff = PlaybackStackHandoff(
                        items = bootstrap.songs.map(::serviceBootstrapMediaItem),
                        currentIndex = bootstrap.currentIndex,
                        positionMs = bootstrap.positionMs,
                        playWhenReady = false,
                        repeatMode = bootstrap.repeatMode,
                        playbackParameters = bootstrap.playbackTuning.toPlaybackParameters(),
                        volume = compositePlayer?.volume ?: 1f,
                    )
                    DiagnosticLog.event(
                        "PlaybackRestore",
                        "usb service-create bootstrap items=${bootstrap.songs.size} " +
                            "index=${bootstrap.currentIndex} positionMs=${bootstrap.positionMs} resumed=false",
                    )
                }
                usbOutputCoordinator?.start(selectedMode)
            }
        } ?: run {
            if (usbOutputDestroyed) return
            usbServiceCreateBootstrapMode = null
            usbOutputCoordinator?.start(mode)
        }
    }

    private fun applyUsbOutputMode(mode: UsbHybridOutputMode, reason: String) {
        if (usbOutputDestroyed) return
        usbOutputCoordinator?.submit(UsbOutputCommand.SelectMode(mode, reason))
    }

    private fun DesiredUsbOutput.toOutputPath(): AudioOutputPathConfig = when (this) {
        DesiredUsbOutput.Shared -> AudioOutputPathConfig.PRODUCTION
        DesiredUsbOutput.ExactPcm -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDirectPcm)
        DesiredUsbOutput.Dop -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDop)
        DesiredUsbOutput.NativeDsd -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbNativeDsdExperimental)
    }

    private fun retirePlaybackStackBeforeUsbRequest() {
        playbackStackLifecycle.retire(
            detachOwners = ::releasePlaybackStackOwners,
            verifyPlayerRelease = true,
        )
    }
    private fun captureUsbPlaybackHandoff(): PlaybackStackHandoff? {
        playbackStackLifecycle.captureHandoff()?.let { live ->
            usbBootstrapHandoff = null
            return live
        }
        return usbBootstrapHandoff.also { usbBootstrapHandoff = null }
    }

    private fun rebuildPlaybackStack(
        target: AudioOutputPathConfig,
        usbBinding: UsbHybridPlaybackBinding?,
        reason: String,
        handoffOverride: PlaybackStackHandoff? = null,
    ) {
        val requestedUsbEpoch = usbBinding?.epoch?.value
        if (exoPlayer != null && target == activeOutputPath && requestedUsbEpoch == activeUsbEpoch) return
        val micaApp = application as MicaApp
        val handoff = handoffOverride ?: playbackStackLifecycle.captureHandoff()
        usbBinding?.owner?.awaitIdle()
        if (usbBinding != null && usbBinding.owner.currentEpoch() != usbBinding.epoch) return
        playbackStackLifecycle.retire(detachOwners = ::releasePlaybackStackOwners)
        val newStack = runCatching {
            ExoPlaybackStackFactory.build(
                context = this,
                outputPath = target,
                usbBinding = usbBinding,
                remoteResolver = remoteHttpPlaybackResolver,
                smbResolver = remoteSmbPlaybackResolver,
                isMusicVideoEnabledFor = ::isMusicVideoEnabledForSource,
            )
        }
            .getOrElse { error ->
                DiagnosticLog.important(
                    "AudioOutputPath",
                    "rebuild-failed reason=$reason target=${target.outputMode} error=${error.message}",
                )
                return
            }
        activeOutputPath = target
        activeUsbEpoch = requestedUsbEpoch
        installPlaybackStackOwners(newStack, micaApp, handoff)
        mediaSession?.broadcastCustomCommand(PlaybackStackSessionEvent.command, Bundle.EMPTY)
        DiagnosticLog.event(
            "AudioOutputPath",
            "rebuild-complete reason=$reason mode=${target.outputMode} " +
                "items=${handoff?.items?.size ?: 0} index=${handoff?.currentIndex ?: 0} " +
                "positionMs=${handoff?.positionMs ?: 0} resume=${handoff?.playWhenReady == true}",
        )
    }

    private fun installPlaybackStackOwners(
        stack: ExoPlaybackStack,
        micaApp: MicaApp,
        handoff: PlaybackStackHandoff? = null,
    ) {
        playbackStackLifecycle.install(
            stack = stack,
            attachOwners = { installedStack ->
                attachPlaybackStackOwners(installedStack, micaApp, handoff)
            },
            detachOwners = ::releasePlaybackStackOwners,
        )
    }

    private fun attachPlaybackStackOwners(
        stack: ExoPlaybackStack,
        micaApp: MicaApp,
        handoff: PlaybackStackHandoff?,
    ) {
        musicVideoPreferenceOwner.attach(stack.compositePlayer)

        if (handoff != null) {
            stack.compositePlayer.selectWithoutPlayback(
                mediaItems = handoff.items,
                startIndex = handoff.currentIndex,
                startPositionMs = handoff.positionMs,
            )
            stack.compositePlayer.repeatMode = handoff.repeatMode
            stack.compositePlayer.playbackParameters = handoff.playbackParameters
            stack.compositePlayer.volume = if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
                handoff.volume
            } else {
                1f
            }
        }
        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            installAudioOffloadCircuitBreaker(stack.exoPlayer)
            installAudioPipelineCoordinator(stack.exoPlayer)
            wireEqualizerAndSpectrumHandlers()
            replayGainStateOwner = ReplayGainStateOwner(this, stack.compositePlayer).also { it.start() }
        }

        playbackEngineCoordinator = ServicePlaybackEngineCoordinator(
            player = stack.compositePlayer,
            context = this,
            musicVideoFailures = musicVideoFailureRegistry,
        ).also { coordinator ->
            coordinator.start()
            coordinator.onPlaybackBoundary = { boundary ->
                if (playbackStackLifecycle.isActive(stack)) {
                    mediaSession?.broadcastCustomCommand(
                        PlaybackBoundarySessionEvent.command,
                        PlaybackBoundarySessionEvent.encode(boundary),
                    )
                }
            }
            coordinator.onMusicVideoFallback = { song ->
                if (playbackStackLifecycle.isActive(stack)) {
                    DiagnosticLog.event(
                        "MusicVideo",
                        "fallback-complete song=${song.id} revision=${song.musicVideoRevision}",
                    )
                }
            }
        }

        var restoredFromStore = false
        playbackStateCoordinator = ServicePlaybackStateCoordinator(
            player = stack.compositePlayer,
            store = ServicePlaybackStateStore(this),
            handler = mainHandler,
            initialQualityMode = if (
                activeOutputPath.outputMode.allowsSharedPcmDsp &&
                EqualizerPreferences.equalizerEnabled(this)
            ) {
                AudioQualityMode.DSP
            } else {
                AudioQualityMode.HIFI
            },
            externalSongResolver = micaApp.transientPlaybackCatalog::songForPersistence,
        ).also { coordinator ->
            coordinator.onRestoreCompleted = {
                restoredFromStore = true
                mainHandler.post {
                    if (!playbackStackLifecycle.isActive(stack)) return@post
                    activeAppShuffleRequest?.let(::applyAppShuffleRequest)
                    logRestoredSongDiagnostics(stack.compositePlayer)
                    if (handoff?.playWhenReady == true) {
                        stack.compositePlayer.playWhenReady = true
                    }
                    widgetCommandCoordinator.drain()
                }
            }
            coordinator.start()
        }
        playbackWidgetCoordinator = PlaybackWidgetCoordinator(
            context = this,
            player = stack.compositePlayer,
        ).also { it.start() }

        if (handoff != null && !restoredFromStore) {
            stack.compositePlayer.seekTo(handoff.currentIndex, handoff.positionMs)
            stack.compositePlayer.prepare()
            stack.compositePlayer.playWhenReady = handoff.playWhenReady
        }
        activeAppShuffleRequest?.let(::applyAppShuffleRequest)

        stack.compositePlayer.onUserPlayIntentChanged = { playWhenReady ->
            if (playbackStackLifecycle.isActive(stack)) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    usbOutputCoordinator?.submit(UsbOutputCommand.PlaybackIntentChanged(playWhenReady))
                } else {
                    mainHandler.post {
                        if (playbackStackLifecycle.isActive(stack)) {
                            usbOutputCoordinator?.submit(UsbOutputCommand.PlaybackIntentChanged(playWhenReady))
                        }
                    }
                }
            }
        }
        stack.compositePlayer.shouldDeferUserPlayIntent = { false }

        // EXPERIMENTAL: keep one active MediaSession and project lyric metadata through its Player.
        sessionPresentationPlayer = MicaSessionPresentationPlayer(stack.compositePlayer)
        lyriconLyricsSink = LyriconLyricsSink(this).also {
            it.start(LyricsPreferences.lyriconLyricsEnabled(this))
        }
        notificationLyricsCoordinator = NotificationLyricsCoordinator(
            context = this,
            player = stack.compositePlayer,
            handler = mainHandler,
            notificationPresentation = sessionPresentationPlayer,
            desktopLyrics = micaApp.desktopLyricsOverlayStateStore,
            lyriconLyrics = lyriconLyricsSink,
            transientSongResolver = micaApp.transientPlaybackCatalog::songById,
        ).also { it.start() }
        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            attachEqualizerSessionListener(stack)
        }
    }

    private fun logRestoredSongDiagnostics(player: MicaCompositePlayer? = compositePlayer) {
        val activePlayer = player ?: return
        val song = activePlayer.currentMediaItem
            ?.let(SongMediaItemCodec::decode)
            ?: return
        SharedPcmPipelineDiagnostics.logSongFormat(song)
        PcmDeliveryProbeDiagnostics.logForSong(
            context = this,
            song = song,
            playbackParameters = activePlayer.playbackParameters,
        )
    }

    private fun isMusicVideoEnabledForSource(item: MediaItem): Boolean {
        if (!MusicVideoPlaybackPolicyCodec.isEnabled(item)) return false
        val song = SongMediaItemCodec.decode(item) ?: return false
        return !musicVideoFailureRegistry.isFailed(song.id, song.musicVideoRevision)
    }

    private fun decorateResolvedSong(song: com.mica.music.data.Song, item: MediaItem): MediaItem {
        val decorated = musicVideoPreferenceOwner.decorateNew(item)
        return if (musicVideoFailureRegistry.isFailed(song.id, song.musicVideoRevision)) {
            MusicVideoPlaybackPolicyCodec.afterFailure(decorated, song.musicVideoRevision)
        } else {
            decorated
        }
    }

    private fun releasePlaybackStackOwners(stack: ExoPlaybackStack) {
        if (::musicVideoPreferenceOwner.isInitialized) {
            musicVideoPreferenceOwner.releasePlayer(stack.compositePlayer)
        }
        stack.compositePlayer.retireForReplacement()
        stack.compositePlayer.onUserPlayIntentChanged = null
        stack.compositePlayer.shouldDeferUserPlayIntent = null
        replayGainStateOwner?.release()
        replayGainStateOwner = null
        playbackStateCoordinator?.release()
        playbackStateCoordinator = null
        playbackWidgetCoordinator?.release()
        playbackWidgetCoordinator = null
        notificationLyricsCoordinator?.release()
        notificationLyricsCoordinator = null
        lyriconLyricsSink?.release()
        lyriconLyricsSink = null
        sessionPresentationPlayer?.clear()
        sessionPresentationPlayer = null
        playbackEngineCoordinator?.release()
        playbackEngineCoordinator = null
        audioOffloadCircuitBreaker?.let { breaker ->
            stack.exoPlayer.removeListener(breaker)
            stack.exoPlayer.removeAudioOffloadListener(breaker)
            breaker.release()
        }
        audioOffloadCircuitBreaker = null
        audioPipelineCoordinator = null
        MicaEqualizerManager.onEnabledChanged = null
        MicaEqualizerManager.onReplayGainDspActiveChanged = null
        MicaEqualizerManager.onChannelBalanceDspActiveChanged = null
        MicaEqualizerManager.onSoundFxDspActiveChanged = null
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaEqualizerManager.release()
    }

    override fun onDestroy() {
        if (::widgetCommandCoordinator.isInitialized) {
            widgetCommandCoordinator.release()
        }
        usbOutputDestroyed = true
        usbOutputCoordinator?.close()
        playbackRouteMonitor?.release()
        playbackRouteMonitor = null
        unregisterLyricsPreferenceListener?.invoke()
        unregisterLyricsPreferenceListener = null
        unregisterAudioOffloadPreferenceListener?.invoke()
        unregisterAudioOffloadPreferenceListener = null
        unregisterUsbOutputPreferenceListener?.invoke()
        unregisterUsbOutputPreferenceListener = null
        unregisterMusicVideoPreferenceListener?.invoke()
        unregisterMusicVideoPreferenceListener = null
        sessionScope?.cancel()
        sessionScope = null
        trustedMediaItemResolver = null
        remoteHttpPlaybackResolver = null
        remoteSmbPlaybackResolver = null
        playbackStackLifecycle.retire(
            detachOwners = ::releasePlaybackStackOwners,
            beforePlayerRelease = {
                spectrumAnalyzerStateOwner?.release()
                spectrumAnalyzerStateOwner = null
                mediaSession?.release()
                mediaSession = null
            },
        )
        activeUsbEpoch = null
        usbOutputCoordinator = null
        clearListener()
        super.onDestroy()
    }

    private fun createMediaSessionCallback(): MediaSession.Callback =
        MicaMediaSessionCallback(
            context = this,
            mainHandler = mainHandler,
            externalLyricsOverlayControl = externalLyricsOverlayControl,
            sessionScopeProvider = { sessionScope },
            trustedMediaItemResolverProvider = { trustedMediaItemResolver },
            decorateResolvedSong = ::decorateResolvedSong,
            applyAppShuffleRequest = ::applyAppShuffleRequest,
            updateMediaButtonPreferences = ::updateMediaButtonPreferences,
        )

    private fun applyAppShuffleRequest(request: PlaybackShuffleRequest) {
        val exo = exoPlayer ?: return
        if (!request.enabled) {
            activeAppShuffleRequest = null
            exo.shuffleModeEnabled = false
            return
        }
        val seed = request.seed ?: return
        activeAppShuffleRequest = request
        val physicalIds = List(exo.mediaItemCount) { index -> exo.getMediaItemAt(index).mediaId }
        val indices = PlaybackShuffleOrder.physicalIndices(
            physicalIds = physicalIds,
            currentId = exo.currentMediaItem?.mediaId,
            seed = seed,
        )
        if (indices.size != physicalIds.size) return
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(indices, seed))
        exo.shuffleModeEnabled = true
    }

    private fun updateMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(
            ExternalLyricsSessionCommands.mediaButtonPreferences(
                context = this,
                overlayAvailable = externalLyricsOverlayControl.canDrawOverlays(),
            ),
        )
    }

    private fun wireEqualizerAndSpectrumHandlers() {
        MicaEqualizerManager.onEnabledChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onEqualizerEnabledChanged(enabled)
            }
        }

        MicaEqualizerManager.onReplayGainDspActiveChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onReplayGainDspEnabledChanged(enabled)
            }
        }

        MicaEqualizerManager.onChannelBalanceDspActiveChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onChannelBalanceDspEnabledChanged(enabled)
            }
        }

        MicaEqualizerManager.onSoundFxDspActiveChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onSoundFxDspEnabledChanged(enabled)
            }
        }

        MicaSpectrumAnalyzer.onEnabledChanged = { enabled ->
            mainHandler.post {
                audioPipelineCoordinator?.onSpectrumTapEnabledChanged(enabled)
            }
        }
    }

    private fun attachEqualizerSessionListener(stack: ExoPlaybackStack) {
        stack.exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (playbackStackLifecycle.isActive(stack)) {
                    MicaEqualizerManager.attach(this@MicaMediaService, audioSessionId)
                }
            }
        })
        if (playbackStackLifecycle.isActive(stack) && stack.exoPlayer.audioSessionId != 0) {
            MicaEqualizerManager.attach(this, stack.exoPlayer.audioSessionId)
        }
    }

    private fun createSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun spectrumTapEnabled(): Boolean =
        spectrumAnalyzerStateOwner?.currentEnabled ?: PlaybackUiPreferences.spectrumTapEnabled(this)

    private fun installPlaybackRouteMonitor() {
        playbackRouteMonitor?.release()
        playbackRouteMonitor = PlaybackRouteMonitor(
            context = this,
            mainHandler = mainHandler,
        ) { previous, current, event ->
            audioPipelineCoordinator?.onRouteChanged(
                "route-change event=$event ${previous.deviceName}->${current.deviceName}",
            )
        }.also { it.install() }
    }

    private fun flushAudioPipeline(reason: String) {
        val player = compositePlayer ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val positionMs = player.currentPosition
        val shouldResume = player.playWhenReady
        player.flushPlaybackPipeline(positionMs, resumePlayback = shouldResume)
        DiagnosticLog.event(
            "AudioPipeline",
            "pipeline-flush reason=$reason pos=$positionMs resume=$shouldResume",
        )
    }

    private fun installAudioPipelineCoordinator(exoPlayer: ExoPlayer) {
        val preferenceState = AudioOffloadPreferences.state(this)
        audioPipelineCoordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = EqualizerPreferences.equalizerEnabled(this),
                spectrumTapEnabled = spectrumTapEnabled(),
                offloadPreferenceEnabled = preferenceState.enabled,
                replayGainDspEnabled = ReplayGainPreferences.mode(this) != ReplayGainMode.OFF,
                channelBalanceDspEnabled =
                    ChannelBalancePreferences.balancePercent(this) != ChannelBalancePreferences.CENTER,
                soundFxDspEnabled = SoundFxPreferences.isDspActive(this),
            ),
            invalidateCircuitBreaker = {
                audioOffloadCircuitBreaker?.invalidateExternalBoundary()
            },
            resetCircuitBreaker = {
                audioOffloadCircuitBreaker?.resetForManualRetry()
            },
            applyConfiguration = { state ->
                applyAudioPipelineConfiguration(exoPlayer, state)
            },
            persistQualityMode = { mode ->
                playbackStateCoordinator?.setQualityMode(mode)
            },
            flushPipeline = ::flushAudioPipeline,
            isOffloadedPlayback = {
                audioOffloadCircuitBreaker?.currentlyOffloaded == true
            },
        ).also { it.applyInitialConfiguration() }
    }

    private fun applyAudioPipelineConfiguration(
        exoPlayer: ExoPlayer,
        state: AudioPipelineState,
    ) {
        val offloadMode = if (state.offloadEnabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        val preferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(offloadMode)
            .build()
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(preferences)
            .build()
        DiagnosticLog.event(
            "AudioQuality",
            "mode=${if (state.equalizerEnabled) "DSP" else "HIFI"} " +
                "dsp=${state.equalizerEnabled} spectrum=${state.spectrumTapEnabled} " +
                "replayGain=${state.replayGainDspEnabled} balance=${state.channelBalanceDspEnabled} " +
                "soundFx=${state.soundFxDspEnabled} " +
                "pcmLatched=${state.pcmSessionLatched} " +
                "offload=${state.offloadEnabled} preference=${state.offloadPreferenceEnabled} " +
                "circuitOpen=${state.circuitOpen}",
        )
    }

    private fun installAudioOffloadCircuitBreaker(exo: ExoPlayer) {
        val breaker = AudioOffloadCircuitBreaker(
            snapshot = {
                AudioOffloadPlaybackSnapshot(
                    mediaId = exo.currentMediaItem?.mediaId,
                    uriScheme = exo.currentMediaItem?.localConfiguration?.uri?.scheme?.lowercase(),
                    playbackState = exo.playbackState,
                    playWhenReady = exo.playWhenReady,
                    isPlaying = exo.isPlaying,
                    playbackSuppressionReason = exo.playbackSuppressionReason,
                    totalBufferedDurationMs = exo.totalBufferedDuration,
                    currentPositionMs = exo.currentPosition,
                )
            },
            scheduler = HandlerAudioOffloadWatchdogScheduler(mainHandler),
            onFallbackToPcm = {
                DiagnosticLog.important(
                    "AudioOffload",
                    "stall-detected fallback=pcm mediaId=${exo.currentMediaItem?.mediaId}",
                )
                audioPipelineCoordinator?.onOffloadCircuitOpened()
            },
            onVerifiedFailure = {
                DiagnosticLog.important(
                    "AudioOffload",
                    "pcm-recovery-verified disable-current-build=true",
                )
                AudioOffloadPreferences.recordVerifiedFailure(this)
            },
        )
        audioOffloadCircuitBreaker = breaker
        exo.addListener(breaker)
        exo.addAudioOffloadListener(breaker)
    }
}
