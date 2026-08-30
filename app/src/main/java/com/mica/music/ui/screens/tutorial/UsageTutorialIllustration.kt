package com.mica.music.ui.screens.tutorial

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mica.music.data.*
import com.mica.music.ui.components.*
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.screens.home.HomeTopBar
import com.mica.music.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Only four sample songs. No media URI, library owner, real lyrics, settings facade or live callbacks. */
internal val TutorialSongs = listOf("夜航", "晴天来信", "山间回声", "慢慢醒来").mapIndexed { index, title ->
    Song(
        id = "tutorial-$index", title = title, artist = "Mica Sessions", album = "日常的声音",
        durationSec = 218 + index * 13, metadata = TrackMetadata("FLAC", 44100, 16, 850, 2, "audio/flac"),
        albumArtUri = null, coverColorArgb = 0xFF8B7AFF.toInt(), mediaUri = "",
    )
}

private val LocateSongs = listOf("晨光", "雨后", "远山", "海风", "归途", "午后", "星河", "晚霞").mapIndexed { index, title ->
    TutorialSongs[0].copy(id = "tutorial-locate-$index", title = title)
} + TutorialSongs

/** A linear lesson clock; each local movement still uses the common Mica easing. */
internal fun tutorialStep(time: Float, start: Float, end: Float): Float =
    MicaMotion.Easing.transform(((time - start) / (end - start)).coerceIn(0f, 1f))

/** Actual production UI, measured at phone width and scaled into a read-only local viewport. */
@Composable
internal fun UsageTutorialIllustration(tip: UsageTip, previewTime: Float? = null, modifier: Modifier = Modifier) {
    val enabled = rememberMicaMotionEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember(tip) { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(tip, enabled, lifecycle, previewTime) {
        if (previewTime != null) return@LaunchedEffect
        if (!enabled) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                progress.snapTo(0f)
                delay(650)
                // Educational timeline exception documented in USAGE_TUTORIAL.md.
                val duration = when (tip) { UsageTip.SORT -> 10000; UsageTip.MENU -> 6500; else -> 3200 }
                progress.animateTo(1f, tween(duration, easing = LinearEasing))
                delay(1800)
            }
        }
    }
    val configuration = LocalConfiguration.current
    val virtualPhone = remember(configuration) {
        Configuration(configuration).apply { screenWidthDp = 360; screenHeightDp = 780; orientation = Configuration.ORIENTATION_PORTRAIT }
    }
    val density = LocalDensity.current
    val time = previewTime ?: progress.value
    val sourceHeightDp = 620
    Box(modifier.fillMaxWidth()) {
        CompositionLocalProvider(
            LocalConfiguration provides virtualPhone,
            LocalDensity provides Density(density.density, fontScale = 1f),
            // Do not sample the live app behind the dialog or load the user's custom wallpaper.
            LocalMicaBlurTarget provides null,
            LocalCustomWallpaperPath provides null,
        ) {
            Layout(
                modifier = Modifier.fillMaxSize().clipToBounds()
                    .clearAndSetSemantics { contentDescription = "${tip.instruction} ${tip.result}。真实界面组件演示，不需要操作。" }
                    .pointerInput(Unit) {
                        // Intercept at Initial so children cannot scroll, click, long-press or edit anything.
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
                content = {
                    Box(Modifier.fillMaxSize().clipToBounds().micaAppBackground()) {
                        TutorialScene(tip, time)
                        TutorialGestureOverlay(tip, time, sourceHeightDp)
                    }
                },
            ) { measurables, constraints ->
                val sourceWidth = 360.dp.roundToPx()
                val sourceHeight = sourceHeightDp.dp.roundToPx()
                val child = measurables.single().measure(Constraints.fixed(sourceWidth, sourceHeight))
                val viewportWidth = constraints.maxWidth
                val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else (sourceHeight * viewportWidth.toFloat() / sourceWidth).toInt()
                val scale = minOf(viewportWidth.toFloat() / sourceWidth, viewportHeight.toFloat() / sourceHeight)
                layout(viewportWidth, viewportHeight) {
                    child.placeWithLayer(((viewportWidth - sourceWidth * scale) / 2).toInt(), ((viewportHeight - sourceHeight * scale) / 2).toInt()) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
        }
    }
}

@Composable
internal fun TutorialScene(tip: UsageTip, time: Float) {
    val action = tutorialStep(time, .15f, .8f)
    val colors = MicaTheme.colors
    when (tip) {
        UsageTip.DRAWER -> {
            HomeDrawerPanel(
                selectedSection = HomeSection.Songs, activePlaylistId = null, playlists = emptyList(),
                statusBarTop = 0.dp, bottomInset = 0.dp, onSectionSelected = {}, onOpenEqualizer = {},
                onOpenAbout = {}, onPlaylistSelected = {}, onCreatePlaylist = {},
            )
            Column(Modifier.fillMaxSize().graphicsLayer { translationX = 180.dp.toPx() * action }.micaAppBackground()) {
                TutorialTopBar("歌曲")
                TutorialSongs.forEach { SongRow(it, isCurrent = false, isPlaying = false, onClick = {}) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { TutorialMiniPlayer() }
        }
        UsageTip.LOCATE -> {
            Column(Modifier.fillMaxSize()) {
                TutorialTopBar("歌曲")
                Box(Modifier.weight(1f).clipToBounds()) {
                    Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer {
                        translationY = -7 * HifiSize.listRowHeight.toPx() * tutorialStep(time, .3f, .8f)
                    }) {
                        LocateSongs.forEach { song ->
                            SongRow(song, isCurrent = song.id == TutorialSongs[0].id, isPlaying = false, onClick = {}, modifier = Modifier.testTag("locate-${song.id}"))
                        }
                    }
                }
                TutorialMiniPlayer()
            }
        }
        UsageTip.ZOOM -> Column(Modifier.fillMaxSize()) {
            TutorialTopBar("歌曲")
            TutorialZoomScene(action, Modifier.weight(1f))
        }
        UsageTip.MENU -> TutorialPlayerMenu(time)
        UsageTip.FOLDERS -> Column(Modifier.fillMaxSize()) {
            TutorialTopBar("文件夹")
            Text("层级浏览 · 文件夹统合", style = MicaTheme.typography.caption, color = colors.textSecondary, modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm))
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                repeat(2) { page ->
                    Column(Modifier.fillMaxSize().graphicsLayer { translationX = (page - action) * size.width }) {
                        val folders = if (page == 0) listOf("Music", "Download", "Recordings") else listOf("专辑", "收藏", "现场")
                        folders.forEachIndexed { index, name ->
                            BrowseGroupRow(title = name, subtitle = if (page == 0) "${12 + index * 6} 首" else "Music / $name · ${6 + index * 3} 首", onClick = {})
                        }
                    }
                }
            }
        }
        UsageTip.SORT -> TutorialSortScene(time)
    }
}

@Composable
internal fun TutorialTopBar(title: String) = HomeTopBar(
    title = title, showBack = false, searchOpen = false, searchQuery = "", onSearchQueryChange = {},
    motionEnabled = false, onLeadingClick = {}, onSearchClick = {},
)

@Composable
private fun TutorialMiniPlayer() = MiniPlayer(
    style = MiniPlayerStyle.FLOATING_ISLAND, song = TutorialSongs[0], isPlaying = false, positionMs = 42000,
    miniPlayerLyricsEnabled = false, resolvedText = MiniPlayerText("夜航", "Mica Sessions"),
    onPlayPause = {}, onNext = {}, onExpand = {}, onLongPress = {},
)

/** Reuses the exact live list/grid child scene (cover, labels, selection stripe and metadata). */
@Composable
private fun TutorialZoomScene(p: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val widthPx = with(density) { 360.dp.toPx() }
    val insetPx = with(density) { HifiSpacing.md.toPx() }
    val cellWidth = (widthPx - insetPx * 5) / 4
    val rowHeight = with(density) { HifiSize.listRowHeight.toPx() }
    Layout(modifier = modifier.fillMaxWidth(), content = {
        TutorialSongs.forEach { song ->
            SongZoomSceneItem(
                song, SongZoomPreset.NORMAL_LIST, SongZoomPreset.FOUR_COLUMN_GRID, widthPx, cellWidth, p,
                isCurrent = false, isPlaying = false, selectionMode = false, selected = false, infoVisibility = SongListInfoVisibility(),
            )
        }
    }) { measurables, constraints ->
        val width = (widthPx + (cellWidth - widthPx) * p).toInt().coerceAtLeast(1)
        val height = (rowHeight + (cellWidth + 52.dp.toPx() - rowHeight) * p).toInt().coerceAtLeast(1)
        val children = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            children.forEachIndexed { index, child ->
                child.placeRelative(((insetPx + index * (cellWidth + insetPx)) * p).toInt(), (index * rowHeight * (1 - p)).toInt())
            }
        }
    }
}

/** Only the explanatory touch marks are drawn separately; all application UI above is production UI. */
@Composable
private fun TutorialGestureOverlay(tip: UsageTip, time: Float, sourceHeight: Int) {
    val accent = MicaTheme.colors.accent
    val surface = MicaTheme.colors.surfaceCard
    Canvas(Modifier.fillMaxSize()) {
        val p = tutorialStep(time, .15f, .8f)
        fun point(x: Float, y: Float) = Offset(x / 360f * size.width, y / sourceHeight * size.height)
        fun finger(x: Float, y: Float) {
            drawCircle(accent.copy(alpha = 0.18f), 20.dp.toPx(), point(x, y))
            drawCircle(surface, 8.dp.toPx(), point(x, y))
            drawCircle(accent, 8.dp.toPx(), point(x, y), style = Stroke(2.dp.toPx()))
        }
        fun arrow(x: Float, y: Float, dx: Float) {
            drawLine(accent, point(x, y), point(x + dx, y), 2.dp.toPx())
            val sign = if (dx > 0) 1 else -1
            drawLine(accent, point(x + dx - sign * 6, y - 5), point(x + dx, y), 2.dp.toPx())
            drawLine(accent, point(x + dx - sign * 6, y + 5), point(x + dx, y), 2.dp.toPx())
        }
        when (tip) {
            UsageTip.DRAWER -> { arrow(90f, 275f, 125f); finger(90f + 125 * p, 275f) }
            UsageTip.ZOOM -> { arrow(137f, 277f, -42f); arrow(223f, 277f, 42f); finger(147f - 48 * p, 282f + 20 * p); finger(213f + 48 * p, 282f - 20 * p) }
            UsageTip.LOCATE -> if (time < .42f) finger(170f, sourceHeight - 40f)
            UsageTip.MENU -> if (time in .12f.. .34f) finger(180f, 165f)
            UsageTip.FOLDERS -> { arrow(248f, 285f, -130f); finger(248f - 130 * p, 285f) }
            UsageTip.SORT -> when {
                time in .07f.. .20f || time in .73f.. .85f -> finger(50f, sourceHeight - 181f)
                time in .30f.. .46f -> finger(336f, 132f + 128f * tutorialStep(time, .32f, .43f))
                time in .49f.. .64f -> finger(336f, 324f - 128f * tutorialStep(time, .50f, .61f))
            }
        }
    }
}
