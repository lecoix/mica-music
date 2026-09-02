package com.mica.music.media

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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
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
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding
import com.mica.music.queue.PlaybackShuffleOrder
import com.mica.music.util.DiagnosticLog
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

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var compositePlayer: MicaCompositePlayer? = null
    private var replayGainStateOwner: ReplayGainStateOwner? = null
    private var spectrumAnalyzerStateOwner: SpectrumAnalyzerStateOwner? = null
    private var activeOutputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION
    private var playbackStateCoordinator: ServicePlaybackStateCoordinator? = null
    private var notificationLyricsCoordinator: NotificationLyricsCoordinator? = null
    private var carBluetoothLyricsSession: CarBluetoothLyricsSession? = null
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
    private var audioOffloadCircuitBreaker: AudioOffloadCircuitBreaker? = null
    private var audioPipelineCoordinator: AudioPipelineCoordinator? = null
    private var usbOutputCoordinator: UsbOutputCoordinator? = null
    private var activeUsbEpoch: Long? = null
    private var usbServiceCreateBootstrapMode: UsbHybridOutputMode? = null
    private var usbBootstrapHandoff: UsbPlaybackStackHandoff? = null
    @Volatile
    private var usbOutputDestroyed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        activeOutputPath = UsbHostPrototypeOutput.selectedPath(this)
        val micaApp = application as MicaApp
        musicVideoPreferenceOwner = MusicVideoPreferenceOwner(
            initialRequested = PlaybackUiPreferences.musicVideoEnabled(this),
        )
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        PcmDeliveryExperiment.logActiveExperiments()
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
            DesktopLyricsOverlayController.canDrawOverlays(this)
        ) {
            DesktopLyricsOverlayController.start(this)
        }

        mediaSession = MediaSession.Builder(this, stack.compositePlayer)
            .setCallback(createMediaSessionCallback())
            .setSessionActivity(createSessionActivityPendingIntent())
            .setMediaButtonPreferences(
                ExternalLyricsSessionCommands.mediaButtonPreferences(this),
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
        applyUsbOutputModeOnServiceCreate(
            UsbHybridPreferences.outputMode(this),
            libraryRepository,
            micaApp,
        )

    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

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

    private fun installUsbOutputCoordinator() {
        usbOutputCoordinator = DefaultUsbOutputCoordinator(
            context = this,
            mainHandler = mainHandler,
            playback = object : UsbOutputPlaybackPort {
                override fun captureHandoff(): UsbPlaybackStackHandoff? = captureUsbPlaybackHandoff()

                override fun hasPlaybackStack(): Boolean = exoPlayer != null

                override fun isSharedOutputActive(): Boolean =
                    activeOutputPath.outputMode == PlaybackOutputMode.SharedPcm

                override fun currentPlayWhenReady(): Boolean = compositePlayer?.playWhenReady ?: false

                override fun currentAudioSessionId(): Int? = exoPlayer?.audioSessionId

                override fun retireBeforeUsbRequest() {
                    retirePlaybackStackBeforeUsbRequest()
                }

                override fun rebuildShared(handoff: UsbPlaybackStackHandoff?, reason: String) {
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
                    handoff: UsbPlaybackStackHandoff?,
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
                    if (uri != null && isExternalAudioUriRestorableNow(this@MicaMediaService, uri)) {
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
            val bootstrap = ServicePlaybackBootstrapResolver.resolve(snapshot, songsById)
            if (usbOutputDestroyed) return@launch
            mainHandler.post usbBootstrap@{
                if (usbOutputDestroyed) return@usbBootstrap
                val selectedMode = usbServiceCreateBootstrapMode
                    ?: UsbHybridPreferences.outputMode(this@MicaMediaService)
                usbServiceCreateBootstrapMode = null
                if (selectedMode != UsbHybridOutputMode.SharedPcm && bootstrap != null) {
                    usbBootstrapHandoff = UsbPlaybackStackHandoff(
                        items = bootstrap.songs.map { song ->
                            if (song.isRemote) {
                                RemoteMediaItemCodec.encode(song)
                            } else {
                                decorateResolvedSong(song, SongMediaItemCodec.encode(song))
                            }
                        },
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
        val oldExo = exoPlayer
        var releaseError: PlaybackException? = null
        val releaseObserver = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                releaseError = error
            }
        }
        oldExo?.addListener(releaseObserver)
        releasePlaybackStackOwners()
        oldExo?.release()
        exoPlayer = null
        compositePlayer = null
        releaseError?.let { error ->
            throw IllegalStateException(
                "Old ExoPlayer release failed: ${error.cause?.message ?: error.message}",
                error,
            )
        }
    }
    private fun captureUsbPlaybackHandoff(): UsbPlaybackStackHandoff? {
        capturePlaybackStackHandoff()?.let { live ->
            usbBootstrapHandoff = null
            return live
        }
        return usbBootstrapHandoff.also { usbBootstrapHandoff = null }
    }

    private fun capturePlaybackStackHandoff(): UsbPlaybackStackHandoff? {
        val player = compositePlayer ?: return null
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) return null
        return UsbPlaybackStackHandoff(
            items = queue.items,
            currentIndex = queue.currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            playbackParameters = player.playbackParameters,
            volume = player.volume,
        )
    }

    private fun rebuildPlaybackStack(
        target: AudioOutputPathConfig,
        usbBinding: UsbHybridPlaybackBinding?,
        reason: String,
        handoffOverride: UsbPlaybackStackHandoff? = null,
    ) {
        val requestedUsbEpoch = usbBinding?.epoch?.value
        if (exoPlayer != null && target == activeOutputPath && requestedUsbEpoch == activeUsbEpoch) return
        val micaApp = application as MicaApp
        val handoff = handoffOverride ?: capturePlaybackStackHandoff()
        usbBinding?.owner?.awaitIdle()
        if (usbBinding != null && usbBinding.owner.currentEpoch() != usbBinding.epoch) return
        val oldExo = exoPlayer
        releasePlaybackStackOwners()
        oldExo?.release()
        exoPlayer = null
        compositePlayer = null
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
                DiagnosticLog.event(
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
        handoff: UsbPlaybackStackHandoff? = null,
    ) {
        exoPlayer = stack.exoPlayer
        compositePlayer = stack.compositePlayer
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
        mediaSession?.setPlayer(stack.compositePlayer)

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
                mediaSession?.broadcastCustomCommand(
                    PlaybackBoundarySessionEvent.command,
                    PlaybackBoundarySessionEvent.encode(boundary),
                )
            }
            coordinator.onMusicVideoFallback = { song ->
                DiagnosticLog.event(
                    "MusicVideo",
                    "fallback-complete song=${song.id} revision=${song.musicVideoRevision}",
                )
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
                    activeAppShuffleRequest?.let(::applyAppShuffleRequest)
                    logRestoredSongDiagnostics()
                    if (handoff?.playWhenReady == true) {
                        compositePlayer?.playWhenReady = true
                    }
                }
            }
            coordinator.start()
        }

        if (handoff != null && !restoredFromStore) {
            stack.compositePlayer.seekTo(handoff.currentIndex, handoff.positionMs)
            stack.compositePlayer.prepare()
            stack.compositePlayer.playWhenReady = handoff.playWhenReady
        }
        activeAppShuffleRequest?.let(::applyAppShuffleRequest)

        stack.compositePlayer.onUserPlayIntentChanged = { playWhenReady ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                usbOutputCoordinator?.submit(UsbOutputCommand.PlaybackIntentChanged(playWhenReady))
            } else {
                mainHandler.post {
                    usbOutputCoordinator?.submit(UsbOutputCommand.PlaybackIntentChanged(playWhenReady))
                }
            }
        }
        stack.compositePlayer.shouldDeferUserPlayIntent = { false }

        carBluetoothLyricsSession = CarBluetoothLyricsSession(
            context = this,
            player = stack.compositePlayer,
            sessionActivity = createSessionActivityPendingIntent(),
        )
        notificationLyricsCoordinator = NotificationLyricsCoordinator(
            context = this,
            player = stack.compositePlayer,
            handler = mainHandler,
            carBluetoothLyrics = carBluetoothLyricsSession,
            desktopLyrics = micaApp.desktopLyricsOverlayStateStore,
            transientSongResolver = micaApp.transientPlaybackCatalog::songById,
        ).also { it.start() }
        if (activeOutputPath.outputMode.allowsSharedPcmDsp) {
            attachEqualizerSessionListener(stack.exoPlayer)
        }
    }

    private fun logRestoredSongDiagnostics() {
        val song = compositePlayer?.currentMediaItem
            ?.let(SongMediaItemCodec::decode)
            ?: return
        SharedPcmPipelineDiagnostics.logSongFormat(song)
        PcmDeliveryProbeDiagnostics.logForSong(
            context = this,
            song = song,
            playbackParameters = compositePlayer?.playbackParameters ?: PlaybackParameters.DEFAULT,
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

    private fun releasePlaybackStackOwners() {
        if (::musicVideoPreferenceOwner.isInitialized) {
            musicVideoPreferenceOwner.releasePlayer(compositePlayer)
        }
        compositePlayer?.retireForReplacement()
        compositePlayer?.onUserPlayIntentChanged = null
        compositePlayer?.shouldDeferUserPlayIntent = null
        replayGainStateOwner?.release()
        replayGainStateOwner = null
        playbackStateCoordinator?.release()
        playbackStateCoordinator = null
        notificationLyricsCoordinator?.release()
        notificationLyricsCoordinator = null
        carBluetoothLyricsSession?.release()
        carBluetoothLyricsSession = null
        playbackEngineCoordinator?.release()
        playbackEngineCoordinator = null
        audioOffloadCircuitBreaker?.let { breaker ->
            exoPlayer?.removeListener(breaker)
            exoPlayer?.removeAudioOffloadListener(breaker)
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
        releasePlaybackStackOwners()
        spectrumAnalyzerStateOwner?.release()
        spectrumAnalyzerStateOwner = null
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        compositePlayer = null
        activeUsbEpoch = null
        usbOutputCoordinator = null
        clearListener()
        super.onDestroy()
    }

    private fun createMediaSessionCallback(): MediaSession.Callback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                DiagnosticLog.event(
                    "MediaSession",
                    "controller-connect package=${identity.packageName} uid=${identity.uid} " +
                        "trusted=${identity.isTrusted} version=${identity.controllerVersion} " +
                        "class=${capabilities.controllerClass} " +
                        "hints=${identity.connectionHintKeys.sorted().joinToString(",")}",
                )

                // Media3's default callback preserves its standard trusted/untrusted player
                // command rules. Mica-specific capabilities are handled below and are explicit.
                val defaultResult = super.onConnect(session, controller)
                if (!controller.isTrusted && controller.packageName != packageName) {
                    return defaultResult.also {
                        grantArtworkUriPermissions(
                            targetPackage = controller.packageName,
                            mediaItems = session.player.timelineMediaItems(),
                        )
                    }
                }
                val availableSessionCommands = defaultResult.availableSessionCommands
                    .buildUpon()
                    .add(ExternalLyricsSessionCommands.toggleDesktopLyrics)
                    .add(ExternalLyricsSessionCommands.toggleDesktopLock)
                    .add(PlaybackShuffleSessionCommand.command)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(defaultResult.availablePlayerCommands)
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setMediaButtonPreferences(
                        ExternalLyricsSessionCommands.mediaButtonPreferences(this@MicaMediaService),
                    )
                    .build().also {
                    grantArtworkUriPermissions(
                        targetPackage = controller.packageName,
                        mediaItems = session.player.timelineMediaItems(),
                    )
                }
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val identity = controllerIdentity(controller)
                PlaybackShuffleSessionCommand.decode(customCommand, args)?.let { request ->
                    if (identity.packageName != packageName) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    mainHandler.post { applyAppShuffleRequest(request) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction ==
                    ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LYRICS_ACTION &&
                    isMediaNotificationController(session, controller)
                ) {
                    mainHandler.post {
                        val currentMode = LyricsPreferences.externalLyricsMode(this@MicaMediaService)
                        LyricsPreferences.setExternalLyricsMode(
                            this@MicaMediaService,
                            ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(currentMode),
                        )
                        DesktopLyricsOverlayController.sync(this@MicaMediaService)
                        updateMediaButtonPreferences()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction ==
                    ExternalLyricsSessionCommands.TOGGLE_DESKTOP_LOCK_ACTION &&
                    isMediaNotificationController(session, controller)
                ) {
                    mainHandler.post {
                        if (LyricsPreferences.externalLyricsMode(this@MicaMediaService) ==
                            com.mica.music.data.ExternalLyricsMode.DESKTOP
                        ) {
                            LyricsPreferences.setDesktopLyricsLocked(
                                this@MicaMediaService,
                                !LyricsPreferences.desktopLyricsLocked(this@MicaMediaService),
                            )
                            DesktopLyricsOverlayController.refreshSettings(this@MicaMediaService)
                            updateMediaButtonPreferences()
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (!ControllerCapabilityPolicy.allowsIncomingCustomAction(
                        identity = identity,
                        ownPackageName = packageName,
                        action = customCommand.customAction,
                    )
                ) {
                    DiagnosticLog.event(
                        "MediaSession",
                        "custom-command-rejected package=${identity.packageName} " +
                            "action=${customCommand.customAction}",
                    )
                    return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                    )
                }
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                if (!capabilities.resolveMediaItemsFromCatalog) {
                    return Futures.immediateFuture(decorateOwnAppMediaItems(mediaItems))
                }
                return launchSessionFuture {
                    val resolved = trustedMediaItemResolver
                        ?.resolve(mediaItems)
                        ?.mediaItems.orEmpty()
                    grantArtworkUriPermissions(controller.packageName, resolved)
                    resolved.toMutableList()
                }
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val identity = controllerIdentity(controller)
                val capabilities = ControllerCapabilityPolicy.evaluate(identity, packageName)
                if (!capabilities.resolveMediaItemsFromCatalog) {
                    val decorated = decorateOwnAppMediaItems(mediaItems)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            decorated,
                            startIndex.coerceIn(0, (decorated.size - 1).coerceAtLeast(0)),
                            startPositionMs,
                        ),
                    )
                }
                if (mediaItems.size == 1 &&
                    MediaItemRequestPolicy.isEmptyRequest(mediaItems.single())
                ) {
                    val current = mediaSession.player.currentMediaItem
                    if (current != null) {
                        grantArtworkUriPermissions(controller.packageName, listOf(current))
                        DiagnosticLog.event(
                            "MediaSession",
                            "empty-set-request-preserved-current package=${controller.packageName}",
                        )
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(current),
                                0,
                                startPositionMs,
                            ),
                        )
                    }
                }
                return launchSessionFuture {
                    val resolution = trustedMediaItemResolver?.resolve(mediaItems, startIndex)
                        ?: TrustedMediaItemsResolution(emptyList(), null)
                    grantArtworkUriPermissions(controller.packageName, resolution.mediaItems)
                    MediaSession.MediaItemsWithStartPosition(
                        resolution.mediaItems.toMutableList(),
                        resolution.resolvedStartIndex ?: 0,
                        startPositionMs,
                    )
                }
            }
        }

    private fun decorateOwnAppMediaItems(mediaItems: MutableList<MediaItem>): MutableList<MediaItem> =
        mediaItems.map { item ->
            SongMediaItemCodec.decode(item)?.let { song -> decorateResolvedSong(song, item) } ?: item
        }.toMutableList()

    private fun grantArtworkUriPermissions(targetPackage: String, mediaItems: List<MediaItem>) {
        val targetPackages = ArtworkUriGrantPolicy.targetPackages(targetPackage)
        if (targetPackages.isEmpty()) return
        mediaItems.forEach { mediaItem ->
            val artworkUri = mediaItem.mediaMetadata.artworkUri ?: return@forEach
            if (!ArtworkUriGrantPolicy.isGrantable(packageName, artworkUri)) return@forEach
            targetPackages.forEach { grantPackage ->
                runCatching {
                    grantUriPermission(grantPackage, artworkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { error ->
                    DiagnosticLog.event(
                        "MediaSession",
                        "artwork-grant-failed package=$grantPackage uri=$artworkUri " +
                            "error=${error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun isMediaNotificationController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Boolean {
        val notificationController = session.getMediaNotificationControllerInfo()
            ?: return false
        return notificationController.packageName == controller.packageName &&
            notificationController.uid == controller.uid
    }

    private fun applyAppShuffleRequest(request: PlaybackShuffleRequest) {
        val exo = exoPlayer ?: return
        if (!request.enabled) {
            activeAppShuffleRequest = null
            exo.shuffleModeEnabled = false
            DiagnosticLog.event("Shuffle", "service mode=off items=${exo.mediaItemCount}")
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
        DiagnosticLog.event(
            "Shuffle",
            "service mode=on seed=$seed items=${physicalIds.size} current=${exo.currentMediaItem?.mediaId}",
        )
    }

    private fun updateMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(
            ExternalLyricsSessionCommands.mediaButtonPreferences(this),
        )
    }

    private fun Player.timelineMediaItems(): List<MediaItem> =
        List(mediaItemCount) { index -> getMediaItemAt(index) }

    private fun controllerIdentity(controller: MediaSession.ControllerInfo): ControllerIdentity =
        ControllerIdentity(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            connectionHintKeys = controller.connectionHints.keySet(),
        )

    private fun <T> launchSessionFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val scope = sessionScope
        if (scope == null) {
            future.setException(IllegalStateException("MediaSession scope is not active"))
            return future
        }
        scope.launch {
            runCatching { block() }
                .onSuccess(future::set)
                .onFailure(future::setException)
        }
        return future
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

    private fun attachEqualizerSessionListener(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                MicaEqualizerManager.attach(this@MicaMediaService, audioSessionId)
            }
        })
        if (exo.audioSessionId != 0) {
            MicaEqualizerManager.attach(this, exo.audioSessionId)
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
                DiagnosticLog.event(
                    "AudioOffload",
                    "stall-detected fallback=pcm mediaId=${exo.currentMediaItem?.mediaId}",
                )
                audioPipelineCoordinator?.onOffloadCircuitOpened()
            },
            onVerifiedFailure = {
                DiagnosticLog.event(
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
