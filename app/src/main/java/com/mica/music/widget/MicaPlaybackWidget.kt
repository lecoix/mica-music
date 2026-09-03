package com.mica.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mica.music.R
import com.mica.music.util.DiagnosticLog
import java.io.File

enum class PlaybackWidgetStyle {
    ADAPTIVE,
    ARTWORK,
    MINIMAL,
    HIFI,
}

open class MicaPlaybackWidget protected constructor(
    private val style: PlaybackWidgetStyle,
) : GlanceAppWidget() {
    constructor() : this(PlaybackWidgetStyle.ADAPTIVE)
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallback = WidgetPlaybackStateStore.load(context)
        provideContent {
            val snapshot = WidgetPlaybackGlanceState.read(
                preferences = currentState<Preferences>(),
                fallback = fallback,
            )
            val artwork = snapshot.artworkPath
                ?.takeIf { File(it).isFile }
                ?.let(BitmapFactory::decodeFile)
            GlanceTheme {
                val size = LocalSize.current
                val title = snapshot.title.ifBlank { context.getString(R.string.app_name) }
                val artist = snapshot.artist.ifBlank {
                    if (snapshot.mediaId.isBlank()) "鏆傛棤鎾斁璁板綍" else context.getString(R.string.app_name)
                }
                val widthClass = widthClass(size.width)
                val heightClass = heightClass(size.height)

                when (style) {
                    PlaybackWidgetStyle.ARTWORK -> ArtworkWidgetContent(
                        context = context,
                        snapshot = snapshot,
                        title = title,
                        artist = artist,
                        artwork = artwork,
                    )
                    PlaybackWidgetStyle.MINIMAL -> MinimalSquareWidgetContent(
                        context = context,
                        snapshot = snapshot,
                        artwork = artwork,
                    )
                    PlaybackWidgetStyle.ADAPTIVE,
                    PlaybackWidgetStyle.HIFI,
                    -> WidgetSurface {
                        when (heightClass) {
                    WidgetHeightClass.COMPACT -> when (widthClass) {
                        WidgetWidthClass.NARROW -> CompactMinimalLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                        )
                        WidgetWidthClass.MEDIUM -> CompactStandardLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                        WidgetWidthClass.WIDE -> CompactWideLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                    }
                    WidgetHeightClass.MEDIUM -> when (widthClass) {
                        WidgetWidthClass.NARROW -> ArtworkSquareLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                        WidgetWidthClass.MEDIUM -> MediumInfoLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                        WidgetWidthClass.WIDE -> WideCompleteLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                    }
                    WidgetHeightClass.EXPANDED -> when (widthClass) {
                        WidgetWidthClass.NARROW -> ArtworkPortraitLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                        WidgetWidthClass.MEDIUM -> LargeSquareLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                        WidgetWidthClass.WIDE -> HiFiPanelLayout(
                            context = context,
                            snapshot = snapshot,
                            title = title,
                            artist = artist,
                            artwork = artwork,
                        )
                    }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MinimalSquareWidgetContent(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        artwork: Bitmap?,
    ) {
        val size = LocalSize.current
        val squareSize = (minOf(size.width, size.height) - SURFACE_INSET * 2)
            .coerceAtLeast(96.dp)
        val gap = (squareSize.value * 0.045f).dp.coerceIn(6.dp, 12.dp)
        val tileSize = ((squareSize - gap) / 2f).coerceAtLeast(44.dp)
        val sideIconSize = (tileSize.value * 0.30f).dp.coerceIn(22.dp, 42.dp)
        val playIconSize = (tileSize.value * 0.38f).dp.coerceIn(28.dp, 52.dp)

        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = GlanceModifier.size(squareSize)) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    MinimalSquareArtworkTile(
                        artwork = artwork,
                        tileSize = tileSize,
                    )
                    Spacer(GlanceModifier.width(gap))
                    MinimalSquareControlTile(
                        context = context,
                        iconRes = R.drawable.ic_widget_previous,
                        action = WidgetPlaybackActions.PREVIOUS,
                        contentDescription = "Previous",
                        tileSize = tileSize,
                        iconSize = sideIconSize,
                    )
                }
                Spacer(GlanceModifier.height(gap))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    MinimalSquareControlTile(
                        context = context,
                        iconRes = if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        action = WidgetPlaybackActions.PLAY_PAUSE,
                        contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                        tileSize = tileSize,
                        iconSize = playIconSize,
                        accent = snapshot.isPlaying,
                    )
                    Spacer(GlanceModifier.width(gap))
                    MinimalSquareControlTile(
                        context = context,
                        iconRes = R.drawable.ic_widget_next,
                        action = WidgetPlaybackActions.NEXT,
                        contentDescription = "Next",
                        tileSize = tileSize,
                        iconSize = sideIconSize,
                    )
                }
            }
        }
    }

    @Composable
    private fun MinimalSquareArtworkTile(
        artwork: Bitmap?,
        tileSize: Dp,
    ) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier.size(tileSize),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = GlanceModifier
                    .size(tileSize)
                    .background(ColorProvider(R.color.widget_minimal_square_tile)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music),
                    contentDescription = null,
                    modifier = GlanceModifier.size(
                        (tileSize.value * 0.34f).dp.coerceIn(24.dp, 48.dp),
                    ),
                    colorFilter = ColorFilter.tint(
                        ColorProvider(R.color.widget_minimal_square_icon),
                    ),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    @Composable
    private fun MinimalSquareControlTile(
        context: Context,
        iconRes: Int,
        action: String,
        contentDescription: String,
        tileSize: Dp,
        iconSize: Dp,
        accent: Boolean = false,
    ) {
        val clickAction = actionStartService(
            intent = WidgetPlaybackActions.serviceIntent(context, action),
            isForegroundService = true,
        )
        Box(
            modifier = GlanceModifier
                .size(tileSize)
                .background(ColorProvider(R.color.widget_minimal_square_tile))
                .clickable(clickAction),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(
                    if (accent) ColorProvider(R.color.widget_accent)
                    else ColorProvider(R.color.widget_minimal_square_icon),
                ),
                contentScale = ContentScale.Fit,
            )
        }
    }

    @Composable
    private fun ArtworkWidgetContent(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        val size = LocalSize.current
        val contentWidth = (size.width - SURFACE_INSET * 2).coerceAtLeast(100.dp)
        val contentHeight = (size.height - SURFACE_INSET * 2).coerceAtLeast(160.dp)

        // Artwork is always the visual anchor: full-width and strictly square.
        // Text stops scaling early; the controls use the remaining vertical room.
        val coverHeight = contentWidth
        val overlayHeight = (contentHeight - coverHeight).coerceAtLeast(64.dp)
        val widthScale = (contentWidth.value / ARTWORK_BASE_WIDTH_DP)
            .coerceIn(ARTWORK_MIN_SCALE, ARTWORK_MAX_SCALE)
        val textScale = widthScale.coerceIn(ARTWORK_MIN_SCALE, 1.15f)
        val horizontalPadding = (12f * textScale).dp
        val topPadding = (8f * textScale).dp
        val bottomPadding = (4f * textScale).dp

        // Two metadata rows only: title + "artist · album". Everything left below
        // those rows belongs to the transport-control area. Controls grow until
        // either the remaining height or the available row width becomes the limit.
        val metadataHeightBudget = 40f * textScale
        val controlAreaHeight = (
            overlayHeight.value - topPadding.value - bottomPadding.value - metadataHeightBudget
        ).coerceAtLeast(42f).dp
        val controlGap = minOf((7f * widthScale).dp, 12.dp)
        val availableControlWidth = (contentWidth - horizontalPadding * 2).coerceAtLeast(120.dp)
        val maxButtonByWidth = ((availableControlWidth - controlGap * 2) / 3f)
            .coerceAtLeast(42.dp)
        val buttonSize = minOf(controlAreaHeight, maxButtonByWidth, 72.dp)
            .coerceAtLeast(42.dp)
        val iconLimit = (buttonSize - 6.dp).coerceAtLeast(24.dp)
        val sideIconSize = minOf((buttonSize.value * 0.56f).dp, iconLimit)
        val playIconSize = minOf((buttonSize.value * 0.70f).dp, iconLimit)


        DiagnosticLog.event(
            "PlaybackWidget",
            "artwork-layout size=${size.width.value}x${size.height.value} " +
                "widthScale=${"%.2f".format(widthScale)} textScale=${"%.2f".format(textScale)} " +
                "controlArea=${controlAreaHeight.value} overlay=${overlayHeight.value} " +
                "side=${sideIconSize.value} play=${playIconSize.value} " +
                "button=${buttonSize.value} gap=${controlGap.value}",
        )
        ArtworkStyleSurface {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                ArtworkFill(
                    artwork = artwork,
                    height = coverHeight,
                )
                ArtworkOverlayPanel(
                    context = context,
                    snapshot = snapshot,
                    title = title,
                    artist = artist,
                    textScale = textScale,
                    sideIconSize = sideIconSize,
                    playIconSize = playIconSize,
                    buttonSize = buttonSize,
                    controlGap = controlGap,
                    controlAreaHeight = controlAreaHeight,
                    height = overlayHeight,
                )
            }
        }
    }

    @Composable
    private fun ArtworkFill(
        artwork: Bitmap?,
        height: Dp,
    ) {
        val colors = micaWidgetColors()
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(height),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(height)
                    .background(colors.artworkPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music),
                    contentDescription = null,
                    modifier = GlanceModifier.size(52.dp),
                    colorFilter = ColorFilter.tint(colors.tertiary),
                )
            }
        }
    }

    @Composable
    private fun ArtworkOverlayPanel(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        textScale: Float,
        sideIconSize: Dp,
        playIconSize: Dp,
        buttonSize: Dp,
        controlGap: Dp,
        controlAreaHeight: Dp,
        height: Dp,
    ) {
        val overlayTitle = ColorProvider(R.color.widget_artwork_overlay_title)
        val overlaySecondary = ColorProvider(R.color.widget_artwork_overlay_secondary)
        val horizontalPadding = (12f * textScale).dp
        val topPadding = (8f * textScale).dp
        val bottomPadding = (4f * textScale).dp
        val secondary = if (snapshot.album.isNotBlank()) {
            "$artist \u00B7 ${snapshot.album}"
        } else {
            artist
        }
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(height)
                .background(
                    imageProvider = ImageProvider(R.drawable.widget_artwork_fade_down),
                    contentScale = ContentScale.FillBounds,
                )
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = overlayTitle,
                    fontSize = (16f * textScale).sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height((2f * textScale).dp))
            Text(
                text = secondary,
                style = TextStyle(color = overlaySecondary, fontSize = (12f * textScale).sp),
                maxLines = 1,
            )
            if (snapshot.mediaId.isNotBlank()) {
                ArtworkTransportControls(
                    context = context,
                    snapshot = snapshot,
                    buttonSize = buttonSize,
                    gap = controlGap,
                    sideIconSize = sideIconSize,
                    playIconSize = playIconSize,
                    foreground = overlayTitle,
                    areaHeight = controlAreaHeight,
                )
            } else {
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }

    @Composable
    private fun ArtworkTransportControls(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        buttonSize: Dp,
        gap: Dp,
        sideIconSize: Dp,
        playIconSize: Dp,
        foreground: ColorProvider,
        areaHeight: Dp,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(areaHeight),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TransportButton(
                    context = context,
                    iconRes = R.drawable.ic_widget_previous,
                    action = WidgetPlaybackActions.PREVIOUS,
                    contentDescription = "Previous",
                    buttonSize = buttonSize,
                    iconSize = sideIconSize,
                    foreground = foreground,
                    accentForeground = foreground,
                )
                Spacer(GlanceModifier.width(gap))
                PlayPauseButton(
                    context = context,
                    snapshot = snapshot,
                    buttonSize = buttonSize,
                    iconSize = playIconSize,
                    foreground = foreground,
                    accentForeground = foreground,
                )
                Spacer(GlanceModifier.width(gap))
                TransportButton(
                    context = context,
                    iconRes = R.drawable.ic_widget_next,
                    action = WidgetPlaybackActions.NEXT,
                    contentDescription = "Next",
                    buttonSize = buttonSize,
                    iconSize = sideIconSize,
                    foreground = foreground,
                    accentForeground = foreground,
                )
            }
        }
    }

    @Composable
    private fun ArtworkStyleSurface(content: @Composable () -> Unit) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(SURFACE_INSET),
        ) {
            content()
        }
    }

    @Composable
    private fun CompactMinimalLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(title, style = titleStyle(14.sp), maxLines = 1)
                Spacer(GlanceModifier.height(1.dp))
                Text(artist, style = secondaryStyle(11.sp), maxLines = 1)
            }
            if (snapshot.mediaId.isNotBlank()) {
                PlayPauseButton(context, snapshot, buttonSize = 48.dp, iconSize = 24.dp)
            }
        }
    }

    @Composable
    private fun CompactStandardLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artwork, 32.dp)
            Spacer(GlanceModifier.width(6.dp))
            Metadata(
                title = title,
                artist = artist,
                modifier = GlanceModifier.defaultWeight(),
                compact = true,
            )
            if (snapshot.mediaId.isNotBlank()) {
                CompactTransportControls(context, snapshot, buttonSize = 38.dp, iconSize = 21.dp)
            }
        }
    }

    @Composable
    private fun CompactWideLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artwork, 44.dp)
            Spacer(GlanceModifier.width(12.dp))
            Metadata(
                title = title,
                artist = artist,
                album = snapshot.album,
                modifier = GlanceModifier.defaultWeight(),
                compact = true,
                combineSecondary = true,
            )
            if (snapshot.mediaId.isNotBlank()) {
                CompactTransportControls(context, snapshot, buttonSize = 44.dp, iconSize = 24.dp)
            }
        }
    }

    @Composable
    private fun ArtworkSquareLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        val size = LocalSize.current
        val artSize = minOf(
            72.dp,
            (size.width - 20.dp).coerceAtLeast(52.dp),
            (size.height - 76.dp).coerceAtLeast(52.dp),
        )
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Artwork(artwork, artSize)
            Spacer(GlanceModifier.height(6.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(title, style = titleStyle(14.sp), maxLines = 1)
                    Spacer(GlanceModifier.height(1.dp))
                    Text(artist, style = secondaryStyle(11.sp), maxLines = 1)
                }
                if (snapshot.mediaId.isNotBlank()) {
                    PlayPauseButton(context, snapshot, buttonSize = 48.dp, iconSize = 26.dp)
                }
            }
        }
    }

    @Composable
    private fun MediumInfoLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artSize = minOf(76.dp, (LocalSize.current.height - 24.dp).coerceAtLeast(52.dp))
            Artwork(artwork, artSize)
            Spacer(GlanceModifier.width(12.dp))
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            ) {
                Metadata(
                    title = title,
                    artist = artist,
                    album = snapshot.album,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
                Spacer(GlanceModifier.defaultWeight())
                                if (snapshot.mediaId.isNotBlank()) {
                    TransportControls(
                        context = context,
                        snapshot = snapshot,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Composable
    private fun WideCompleteLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artSize = minOf(92.dp, (LocalSize.current.height - 32.dp).coerceAtLeast(64.dp))
            Artwork(artwork, artSize)
            Spacer(GlanceModifier.width(16.dp))
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            ) {
                Metadata(
                    title = title,
                    artist = artist,
                    album = snapshot.album,
                    modifier = GlanceModifier.fillMaxWidth(),
                    largeTitle = true,
                )
                Spacer(GlanceModifier.defaultWeight())
                                if (snapshot.mediaId.isNotBlank()) {
                    TransportControls(
                        context = context,
                        snapshot = snapshot,
                        modifier = GlanceModifier.fillMaxWidth(),
                        playIconSize = 30.dp,
                    )
                }
            }
        }
    }

    @Composable
    private fun ArtworkPortraitLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
        ) {
            val artSize = when {
                LocalSize.current.height < 220.dp -> 72.dp
                LocalSize.current.height < 260.dp -> 96.dp
                else -> 132.dp
            }
            Artwork(artwork, artSize)
            Spacer(GlanceModifier.height(12.dp))
            Text(title, style = titleStyle(17.sp), maxLines = 1)
            Spacer(GlanceModifier.height(2.dp))
            Text(artist, style = secondaryStyle(13.sp), maxLines = 1)
            if (snapshot.album.isNotBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(snapshot.album, style = tertiaryStyle(12.sp), maxLines = 1)
            }
            if (snapshot.mediaId.isNotBlank()) {
                Spacer(GlanceModifier.height(12.dp))
                PlayPauseButton(context, snapshot, buttonSize = 48.dp, iconSize = 30.dp)
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }

    @Composable
    private fun LargeSquareLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        val size = LocalSize.current
        val artSize = minOf(
            112.dp,
            (size.width - 48.dp).coerceAtLeast(88.dp),
            (size.height - 132.dp).coerceAtLeast(88.dp),
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Artwork(artwork, artSize)
            Spacer(GlanceModifier.height(10.dp))
            Metadata(
                title = title,
                artist = artist,
                album = snapshot.album,
                modifier = GlanceModifier.fillMaxWidth(),
                largeTitle = true,
            )
            if (snapshot.mediaId.isNotBlank()) {
                Spacer(GlanceModifier.height(10.dp))
                TightTransportControls(
                    context = context,
                    snapshot = snapshot,
                    modifier = GlanceModifier.fillMaxWidth(),
                    buttonSize = 44.dp,
                    gap = 10.dp,
                    playIconSize = 30.dp,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }
    @Composable
    private fun HiFiPanelLayout(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        title: String,
        artist: String,
        artwork: Bitmap?,
    ) {
        val size = LocalSize.current
        val artSize = when {
            size.height < 220.dp -> 116.dp
            size.height < 280.dp -> 132.dp
            else -> 144.dp
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp),
        ) {
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artwork, artSize)
                Spacer(GlanceModifier.width(16.dp))
                Metadata(
                    title = title,
                    artist = artist,
                    album = snapshot.album,
                    modifier = GlanceModifier.defaultWeight(),
                    largeTitle = true,
                )
            }
            if (snapshot.mediaId.isNotBlank()) {
                Spacer(GlanceModifier.height(10.dp))
                TightTransportControls(
                    context = context,
                    snapshot = snapshot,
                    modifier = GlanceModifier.fillMaxWidth(),
                    buttonSize = 48.dp,
                    gap = 12.dp,
                    playIconSize = 32.dp,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }
    @Composable
    private fun Metadata(
        title: String,
        artist: String,
        modifier: GlanceModifier,
        album: String = "",
        compact: Boolean = false,
        combineSecondary: Boolean = false,
        largeTitle: Boolean = false,
    ) {
        Column(modifier = modifier) {
            Text(
                text = title,
                style = titleStyle(if (largeTitle) 18.sp else if (compact) 13.sp else 16.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(if (compact) 2.dp else 4.dp))
            val secondary = if (combineSecondary && album.isNotBlank()) "$artist 路 $album" else artist
            Text(
                text = secondary,
                style = secondaryStyle(if (compact) 11.sp else 13.sp),
                maxLines = 1,
            )
            if (!combineSecondary && album.isNotBlank() && !compact) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = album,
                    style = tertiaryStyle(12.sp),
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun Artwork(artwork: Bitmap?, size: Dp) {
        val colors = micaWidgetColors()
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = GlanceModifier.size(size).background(colors.artworkPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music),
                    contentDescription = null,
                    modifier = GlanceModifier.size(size / 2),
                    colorFilter = ColorFilter.tint(colors.tertiary),
                )
            }
        }
    }

    @Composable
    private fun CompactTransportControls(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        buttonSize: Dp,
        iconSize: Dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportButton(
                context = context,
                iconRes = R.drawable.ic_widget_previous,
                action = WidgetPlaybackActions.PREVIOUS,
                contentDescription = "Previous",
                buttonSize = buttonSize,
                iconSize = iconSize,
            )
            PlayPauseButton(context, snapshot, buttonSize, iconSize + 2.dp)
            TransportButton(
                context = context,
                iconRes = R.drawable.ic_widget_next,
                action = WidgetPlaybackActions.NEXT,
                contentDescription = "Next",
                buttonSize = buttonSize,
                iconSize = iconSize,
            )
        }
    }

    @Composable
    private fun TightTransportControls(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        modifier: GlanceModifier = GlanceModifier,
        buttonSize: Dp = 48.dp,
        gap: Dp = 8.dp,
        playIconSize: Dp = 30.dp,
    ) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TransportButton(
                    context = context,
                    iconRes = R.drawable.ic_widget_previous,
                    action = WidgetPlaybackActions.PREVIOUS,
                    contentDescription = "Previous",
                    buttonSize = buttonSize,
                    iconSize = 24.dp,
                )
                Spacer(GlanceModifier.width(gap))
                PlayPauseButton(context, snapshot, buttonSize, playIconSize)
                Spacer(GlanceModifier.width(gap))
                TransportButton(
                    context = context,
                    iconRes = R.drawable.ic_widget_next,
                    action = WidgetPlaybackActions.NEXT,
                    contentDescription = "Next",
                    buttonSize = buttonSize,
                    iconSize = 24.dp,
                )
            }
        }
    }
    @Composable
    private fun TransportControls(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        modifier: GlanceModifier = GlanceModifier,
        playIconSize: Dp = 28.dp,
    ) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                context = context,
                iconRes = R.drawable.ic_widget_previous,
                action = WidgetPlaybackActions.PREVIOUS,
                contentDescription = "Previous",
                buttonSize = 48.dp,
                iconSize = 24.dp,
            )
            Spacer(GlanceModifier.defaultWeight())
            PlayPauseButton(context, snapshot, 48.dp, playIconSize)
            Spacer(GlanceModifier.defaultWeight())
            TransportButton(
                context = context,
                iconRes = R.drawable.ic_widget_next,
                action = WidgetPlaybackActions.NEXT,
                contentDescription = "Next",
                buttonSize = 48.dp,
                iconSize = 24.dp,
            )
        }
    }

    @Composable
    private fun PlayPauseButton(
        context: Context,
        snapshot: WidgetPlaybackSnapshot,
        buttonSize: Dp,
        iconSize: Dp,
        foreground: ColorProvider? = null,
        accentForeground: ColorProvider? = null,
    ) {
        TransportButton(
            context = context,
            iconRes = if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            action = WidgetPlaybackActions.PLAY_PAUSE,
            contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
            buttonSize = buttonSize,
            iconSize = iconSize,
            accent = snapshot.isPlaying,
            foreground = foreground,
            accentForeground = accentForeground,
        )
    }

    @Composable
    private fun TransportButton(
        context: Context,
        iconRes: Int,
        action: String,
        contentDescription: String,
        buttonSize: Dp,
        iconSize: Dp,
        accent: Boolean = false,
        foreground: ColorProvider? = null,
        accentForeground: ColorProvider? = null,
    ) {
        val colors = micaWidgetColors()
        val normalColor = foreground ?: colors.title
        val activeColor = accentForeground ?: colors.accent
        val clickAction = actionStartService(
            intent = WidgetPlaybackActions.serviceIntent(context, action),
            isForegroundService = true,
        )
        Box(
            modifier = GlanceModifier
                .size(buttonSize)
                .clickable(clickAction),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(iconSize),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(if (accent) activeColor else normalColor),
            )
        }
    }

    @Composable
    private fun WidgetSurface(content: @Composable () -> Unit) {
        val colors = micaWidgetColors()
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(SURFACE_INSET),
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.background),
            ) {
                content()
            }
        }
    }

    @Composable
    private fun titleStyle(size: androidx.compose.ui.unit.TextUnit): TextStyle {
        val colors = micaWidgetColors()
        return TextStyle(
            color = colors.title,
            fontSize = size,
            fontWeight = FontWeight.Medium,
        )
    }

    @Composable
    private fun secondaryStyle(size: androidx.compose.ui.unit.TextUnit): TextStyle {
        val colors = micaWidgetColors()
        return TextStyle(
            color = colors.secondary,
            fontSize = size,
        )
    }

    @Composable
    private fun tertiaryStyle(size: androidx.compose.ui.unit.TextUnit): TextStyle {
        val colors = micaWidgetColors()
        return TextStyle(
            color = colors.tertiary,
            fontSize = size,
        )
    }

    private fun widthClass(width: Dp): WidgetWidthClass = when {
        width < MEDIUM_WIDTH_THRESHOLD -> WidgetWidthClass.NARROW
        width < WIDE_WIDTH_THRESHOLD -> WidgetWidthClass.MEDIUM
        else -> WidgetWidthClass.WIDE
    }

    private fun heightClass(height: Dp): WidgetHeightClass = when {
        height < MEDIUM_HEIGHT_THRESHOLD -> WidgetHeightClass.COMPACT
        height < EXPANDED_HEIGHT_THRESHOLD -> WidgetHeightClass.MEDIUM
        else -> WidgetHeightClass.EXPANDED
    }

    private companion object {
        val MEDIUM_WIDTH_THRESHOLD = 180.dp
        val WIDE_WIDTH_THRESHOLD = 280.dp
        val MEDIUM_HEIGHT_THRESHOLD = 88.dp
        val EXPANDED_HEIGHT_THRESHOLD = 180.dp
        val SURFACE_INSET = 4.dp
        const val ARTWORK_BASE_WIDTH_DP = 160f
        const val ARTWORK_MAX_SCALE = 1.55f
        const val ARTWORK_MIN_SCALE = 0.85f
        const val ARTWORK_BASE_OVERLAY_HEIGHT_DP = 120f
    }
}

class MicaPlaybackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicaPlaybackWidget()
}

class MicaArtworkPlaybackWidget : MicaPlaybackWidget(PlaybackWidgetStyle.ARTWORK)

class MicaArtworkPlaybackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicaArtworkPlaybackWidget()
}

class MicaMinimalSquarePlaybackWidget : MicaPlaybackWidget(PlaybackWidgetStyle.MINIMAL)

class MicaMinimalSquarePlaybackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicaMinimalSquarePlaybackWidget()
}

private enum class WidgetWidthClass { NARROW, MEDIUM, WIDE }
private enum class WidgetHeightClass { COMPACT, MEDIUM, EXPANDED }

private data class MicaWidgetColors(
    val background: ColorProvider,
    val title: ColorProvider,
    val secondary: ColorProvider,
    val tertiary: ColorProvider,
    val accent: ColorProvider,
    val divider: ColorProvider,
    val artworkPlaceholder: ColorProvider,
)

@Composable
private fun micaWidgetColors(): MicaWidgetColors = MicaWidgetColors(
    background = ColorProvider(R.color.widget_background),
    title = ColorProvider(R.color.widget_title),
    secondary = ColorProvider(R.color.widget_secondary),
    tertiary = ColorProvider(R.color.widget_tertiary),
    accent = ColorProvider(R.color.widget_accent),
    divider = ColorProvider(R.color.widget_divider),
    artworkPlaceholder = ColorProvider(R.color.widget_artwork_placeholder),
)
