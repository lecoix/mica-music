package com.mica.music.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.MicaApp
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.data.remote.smb.*
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Folder rows never mount artwork/lyrics loaders. Only explicit selection reaches persistence. */
@Composable
internal fun SmbBrowserPane(
    onPlay: (List<Song>, String) -> Unit,
    bottomPadding: Dp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSourceId: String? = null,
    playerOverlayOpen: Boolean = false,
    backRequestKey: Int = 0,
    refreshRequestKey: Int = 0,
    onInfoActionsChange: (SmbBrowserInfoActions) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MicaApp
    val browser = remember(app) { SmbDirectoryBrowser(app.remoteCatalogRepository, app.remoteCredentialStore) }
    SmbBrowserContent(
        repo = app.remoteCatalogRepository,
        playlists = app.playlistStore,
        browser = browser,
        onPlay = onPlay,
        bottomPadding = bottomPadding,
        onBack = onBack,
        modifier = modifier,
        initialSourceId = initialSourceId,
        playerOverlayOpen = playerOverlayOpen,
        folderLoader = app.smbFolderLibraryLoader,
        backRequestKey = backRequestKey,
        refreshRequestKey = refreshRequestKey,
        onInfoActionsChange = onInfoActionsChange,
    )
}

internal data class SmbBrowserInfoActions(
    val backLabel: String = "返回远程歌曲",
    val showRefresh: Boolean = false,
    val refreshEnabled: Boolean = false,
)

@Composable
internal fun SmbBrowserContent(
    repo: com.mica.music.data.remote.RemoteCatalogRepository,
    playlists: com.mica.music.data.PlaylistStore,
    browser: SmbDirectoryBrowser,
    onPlay: (List<Song>, String) -> Unit,
    bottomPadding: Dp,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSourceId: String? = null,
    playerOverlayOpen: Boolean = false,
    folderIndexer: SmbFolderLibraryIndexer? = null,
    folderLoader: SmbFolderLibraryLoader? = null,
    backRequestKey: Int = 0,
    refreshRequestKey: Int = 0,
    onInfoActionsChange: (SmbBrowserInfoActions) -> Unit = {},
) {
    val sources by remember(repo) { repo.observeSources() }.collectAsState(emptyList())
    var sourceId by rememberSaveable { mutableStateOf(initialSourceId) }
    var path by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember(sourceId, path) { mutableStateOf(emptySet<String>()) }
    var choosing by remember { mutableStateOf(false) }
    var selectionRevision by remember { mutableIntStateOf(0) }
    var actionBusy by remember { mutableStateOf(false) }
    val loading by (folderLoader?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(SmbFolderLoadingState()) }).collectAsState()
    val busy = actionBusy || (loading.active && !loading.entriesReady)
    var pendingImport by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(loading.requestId, loading.entriesReady, pendingImport) {
        if (pendingImport == loading.requestId && loading.entriesReady) {
            pendingImport = null
            onBack()
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var folderScopes by remember { mutableStateOf(emptyList<SmbFolderScope>()) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var choosingLibraryMode by remember { mutableStateOf(false) }
    var includeSubdirectories by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val owner = remember(browser, sourceId) {
        SmbBrowseOwner(publish = { snapshot, action -> repo.publishIfCurrent(snapshot.token, action) }, fetch = browser::list)
    }
    val state by owner.state.collectAsState()
    val snapshot = state.snapshot
    val source = sources.firstOrNull { it.id == sourceId && it.enabled }
    val currentFolderScope = folderScopes.firstOrNull { it.relativeDirectory == path }
    val scrollPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }
    val scrollKey = "$sourceId/$path"
    val initialScroll = scrollPositions[scrollKey]
    val listState = key(scrollKey) { rememberLazyListState(initialScroll?.first ?: 0, initialScroll?.second ?: 0) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    DisposableEffect(scrollKey) { onDispose {
        if (scrollPositions.size >= 32) scrollPositions.remove(scrollPositions.keys.first())
        scrollPositions[scrollKey] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    } }
    LaunchedEffect(sourceId, libraryRevision) {
        folderScopes = sourceId?.let { repo.smbFolderScopes(it) }.orEmpty()
    }
    LaunchedEffect(source, path, refresh) {
        selected = emptySet()
        choosing = false
        choosingLibraryMode = false
        if (source != null) owner.load(source.id, path)
    }
    fun back() {
        choosing = false
        choosingLibraryMode = false
        when {
            path.isNotEmpty() -> path = path.substringBeforeLast('/', "")
            sourceId != null -> sourceId = null
            else -> onBack()
        }
    }
    LaunchedEffect(sourceId, path, state.loading, busy) {
        onInfoActionsChange(
            SmbBrowserInfoActions(
                backLabel = if (sourceId == null) "返回远程歌曲" else "返回上级",
                showRefresh = source != null,
                refreshEnabled = source != null && !state.loading && !busy,
            ),
        )
    }
    LaunchedEffect(backRequestKey) {
        if (backRequestKey > 0 && !busy) back()
    }
    LaunchedEffect(refreshRequestKey) {
        if (refreshRequestKey > 0 && source != null && !state.loading && !busy) refresh++
    }
    BackHandler(enabled = !playerOverlayOpen && !busy) { back() }

    fun play(all: Boolean, targetPath: String? = null) {
        val captured = snapshot ?: return
        if (!owner.isCurrent(captured) || busy) return
        val tracks = captured.entries.mapNotNull { it.track }.filter { all || it.ref.opaqueTrackId == targetPath }
        if (tracks.isEmpty()) return
        actionBusy = true
        scope.launch {
            try {
                var played = false
                if (repo.registerSelectedTracks(captured.token, tracks)) {
                    val songs = tracks.map { it.toPlaybackSong() }
                    repo.publishIfCurrent(captured.token) {
                        if (owner.isCurrent(captured)) {
                            onPlay(songs, songs.first().id)
                            played = true
                        }
                    }
                }
                if (!played) message = "目录或连接已改变，请重试"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = "保存播放记录失败，请重试"
            } finally { actionBusy = false }
        }
    }

    fun indexCurrentFolder(includeChildren: Boolean) {
        val activeSource = source ?: return
        if (busy || state.loading) return
        if (folderLoader != null) {
            val directory = path
            actionBusy = true
            scope.launch {
                try {
                    pendingImport = folderLoader.start(activeSource.id, directory, includeChildren)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message = "目录建库失败，请检查连接后重试"
                } finally {
                    actionBusy = false
                    choosingLibraryMode = false
                }
            }
            return
        }
        val indexer = folderIndexer ?: return
        val refreshing = currentFolderScope?.includeSubdirectories == includeChildren
        actionBusy = true
        scope.launch {
            try {
                val result = indexer.index(
                    sourceId = activeSource.id,
                    directory = path,
                    includeSubdirectories = includeChildren,
                )
                message = when {
                    result.published && refreshing -> "曲库已刷新，共 ${result.trackCount} 首"
                    result.published -> "已加入曲库，共 ${result.trackCount} 首"
                    !result.complete -> "目录读取不完整，未修改曲库"
                    else -> "连接已改变，未修改曲库"
                }
                if (result.published) libraryRevision++
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = "目录建库失败，请检查连接后重试"
            } finally {
                actionBusy = false
                choosingLibraryMode = false
            }
        }
    }

    fun removeCurrentFolderScope() {
        val activeSource = source ?: return
        val managed = currentFolderScope ?: return
        if (busy) return
        actionBusy = true
        scope.launch {
            try {
                val removed = repo.removeSmbFolderScope(activeSource.id, managed.id)
                message = if (removed) "已从曲库移除此目录" else "目录建库状态已改变，请刷新"
                if (removed) libraryRevision++
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = "移除失败，请重试"
            } finally {
                actionBusy = false
            }
        }
    }

    suspend fun addToPlaylist(id: String, revision: Int) {
        val captured = snapshot ?: return
        if (!owner.isCurrent(captured)) return
        val tracks = captured.entries.mapNotNull { it.track }.filter { it.ref.opaqueTrackId in selected }
        if (tracks.isEmpty()) return
        actionBusy = true
        try {
            val success = playlists.addRemoteSongsToPlaylist(id, tracks, captured.token, repo, revision)
            message = if (success) "已添加 ${tracks.size} 首歌曲" else "歌单或连接已改变，请重新选择"
            choosing = false
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            message = "添加失败，原歌单已保留"
        } finally { actionBusy = false }
    }

    Column(modifier.fillMaxSize()) {
        if (folderLoader != null) SmbFolderLoadingStatus(folderLoader)
        if (sourceId == null) {
            Text("SMB 连接", style = MicaTheme.typography.titleMd, modifier = Modifier.padding(16.dp))
            Text("打开哪一层，就读取哪一层。已有歌曲信息保留；SMB 不再后台扫描整库。",
                style = MicaTheme.typography.bodySm, modifier = Modifier.padding(horizontal = 16.dp))
            val smbSources = sources.filter { it.type == RemoteSourceType.SMB }
            if (smbSources.isEmpty()) Text("请在设置 → 曲库 → 远程曲库中添加 SMB 连接", modifier = Modifier.padding(16.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(smbSources, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = item.enabled) { sourceId = item.id; path = "" }
                        .heightIn(min = 64.dp).padding(16.dp)) {
                        Text(item.displayName, style = MicaTheme.typography.bodyLg)
                        Text(if (item.enabled) "浏览文件夹" else "已停用", style = MicaTheme.typography.bodySm)
                    }
                }
            }
        } else if (source == null) {
            Text("连接已停用或删除，请返回选择连接", modifier = Modifier.padding(16.dp))
        } else {
            Text("${source.displayName} / $path", style = MicaTheme.typography.bodyLg,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
            if (state.loading) Text("正在读取当前目录…", modifier = Modifier.padding(horizontal = 16.dp))
            state.error?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (snapshot != null && !snapshot.complete) Text("目录过大，仅显示部分内容；请进入子目录或选择具体歌曲。",
                modifier = Modifier.padding(horizontal = 16.dp))
            Row {
                TextButton(onClick = { play(true) }, enabled = snapshot?.complete == true && !state.loading && !busy) { Text("播放本目录") }
                TextButton(onClick = { selected = snapshot?.entries?.mapNotNull { it.track?.ref?.opaqueTrackId }?.toSet().orEmpty() },
                    enabled = snapshot?.complete == true && !state.loading && !busy) { Text("全选本层") }
            }
            if (folderIndexer != null || folderLoader != null) {
                if (currentFolderScope == null) {
                    TextButton(
                        onClick = {
                            includeSubdirectories = false
                            choosingLibraryMode = true
                        },
                        enabled = !state.loading && !busy,
                    ) { Text("加入曲库") }
                } else {
                    Text(
                        text = if (currentFolderScope.includeSubdirectories) "已建库 · 包含子目录" else "已建库 · 仅本层",
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row {
                        TextButton(
                            onClick = { indexCurrentFolder(currentFolderScope.includeSubdirectories) },
                            enabled = !state.loading && !busy,
                        ) { Text("刷新曲库") }
                        TextButton(
                            onClick = ::removeCurrentFolderScope,
                            enabled = !busy,
                        ) { Text("移出曲库") }
                    }
                }
            }
            if (selected.isNotEmpty()) Row {
                TextButton(onClick = { selectionRevision = playlists.revision; choosing = true }, enabled = !busy && !state.loading) { Text("添加 ${selected.size} 首到歌单") }
                TextButton(onClick = { selected = emptySet() }, enabled = !busy) { Text("取消选择") }
            }
            message?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (snapshot?.entries?.isEmpty() == true && !state.loading && state.error == null) Text("此目录没有可显示的音乐或文件夹", modifier = Modifier.padding(16.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding), modifier = Modifier.weight(1f)) {
                items(snapshot?.entries.orEmpty(), key = { it.path }) { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !state.loading && !busy) {
                        if (entry.directory) path = entry.path else play(false, entry.path)
                    }.padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(entry.name, style = MicaTheme.typography.bodyLg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.directory) "文件夹" else "${entry.track?.suffix?.uppercase()} · —",
                                style = MicaTheme.typography.bodySm, color = MicaTheme.colors.textSecondary)
                        }
                        if (!entry.directory) Checkbox(checked = entry.path in selected, enabled = !state.loading && !busy,
                            modifier = Modifier.semantics { contentDescription = "选择 ${entry.name}" },
                            onCheckedChange = { checked -> selected = if (checked) selected + entry.path else selected - entry.path })
                    }
                }
            }
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { if (!busy) choosing = false }, shape = RectangleShape,
        title = { Text("添加到普通歌单") },
        text = {
            Column(Modifier.width(240.dp)) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(playlists.playlists, key = { it.id }) { playlist ->
                        TextButton(enabled = !busy, onClick = { scope.launch { addToPlaylist(playlist.id, selectionRevision) } }) { Text(playlist.name) }
                    }
                }
                Text("新歌单名称", style = MicaTheme.typography.bodySm)
                OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it },
                    modifier = Modifier.width(240.dp).semantics { contentDescription = "新歌单名称" },
                    enabled = !busy, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = !busy && newPlaylistName.isNotBlank(), onClick = {
            scope.launch {
                actionBusy = true
                try {
                    val playlist = playlists.createPlaylist(newPlaylistName)
                    addToPlaylist(playlist.id, playlists.revision)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    message = "创建歌单失败，请重试"
                } finally { actionBusy = false }
            }
        }) { Text("创建并添加") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { choosing = false }) { Text("取消") } },
    )
    if (choosingLibraryMode) AlertDialog(
        onDismissRequest = { if (!busy) choosingLibraryMode = false },
        shape = RectangleShape,
        title = { Text("加入当前目录到曲库") },
        text = {
            Column(Modifier.width(260.dp)) {
                Text(
                    "只处理你选择的目录，并读取其中歌曲的标签信息；开启“包含子目录”后才会继续进入下面的文件夹。不会扫描共享里的其他目录，也不会自动抓取歌词或在线封面；发现不完整时不会修改已有曲库。",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !busy) { includeSubdirectories = !includeSubdirectories },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeSubdirectories,
                        onCheckedChange = { includeSubdirectories = it },
                        enabled = !busy,
                    )
                    Text("包含子目录")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { indexCurrentFolder(includeSubdirectories) },
            ) { Text("加入曲库") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = { choosingLibraryMode = false },
            ) { Text("取消") }
        },
    )
}
