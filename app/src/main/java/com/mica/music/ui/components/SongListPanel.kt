package com.mica.music.ui.components

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.mica.music.data.ArtistNames
import com.mica.music.data.FastScrollIndex
import com.mica.music.data.LibraryFastScrollIndex
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.preferences.LibraryZoomPage
import com.mica.music.data.preferences.LibraryZoomPreferences
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import com.mica.music.ui.zoom.PinchZoomGridAnchor
import com.mica.music.ui.zoom.PinchZoomItemRect
import com.mica.music.ui.zoom.capturePinchZoomAnchor
import com.mica.music.ui.zoom.visiblePinchZoomItemRects
import com.mica.music.ui.zoom.calculatePinchZoomItemMorph
import com.mica.music.ui.zoom.pinchZoomItemBoundsMorph
import com.mica.music.ui.zoom.pinchZoomGesture
import com.mica.music.ui.zoom.rememberPinchZoomState
import com.mica.music.ui.zoom.restorePinchZoomAnchor
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * Main song browser with Poweramp-style ordered pinch zoom.
 *
 * Four stable visual presets are kept deliberately fewer than Poweramp's ten. The preset identity
 * is persisted independently from the ordered gesture axis. During a pinch the two adjacent
 * LazyGrid layout worlds coexist, share stable song keys, and are kept aligned around the visible
 * center song before the transition is shown.
 */
@Composable
fun SongListPanel(
    songs: List<Song>,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    emptyMessage: String,
    listState: LazyListState? = null,
    fastScrollSortField: SongSortField? = library.sortField,
    fastScrollSortDirection: SortDirection = library.sortDirection,
    fastScrollLabels: List<String>? = null,
    fastScrollSectionTargets: Map<String, Int>? = null,
    listBottomPadding: Dp = 0.dp,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    infoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    zoomPage: LibraryZoomPage = LibraryZoomPage.SONGS,
    zoomEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val context = LocalContext.current
    val motionEnabled = rememberMicaMotionEnabled()
    val externalListState = listState ?: rememberLazyListState()
    val fallbackFastScrollIndex = rememberSongListFastScrollIndex(
        songs = songs,
        field = fastScrollSortField.takeIf { fastScrollLabels == null },
    )
    val resolvedFastScrollLabels = fastScrollLabels ?: fallbackFastScrollIndex?.labels
    val resolvedFastScrollSectionTargets =
        fastScrollSectionTargets ?: fallbackFastScrollIndex?.sectionTargets

    if (songs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    val validPresetIds = remember { SongZoomOrder.mapTo(linkedSetOf()) { it.id } }
    val initialPresetId = remember(context, zoomPage) {
        LibraryZoomPreferences.presetId(
            context = context,
            page = zoomPage,
            defaultId = SongZoomPreset.NORMAL_LIST.id,
            validIds = validPresetIds,
        )
    }
    val initialIndex = SongZoomOrder.indexOfFirst { it.id == initialPresetId }.coerceAtLeast(0)
    val initialItemIndex = externalListState.firstVisibleItemIndex
    val initialItemOffset = externalListState.firstVisibleItemScrollOffset
    val states = List(SongZoomOrder.size) { index ->
        rememberLazyGridState(
            initialFirstVisibleItemIndex = if (index == initialIndex) initialItemIndex else 0,
            initialFirstVisibleItemScrollOffset = if (index == initialIndex) initialItemOffset else 0,
        )
    }
    val zoomState = rememberPinchZoomState(
        presetCount = SongZoomOrder.size,
        initialIndex = initialIndex,
        externalIndex = initialIndex,
        motionEnabled = motionEnabled,
        stateKey = zoomPage,
        onSettledIndexChanged = { index ->
            LibraryZoomPreferences.setPresetId(
                context = context,
                page = zoomPage,
                presetId = SongZoomOrder[index].id,
                validIds = validPresetIds,
            )
        },
    )
    val segment = zoomState.segment
    var gestureAnchor by remember(zoomPage) { mutableStateOf<PinchZoomGridAnchor?>(null) }
    var gestureAnchorSource by remember(zoomPage) { mutableStateOf(initialIndex) }
    var gestureAlignedPresetIndices by remember(zoomPage) { mutableStateOf(setOf(initialIndex)) }

    LaunchedEffect(segment.lowerIndex, segment.upperIndex, gestureAnchor, zoomState.gestureActive) {
        val anchor = gestureAnchor
        if (anchor != null) {
            setOf(segment.lowerIndex, segment.upperIndex).forEach { index ->
                if (index !in gestureAlignedPresetIndices) {
                    states[index].restorePinchZoomAnchor(anchor)
                    gestureAlignedPresetIndices = gestureAlignedPresetIndices + index
                }
            }
        }
        zoomState.markGestureGeometryReady()
    }

    // Keep callers that already own a LazyListState (Home navigation restoration) synchronized with
    // whichever preset became authoritative after settle.
    LaunchedEffect(zoomState.settledIndex, zoomPage) {
        val settled = zoomState.settledIndex
        // The target grid was already aligned when the gesture pair was introduced. Never restore
        // it again when the pair collapses at settle; that second scroll caused the visible jump.
        gestureAnchor = null
        gestureAnchorSource = settled
        gestureAlignedPresetIndices = setOf(settled)
        val state = states[settled]
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                externalListState.scrollToItem(index, offset)
            }
    }

    val zoomContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchZoomGesture(
                    state = zoomState,
                    enabled = zoomEnabled,
                    onGestureStart = { _ ->
                        val source = zoomState.settledIndex
                        gestureAnchorSource = source
                        gestureAlignedPresetIndices = setOf(source)
                        gestureAnchor = states[source].capturePinchZoomAnchor()
                    },
                ),
        ) {
            val progress = segment.progress
            val transitionActive = segment.lowerIndex != segment.upperIndex
            val dominant = zoomState.dominantIndex

            val lowerPreset = SongZoomOrder[segment.lowerIndex]
            val upperPreset = SongZoomOrder[segment.upperIndex]

            // Keep all preset layout worlds measured from the first frame. Previously only the
            // current world existed while settled, so the first pinch into each preset saw an empty
            // target visibleItemsInfo for one frame and the visible overlay disappeared. These are
            // lightweight geometry/interaction oracles: no cover decoding and no visible content.
            SongZoomOrder.forEachIndexed { index, preset ->
                SongZoomGeometryLayer(
                    songs = songs,
                    preset = preset,
                    landscape = landscape,
                    state = states[index],
                    onSongClick = onSongClick,
                    onSongOpenMenu = onSongOpenMenu,
                    listBottomPadding = listBottomPadding,
                    selectionMode = selectionMode,
                    selectedSongIds = selectedSongIds,
                    onSelectionToggle = onSelectionToggle,
                    interactive = index == dominant,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0f }
                        .zIndex(if (index == dominant) 1f else 0f),
                )
            }
            // Keep one visible stable-key scene renderer even when settled. The LazyGrids above are
            // permanently transparent geometry/interaction oracles, so landing on a preset never
            // swaps the visible SongCover/Text tree back to SongRow/SongZoomGridTile.
            SongZoomMorphOverlay(
                songs = songs,
                lowerPreset = lowerPreset,
                upperPreset = upperPreset,
                landscape = landscape,
                lowerState = states[segment.lowerIndex],
                upperState = states[segment.upperIndex],
                progress = progress,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                selectionMode = selectionMode,
                selectedSongIds = selectedSongIds,
                infoVisibility = infoVisibility,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .zIndex(2f),
            )
        }
    }

    if (resolvedFastScrollLabels == null) {
        Box(modifier = modifier.fillMaxSize()) { zoomContent() }
    } else {
        AlphabetFastScroller(
            labels = resolvedFastScrollLabels,
            sectionTargetsOverride = resolvedFastScrollSectionTargets,
            scrollToIndex = { states[zoomState.dominantIndex].scrollToItem(it) },
            descending = fastScrollSortDirection == SortDirection.DESC,
            fullHeightOverlay = SongZoomOrder[zoomState.dominantIndex].columns(landscape) > 1,
            modifier = modifier.fillMaxSize(),
        ) {
            zoomContent()
        }
    }
}

internal fun songListColumnsFor(widthDp: Int, heightDp: Int): Int =
    if (widthDp > heightDp) 2 else 1

@Composable
internal fun rememberSongListFastScrollIndex(
    songs: List<Song>,
    field: SongSortField?,
): FastScrollIndex? = remember(songs, field) {
    if (field == null) return@remember null
    val startedMs = SystemClock.elapsedRealtime()
    val index = LibraryFastScrollIndex.forSongs(songs, field)
    DiagnosticLog.event(
        "LibraryUi",
        "songList fastScrollIndex durMs=${SystemClock.elapsedRealtime() - startedMs} " +
            "songs=${songs.size} field=$field labels=${index?.labels?.size ?: 0} " +
            "sections=${index?.sectionTargets?.size ?: 0}",
    )
    index
}

private enum class SongZoomPreset(
    val id: String,
    val compact: Boolean,
    val showCover: Boolean,
    val gridTile: Boolean,
) {
    DENSE_LIST("dense_list", compact = true, showCover = false, gridTile = false),
    NORMAL_LIST("normal_list", compact = false, showCover = true, gridTile = false),
    DENSE_GRID("dense_grid", compact = false, showCover = true, gridTile = true),
    LARGE_GRID("large_grid", compact = false, showCover = true, gridTile = true),
    ;

    fun columns(landscape: Boolean): Int = when (this) {
        DENSE_LIST, NORMAL_LIST -> if (landscape) 2 else 1
        DENSE_GRID -> if (landscape) 4 else 3
        LARGE_GRID -> if (landscape) 3 else 2
    }
}

/** Ordered gesture axis. IDs above remain stable if this order changes later. */
private val SongZoomOrder = listOf(
    SongZoomPreset.DENSE_LIST,
    SongZoomPreset.NORMAL_LIST,
    SongZoomPreset.DENSE_GRID,
    SongZoomPreset.LARGE_GRID,
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongZoomGeometryLayer(
    songs: List<Song>,
    preset: SongZoomPreset,
    landscape: Boolean,
    state: LazyGridState,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)?,
    listBottomPadding: Dp,
    selectionMode: Boolean,
    selectedSongIds: Set<String>,
    onSelectionToggle: (String) -> Unit,
    interactive: Boolean,
    modifier: Modifier,
) {
    val columns = preset.columns(landscape)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        userScrollEnabled = interactive,
        modifier = modifier,
        contentPadding = if (preset.gridTile) {
            PaddingValues(
                start = HifiSpacing.md,
                end = HifiSpacing.md,
                bottom = listBottomPadding,
            )
        } else {
            PaddingValues(bottom = listBottomPadding)
        },
        horizontalArrangement = if (preset.gridTile) {
            Arrangement.spacedBy(HifiSpacing.md)
        } else {
            Arrangement.Start
        },
        verticalArrangement = if (preset.gridTile) {
            Arrangement.spacedBy(HifiSpacing.lg)
        } else {
            Arrangement.Top
        },
    ) {
        gridItemsIndexed(songs, key = { _, song -> song.id }) { _, song ->
            val click = {
                if (selectionMode) onSelectionToggle(song.id) else onSongClick(song.id)
            }
            val interaction = if (interactive) {
                Modifier.combinedClickable(
                    onClick = click,
                    onLongClick = if (!selectionMode) onSongOpenMenu?.let { open -> { open(song) } } else null,
                )
            } else {
                Modifier
            }
            if (preset.gridTile) {
                // Exact grid height without constructing SongCover/AsyncImage. Text nodes are kept
                // only because their font-scale-aware line heights are part of the grid geometry.
                Column(
                    modifier = Modifier.fillMaxWidth().then(interaction),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(text = "M", style = MicaTheme.typography.bodyMd, maxLines = 1)
                    Text(text = "M", style = MicaTheme.typography.bodySm, maxLines = 1)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (preset.compact) 48.dp else HifiSize.listRowHeight)
                        .then(interaction),
                )
            }
        }
    }
}

@Composable
private fun SongZoomLayer(
    songs: List<Song>,
    preset: SongZoomPreset,
    landscape: Boolean,
    state: LazyGridState,
    counterpartState: LazyGridState,
    morphProgress: Float,
    morphFromLower: Boolean,
    transitionActive: Boolean,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)?,
    listBottomPadding: Dp,
    selectionMode: Boolean,
    selectedSongIds: Set<String>,
    onSelectionToggle: (String) -> Unit,
    infoVisibility: SongListInfoVisibility,
    interactive: Boolean,
    modifier: Modifier,
) {
    val columns = preset.columns(landscape)
    val currentRects = state.visiblePinchZoomItemRects()
    val counterpartRects = counterpartState.visiblePinchZoomItemRects()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        userScrollEnabled = interactive,
        modifier = modifier,
        contentPadding = if (preset.gridTile) {
            PaddingValues(
                start = HifiSpacing.md,
                end = HifiSpacing.md,
                bottom = listBottomPadding,
            )
        } else {
            PaddingValues(bottom = listBottomPadding)
        },
        horizontalArrangement = if (preset.gridTile) {
            Arrangement.spacedBy(HifiSpacing.md)
        } else {
            Arrangement.Start
        },
        verticalArrangement = if (preset.gridTile) {
            Arrangement.spacedBy(HifiSpacing.lg)
        } else {
            Arrangement.Top
        },
    ) {
        gridItemsIndexed(songs, key = { _, song -> song.id }) { _, song ->
            val itemMorph = calculatePinchZoomItemMorph(
                current = currentRects[song.id],
                counterpart = counterpartRects[song.id],
                progress = morphProgress,
                fromLower = morphFromLower,
                transitionActive = transitionActive,
            )
            val morphModifier = Modifier.pinchZoomItemBoundsMorph(itemMorph)
            val isCurrent = currentSongId == song.id
            val click = {
                if (selectionMode) onSelectionToggle(song.id) else onSongClick(song.id)
            }
            if (preset.gridTile) {
                SongZoomGridTile(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    onClick = if (interactive) click else ({}),
                    onLongClick = if (interactive && !selectionMode) {
                        onSongOpenMenu?.let { open -> { open(song) } }
                    } else {
                        null
                    },
                    selected = song.id in selectedSongIds,
                    selectionMode = selectionMode,
                    modifier = morphModifier,
                )
            } else {
                SongRow(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    onClick = if (interactive) click else ({}),
                    onLongClick = if (interactive && !selectionMode) {
                        onSongOpenMenu?.let { open -> { open(song) } }
                    } else {
                        null
                    },
                    selectionMode = selectionMode,
                    isSelected = song.id in selectedSongIds,
                    showCover = preset.showCover,
                    compact = preset.compact,
                    infoVisibility = infoVisibility,
                    modifier = morphModifier,
                )
            }
        }
    }
}


private data class SongZoomMorphRecord(
    val song: Song,
    val lowerRect: PinchZoomItemRect,
    val upperRect: PinchZoomItemRect,
    val displayRect: PinchZoomItemRect,
)

private data class SongZoomChildScene(
    val coverX: Float,
    val coverY: Float,
    val coverSize: Float,
    val coverAlpha: Float,
    val titleX: Float,
    val titleY: Float,
    val titleScale: Float,
    val titleVisibleWidth: Float,
    val subtitleX: Float,
    val subtitleY: Float,
    val subtitleScale: Float,
    val subtitleAlpha: Float,
    val subtitleVisibleWidth: Float,
    val accentAlpha: Float,
    val rightAccessoryAlpha: Float,
)

@Composable
private fun SongZoomMorphOverlay(
    songs: List<Song>,
    lowerPreset: SongZoomPreset,
    upperPreset: SongZoomPreset,
    landscape: Boolean,
    lowerState: LazyGridState,
    upperState: LazyGridState,
    progress: Float,
    currentSongId: String?,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selectedSongIds: Set<String>,
    infoVisibility: SongListInfoVisibility,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val p = progress.coerceIn(0f, 1f)
    val lowerColumns = lowerPreset.columns(landscape)
    val upperColumns = upperPreset.columns(landscape)
    val lowerInfos = lowerState.layoutInfo.visibleItemsInfo
    val upperInfos = upperState.layoutInfo.visibleItemsInfo
    val candidateIndices = buildSet {
        lowerInfos.forEach { add(it.index) }
        upperInfos.forEach { add(it.index) }
    }.sorted()

    val lowerHSpacing = with(density) { (if (lowerPreset.gridTile) HifiSpacing.md else 0.dp).toPx() }
    val lowerVSpacing = with(density) { (if (lowerPreset.gridTile) HifiSpacing.lg else 0.dp).toPx() }
    val upperHSpacing = with(density) { (if (upperPreset.gridTile) HifiSpacing.md else 0.dp).toPx() }
    val upperVSpacing = with(density) { (if (upperPreset.gridTile) HifiSpacing.lg else 0.dp).toPx() }
    // LazyGridItemInfo cross-axis offsets are in the padded content coordinate space. The visible
    // overlay is in viewport coordinates, so add the start content inset back for grid presets.
    val lowerCrossInset = with(density) { (if (lowerPreset.gridTile) HifiSpacing.md else 0.dp).toPx() }
    val upperCrossInset = with(density) { (if (upperPreset.gridTile) HifiSpacing.md else 0.dp).toPx() }

    val records = candidateIndices.mapNotNull { index ->
        val song = songs.getOrNull(index) ?: return@mapNotNull null
        val lower = lowerInfos.firstOrNull { it.index == index }?.toPinchZoomRect(lowerCrossInset)
            ?: extrapolateSongRect(lowerInfos, index, lowerColumns, lowerHSpacing, lowerVSpacing, lowerCrossInset)
        val upper = upperInfos.firstOrNull { it.index == index }?.toPinchZoomRect(upperCrossInset)
            ?: extrapolateSongRect(upperInfos, index, upperColumns, upperHSpacing, upperVSpacing, upperCrossInset)
        if (lower == null || upper == null) null else SongZoomMorphRecord(
            song = song,
            lowerRect = lower,
            upperRect = upper,
            displayRect = interpolateSongZoomRect(lower, upper, p),
        )
    }

    Layout(
        modifier = modifier,
        content = {
            records.forEach { record ->
                androidx.compose.runtime.key(record.song.id) {
                    SongZoomSceneItem(
                        song = record.song,
                        lowerPreset = lowerPreset,
                        upperPreset = upperPreset,
                        lowerWidthPx = record.lowerRect.widthPx,
                        upperWidthPx = record.upperRect.widthPx,
                        progress = p,
                        isCurrent = currentSongId == record.song.id,
                        isPlaying = isPlaying && currentSongId == record.song.id,
                        selectionMode = selectionMode,
                        selected = record.song.id in selectedSongIds,
                        infoVisibility = infoVisibility,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = records[index].displayRect
            measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(
                    rect.widthPx.toInt().coerceAtLeast(1),
                    rect.heightPx.toInt().coerceAtLeast(1),
                ),
            )
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val rect = records[index].displayRect
                placeable.placeRelative(rect.leftPx.toInt(), rect.topPx.toInt())
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridItemInfo.toPinchZoomRect(
    crossAxisInsetPx: Float = 0f,
): PinchZoomItemRect =
    PinchZoomItemRect(
        leftPx = offset.x.toFloat() + crossAxisInsetPx,
        topPx = offset.y.toFloat(),
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
    )

private fun extrapolateSongRect(
    visible: List<androidx.compose.foundation.lazy.grid.LazyGridItemInfo>,
    index: Int,
    columns: Int,
    horizontalSpacingPx: Float,
    verticalSpacingPx: Float,
    crossAxisInsetPx: Float = 0f,
): PinchZoomItemRect? {
    val safeColumns = columns.coerceAtLeast(1)
    val anchor = visible.minByOrNull { abs(it.index - index) } ?: return null
    val anchorRow = anchor.index / safeColumns
    val anchorColumn = anchor.index % safeColumns
    val targetRow = index / safeColumns
    val targetColumn = index % safeColumns
    val xStep = anchor.size.width.toFloat() + horizontalSpacingPx
    val yStep = anchor.size.height.toFloat() + verticalSpacingPx
    return PinchZoomItemRect(
        leftPx = anchor.offset.x + crossAxisInsetPx + (targetColumn - anchorColumn) * xStep,
        topPx = anchor.offset.y + (targetRow - anchorRow) * yStep,
        widthPx = anchor.size.width.toFloat(),
        heightPx = anchor.size.height.toFloat(),
    )
}

@Composable
private fun SongZoomSceneItem(
    song: Song,
    lowerPreset: SongZoomPreset,
    upperPreset: SongZoomPreset,
    lowerWidthPx: Float,
    upperWidthPx: Float,
    progress: Float,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    infoVisibility: SongListInfoVisibility,
) {
    val density = LocalDensity.current
    val p = progress.coerceIn(0f, 1f)
    val lowerScene = songZoomChildScene(lowerPreset, lowerWidthPx, density)
    val upperScene = songZoomChildScene(upperPreset, upperWidthPx, density)
    fun lerp(a: Float, b: Float): Float = a + (b - a) * p

    val coverX = lerp(lowerScene.coverX, upperScene.coverX)
    val coverY = lerp(lowerScene.coverY, upperScene.coverY)
    val coverSize = lerp(lowerScene.coverSize, upperScene.coverSize).coerceAtLeast(1f)
    val coverAlpha = lerp(lowerScene.coverAlpha, upperScene.coverAlpha).coerceIn(0f, 1f)
    val titleX = lerp(lowerScene.titleX, upperScene.titleX)
    val titleY = lerp(lowerScene.titleY, upperScene.titleY)
    val titleScale = lerp(lowerScene.titleScale, upperScene.titleScale).coerceAtLeast(0.1f)
    val titleVisibleWidth = lerp(lowerScene.titleVisibleWidth, upperScene.titleVisibleWidth).coerceAtLeast(1f)
    val subtitleX = lerp(lowerScene.subtitleX, upperScene.subtitleX)
    val subtitleY = lerp(lowerScene.subtitleY, upperScene.subtitleY)
    val subtitleScale = lerp(lowerScene.subtitleScale, upperScene.subtitleScale).coerceAtLeast(0.1f)
    val subtitleAlpha = lerp(lowerScene.subtitleAlpha, upperScene.subtitleAlpha).coerceIn(0f, 1f)
    val subtitleVisibleWidth = lerp(lowerScene.subtitleVisibleWidth, upperScene.subtitleVisibleWidth).coerceAtLeast(1f)
    val accentAlpha = lerp(lowerScene.accentAlpha, upperScene.accentAlpha).coerceIn(0f, 1f)
    val rightAccessoryAlpha = lerp(lowerScene.rightAccessoryAlpha, upperScene.rightAccessoryAlpha).coerceIn(0f, 1f)
    val subtitle = if (selectionMode && selected && upperPreset.gridTile && p > 0.5f) {
        "已选择"
    } else {
        songSubtitle(song, infoVisibility)
    }
    val trailing = songTrailingLabel(song, infoVisibility.trailingInfo).orEmpty()

    Layout(
        content = {
            // Stable request + zero crossfade: normal-list <-> grid keeps the exact same bitmap node.
            SongCover(
                albumArtUri = song.albumArtUri,
                fallbackColor = song.coverColor,
                contentDescription = song.title,
                decodeTarget = CoverDecodeTarget.forCompactCover(),
                stableMemoryCacheKey = song.albumArtUri,
                crossfadeMillis = 0,
                allowPreviousImageUnderlay = false,
                publishHoldoverOnSuccess = false,
            )
            Text(
                text = song.title,
                style = MicaTheme.typography.bodyLg,
                color = if (selected || isCurrent) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(12.dp)) {
                if (isPlaying) PlayingIndicator(Modifier.fillMaxSize())
            }
            Text(
                text = trailing,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
                maxLines = 1,
            )
            Box(Modifier.size(24.dp)) {
                if (selectionMode) SongSelectionCheckbox(selected = selected)
            }
            Box(
                Modifier
                    .width(HifiSize.accentBarWidth)
                    .fillMaxSize()
                    .background(if (isCurrent) MicaTheme.colors.accent else Color.Transparent),
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
        val coverBase = maxOf(lowerScene.coverSize, upperScene.coverSize).toInt().coerceAtLeast(1)
        // Poweramp changes each child scene's available text width continuously. Measuring at the
        // endpoint maximum made Mica keep the old ellipsis until settle, then crop/expand in one
        // frame. Convert the interpolated visual width back into the child's unscaled measure width.
        val titleBaseWidth = (titleVisibleWidth / titleScale)
            .toInt().coerceIn(1, width.coerceAtLeast(1))
        val subtitleBaseWidth = (subtitleVisibleWidth / subtitleScale)
            .toInt().coerceIn(1, width.coerceAtLeast(1))
        val cover = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(coverBase, coverBase))
        val title = measurables[1].measure(
            androidx.compose.ui.unit.Constraints(maxWidth = titleBaseWidth, maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
        )
        val line2 = measurables[2].measure(
            androidx.compose.ui.unit.Constraints(maxWidth = subtitleBaseWidth, maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
        )
        val indicatorSize = with(density) { 12.dp.roundToPx() }
        val indicator = measurables[3].measure(androidx.compose.ui.unit.Constraints.fixed(indicatorSize, indicatorSize))
        val trailingP = measurables[4].measure(
            androidx.compose.ui.unit.Constraints(maxWidth = width.coerceAtLeast(1), maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
        )
        val selectionSize = with(density) { 24.dp.roundToPx() }
        val selection = measurables[5].measure(androidx.compose.ui.unit.Constraints.fixed(selectionSize, selectionSize))
        val accentWidth = with(density) { HifiSize.accentBarWidth.roundToPx() }
        val accent = measurables[6].measure(androidx.compose.ui.unit.Constraints.fixed(accentWidth, height.coerceAtLeast(1)))
        val endInset = with(density) { HifiSpacing.lg.roundToPx() }
        val indicatorGap = with(density) { HifiSpacing.sm.roundToPx() }

        layout(width, height) {
            cover.placeWithLayer(coverX.toInt(), coverY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                val scale = coverSize / coverBase.toFloat()
                scaleX = scale
                scaleY = scale
                alpha = coverAlpha
            }
            title.placeWithLayer(titleX.toInt(), titleY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = titleScale
                scaleY = titleScale
            }
            line2.placeWithLayer(subtitleX.toInt(), subtitleY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = subtitleScale
                scaleY = subtitleScale
                alpha = subtitleAlpha
            }
            if (isPlaying) {
                val visualTitleWidth = (title.width * titleScale).toInt()
                val indicatorX = (titleX.toInt() + visualTitleWidth + indicatorGap)
                    .coerceAtMost((width - indicatorSize).coerceAtLeast(0))
                indicator.placeWithLayer(indicatorX, titleY.toInt()) { alpha = 1f }
            }
            val accessoryY = ((height - maxOf(trailingP.height, selection.height)) / 2).coerceAtLeast(0)
            if (selectionMode) {
                selection.placeWithLayer((width - endInset - selection.width).coerceAtLeast(0), accessoryY) {
                    alpha = rightAccessoryAlpha
                }
            } else if (trailing.isNotBlank()) {
                trailingP.placeWithLayer((width - endInset - trailingP.width).coerceAtLeast(0), accessoryY) {
                    alpha = rightAccessoryAlpha
                }
            }
            accent.placeWithLayer(0, 0) { alpha = accentAlpha }
        }
    }
}

private fun songZoomChildScene(
    preset: SongZoomPreset,
    itemWidthPx: Float,
    density: androidx.compose.ui.unit.Density,
): SongZoomChildScene {
    fun dp(value: Dp): Float = with(density) { value.toPx() }
    val itemWidth = itemWidthPx.coerceAtLeast(1f)
    val accent = dp(HifiSize.accentBarWidth)
    val md = dp(HifiSpacing.md)
    val lg = dp(HifiSpacing.lg)
    val xs = dp(HifiSpacing.xs)
    val coverSm = dp(HifiSize.coverSm)
    val endReserve = lg + dp(56.dp)

    return when (preset) {
        SongZoomPreset.DENSE_LIST -> {
            val rowHeight = dp(48.dp)
            val titleScale = 14f / 16f
            val titleX = accent + md
            SongZoomChildScene(
                coverX = titleX,
                coverY = (rowHeight - dp(32.dp)) / 2f,
                coverSize = dp(32.dp),
                coverAlpha = 0f,
                titleX = titleX,
                titleY = (rowHeight - dp(24.dp) * titleScale) / 2f,
                titleScale = titleScale,
                titleVisibleWidth = (itemWidth - titleX - endReserve).coerceAtLeast(1f),
                subtitleX = titleX,
                subtitleY = rowHeight / 2f,
                subtitleScale = 0.85f,
                subtitleAlpha = 0f,
                subtitleVisibleWidth = (itemWidth - titleX - endReserve).coerceAtLeast(1f),
                accentAlpha = 1f,
                rightAccessoryAlpha = 1f,
            )
        }
        SongZoomPreset.NORMAL_LIST -> {
            val rowHeight = dp(HifiSize.listRowHeight)
            val coverX = accent + md
            val titleX = coverX + coverSm + md
            SongZoomChildScene(
                coverX = coverX,
                coverY = ((rowHeight - coverSm) / 2f).coerceAtLeast(0f),
                coverSize = coverSm,
                coverAlpha = 1f,
                titleX = titleX,
                titleY = dp(11.dp),
                titleScale = 1f,
                titleVisibleWidth = (itemWidth - titleX - endReserve).coerceAtLeast(1f),
                subtitleX = titleX,
                subtitleY = dp(35.dp),
                subtitleScale = 1f,
                subtitleAlpha = 1f,
                subtitleVisibleWidth = (itemWidth - titleX - endReserve).coerceAtLeast(1f),
                accentAlpha = 1f,
                rightAccessoryAlpha = 1f,
            )
        }
        SongZoomPreset.DENSE_GRID, SongZoomPreset.LARGE_GRID -> {
            val titleScale = 14f / 16f
            val titleY = itemWidth + xs
            SongZoomChildScene(
                coverX = 0f,
                coverY = 0f,
                coverSize = itemWidth,
                coverAlpha = 1f,
                titleX = 0f,
                titleY = titleY,
                titleScale = titleScale,
                titleVisibleWidth = itemWidth,
                subtitleX = 0f,
                subtitleY = titleY + dp(20.dp) + xs,
                subtitleScale = 1f,
                subtitleAlpha = 1f,
                subtitleVisibleWidth = itemWidth,
                accentAlpha = 0f,
                rightAccessoryAlpha = 0f,
            )
        }
    }
}

private fun interpolateSongZoomRect(
    lower: PinchZoomItemRect,
    upper: PinchZoomItemRect,
    progress: Float,
): PinchZoomItemRect {
    val p = progress.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return PinchZoomItemRect(
        leftPx = lerp(lower.leftPx, upper.leftPx),
        topPx = lerp(lower.topPx, upper.topPx),
        widthPx = lerp(lower.widthPx, upper.widthPx).coerceAtLeast(1f),
        heightPx = lerp(lower.heightPx, upper.heightPx).coerceAtLeast(1f),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongZoomGridTile(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    selected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
    ) {
        SongCover(
            albumArtUri = song.albumArtUri,
            fallbackColor = song.coverColor,
            contentDescription = song.title,
            decodeTarget = CoverDecodeTarget.forCompactCover(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = song.title,
                style = MicaTheme.typography.bodyMd,
                color = if (selected || isCurrent) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isPlaying) {
                PlayingIndicator(modifier = Modifier.size(12.dp))
            }
        }
        Text(
            text = if (selectionMode && selected) "已选择" else ArtistNames.normalizeDisplay(song.artist),
            style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}
