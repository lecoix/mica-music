package com.mica.music.ui.screens.home

import com.mica.music.ui.screens.HomeBrowseContent
import com.mica.music.ui.screens.HomePlaylistContent
import com.mica.music.ui.screens.LibraryAnalysisContent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mica.music.R
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSession
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.media.NotificationLyrics
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.ui.components.HomeDrawerPanel
import com.mica.music.ui.components.LibrarySearchPanel
import com.mica.music.ui.components.LocalAlphabetFastScrollGesturesEnabled
import com.mica.music.ui.components.MiniPlayer
import com.mica.music.ui.components.miniPlayerText
import com.mica.music.ui.components.rememberSongWithLyrics
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.ui.components.homeDrawerWidth
import com.mica.music.ui.components.miniPlayerListClearance
import com.mica.music.ui.components.miniPlayerOverlayHeight
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.logBackFlow
import com.mica.music.util.openAppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HomeDrawerSwipeVelocityThreshold = 400.dp
private const val HomeDrawerSwipePositionThreshold = 0.5f

@Composable
fun HomeScreen(
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    playbackState: HomePlaybackState,
    playbackActions: HomePlaybackActions,
    uiSettings: AppUiSettings,
    onSongClick: (String) -> Unit,
    onMiniPlayerExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSongDetail: (String) -> Unit,
    miniPlayerCoverAlpha: Float = 1f,
    onMiniPlayerCoverBoundsChanged: (Rect?) -> Unit = {},
    showMiniPlayer: Boolean = true,
    locateCurrentSongRequest: Int = 0,
    homeNavigationIntent: HomeNavigationIntent? = null,
    onHomeNavigationIntentConsumed: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    playerOverlayOpen: Boolean = false,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscapeWindow = configuration.screenWidthDp > configuration.screenHeightDp
    var uiState by rememberSaveable(stateSaver = HomeUiStateSaver) {
        mutableStateOf(HomeUiState.initial(context))
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(HomeOverlayState()) }
    var songMultiSelectActive by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<String>()) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val homeController = rememberHomeScreenController(library, playlistStore)
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val appName = stringResource(R.string.app_name)
    val songListState = rememberLazyListState()
    val artistListState = rememberLazyListState()
    val artistGridState = rememberLazyGridState()
    val albumListState = rememberLazyListState()
    val albumGridState = rememberLazyGridState()

    fun currentNavigationSnapshot() = uiState.navigationSnapshot(
        songMultiSelectActive = songMultiSelectActive,
        selectedSongIds = selectedSongIds,
    )

    fun applyNavigationSnapshot(snapshot: HomeNavigationSnapshot) {
        uiState = uiState.withNavigationSnapshot(snapshot)
        songMultiSelectActive = snapshot.songMultiSelectActive
        selectedSongIds = snapshot.selectedSongIds
    }

    fun openSongActionMenu(song: Song, playlistId: String? = null) {
        overlay = homeController.openActionMenu(overlay, song, playlistId)
    }

    fun applySongMenuOutcome(outcome: SongMenuActionOutcome) {
        outcome.overlay?.let { overlay = it }
        outcome.openSongDetailId?.let { onOpenSongDetail(it) }
        outcome.insertPlayNext?.let { playbackActions.insertPlayNext(it) }
        outcome.snackbarMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    fun onSongMenuAction(action: SongMenuAction, song: Song) {
        applySongMenuOutcome(
            homeController.handleSongMenuAction(context, overlay, action, song),
        )
    }

    fun confirmDeleteSong(song: Song) {
        scope.launch {
            val message = homeController.deleteSong(
                context = context,
                song = song,
                currentQueue = playbackState.queue,
                setQueue = playbackActions.setQueue,
            )
            snackbarHostState.showSnackbar(message)
            overlay = homeController.clearPendingDeleteSong(overlay)
        }
    }

    fun confirmDeletePlaylist(playlistId: String) {
        val outcome = homeController.deletePlaylist(playlistId, uiState.section, uiState.activePlaylistId)
        if (outcome.section != null) {
            uiState = uiState.copy(
                section = outcome.section,
                activePlaylistId = outcome.activePlaylistId,
            )
        }
        scope.launch { snackbarHostState.showSnackbar(outcome.snackbarMessage) }
        overlay = homeController.clearPendingDeletePlaylist(overlay)
    }

    fun createPlaylist(name: String) {
        overlay = homeController.dismissCreatePlaylistDialog(overlay)
        val outcome = homeController.createPlaylist(name)
        if (outcome.section != null) {
            uiState = uiState.copy(
                section = outcome.section,
                activePlaylistId = outcome.activePlaylistId,
            )
        }
        outcome.snackbarMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    fun exitSongMultiSelect() {
        songMultiSelectActive = false
        selectedSongIds = emptySet()
    }

    fun toggleSongSelection(songId: String) {
        selectedSongIds = if (songId in selectedSongIds) {
            selectedSongIds - songId
        } else {
            selectedSongIds + songId
        }
    }

    fun selectAllSongs() {
        selectedSongIds = library.songs.mapTo(mutableSetOf()) { it.id }
    }

    fun invertSongSelection() {
        selectedSongIds = library.songs
            .map { it.id }
            .filterNot { it in selectedSongIds }
            .toSet()
    }

    fun openSongMultiSelect() {
        if (uiState.section != HomeSection.Songs || uiState.searchOpen) return
        songMultiSelectActive = true
        selectedSongIds = emptySet()
    }

    val browseSort = uiState.browseSort

    fun updateBrowseSort(updated: HomeBrowseSortState) {
        uiState = uiState.copy(browseSort = updated)
    }

    LaunchedEffect(library.lastScanSyncSummary) {
        val summary = library.lastScanSyncSummary ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(summary)
        library.clearScanSyncSummary()
    }

    val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)

    val latestPlaybackState by rememberUpdatedState(playbackState)
    val libraryAccess = rememberHomeLibraryAccess(
        library = library,
        activity = activity,
        onResumeWithPermission = { granted ->
            if (granted && library.hasScanned) {
                val syncStartedMs = SystemClock.elapsedRealtime()
                playbackActions.syncPlaybackState()
                val state = latestPlaybackState
                DiagnosticLog.event(
                    "LibraryResume",
                    "syncPlaybackState durMs=${SystemClock.elapsedRealtime() - syncStartedMs} " +
                        "queue=${state.queue.size} current=${state.currentSong?.id}",
                )
            }
        },
    )

    fun performNavigateBack() {
        logBackFlow(
            "back-consume source=home-internal section=${uiState.section} " +
                "searchOpen=${uiState.searchOpen} browse=${uiState.browseDestination} " +
                "multiSelect=$songMultiSelectActive returnSection=${uiState.returnSection}",
        )
        val result = navigateBack(currentNavigationSnapshot())
        applyNavigationSnapshot(result.snapshot)
        if (result.hideKeyboard) keyboardController?.hide()
    }

    fun onDrawerPick(target: HomeSection) {
        logBackFlow("page-action home-drawer-pick target=$target previous=${uiState.section}")
        drawerOpen = false
        when (target) {
            HomeSection.Settings -> onOpenSettings()
            HomeSection.Playlist -> Unit
            else -> {
                val nextReturnSection = if (
                    (target == HomeSection.Recent || target == HomeSection.LibraryAnalysis) &&
                    uiState.section != HomeSection.Recent &&
                    uiState.section != HomeSection.LibraryAnalysis
                ) {
                    uiState.section
                } else {
                    uiState.returnSection
                }
                uiState = uiState.copy(
                    section = target,
                    activePlaylistId = null,
                    browseDestination = BrowseDestination.Root,
                    browseStack = emptyList(),
                    returnSection = nextReturnSection,
                )
            }
        }
    }

    fun onDrawerPlaylistPick(playlistId: String) {
        logBackFlow("page-action home-drawer-playlist playlist=$playlistId previous=${uiState.section}")
        drawerOpen = false
        uiState = uiState.copy(
            section = HomeSection.Playlist,
            activePlaylistId = playlistId,
            browseDestination = BrowseDestination.Root,
            browseStack = emptyList(),
            searchOpen = false,
        )
    }

    fun locateCurrentSongInLibrary() {
        val song = playbackState.currentSong ?: return
        val index = library.songs.indexOfFirst { it.id == song.id }
        if (index < 0) {
            scope.launch { snackbarHostState.showSnackbar("当前播放歌曲不在歌曲列表中") }
            return
        }
        drawerOpen = false
        keyboardController?.hide()
        uiState = uiState.copy(
            searchOpen = false,
            searchQuery = "",
            activePlaylistId = null,
            browseDestination = BrowseDestination.Root,
            browseStack = emptyList(),
            section = HomeSection.Songs,
        )
        scope.launch {
            delay(MicaMotion.DurationShortMs.toLong())
            songListState.animateScrollToItem(index)
        }
    }

    LaunchedEffect(playlistStore.playlists, uiState.activePlaylistId) {
        val activePlaylistId = uiState.activePlaylistId
        if (uiState.section == HomeSection.Playlist && activePlaylistId != null) {
            if (playlistStore.playlists.none { it.id == activePlaylistId }) {
                uiState = uiState.copy(
                    section = HomeSection.Songs,
                    activePlaylistId = null,
                )
            }
        }
    }

    LaunchedEffect(uiState.section, uiState.activePlaylistId) {
        when (uiState.section) {
            HomeSection.Songs, HomeSection.Artists, HomeSection.Albums ->
                LibraryBrowseSettings.setLastHomeLocation(context, uiState.section.name, null)
            HomeSection.Playlist -> uiState.activePlaylistId?.let { playlistId ->
                LibraryBrowseSettings.setLastHomeLocation(context, uiState.section.name, playlistId)
            }
            else -> Unit
        }
    }

    LaunchedEffect(locateCurrentSongRequest) {
        if (locateCurrentSongRequest > 0) {
            locateCurrentSongInLibrary()
        }
    }

    fun openAlbumBrowse(albumTitle: String) {
        applyNavigationSnapshot(navigateToAlbum(currentNavigationSnapshot(), albumTitle))
        drawerOpen = false
    }

    fun openArtistBrowse(artistName: String) {
        applyNavigationSnapshot(navigateToArtist(currentNavigationSnapshot(), artistName))
        drawerOpen = false
    }

    LaunchedEffect(homeNavigationIntent) {
        val intent = homeNavigationIntent ?: return@LaunchedEffect
        logBackFlow("page-action home-intent section=${intent.section} browse=${intent.browseDestination}")
        drawerOpen = false
        applyNavigationSnapshot(consumeNavigationIntent(currentNavigationSnapshot(), intent))
        keyboardController?.hide()
        onHomeNavigationIntentConsumed()
    }

    LaunchedEffect(uiState.section, uiState.browseDestination) {
        if (uiState.section == HomeSection.Folders) {
            val folder = uiState.browseDestination as? BrowseDestination.Folder
            uiState = uiState.copy(
                folderVisibleDepth = folder?.depth ?: 0,
                folderVisibleScope = folder?.scopePathSegments.orEmpty(),
            )
        }
    }

    LaunchedEffect(uiState.section, uiState.searchOpen) {
        if (shouldClearSongMultiSelect(uiState.section, uiState.searchOpen)) {
            exitSongMultiSelect()
        }
    }

    val canNavigateBack = canNavigateBack(currentNavigationSnapshot())
    val showFolderMenuButton = showFolderMenuButton(uiState.section, uiState.searchOpen)
    val showBackButton = canNavigateBack && !showFolderMenuButton

    LaunchedEffect(
        drawerOpen,
        uiState.section,
        uiState.activePlaylistId,
        uiState.searchOpen,
        uiState.browseDestination,
        songMultiSelectActive,
        canNavigateBack,
        playerOverlayOpen,
    ) {
        logBackFlow(
            "page home drawer=$drawerOpen section=${uiState.section} " +
                "playlist=${uiState.activePlaylistId ?: "none"} " +
                "search=${uiState.searchOpen} browse=${uiState.browseDestination} " +
                "multiSelect=$songMultiSelectActive " +
                "canBack=$canNavigateBack playerOverlayOpen=$playerOverlayOpen",
        )
    }

    BackHandler(enabled = drawerOpen && !playerOverlayOpen) {
        logBackFlow("back-consume source=home-drawer section=${uiState.section}")
        drawerOpen = false
    }
    BackHandler(enabled = canNavigateBack && !drawerOpen && !playerOverlayOpen) {
        performNavigateBack()
    }

    val activePlaylist = uiState.activePlaylistId?.let { id ->
        playlistStore.playlists.find { it.id == id }
    }
    val activePlaylistSongCount = activePlaylist?.songIds?.size ?: 0

    val visibleBrowseDestination = visibleBrowseDestination(
        section = uiState.section,
        browseDestination = uiState.browseDestination,
        folderVisibleDepth = uiState.folderVisibleDepth,
        folderVisibleScope = uiState.folderVisibleScope,
    )

    val topBarTitle = resolveTopBarTitle(
        appName = appName,
        section = uiState.section,
        playlistName = activePlaylist?.name,
        searchOpen = uiState.searchOpen,
        browseDestination = visibleBrowseDestination,
    )

    val statsBarModel = if (!uiState.searchOpen) {
        rememberLibraryStatsBarModel(
            section = uiState.section,
            browseDestination = visibleBrowseDestination,
            library = library,
            activePlaylistId = uiState.activePlaylistId,
            playlistSongCount = activePlaylistSongCount,
            playlistSortField = activePlaylist?.sortField,
            playlistSortDirection = activePlaylist?.sortDirection,
            albumSortField = uiState.browseSort.albumSortField,
            albumSortDirection = uiState.browseSort.albumSortDirection,
            albumGridColumns = uiState.browseSort.albumGridColumns,
            artistSortField = uiState.browseSort.artistSortField,
            artistSortDirection = uiState.browseSort.artistSortDirection,
            artistGridColumns = uiState.browseSort.artistGridColumns,
            songListInfoVisibility = uiSettings.songListInfoVisibility,
            browseListInfoVisibility = uiSettings.browseListInfoVisibility,
        )
    } else {
        null
    }
    var statsBarSnapshot by remember { mutableStateOf<LibraryStatsBarModel?>(null) }
    if (statsBarModel != null) {
        statsBarSnapshot = statsBarModel
    }

    val isPlaylistSort = uiState.section == HomeSection.Playlist && uiState.activePlaylistId != null
    val isAlbumRootSort =
        uiState.section == HomeSection.Albums && visibleBrowseDestination == BrowseDestination.Root
    val isArtistRootSort =
        uiState.section == HomeSection.Artists && visibleBrowseDestination == BrowseDestination.Root

    val miniPlayerStyle = uiSettings.miniPlayerStyle
    val currentSongSummary = playbackState.currentSong
    val currentQueueIndex = playbackState.queue.indexOfFirst { it.id == currentSongSummary?.id }
    val nextSong = playbackState.queue.getOrNull(currentQueueIndex + 1)
    val currentSong = currentSongSummary?.let {
        rememberSongWithLyrics(library, it, nextSong, uiSettings.lyricsSlotPriority)
    }
    val infoRowLyricsSession = remember(currentSong?.id, currentSong?.lyricsDocument) {
        currentSong?.let { LyricsSession(it.lyricsDocument) }
    }
    val lineStartTimesMs = remember(infoRowLyricsSession) {
        infoRowLyricsSession?.lyrics?.map { it.timeMs }?.toIntArray() ?: IntArray(0)
    }
    val lyricsVisible = uiSettings.infoRowLyricsEnabled || uiSettings.miniPlayerLyricsEnabled
    LaunchedEffect(
        currentSong?.id,
        infoRowLyricsSession,
        playbackState.positionMs,
        playbackState.isPlaying,
        playbackState.isBuffering,
        playbackState.playbackSpeed,
        lyricsVisible,
    ) {
        if (lyricsVisible) {
            awaitNextHomeLyricBoundary(
                lineStartTimesMs = lineStartTimesMs,
                positionMs = playbackState.positionMs,
                playbackSpeed = playbackState.playbackSpeed,
                isAdvancing = playbackState.isPlaying && !playbackState.isBuffering,
                syncPosition = playbackActions.syncPosition,
            )
        }
    }
    val activeLyricIndex = remember(infoRowLyricsSession, playbackState.positionMs) {
        infoRowLyricsSession?.let {
            NotificationLyrics.lyricIndexForPosition(it, playbackState.positionMs)
        } ?: -1
    }
    val activeLyricLine = infoRowLyricsSession
        ?.takeIf { activeLyricIndex >= 0 }
        ?.lyrics
        ?.getOrNull(activeLyricIndex)
    val nextLyricLineTimeMs = infoRowLyricsSession
        ?.lyrics
        ?.getOrNull(activeLyricIndex + 1)
        ?.timeMs
    val sharedLyricText = remember(
        infoRowLyricsSession,
        activeLyricIndex,
        uiSettings.lyricSplitEnabled,
        uiSettings.lyricsBilingualDisplayMode,
    ) {
        infoRowLyricsSession?.takeIf { activeLyricIndex >= 0 }?.let { session ->
            NotificationLyrics.lyricLineText(
                lyrics = session.lyrics,
                index = activeLyricIndex,
                display = NotificationLyrics.DisplayOptions(
                    splitEnabled = uiSettings.lyricSplitEnabled,
                    bilingualMode = uiSettings.lyricsBilingualDisplayMode,
                ),
            )
        }
    }
    val originalLyricText = remember(
        infoRowLyricsSession,
        activeLyricIndex,
        uiSettings.lyricSplitEnabled,
    ) {
        infoRowLyricsSession?.takeIf { activeLyricIndex >= 0 }?.let { session ->
            NotificationLyrics.lyricLineText(
                lyrics = session.lyrics,
                index = activeLyricIndex,
                display = NotificationLyrics.DisplayOptions(
                    splitEnabled = uiSettings.lyricSplitEnabled,
                    bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
                ),
            )
        }
    }
    val infoRowWordActive = uiSettings.infoRowLyricsEnabled &&
        uiSettings.infoRowWordLyricsEnabled &&
        playbackState.isPlaying
    val infoRowLyricText = when {
        !uiSettings.infoRowLyricsEnabled || !playbackState.isPlaying -> null
        infoRowWordActive -> originalLyricText
        else -> sharedLyricText
    }
    val infoRowKaraokeLine = activeLyricLine?.takeIf {
        infoRowWordActive && it.cues.isNotEmpty()
    }
    val miniPlayerWordActive = uiSettings.miniPlayerLyricsEnabled &&
        uiSettings.miniPlayerWordLyricsEnabled &&
        playbackState.isPlaying
    val miniPlayerLyricText = if (miniPlayerWordActive) originalLyricText else sharedLyricText
    val miniPlayerKaraokeLine = activeLyricLine?.takeIf {
        miniPlayerWordActive && it.cues.isNotEmpty()
    }
    val listBottomPadding = if (currentSong != null) {
        miniPlayerListClearance(miniPlayerStyle)
    } else {
        0.dp
    }

    val motionEnabled = rememberMicaMotionEnabled()
    val drawerWidth = homeDrawerWidth()
    val drawerProgress = remember { Animatable(if (drawerOpen) 1f else 0f) }
    val drawerDragProgress = remember { mutableFloatStateOf(drawerProgress.value) }
    var drawerDragging by remember { mutableStateOf(false) }
    val drawerPushTween = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    LaunchedEffect(drawerOpen, drawerDragging, motionEnabled) {
        if (!drawerDragging) {
            drawerProgress.animateTo(
                targetValue = if (drawerOpen) 1f else 0f,
                animationSpec = drawerPushTween,
            )
        }
    }
    val visibleDrawerProgress = if (drawerDragging) {
        drawerDragProgress.floatValue
    } else {
        drawerProgress.value
    }
    val contentOffsetX = drawerWidth * visibleDrawerProgress
    val drawerOffsetX = -drawerWidth * (1f - visibleDrawerProgress)
    val drawerVelocityThresholdPx = with(LocalDensity.current) {
        HomeDrawerSwipeVelocityThreshold.toPx()
    }
    val drawerBottomInset = if (currentSong != null) {
        miniPlayerOverlayHeight(miniPlayerStyle)
    } else {
        0.dp
    }
    CompositionLocalProvider(
        LocalAlphabetFastScrollGesturesEnabled provides !playerOverlayOpen,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground(),
    ) {
        HomeDrawerPanel(
            selectedSection = uiState.section,
            activePlaylistId = uiState.activePlaylistId,
            playlists = playlistStore.playlists,
            statusBarTop = statusBarTop,
            bottomInset = drawerBottomInset,
            onSectionSelected = ::onDrawerPick,
            onOpenEqualizer = {
                drawerOpen = false
                onOpenEqualizer()
            },
            onOpenAbout = {
                drawerOpen = false
                onOpenAbout()
            },
            onPlaylistSelected = ::onDrawerPlaylistPick,
            onCreatePlaylist = {
                drawerOpen = false
                overlay = homeController.showCreatePlaylistDialog(overlay)
            },
            modifier = Modifier.offset(x = drawerOffsetX),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = contentOffsetX)
                .homeDrawerSwipe(
                    enabled = !showBackButton && !playerOverlayOpen,
                    drawerWidth = drawerWidth,
                    onProgressChange = { drawerDragProgress.floatValue = it },
                    onDragStarted = {
                        drawerProgress.stop()
                        drawerDragProgress.floatValue = drawerProgress.value
                        drawerDragging = true
                        drawerProgress.value
                    },
                    onDragStopped = { progress, velocity ->
                        drawerProgress.snapTo(progress)
                        drawerOpen = homeDrawerTargetOpen(
                            progress = progress,
                            velocityPxPerSecond = velocity,
                            velocityThresholdPxPerSecond = drawerVelocityThresholdPx,
                        )
                        drawerDragging = false
                    },
                )
                .padding(top = statusBarTop),
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeTopBar(
                title = topBarTitle,
                showBack = showBackButton,
                searchOpen = uiState.searchOpen,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = { query ->
                    uiState = uiState.copy(searchQuery = query)
                },
                motionEnabled = motionEnabled,
                onLeadingClick = {
                    when {
                        showFolderMenuButton -> drawerOpen = !drawerOpen
                        canNavigateBack -> performNavigateBack()
                        drawerOpen -> drawerOpen = false
                        else -> drawerOpen = true
                    }
                },
                onSearchClick = {
                    uiState = uiState.copy(searchOpen = true)
                    drawerOpen = false
                },
            )

            if (statsBarModel != null) {
                Spacer(Modifier.height(HifiSpacing.xs))
            }

            AnimatedVisibility(
                visible = statsBarModel != null,
                enter = fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                    expandVertically(MicaMotion.tweenIntSize(motionEnabled, MicaMotion.DurationShortMs)),
                exit = fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                    shrinkVertically(MicaMotion.tweenIntSize(motionEnabled, MicaMotion.DurationShortMs)),
            ) {
                statsBarSnapshot?.let { model ->
                    Column {
                        if (songMultiSelectActive) {
                            SongMultiSelectStatsRow(
                                selectedCount = selectedSongIds.size,
                                canSelectSongs = library.songs.isNotEmpty(),
                                onSelectAll = ::selectAllSongs,
                                onInvertSelection = ::invertSongSelection,
                                onClearSelection = { selectedSongIds = emptySet() },
                                onAddToPlaylist = {
                                    val songs = library.songs.filter { it.id in selectedSongIds }
                                    if (songs.isNotEmpty()) {
                                        overlay = overlay.copy(addToPlaylistSongs = songs)
                                    }
                                },
                            )
                        } else {
                            LibraryStatsRow(
                                model = model,
                                lyricText = infoRowLyricText,
                                karaokeLine = infoRowKaraokeLine,
                                nextLyricLineTimeMs = nextLyricLineTimeMs,
                                positionMs = playbackState.positionMs,
                                isPlaying = playbackState.isPlaying,
                                onSortClick = { sortSheetOpen = true },
                                onRescan = libraryAccess.onRequestRescan,
                                onDeletePlaylist = {
                                    uiState.activePlaylistId?.let { playlistId ->
                                        overlay = homeController.requestDeletePlaylist(overlay, playlistId)
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(HifiSpacing.md))
                    }
                }
            }

            HomeSortSheets(
                visible = sortSheetOpen,
                context = context,
                section = uiState.section,
                isAlbumRootSort = isAlbumRootSort,
                isArtistRootSort = isArtistRootSort,
                isPlaylistSort = isPlaylistSort,
                browseSort = browseSort,
                onBrowseSortChange = ::updateBrowseSort,
                library = library,
                playlistStore = playlistStore,
                activePlaylistId = uiState.activePlaylistId,
                playlistSortField = activePlaylist?.sortField,
                playlistSortDirection = activePlaylist?.sortDirection,
                onDismiss = { sortSheetOpen = false },
                onMultiSelectClick = ::openSongMultiSelect,
                uiSettings = uiSettings,
            )

            val paneKey = resolveHomePaneKey(
                searchOpen = uiState.searchOpen,
                section = uiState.section,
                activePlaylistId = uiState.activePlaylistId,
                browseDestination = uiState.browseDestination,
            )
            AnimatedContent(
                targetState = paneKey,
                modifier = Modifier.weight(1f),
                transitionSpec = MicaMotion.homePaneWithSearchTransition(
                    motionEnabled,
                    ::homePaneDepth,
                ) { it is HomePaneKey.Search },
                label = "homePane",
            ) { key ->
                when (key) {
                    HomePaneKey.Search -> LibrarySearchPanel(
                        query = uiState.searchQuery,
                        library = library,
                        currentSongId = currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onQueueSongs = playbackActions.setQueue,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        listBottomPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HomePaneKey.Songs -> HomeLibraryPane(
                        library = library,
                        songListInfoVisibility = uiSettings.songListInfoVisibility,
                        currentSongId = currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onQueueSongs = playbackActions.setQueue,
                        shouldOpenSettings = libraryAccess.shouldOpenSettings,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        onPickLibraryFolder = libraryAccess.onPickLibraryFolder,
                        onRequestFullScan = libraryAccess.onRequestFullScan,
                        onStartScan = libraryAccess.onStartScan,
                        onRequestRescan = libraryAccess.onRequestRescan,
                        onOpenSettings = { openAppSettings(context) },
                        listState = songListState,
                        listBottomPadding = listBottomPadding,
                        selectionMode = songMultiSelectActive,
                        selectedSongIds = selectedSongIds,
                        onSelectionToggle = ::toggleSongSelection,
                        onMoveSong = { from, to ->
                            library.moveSongInLibrary(from, to)
                        },
                    )
                    HomePaneKey.Analysis -> LibraryAnalysisContent(
                        library = library,
                        listBottomPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is HomePaneKey.Playlist -> HomePlaylistContent(
                        playlistId = key.id,
                        playlistStore = playlistStore,
                        library = library,
                        currentSongId = currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onQueueSongs = playbackActions.setQueue,
                        onSongClick = onSongClick,
                        onSongOpenMenu = { openSongActionMenu(it, key.id) },
                        onMoveSong = { from, to ->
                            playlistStore.moveSongInPlaylist(key.id, from, to)
                        },
                        listBottomPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HomePaneKey.Folders -> HomeBrowseContent(
                        section = HomeSection.Folders,
                        destination = uiState.browseDestination,
                        onDestinationChange = { destination ->
                            uiState = uiState.copy(browseDestination = destination)
                        },
                        onFolderPageChange = { depth, scopePathSegments ->
                            uiState = uiState.copy(
                                folderVisibleDepth = depth,
                                folderVisibleScope = scopePathSegments,
                            )
                        },
                        library = library,
                        currentSongId = currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onQueueSongs = playbackActions.setQueue,
                        onAppendSongsToQueue = playbackActions.appendToQueue,
                        onAddSongsToPlaylist = { songs ->
                            overlay = overlay.copy(
                                addToPlaylistSongs = songs,
                                addToPlaylistAsCustomOrder = true,
                            )
                        },
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        onAlbumClick = ::openAlbumBrowse,
                        albumSortField = uiState.browseSort.albumSortField,
                        albumSortDirection = uiState.browseSort.albumSortDirection,
                        albumGridColumns = uiState.browseSort.albumGridColumns,
                        artistSortField = uiState.browseSort.artistSortField,
                        artistSortDirection = uiState.browseSort.artistSortDirection,
                        artistGridColumns = uiState.browseSort.artistGridColumns,
                        artistListState = artistListState,
                        artistGridState = artistGridState,
                        albumListState = albumListState,
                        albumGridState = albumGridState,
                        listBottomPadding = listBottomPadding,
                        motionEnabled = motionEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is HomePaneKey.Browse -> HomeBrowseContent(
                        section = key.section,
                        destination = key.destination,
                        onDestinationChange = { destination ->
                            applyNavigationSnapshot(
                                pushBrowseDestination(currentNavigationSnapshot(), destination),
                            )
                        },
                        library = library,
                        currentSongId = currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onQueueSongs = playbackActions.setQueue,
                        onAppendSongsToQueue = playbackActions.appendToQueue,
                        onAddSongsToPlaylist = { songs ->
                            overlay = overlay.copy(
                                addToPlaylistSongs = songs,
                                addToPlaylistAsCustomOrder = true,
                            )
                        },
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        onAlbumClick = ::openAlbumBrowse,
                        albumSortField = uiState.browseSort.albumSortField,
                        albumSortDirection = uiState.browseSort.albumSortDirection,
                        albumGridColumns = uiState.browseSort.albumGridColumns,
                        artistSortField = uiState.browseSort.artistSortField,
                        artistSortDirection = uiState.browseSort.artistSortDirection,
                        artistGridColumns = uiState.browseSort.artistGridColumns,
                        artistListState = artistListState,
                        artistGridState = artistGridState,
                        albumListState = albumListState,
                        albumGridState = albumGridState,
                        listBottomPadding = listBottomPadding,
                        motionEnabled = motionEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

        }
        }

        var miniPlayerSongSnapshot by remember { mutableStateOf<Song?>(null) }
        LaunchedEffect(currentSong) {
            currentSong?.let { miniPlayerSongSnapshot = it }
        }
        val miniPlayerEnter = if (miniPlayerStyle == MiniPlayerStyle.AUDIOPHILE) {
            fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                slideInVertically(MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationShortMs)) { it }
        } else {
            fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs))
        }
        val miniPlayerExit = if (miniPlayerStyle == MiniPlayerStyle.AUDIOPHILE) {
            fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                slideOutVertically(MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationShortMs)) { it }
        } else {
            fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs))
        }
        if (showMiniPlayer) {
            AnimatedVisibility(
                visible = currentSong != null,
                enter = miniPlayerEnter,
                exit = miniPlayerExit,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f),
            ) {
                (currentSong ?: miniPlayerSongSnapshot)?.let { song ->
                    MiniPlayer(
                        style = miniPlayerStyle,
                        song = song,
                        isPlaying = playbackState.isPlaying,
                        positionMs = playbackState.positionMs,
                        onPlayPause = playbackActions.togglePlay,
                        onPrevious = playbackActions.previous,
                        onNext = playbackActions.next,
                        onExpand = onMiniPlayerExpand,
                        onLongPress = ::locateCurrentSongInLibrary,
                        miniPlayerLyricsEnabled = uiSettings.miniPlayerLyricsEnabled,
                        miniPlayerWordLyricsEnabled = uiSettings.miniPlayerWordLyricsEnabled,
                        lyricSplitEnabled = uiSettings.lyricSplitEnabled,
                        lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                        swipeEnabled = uiSettings.miniPlayerSwipeEnabled,
                        leftSwipeAction = uiSettings.miniPlayerLeftSwipeAction,
                        rightSwipeAction = uiSettings.miniPlayerRightSwipeAction,
                        coverAlpha = miniPlayerCoverAlpha,
                        onCoverBoundsChanged = onMiniPlayerCoverBoundsChanged,
                        resolvedText = miniPlayerText(
                            song = song,
                            isPlaying = playbackState.isPlaying,
                            enabled = uiSettings.miniPlayerLyricsEnabled,
                            lyricText = miniPlayerLyricText,
                        ),
                        karaokeLine = miniPlayerKaraokeLine,
                        nextLyricLineTimeMs = nextLyricLineTimeMs,
                        modifier = if (isLandscapeWindow) {
                            Modifier
                        } else {
                            Modifier.offset(x = contentOffsetX)
                        },
                    )
                }
            }
        }

        val snackbarBottomPadding = if (currentSong != null) {
            miniPlayerOverlayHeight(miniPlayerStyle) + HifiSpacing.md
        } else {
            contentPadding.calculateBottomPadding() + HifiSpacing.md
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = snackbarBottomPadding),
        )

        HomeOverlays(
            overlay = overlay,
            playlistStore = playlistStore,
            resolveSong = library::songById,
            onDismissActionMenu = {
                overlay = homeController.dismissActionMenu(overlay)
            },
            onSongMenuAction = ::onSongMenuAction,
            onArtistClick = ::openArtistBrowse,
            onAlbumClick = ::openAlbumBrowse,
            onDismissAddToPlaylist = {
                overlay = homeController.dismissAddToPlaylist(overlay)
            },
            onAddToPlaylistCreated = { message ->
                if (songMultiSelectActive) {
                    exitSongMultiSelect()
                }
                overlay = homeController.dismissAddToPlaylist(overlay)
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            onDismissCreatePlaylist = {
                overlay = homeController.dismissCreatePlaylistDialog(overlay)
            },
            onCreatePlaylist = ::createPlaylist,
            onConfirmDeleteSong = ::confirmDeleteSong,
            onDismissDeleteSong = {
                overlay = homeController.clearPendingDeleteSong(overlay)
            },
            onConfirmDeletePlaylist = ::confirmDeletePlaylist,
            onDismissDeletePlaylist = {
                overlay = homeController.clearPendingDeletePlaylist(overlay)
            },
        )
    }
    }
}

@Composable
internal fun Modifier.homeDrawerSwipe(
    enabled: Boolean,
    drawerWidth: Dp,
    onProgressChange: (Float) -> Unit,
    onDragStarted: suspend () -> Float,
    onDragStopped: suspend (progress: Float, velocityPxPerSecond: Float) -> Unit,
): Modifier {
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }.coerceAtLeast(1f)
    val currentDrawerWidthPx by rememberUpdatedState(drawerWidthPx)
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragStopped by rememberUpdatedState(onDragStopped)
    val gestureProgress = remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { deltaPx ->
        val updated =
            (gestureProgress.floatValue + deltaPx / currentDrawerWidthPx).coerceIn(0f, 1f)
        gestureProgress.floatValue = updated
        currentOnProgressChange(updated)
    }
    return draggable(
        state = draggableState,
        orientation = Orientation.Horizontal,
        enabled = enabled,
        onDragStarted = {
            gestureProgress.floatValue = currentOnDragStarted()
        },
        onDragStopped = { velocity ->
            currentOnDragStopped(gestureProgress.floatValue, velocity)
        },
    )
}

internal fun homeDrawerTargetOpen(
    progress: Float,
    velocityPxPerSecond: Float,
    velocityThresholdPxPerSecond: Float,
): Boolean = when {
    velocityPxPerSecond >= velocityThresholdPxPerSecond -> true
    velocityPxPerSecond <= -velocityThresholdPxPerSecond -> false
    else -> progress >= HomeDrawerSwipePositionThreshold
}
