package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerController
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.components.AddToPlaylistSheet
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.SongActionMenuSheet
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.ui.components.cachedCoverAspectRatio
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.screens.player.rememberPlayerPageUiModel
import com.mica.music.ui.theme.NowPlayingBackground
import com.mica.music.ui.theme.rememberPlayerScreenAppearance
import com.mica.music.util.deleteSongFile
import com.mica.music.util.openSongInTagEditor
import com.mica.music.util.shareSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NowPlayingActions(
    val syncPosition: () -> Unit,
    val setSeekUiActive: (Boolean) -> Unit,
    val seekToMs: (Int) -> Unit,
    val playQueueIndex: (Int) -> Unit,
    val moveQueueItem: (Int, Int) -> Unit,
    val removeQueueItem: (Int) -> Unit,
    val togglePlay: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val cyclePlaybackQueueMode: () -> Unit,
    val toggleImmersiveLower: () -> Unit,
    val insertPlayNext: (Song) -> Unit,
    val setQueue: (List<Song>) -> Unit,
)

@Composable
fun rememberNowPlayingActions(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
): NowPlayingActions =
    remember(playerController, uiSettings) {
        NowPlayingActions(
            syncPosition = playerController::syncPosition,
            setSeekUiActive = playerController::setAlacSeekUiActive,
            seekToMs = playerController::seekToMs,
            playQueueIndex = playerController::playSong,
            moveQueueItem = playerController::moveInQueue,
            removeQueueItem = playerController::removeFromQueue,
            togglePlay = playerController::togglePlay,
            previous = playerController::previous,
            next = playerController::next,
            cyclePlaybackQueueMode = playerController::cyclePlaybackQueueMode,
            toggleImmersiveLower = uiSettings::togglePlayerImmersiveLower,
            insertPlayNext = playerController::insertPlayNext,
            setQueue = playerController::setQueue,
        )
    }

@Composable
fun NowPlayingScreen(
    library: MusicLibrary,
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
) {
    NowPlayingContent(
        library = library,
        surfaceState = playerController.playbackSurfaceState,
        progressState = playerController.playbackProgressState,
        queueState = playerController.playbackQueueState,
        actions = rememberNowPlayingActions(playerController, uiSettings),
        uiSettings = uiSettings,
        onClose = onClose,
        onOpenEqualizer = onOpenEqualizer,
        onOpenSongDetail = onOpenSongDetail,
        onBrowseArtist = onBrowseArtist,
        onBrowseAlbum = onBrowseAlbum,
        contentPadding = contentPadding,
        coverContentAlpha = coverContentAlpha,
        onCoverBoundsChanged = onCoverBoundsChanged,
    )
}

@Composable
fun NowPlayingContent(
    library: MusicLibrary,
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    queueState: PlaybackQueueState,
    actions: NowPlayingActions,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
) {
    val song = surfaceState.currentSong
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val playlistStore = remember { PlaylistStore(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    var queueSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }

    fun openSongActionMenu(target: Song) {
        actionMenuSong = target
    }

    fun handleSongMenuAction(action: SongMenuAction, target: Song) {
        when (action) {
            SongMenuAction.AddToPlaylist -> {
                actionMenuSong = null
                addToPlaylistSong = target
            }
            SongMenuAction.PlayNext -> {
                library.songById(target.id)?.let { actions.insertPlayNext(it) }
                actionMenuSong = null
            }
            SongMenuAction.Share -> {
                if (!shareSong(context, target)) {
                    scope.launch { snackbarHostState.showSnackbar("无法分享此歌曲") }
                }
                actionMenuSong = null
            }
            SongMenuAction.EditTags -> {
                if (!openSongInTagEditor(context, target)) {
                    scope.launch { snackbarHostState.showSnackbar("未找到可用的标签编辑应用") }
                }
                actionMenuSong = null
            }
            SongMenuAction.SongInfo -> {
                actionMenuSong = null
                onOpenSongDetail(target.id)
            }
            SongMenuAction.RemoveFromPlaylist -> actionMenuSong = null
            SongMenuAction.Delete -> {
                actionMenuSong = null
                pendingDeleteSong = target
            }
        }
    }

    fun performDeleteSong(target: Song) {
        scope.launch {
            val deleted = deleteSongFile(context, target)
            library.removeSongFromLibrary(target.id)
            playlistStore.removeSongFromAllPlaylists(target.id)
            val remaining = queueState.queue.filterNot { it.id == target.id }
            actions.setQueue(remaining)
            val message = if (deleted) "已从设备删除" else "已从曲库移除（无法删除文件）"
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(actions, surfaceState.isPlaying, surfaceState.alacStreamActive) {
        actions.syncPosition()
        if (!surfaceState.isPlaying || surfaceState.alacStreamActive) return@LaunchedEffect
        while (true) {
            delay(500)
            actions.syncPosition()
        }
    }

    val seekState = rememberPlaybackSeekState(
        progressState = progressState,
        onSeekUiActiveChanged = actions.setSeekUiActive,
        onSeekToMs = actions.seekToMs,
    )

    val lowerBackground = uiSettings.playerLowerBackground
    val immersiveLower = uiSettings.playerImmersiveLower
    val preloadBlurredBackground = lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW

    LaunchedEffect(song.id, song.albumArtUri, preloadBlurredBackground) {
        MicaImageLoaders.preloadCover(context, song.albumArtUri)
        if (preloadBlurredBackground) {
            MicaImageLoaders.preloadBackground(context, song.albumArtUri)
        }
    }

    var coverAspectRatio by remember(song.albumArtUri) {
        mutableFloatStateOf(cachedCoverAspectRatio(song.albumArtUri) ?: 1f)
    }

    BackHandler(enabled = lyricsExpanded) { lyricsExpanded = false }
    BackHandler(enabled = !lyricsExpanded) { onClose() }

    if (queueSheetOpen) {
        PlaybackQueueSheet(
            queue = queueState.queue,
            currentIndex = queueState.currentIndex,
            isPlaying = surfaceState.isPlaying,
            onDismiss = { queueSheetOpen = false },
            onPlayAt = actions.playQueueIndex,
            onMove = actions.moveQueueItem,
            onRemove = actions.removeQueueItem,
        )
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val screenHeight = maxHeight
            val screenWidth = maxWidth

            val appearance = rememberPlayerScreenAppearance(song, lowerBackground)

            val previewModel = rememberPlayerPageUiModel(
                surfaceState = surfaceState,
                queueState = queueState,
                uiSettings = uiSettings,
                lyricsExpanded = lyricsExpanded,
                panelHeight = screenHeight * 0.45f,
                screenHeight = screenHeight,
                screenWidth = screenWidth,
                coverAspectRatio = coverAspectRatio,
                coverSwitching = false,
            ) ?: return@BoxWithConstraints

            Box(Modifier.fillMaxSize()) {
                NowPlayingBackground(
                    coverColor = appearance.coverColor,
                    albumArtUri = song.albumArtUri,
                    mode = lowerBackground,
                    coverZoneStop = previewModel.frame.cover.zoneStop,
                    modifier = Modifier.matchParentSize(),
                )
                Column(Modifier.fillMaxSize()) {
                    NowPlayingCoverSection(
                        song = previewModel.song,
                        queue = previewModel.queue,
                        currentIndex = previewModel.currentIndex,
                        frame = previewModel.frame,
                        coverColor = appearance.coverColor,
                        contentColors = appearance.contentColors,
                        lowerBackground = lowerBackground,
                        artworkJunction = appearance.artworkJunction,
                        seekState = seekState,
                        isPlaying = previewModel.isPlaying,
                        coverFlowMode = uiSettings.playerCoverFlowMode,
                        lyricsExpanded = lyricsExpanded,
                        coverContentAlpha = coverContentAlpha,
                        onCoverBoundsChanged = onCoverBoundsChanged,
                        onCoverAspectRatioChanged = { coverAspectRatio = it },
                        onCloseLyrics = { lyricsExpanded = false },
                        onPlayQueueIndex = actions.playQueueIndex,
                        onPrevious = actions.previous,
                        onNext = actions.next,
                        onCoverLongPress = { openSongActionMenu(song) },
                        screenWidth = screenWidth,
                    )
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val panelHeight = maxHeight
                        val pageModel = rememberPlayerPageUiModel(
                            surfaceState = surfaceState,
                            queueState = queueState,
                            uiSettings = uiSettings,
                            lyricsExpanded = lyricsExpanded,
                            panelHeight = panelHeight,
                            screenHeight = screenHeight,
                            screenWidth = screenWidth,
                            coverAspectRatio = coverAspectRatio,
                            coverSwitching = false,
                        ) ?: return@BoxWithConstraints
                        PlayerLowerPanelSection(
                            surfaceState = surfaceState,
                            progressState = progressState,
                            activeSong = song,
                            lyrics = song.lyrics,
                            colors = appearance.contentColors,
                            hifiBadgeColors = appearance.hifiBadgeColors,
                            lowerBackground = lowerBackground,
                            lower = pageModel.frame.lower,
                            seekState = seekState,
                            immersiveLower = immersiveLower,
                            spectrumEnabled = pageModel.frame.spectrumEnabled,
                            onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
                            onPrevious = actions.previous,
                            onTogglePlay = actions.togglePlay,
                            onNext = actions.next,
                            onSeekToMs = actions.seekToMs,
                            onToggleImmersive = actions.toggleImmersiveLower,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenLyrics = { lyricsExpanded = true },
                            onOpenQueue = { queueSheetOpen = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        actionMenuSong?.let { menuSong ->
            SongActionMenuSheet(
                song = menuSong,
                onDismiss = { actionMenuSong = null },
                onAction = { handleSongMenuAction(it, menuSong) },
                onArtistClick = { artistName ->
                    actionMenuSong = null
                    onBrowseArtist(artistName)
                },
                onAlbumClick = { albumTitle ->
                    actionMenuSong = null
                    onBrowseAlbum(albumTitle)
                },
            )
        }

        addToPlaylistSong?.let { playlistSong ->
            AddToPlaylistSheet(
                song = playlistSong,
                playlistStore = playlistStore,
                onDismiss = { addToPlaylistSong = null },
                onCreated = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )
        }

        pendingDeleteSong?.let { deleteSong ->
            MicaConfirmDialog(
                visible = true,
                title = "删除音乐",
                message = "确定从设备删除「${deleteSong.title}」？此操作不可撤销。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    performDeleteSong(deleteSong)
                    pendingDeleteSong = null
                },
                onDismiss = { pendingDeleteSong = null },
            )
        }
    }
}
