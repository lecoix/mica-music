package com.mica.music.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.mica.music.R
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.HomeDrawerOverlay
import com.mica.music.ui.components.LibrarySearchPanel
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.MicaTextInputDialog
import com.mica.music.ui.components.MiniPlayer
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
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.micaBackground
import com.mica.music.util.openAppSettings
import kotlinx.coroutines.launch

enum class HomeSection {
    Songs,
    Artists,
    Albums,
    Recent,
    Favorites,
    Playlist,
    LibraryAnalysis,
    Settings,
}

@Composable
fun HomeScreen(
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onMiniPlayerExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSongDetail: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(HomeSection.Songs) }
    var activePlaylistId by remember { mutableStateOf<String?>(null) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var browseDestination by remember { mutableStateOf<BrowseDestination>(BrowseDestination.Root) }
    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var actionMenuPlaylistId by remember { mutableStateOf<String?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeletePlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    LaunchedEffect(section) {
        browseDestination = BrowseDestination.Root
    }

    val context = LocalContext.current
    val playlistStore = remember { PlaylistStore(context) }
    val activity = context as ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val appName = stringResource(R.string.app_name)

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

    val statusBarTop = homeStatusBarTopPadding()

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
            scope.launch { library.scanDeviceWide() }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        library.setLibraryFolder(uri)
        scope.launch { library.scanLibraryFolder() }
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
            scope.launch { library.scanDeviceWide() }
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    val onStartScan: () -> Unit = {
        scope.launch {
            when {
                library.hasLibraryFolder() -> library.scanLibraryFolder()
                library.hasAudioReadPermission() -> library.scanDeviceWide()
                else -> permissionLauncher.launch(audioPermission)
            }
        }
    }

    val onRequestRescan: () -> Unit = {
        scope.launch { library.rescan() }
    }

    fun onDrawerPick(target: HomeSection) {
        drawerOpen = false
        when (target) {
            HomeSection.Settings -> onOpenSettings()
            HomeSection.Playlist -> Unit
            else -> {
                section = target
                activePlaylistId = null
            }
        }
    }

    fun onDrawerPlaylistPick(playlistId: String) {
        drawerOpen = false
        section = HomeSection.Playlist
        activePlaylistId = playlistId
        searchOpen = false
    }

    LaunchedEffect(playlistStore.playlists, activePlaylistId) {
        if (section == HomeSection.Playlist && activePlaylistId != null) {
            if (playlistStore.playlists.none { it.id == activePlaylistId }) {
                section = HomeSection.Songs
                activePlaylistId = null
            }
        }
    }

    val canNavigateBack = searchOpen || browseDestination != BrowseDestination.Root

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }
    BackHandler(enabled = canNavigateBack && !drawerOpen) {
        when {
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }
            browseDestination != BrowseDestination.Root -> {
                browseDestination = BrowseDestination.Root
            }
        }
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

    val isPlaylistSort = section == HomeSection.Playlist && activePlaylistId != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .micaBackground(MicaPreset.Dawn)
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarTop),
        ) {
            HomeTopBar(
                title = topBarTitle,
                showBack = canNavigateBack,
                onLeadingClick = {
                    when {
                        searchOpen -> {
                            searchOpen = false
                            searchQuery = ""
                        }
                        browseDestination != BrowseDestination.Root -> {
                            browseDestination = BrowseDestination.Root
                        }
                        else -> drawerOpen = true
                    }
                },
                onSearchClick = {
                    searchOpen = true
                    drawerOpen = false
                },
            )

            Spacer(Modifier.height(HifiSpacing.xs))

            if (statsBarModel != null) {
                LibraryStatsRow(
                    model = statsBarModel,
                    onSortClick = { sortSheetOpen = true },
                    onRescan = onRequestRescan,
                    onDeletePlaylist = {
                        activePlaylistId?.let { pendingDeletePlaylistId = it }
                    },
                )
                Spacer(Modifier.height(HifiSpacing.md))
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

            Box(modifier = Modifier.weight(1f)) {
                when {
                    searchOpen -> LibrarySearchPanel(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        modifier = Modifier.fillMaxSize(),
                    )
                    section == HomeSection.Songs -> LibraryContent(
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
                    )
                    section == HomeSection.LibraryAnalysis -> LibraryAnalysisContent(
                        library = library,
                        modifier = Modifier.fillMaxSize(),
                    )
                    section == HomeSection.Playlist && activePlaylistId != null -> {
                        val playlistId = activePlaylistId!!
                        HomePlaylistContent(
                            playlistId = playlistId,
                            playlistStore = playlistStore,
                            library = library,
                            playerController = playerController,
                            onSongClick = onSongClick,
                            onSongOpenMenu = { openSongActionMenu(it, playlistId) },
                            onMoveSong = { from, to ->
                                playlistStore.moveSongInPlaylist(playlistId, from, to)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    section == HomeSection.Settings -> Unit
                    else -> HomeBrowseContent(
                        section = section,
                        destination = browseDestination,
                        onDestinationChange = { browseDestination = it },
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = ::openSongActionMenu,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            val current = playerController.currentSong
            if (current != null) {
                MiniPlayer(
                    song = current,
                    isPlaying = playerController.isPlaying,
                    onPlayPause = { playerController.togglePlay() },
                    onNext = { playerController.next() },
                    onExpand = onMiniPlayerExpand,
                )
            }
        }

        HomeDrawerOverlay(
            open = drawerOpen,
            selectedSection = section,
            activePlaylistId = activePlaylistId,
            playlists = playlistStore.playlists,
            statusBarTop = statusBarTop,
            onSectionSelected = ::onDrawerPick,
            onPlaylistSelected = ::onDrawerPlaylistPick,
            onCreatePlaylist = {
                drawerOpen = false
                showCreatePlaylistDialog = true
            },
            onDismiss = { drawerOpen = false },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + HifiSpacing.md),
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
        HomeSection.Favorites -> "我喜欢"
        HomeSection.LibraryAnalysis -> "音乐库分析"
        HomeSection.Settings -> "设置"
        HomeSection.Playlist -> "歌单"
    }
}

@Composable
private fun HomeTopBar(
    title: String,
    showBack: Boolean,
    onLeadingClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HifiSize.topBarHeight)
            .padding(horizontal = HifiSpacing.sm),
    ) {
        IconButton(
            onClick = onLeadingClick,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(HifiSize.touchTarget),
        ) {
            Icon(
                imageVector = if (showBack) {
                    Icons.AutoMirrored.Outlined.ArrowBack
                } else {
                    Icons.Outlined.Menu
                },
                contentDescription = if (showBack) "返回" else "菜单",
                tint = MicaTheme.colors.textPrimary,
                modifier = Modifier.size(HifiSize.iconLg),
            )
        }
        Text(
            text = title,
            style = MicaTheme.typography.display,
            color = MicaTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = HifiSize.touchTarget),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = onSearchClick,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(HifiSize.touchTarget),
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
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "暂无歌曲",
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

    Column(modifier = Modifier.padding(horizontal = HifiSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
