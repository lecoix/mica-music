package com.mica.music.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.R
import com.mica.music.data.PlaylistStore
import com.mica.music.data.preferences.OnlineMusicPreferences
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.SongQualityBadge
import com.mica.music.data.Song
import com.mica.music.data.lx.*
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnlineMusicPane(
    owner: LxSourceOwner,
    playlistStore: PlaylistStore,
    onPlay: (Song) -> Unit,
    onNext: (Song) -> Unit,
    onAppend: (Song) -> Unit,
    onSettings: () -> Unit,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val source by owner.state.collectAsState()
    val search by owner.onlineSearch.collectAsState()
    val scope = rememberCoroutineScope()
    val qqApi = remember { QqOnlineSearch() }
    val kwApi = remember { KwOnlineSearch() }
    val wyApi = remember { WyOnlineSearch() }
    val kgApi = remember { KgOnlineSearch() }
    var platform by remember { mutableStateOf("tx") }
    var platformMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Pair<LxOnlineResult, String>?>(null) }
    var quality by remember { mutableStateOf<String?>(null) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun platformLabel(id: String) = id.uppercase()

    LaunchedEffect(owner) { runCatching { owner.restore() } }
    DisposableEffect(owner) { onDispose { owner.leaveOnline() } }

    fun submit(page: Int, append: Boolean = false) {
        val requestQuery = if (append) search.query else query.trim()
        val requestPlatform = if (append) search.platform else platform
        if (requestQuery.isBlank()) return
        if (append) {
            if (search.busy || !search.hasMore || search.error != null || page != search.page + 1) return
        } else {
            job?.cancel()
        }
        val fetch: suspend (String, Int) -> List<LxOnlineResult> = when (requestPlatform) {
            "tx" -> qqApi::search
            "kw" -> kwApi::search
            "wy" -> wyApi::search
            "kg" -> kgApi::search
            else -> error("Unsupported online platform: $requestPlatform")
        }
        job = scope.launch {
            runCatching { owner.searchOnline(requestPlatform, requestQuery, page, append = append, fetch = fetch) }
        }
    }

    fun availableQualities(row: LxOnlineResult): List<String> =
        row.qualities.filter { it in source.platforms[row.platform].orEmpty() }

    fun openActionDialog(row: LxOnlineResult, action: String, preferredQuality: String? = null) {
        selection = row to action
        quality = preferredQuality
        playlistId = null
        message = null
    }

    fun executeAction(row: LxOnlineResult, action: String, selectedQuality: String, targetPlaylistId: String? = null) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { owner.admitOnline(row, selectedQuality) }
                var committedPlaylistSongId: String? = null
                withContext(Dispatchers.Main.immediate) {
                    owner.useOnline(row, selectedQuality) { track ->
                        val song = track.toPlaybackSong()
                        when (action) {
                            "播放" -> onPlay(song)
                            "下一首播放" -> onNext(song)
                            "加入队列" -> onAppend(song)
                            else -> committedPlaylistSongId = song.id
                        }
                    }
                }
                // Admission has committed a durable identity; PlaylistStore owns the subsequent
                // suspendable Room mutation and revision-checked publication. No owner lock spans IO.
                committedPlaylistSongId?.let { songId ->
                    check(playlistStore.addSongToPlaylist(requireNotNull(targetPlaylistId), songId))
                }
                message = if (action == "播放") "已请求 $selectedQuality" else "已${action}"
                selection = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.important(
                    "LXOnline",
                    "action-failed action=$action id=${row.id} quality=$selectedQuality",
                    e,
                )
                message = "操作未完成；请重新搜索并检查音源或存储状态"
                selection = null
            } finally {
                saving = false
            }
        }
    }

    fun beginAction(row: LxOnlineResult, action: String) {
        val available = availableQualities(row)
        val preferred = OnlineMusicPreferences.resolveDefaultQuality(
            OnlineMusicPreferences.defaultQuality(context),
            available,
        )
        when {
            action == "加入歌单" -> openActionDialog(row, action, preferred)
            preferred != null -> executeAction(row, action, preferred)
            else -> openActionDialog(row, action)
        }
    }

    val shouldLoadMore by remember(
        search.results.size,
        search.hasMore,
        search.busy,
        search.error,
    ) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            search.results.isNotEmpty() &&
                search.hasMore &&
                !search.busy &&
                search.error == null &&
                lastVisible >= search.results.lastIndex - 2
        }
    }
    LaunchedEffect(shouldLoadMore, search.page, search.query, search.platform) {
        if (shouldLoadMore) submit(search.page + 1, append = true)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val platformReady = !source.platforms[platform].isNullOrEmpty()
        val canSubmit = platformReady && query.isNotBlank() && !search.busy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .fillMaxHeight()
                    .clickable { platformMenu = true }
                    .testTag("online-platform-selector"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${platformLabel(platform)}  ▾",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textPrimary,
                )
                DropdownMenu(
                    expanded = platformMenu,
                    onDismissRequest = { platformMenu = false },
                    shape = RectangleShape,
                ) {
                    listOf("tx", "kw", "wy", "kg").forEach { id ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (id == platform) "— " else "") + platformLabel(id),
                                    color = if (id == platform) MicaTheme.colors.textPrimary else MicaTheme.colors.textSecondary,
                                )
                            },
                            onClick = {
                                platformMenu = false
                                if (platform != id) {
                                    job?.cancel()
                                    platform = id
                                    owner.leaveOnline()
                                    message = null
                                }
                            },
                            modifier = Modifier.testTag("online-platform-$id"),
                        )
                    }
                }
            }
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it.take(100)
                    job?.cancel()
                    owner.leaveOnline()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("online-query")
                    .padding(horizontal = 14.dp),
                enabled = platformReady,
                singleLine = true,
                textStyle = MicaTheme.typography.bodyMd.copy(color = MicaTheme.colors.textPrimary),
                cursorBrush = SolidColor(MicaTheme.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(1) }),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索歌曲、歌手",
                                style = MicaTheme.typography.bodyMd,
                                color = MicaTheme.colors.textTertiary,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .fillMaxHeight()
                    .clickable(enabled = canSubmit) { submit(1) }
                    .testTag("online-search-submit"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "搜索",
                    style = MicaTheme.typography.bodyMd,
                    color = if (canSubmit) MicaTheme.colors.textPrimary else MicaTheme.colors.textTertiary,
                )
            }
        }

        if (!platformReady) {
            val label = platformLabel(platform)
            Text(
                if (source.busy) "正在初始化音源…" else "当前音源不支持 $label；请导入并启用对应平台后再搜索。",
                color = MicaTheme.colors.textSecondary,
            )
            TextButton(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RectangleShape,
            ) { Text("前往设置 · 音源管理") }
        } else {
            if (search.busy && search.results.isEmpty()) Text("正在搜索…", color = MicaTheme.colors.textSecondary)
            else if (search.error != null) Text(search.error!!, color = MicaTheme.colors.textSecondary)
            else if (search.query.isNotEmpty() && search.results.isEmpty()) {
                Text("未找到歌曲", color = MicaTheme.colors.textSecondary)
            }
            LazyColumn(Modifier.weight(1f).testTag("online-results-list"), state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(search.results, key = { it.id }) { row ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(HifiSize.listRowHeight)
                                .testTag("online-song-${row.id}")
                                .combinedClickable(
                                    onClick = { beginAction(row, "播放") },
                                    onLongClick = { menu = true },
                                )
                                .padding(end = HifiSpacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(HifiSpacing.md + HifiSize.accentBarWidth))
                            val previewArtwork = remember(row.platform, row.musicInfo) {
                                lxOnlineArtworkUrl(row.platform, row.musicInfo)
                            }
                            SongCover(
                                albumArtUri = previewArtwork,
                                fallbackColor = MicaTheme.colors.surfaceCard,
                                contentDescription = row.title,
                                noCoverPlaceholderResId = R.drawable.no_cover_placeholder_small,
                                decodeTarget = CoverDecodeTarget.forCompactCover(),
                                modifier = Modifier.size(HifiSize.coverSm).testTag("online-cover-${row.id}"),
                            )
                            Spacer(Modifier.width(HifiSpacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MicaTheme.typography.bodyLg,
                                    color = MicaTheme.colors.textPrimary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val qualityBadge = when {
                                        "flac24bit" in row.qualities -> "HR"
                                        "flac" in row.qualities -> "SQ"
                                        "320k" in row.qualities -> "HQ"
                                        else -> null
                                    }
                                    if (qualityBadge != null) {
                                        SongQualityBadge(label = qualityBadge)
                                        Spacer(Modifier.width(HifiSpacing.xs))
                                    }
                                    Text(
                                        listOf(row.artist, row.album)
                                            .filter { it.isNotBlank() && it != "—" }
                                            .joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MicaTheme.typography.bodySm,
                                        color = MicaTheme.colors.textSecondary,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                            Spacer(Modifier.width(HifiSpacing.xs))
                            val duration = if (row.durationSec > 0) {
                                "%d:%02d".format(row.durationSec / 60, row.durationSec % 60)
                            } else {
                                "—"
                            }
                            Text(
                                duration,
                                style = MicaTheme.typography.monoSm,
                                color = MicaTheme.colors.textTertiary,
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(menu, { menu = false }, shape = RectangleShape) {
                            listOf("下一首播放", "加入队列", "加入歌单").forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action) },
                                    onClick = { menu = false; beginAction(row, action) },
                                )
                            }
                        }
                    }
                }
                if (search.results.isNotEmpty() && search.busy) {
                    item(key = "online-loading-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = HifiSpacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("加载中…", style = MicaTheme.typography.bodySm, color = MicaTheme.colors.textTertiary)
                        }
                    }
                } else if (search.results.isNotEmpty() && search.query.isNotEmpty() && !search.hasMore && search.error == null) {
                    item(key = "online-no-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = HifiSpacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("没有更多", style = MicaTheme.typography.bodySm, color = MicaTheme.colors.textTertiary)
                        }
                    }
                }
            }
        }
        message?.let { Text(it, color = MicaTheme.colors.textSecondary) }
    }

    selection?.let { (row, action) ->
        val available = availableQualities(row)
        val defaultPreference = OnlineMusicPreferences.defaultQuality(context)
        val resolvedDefault = OnlineMusicPreferences.resolveDefaultQuality(defaultPreference, available)
        val askQuality = defaultPreference.value == 0 || resolvedDefault == null
        AlertDialog(
            onDismissRequest = { if (!saving) selection = null },
            shape = RectangleShape,
            title = { Text(row.title) },
            text = {
                LazyColumn(modifier = Modifier.testTag("online-action-dialog-list")) {
                    if (askQuality) {
                        item { Text("选择播放品质") }
                        items(available) { q ->
                            TextButton(
                                onClick = { quality = q },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-quality-$q"),
                                shape = RectangleShape,
                            ) { Text((if (quality == q) "✓  " else "") + q) }
                        }
                    } else {
                        item { Text("播放品质 · ${quality ?: resolvedDefault}") }
                    }
                    if (available.isEmpty()) item { Text("当前音源没有可用于这首歌的品质") }
                    if (action == "加入歌单") {
                        item { Text(if (playlistStore.playlists.isEmpty()) "请先创建歌单" else "选择歌单") }
                        items(playlistStore.playlists, key = { it.id }) { playlist ->
                            TextButton(
                                onClick = { playlistId = playlist.id },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-playlist-${playlist.id}"),
                                shape = RectangleShape,
                            ) { Text((if (playlistId == playlist.id) "✓  " else "") + playlist.name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("online-confirm"),
                    enabled = !saving && quality in available && (action != "加入歌单" || playlistId != null),
                    onClick = {
                        val q = quality ?: resolvedDefault ?: return@TextButton
                        executeAction(row, action, q, playlistId)
                    },
                    shape = RectangleShape,
                ) { Text(if (saving) "正在保存…" else action) }
            },
            dismissButton = {
                TextButton(
                    onClick = { selection = null },
                    enabled = !saving,
                    shape = RectangleShape,
                ) { Text("取消") }
            },
        )
    }
}