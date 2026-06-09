package com.mica.music.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.mica.music.R
import com.mica.music.data.MusicLibrary
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlaylistStore
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.HomeDrawerPanel
import com.mica.music.ui.components.HomeDrawerWidthFraction
import com.mica.music.ui.components.homeDrawerWidth
import com.mica.music.ui.components.miniPlayerOverlayHeight
import com.mica.music.ui.components.LibrarySearchPanel
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.MicaTextInputDialog
import com.mica.music.data.AppUiSettings
import com.mica.music.ui.components.MiniPlayer
import com.mica.music.ui.components.miniPlayerListClearance
import com.mica.music.ui.components.AddToPlaylistSheet
import com.mica.music.ui.components.SongActionMenuSheet
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.ui.components.SongSortSheet
import androidx.compose.material.icons.outlined.Delete
import com.mica.music.util.deleteSongFile
import com.mica.music.util.openSongInTagEditor
import com.mica.music.util.shareSong
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import kotlinx.coroutines.delay
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.openAppSettings
import kotlinx.coroutines.launch

enum class HomeSection {
    Songs,
    Artists,
    Albums,
    Recent,
    Playlist,
    LibraryAnalysis,
    Settings,
}

private sealed interface HomePaneKey {
    data object Search : HomePaneKey
    data object Songs : HomePaneKey
    data object Analysis : HomePaneKey
    data class Playlist(val id: String) : HomePaneKey
    data class Browse(
        val section: HomeSection,
        val destination: BrowseDestination,
    ) : HomePaneKey
}

/** 主页分区栈深度：用于前进/返回滑动方向。 */
private fun homePaneDepth(key: HomePaneKey): Int = when (key) {
    HomePaneKey.Songs, HomePaneKey.Search -> 0
    is HomePaneKey.Analysis, is HomePaneKey.Playlist -> 1
    is HomePaneKey.Browse -> when (key.destination) {
        BrowseDestination.Root -> 1
        else -> 2
    }
}

private fun resolveHomePaneKey(
    searchOpen: Boolean,
    section: HomeSection,
    activePlaylistId: String?,
    browseDestination: BrowseDestination,
): HomePaneKey = when {
    searchOpen -> HomePaneKey.Search
    section == HomeSection.Songs -> HomePaneKey.Songs
    section == HomeSection.LibraryAnalysis -> HomePaneKey.Analysis
    section == HomeSection.Playlist && activePlaylistId != null -> HomePaneKey.Playlist(activePlaylistId)
    section == HomeSection.Artists ||
        section == HomeSection.Albums ||
        section == HomeSection.Recent ->
        HomePaneKey.Browse(section, browseDestination)
    else -> HomePaneKey.Songs
}

private val BrowseDestinationSaver = Saver<BrowseDestination, List<String>>(
    save = { destination ->
        when (destination) {
            BrowseDestination.Root -> listOf("root", "")
            is BrowseDestination.Artist -> listOf("artist", destination.name)
            is BrowseDestination.Album -> listOf("album", destination.title)
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "artist" -> BrowseDestination.Artist(saved.getOrNull(1).orEmpty())
            "album" -> BrowseDestination.Album(saved.getOrNull(1).orEmpty())
            else -> BrowseDestination.Root
        }
    },
)

@Composable
fun HomeScreen(
    library: MusicLibrary,
    playerController: PlayerController,
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
    contentPadding: PaddingValues = PaddingValues(),
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(HomeSection.Songs) }
    var activePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var browseDestination by rememberSaveable(stateSaver = BrowseDestinationSaver) {
        mutableStateOf<BrowseDestination>(BrowseDestination.Root)
    }
    /** 进入「最近播放 / 音乐库分析」前的分区，返回时恢复（从哪来回哪去）。 */
    var returnSection by rememberSaveable { mutableStateOf(HomeSection.Songs) }
    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var actionMenuPlaylistId by remember { mutableStateOf<String?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeletePlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val context = LocalContext.current
    val playlistStore = remember { PlaylistStore(context) }
    val activity = context as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val appName = stringResource(R.string.app_name)
    val songListState = rememberLazyListState()

    fun openSongActionMenu(song: Song, playlistId: String? = null) {
        actionMenuSong = song
        actionMenuPlaylistId = playlistId
    }

    fun handleSongMenuAction(action: SongMenuAction, song: Song) {
        when (action) {
            SongMenuAction.AddToPlaylist -> {
                actionMenuSong = null
                addToPlaylistSong = song
            }
            SongMenuAction.PlayNext -> {
                library.songById(song.id)?.let { playerController.insertPlayNext(it) }
                actionMenuSong = null
            }
            SongMenuAction.Share -> {
                if (!shareSong(context, song)) {
                    scope.launch { snackbarHostState.showSnackbar("无法分享此歌曲") }
                }
                actionMenuSong = null
            }
            SongMenuAction.EditTags -> {
                if (!openSongInTagEditor(context, song)) {
                    scope.launch { snackbarHostState.showSnackbar("未找到可用的标签编辑应用") }
                }
                actionMenuSong = null
            }
            SongMenuAction.SongInfo -> {
                actionMenuSong = null
                onOpenSongDetail(song.id)
            }
            SongMenuAction.RemoveFromPlaylist -> {
                val playlistId = actionMenuPlaylistId
                actionMenuSong = null
                actionMenuPlaylistId = null
                if (playlistId != null && playlistStore.removeSongFromPlaylist(playlistId, song.id)) {
                    scope.launch { snackbarHostState.showSnackbar("已从歌单移除") }
                }
            }
            SongMenuAction.Delete -> {
                actionMenuSong = null
                actionMenuPlaylistId = null
                pendingDeleteSong = song
            }
        }
    }

    fun performDeleteSong(song: Song) {
        scope.launch {
            val deleted = deleteSongFile(context, song)
            library.removeSongFromLibrary(song.id)
            playlistStore.removeSongFromAllPlaylists(song.id)
            val remaining = playerController.songQueue.filterNot { it.id == song.id }
            playerController.setQueue(remaining)
            val message = if (deleted) "已从设备删除" else "已从曲库移除（无法删除文件）"
            snackbarHostState.showSnackbar(message)
        }
    }

    fun performDeletePlaylist(playlistId: String) {
        val name = playlistStore.playlistById(playlistId)?.name ?: "歌单"
        playlistStore.deletePlaylist(playlistId)
        if (section == HomeSection.Playlist && activePlaylistId == playlistId) {
            section = HomeSection.Songs
            activePlaylistId = null
        }
        scope.launch { snackbarHostState.showSnackbar("已删除「$name」") }
    }

    LaunchedEffect(library.lastScanSyncSummary) {
        val summary = library.lastScanSyncSummary ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(summary)
        library.clearScanSyncSummary()
    }

    val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)

    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var audioRequestAttempted by remember { mutableStateOf(false) }
    var shouldOpenSettings by remember { mutableStateOf(false) }

    fun refreshPermissionState() {
        val granted = library.hasAudioReadPermission()
        library.updatePermission(granted)
        shouldOpenSettings = !granted && audioRequestAttempted &&
            !activity.shouldShowRequestPermissionRationale(audioPermission)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioRequestAttempted = true
        library.updatePermission(granted)
        shouldOpenSettings = !granted &&
            !activity.shouldShowRequestPermissionRationale(audioPermission)
        if (granted) {
            library.launchScanDeviceWide()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        library.setLibraryFolder(uri)
        library.launchScanLibraryFolder()
    }

    LaunchedEffect(Unit) {
        refreshPermissionState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
                if (library.hasAudioReadPermission() && library.hasScanned) {
                    playerController.syncPlaybackState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onPickLibraryFolder: () -> Unit = {
        folderPickerLauncher.launch(null)
    }

    val onRequestFullScan: () -> Unit = {
        if (shouldOpenSettings) {
            openAppSettings(context)
        } else if (library.permissionGranted) {
            library.launchScanDeviceWide()
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    val onStartScan: () -> Unit = {
        when {
            library.hasLibraryFolder() -> library.launchScanLibraryFolder()
            library.hasAudioReadPermission() -> library.launchScanDeviceWide()
            else -> permissionLauncher.launch(audioPermission)
        }
    }

    val onRequestRescan: () -> Unit = {
        library.launchRescan()
    }

    fun navigateBack() {
        when {
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
                keyboardController?.hide()
            }
            browseDestination != BrowseDestination.Root -> {
                browseDestination = BrowseDestination.Root
            }
            section == HomeSection.Recent || section == HomeSection.LibraryAnalysis -> {
                section = returnSection
                activePlaylistId = null
            }
        }
    }

    fun onDrawerPick(target: HomeSection) {
        drawerOpen = false
        when (target) {
            HomeSection.Settings -> onOpenSettings()
            HomeSection.Playlist -> Unit
            else -> {
                if (target == HomeSection.Recent || target == HomeSection.LibraryAnalysis) {
                    if (section != HomeSection.Recent && section != HomeSection.LibraryAnalysis) {
                        returnSection = section
                    }
                }
                section = target
                activePlaylistId = null
                browseDestination = BrowseDestination.Root
            }
        }
    }

    fun onDrawerPlaylistPick(playlistId: String) {
        drawerOpen = false
        section = HomeSection.Playlist
        activePlaylistId = playlistId
        browseDestination = BrowseDestination.Root
        searchOpen = false
    }

    fun locateCurrentSongInLibrary() {
        val song = playerController.currentSong ?: return
        val index = library.songs.indexOfFirst { it.id == song.id }
        if (index < 0) {
            scope.launch { snackbarHostState.showSnackbar("当前播放歌曲不在歌曲列表中") }
            return
        }
        drawerOpen = false
        searchOpen = false
        searchQuery = ""
        keyboardController?.hide()
        activePlaylistId = null
        browseDestination = BrowseDestination.Root
        section = HomeSection.Songs
        scope.launch {
            delay(MicaMotion.DurationShortMs.toLong())
            songListState.animateScrollToItem(index)
        }
    }

    LaunchedEffect(playlistStore.playlists, activePlaylistId) {
        if (section == HomeSection.Playlist && activePlaylistId != null) {
            if (playlistStore.playlists.none { it.id == activePlaylistId }) {
                section = HomeSection.Songs
                activePlaylistId = null
            }
        }
    }

    // 长按迷你播放条：定位到歌曲列表中的当前播放歌曲（事件由外层 PlayerSheetHost 转发而来）。
    LaunchedEffect(locateCurrentSongRequest) {
        if (locateCurrentSongRequest > 0) {
            locateCurrentSongInLibrary()
        }
    }

    val canNavigateBack = searchOpen ||
        browseDestination != BrowseDestination.Root ||
        section == HomeSection.Recent ||
        section == HomeSection.LibraryAnalysis

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }
    BackHandler(enabled = canNavigateBack && !drawerOpen) {
        navigateBack()
    }

    val activePlaylist = activePlaylistId?.let { id ->
        playlistStore.playlists.find { it.id == id }
    }
    val activePlaylistSongCount = activePlaylist?.songIds?.size ?: 0

    val topBarTitle = resolveTopBarTitle(
        appName = appName,
        section = section,
        playlistName = activePlaylist?.name,
        searchOpen = searchOpen,
        browseDestination = browseDestination,
    )

    val statsBarModel = if (!searchOpen) {
        rememberLibraryStatsBarModel(
            section = section,
            browseDestination = browseDestination,
            library = library,
            activePlaylistId = activePlaylistId,
            playlistSongCount = activePlaylistSongCount,
            playlistSortField = activePlaylist?.sortField,
            playlistSortDirection = activePlaylist?.sortDirection,
        )
    } else {
        null
    }
    // 退出动画期间 AnimatedVisibility 仍会重组子项，需保留上一份 model，避免 statsBarModel!! NPE
    var statsBarSnapshot by remember { mutableStateOf<LibraryStatsBarModel?>(null) }
    if (statsBarModel != null) {
        statsBarSnapshot = statsBarModel
    }

    val isPlaylistSort = section == HomeSection.Playlist && activePlaylistId != null

    val miniPlayerStyle = uiSettings.miniPlayerStyle
    val currentSong = playerController.currentSong
    val listBottomPadding = if (currentSong != null) {
        miniPlayerListClearance(miniPlayerStyle)
    } else {
        0.dp
    }

    val motionEnabled = rememberMicaMotionEnabled()
    val drawerWidth = homeDrawerWidth()
    val drawerPushTween = MicaMotion.tweenDp(motionEnabled, MicaMotion.DurationMediumMs)
    val contentOffsetX by animateDpAsState(
        targetValue = if (drawerOpen) drawerWidth else 0.dp,
        animationSpec = drawerPushTween,
        label = "homeContentPush",
    )
    val drawerOffsetX by animateDpAsState(
        targetValue = if (drawerOpen) 0.dp else -drawerWidth,
        animationSpec = drawerPushTween,
        label = "homeDrawerSlide",
    )
    val drawerBottomInset = if (currentSong != null) {
        miniPlayerOverlayHeight(miniPlayerStyle)
    } else {
        0.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground(),
    ) {
        HomeDrawerPanel(
            selectedSection = section,
            activePlaylistId = activePlaylistId,
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
                showCreatePlaylistDialog = true
            },
            modifier = Modifier.offset(x = drawerOffsetX),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = contentOffsetX)
                .padding(top = statusBarTop),
        ) {
            HomeTopBar(
                title = topBarTitle,
                showBack = canNavigateBack,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                motionEnabled = motionEnabled,
                onLeadingClick = {
                    when {
                        canNavigateBack -> navigateBack()
                        drawerOpen -> drawerOpen = false
                        else -> drawerOpen = true
                    }
                },
                onSearchClick = {
                    searchOpen = true
                    drawerOpen = false
                },
            )

            Spacer(Modifier.height(HifiSpacing.xs))

            AnimatedVisibility(
                visible = statsBarModel != null,
                enter = fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                    expandVertically(MicaMotion.tweenIntSize(motionEnabled, MicaMotion.DurationShortMs)),
                exit = fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) +
                    shrinkVertically(MicaMotion.tweenIntSize(motionEnabled, MicaMotion.DurationShortMs)),
            ) {
                statsBarSnapshot?.let { model ->
                    Column {
                        LibraryStatsRow(
                            model = model,
                            onSortClick = { sortSheetOpen = true },
                            onRescan = onRequestRescan,
                            onDeletePlaylist = {
                                activePlaylistId?.let { pendingDeletePlaylistId = it }
                            },
                        )
                        Spacer(Modifier.height(HifiSpacing.md))
                    }
                }
            }

            if (sortSheetOpen) {
                SongSortSheet(
                    currentField = if (isPlaylistSort) {
                        activePlaylist?.sortField ?: library.sortField
                    } else {
                        library.sortField
                    },
                    currentDirection = if (isPlaylistSort) {
                        activePlaylist?.sortDirection ?: library.sortDirection
                    } else {
                        library.sortDirection
                    },
                    includeCustomSort = isPlaylistSort,
                    onDismiss = { sortSheetOpen = false },
                    onApply = { field, direction ->
                        if (isPlaylistSort && activePlaylistId != null) {
                            playlistStore.updateSort(activePlaylistId!!, field, direction)
                        } else if (field != SongSortField.CUSTOM) {
                            library.updateSort(field, direction)
                        }
                        sortSheetOpen = false
                    },
                )
            }

            val paneKey = resolveHomePaneKey(
                searchOpen = searchOpen,
                section = section,
                activePlaylistId = activePlaylistId,
                browseDestination = browseDestination,
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
                        query = searchQuery,
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        listBottomPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HomePaneKey.Songs -> LibraryContent(
                        library = library,
                        playerController = playerController,
                        shouldOpenSettings = shouldOpenSettings,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        onPickLibraryFolder = onPickLibraryFolder,
                        onRequestFullScan = onRequestFullScan,
                        onStartScan = onStartScan,
                        onRequestRescan = onRequestRescan,
                        onOpenSettings = { openAppSettings(context) },
                        listState = songListState,
                        listBottomPadding = listBottomPadding,
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
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = { openSongActionMenu(it, key.id) },
                        onMoveSong = { from, to ->
                            playlistStore.moveSongInPlaylist(key.id, from, to)
                        },
                        listBottomPadding = listBottomPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is HomePaneKey.Browse -> HomeBrowseContent(
                        section = key.section,
                        destination = key.destination,
                        onDestinationChange = { browseDestination = it },
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        listBottomPadding = listBottomPadding,
                        motionEnabled = motionEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
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
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                (currentSong ?: miniPlayerSongSnapshot)?.let { song ->
                    MiniPlayer(
                        style = miniPlayerStyle,
                        song = song,
                        isPlaying = playerController.playbackSurfaceState.isPlaying,
                        onPlayPause = { playerController.togglePlay() },
                        onNext = { playerController.next() },
                        onExpand = onMiniPlayerExpand,
                        onLongPress = ::locateCurrentSongInLibrary,
                        coverAlpha = miniPlayerCoverAlpha,
                        onCoverBoundsChanged = onMiniPlayerCoverBoundsChanged,
                        modifier = Modifier,
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

        actionMenuSong?.let { song ->
            SongActionMenuSheet(
                song = song,
                onDismiss = {
                    actionMenuSong = null
                    actionMenuPlaylistId = null
                },
                fromPlaylistId = actionMenuPlaylistId,
                onAction = { handleSongMenuAction(it, song) },
                onArtistClick = { artistName ->
                    section = HomeSection.Artists
                    browseDestination = BrowseDestination.Artist(artistName)
                    actionMenuSong = null
                    drawerOpen = false
                    searchOpen = false
                },
                onAlbumClick = { albumTitle ->
                    section = HomeSection.Albums
                    browseDestination = BrowseDestination.Album(albumTitle)
                    actionMenuSong = null
                    drawerOpen = false
                    searchOpen = false
                },
            )
        }

        addToPlaylistSong?.let { song ->
            AddToPlaylistSheet(
                song = song,
                playlistStore = playlistStore,
                onDismiss = { addToPlaylistSong = null },
                onCreated = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )
        }

        MicaTextInputDialog(
            visible = showCreatePlaylistDialog,
            title = "新建歌单",
            hint = "歌单名称",
            confirmLabel = "创建",
            onConfirm = { name ->
                showCreatePlaylistDialog = false
                runCatching { playlistStore.createPlaylist(name) }
                    .onSuccess { playlist ->
                        section = HomeSection.Playlist
                        activePlaylistId = playlist.id
                    }
                    .onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar(it.message ?: "创建失败")
                        }
                    }
            },
            onDismiss = { showCreatePlaylistDialog = false },
        )

        pendingDeleteSong?.let { song ->
            MicaConfirmDialog(
                visible = true,
                title = "删除音乐",
                message = "确定从设备删除「${song.title}」？此操作不可撤销。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    performDeleteSong(song)
                    pendingDeleteSong = null
                },
                onDismiss = { pendingDeleteSong = null },
            )
        }

        pendingDeletePlaylistId?.let { playlistId ->
            val playlistName = playlistStore.playlistById(playlistId)?.name ?: "歌单"
            MicaConfirmDialog(
                visible = true,
                title = "删除歌单",
                message = "确定删除歌单「$playlistName」？歌单内的歌曲不会被删除。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    performDeletePlaylist(playlistId)
                    pendingDeletePlaylistId = null
                },
                onDismiss = { pendingDeletePlaylistId = null },
            )
        }
    }
}

@Composable
private fun resolveTopBarTitle(
    appName: String,
    section: HomeSection,
    playlistName: String?,
    searchOpen: Boolean,
    browseDestination: BrowseDestination,
): String = when {
    searchOpen -> "搜索"
    browseDestination is BrowseDestination.Artist -> browseDestination.name
    browseDestination is BrowseDestination.Album -> browseDestination.title
    section == HomeSection.Playlist && playlistName != null -> playlistName
    else -> when (section) {
        HomeSection.Songs -> appName
        HomeSection.Artists -> "歌手"
        HomeSection.Albums -> "专辑"
        HomeSection.Recent -> "最近播放"
        HomeSection.LibraryAnalysis -> "音乐库分析"
        HomeSection.Settings -> "设置"
        HomeSection.Playlist -> "歌单"
    }
}

@Composable
private fun HomeTopBar(
    title: String,
    showBack: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    motionEnabled: Boolean,
    onLeadingClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HifiSize.topBarHeight)
            .padding(horizontal = HifiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = showBack,
            transitionSpec = {
                fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) togetherWith
                    fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs))
            },
            label = "topBarLeading",
        ) { back ->
            IconButton(
                onClick = onLeadingClick,
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = if (back) {
                        Icons.AutoMirrored.Outlined.ArrowBack
                    } else {
                        Icons.Outlined.Menu
                    },
                    contentDescription = if (back) "返回" else "菜单",
                    tint = MicaTheme.colors.textPrimary,
                    modifier = Modifier.size(HifiSize.iconLg),
                )
            }
        }

        AnimatedContent(
            targetState = searchOpen,
            modifier = Modifier.weight(1f),
            transitionSpec = MicaMotion.topBarSearchTransition(motionEnabled),
            label = "topBarSearch",
        ) { open ->
            if (open) {
                LaunchedEffect(Unit) {
                    if (motionEnabled) delay(MicaMotion.DurationShortMs.toLong())
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                }
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    placeholder = {
                        Text(
                            text = "搜索歌曲、艺术家、专辑",
                            style = MicaTheme.typography.bodyMd,
                            color = MicaTheme.colors.textTertiary,
                        )
                    },
                    textStyle = MicaTheme.typography.bodyMd,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions.Default,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "清除",
                                    tint = MicaTheme.colors.textSecondary,
                                )
                            }
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MicaTheme.typography.display,
                        color = MicaTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = HifiSpacing.xs),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.size(HifiSize.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索",
                            tint = MicaTheme.colors.textPrimary,
                            modifier = Modifier.size(HifiSize.iconLg),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryContent(
    library: MusicLibrary,
    playerController: PlayerController,
    shouldOpenSettings: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onPickLibraryFolder: () -> Unit,
    onRequestFullScan: () -> Unit,
    onStartScan: () -> Unit,
    onRequestRescan: () -> Unit,
    onOpenSettings: () -> Unit,
    listState: LazyListState,
    listBottomPadding: Dp,
) {
    val folderLabel = library.libraryFolderLabel
    when {
        shouldOpenSettings && !library.permissionGranted && !library.hasLibraryFolder() -> {
            EmptyStatePresets.PermissionDeniedOpenSettings(onOpenSettings = onOpenSettings)
        }
        library.isScanning && library.songs.isEmpty() -> {
            EmptyStatePresets.Scanning(progressLabel = library.scanProgressLabel)
        }
        !library.hasScanned && !library.permissionGranted && !library.hasLibraryFolder() -> {
            EmptyStatePresets.InitialLibrarySetup(
                onPickFolderClick = onPickLibraryFolder,
                onScanAllClick = onRequestFullScan,
            )
        }
        !library.hasScanned -> {
            EmptyStatePresets.ReadyToScan(
                folderLabel = folderLabel,
                onScanClick = onStartScan,
            )
        }
        library.hasScanned && library.songs.isEmpty() -> {
            EmptyStatePresets.NoMusicFound(
                folderLabel = folderLabel,
                onRescanClick = onRequestRescan,
                onPickFolderClick = onPickLibraryFolder,
            )
        }
        else -> {
            SongListPanel(
                songs = library.songs,
                library = library,
                playerController = playerController,
                onSongClick = { songId ->
                    playerController.setQueue(library.songs)
                    onSongClick(songId)
                },
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "暂无歌曲",
                listState = listState,
                listBottomPadding = listBottomPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LibraryStatsRow(
    model: LibraryStatsBarModel,
    onSortClick: () -> Unit,
    onRescan: () -> Unit,
    onDeletePlaylist: () -> Unit,
) {
    val lineText = model.segments.joinToString(" · ")

    val statsRowMinHeight = HifiSize.iconMd + HifiSpacing.xs * 2

    Column(modifier = Modifier.padding(horizontal = HifiSpacing.lg)) {
        Row(
            modifier = Modifier.heightIn(min = statsRowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lineText,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (model.showSortAction) {
                Icon(
                    imageVector = Icons.Outlined.Sort,
                    contentDescription = "排序",
                    tint = MicaTheme.colors.textTertiary,
                    modifier = Modifier
                        .clickable(onClick = onSortClick)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
            if (model.showRescanAction) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "扫描",
                    tint = MicaTheme.colors.textTertiary,
                    modifier = Modifier
                        .clickable(enabled = !model.isScanning, onClick = onRescan)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
            if (model.showDeletePlaylistAction) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除歌单",
                    tint = HifiPalette.LikeRed.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clickable(onClick = onDeletePlaylist)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
        }
        if (!model.scanError.isNullOrBlank()) {
            Spacer(Modifier.height(HifiSpacing.xs))
            Text(
                text = "扫描失败：${model.scanError}",
                style = MicaTheme.typography.caption,
                color = HifiPalette.LikeRed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
