package com.mica.music.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mica.music.MicaApp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CustomMicaBackground
import com.mica.music.data.ExternalLyricsColorMode
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.ExternalLyricsStyle
import com.mica.music.data.ExternalLyricsVisibilityMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsSync
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.ui.components.marqueeHorizontalEdgeFade
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object DesktopLyricsOverlayController {
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun start(context: Context): Boolean {
        if (!canDrawOverlays(context)) return false
        context.startService(Intent(context, DesktopLyricsOverlayService::class.java))
        return true
    }

    fun sync(context: Context): Boolean {
        val enabled = LyricsPreferences.externalLyricsMode(context) != ExternalLyricsMode.OFF
        return if (enabled) start(context) else {
            stop(context)
            true
        }
    }

    fun refreshPosition(context: Context) {
        if (canDrawOverlays(context)) {
            context.startService(Intent(context, DesktopLyricsOverlayService::class.java))
        }
    }

    fun refreshSettings(context: Context) {
        if (canDrawOverlays(context)) {
            context.startService(
                Intent(context, DesktopLyricsOverlayService::class.java)
                    .setAction(DesktopLyricsOverlayService.ACTION_APPLY_SETTINGS),
            )
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, DesktopLyricsOverlayService::class.java))
    }

    fun openPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
            }
    }
}

class DesktopLyricsOverlayService : Service() {
    companion object {
        const val ACTION_APPLY_SETTINGS = "com.mica.music.action.APPLY_DESKTOP_LYRICS_SETTINGS"
        private const val CONTROL_PANEL_TIMEOUT_MS = 4_000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var overlayRoot: LinearLayout
    private lateinit var controlPanelView: LinearLayout
    private lateinit var statusBarView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var statusBarLayoutParams: WindowManager.LayoutParams
    private lateinit var lifecycleOwner: DesktopLyricsOverlayLifecycleOwner
    private lateinit var stateStore: DesktopLyricsOverlayStateStore
    private var startupFailed = false
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownX = 0
    private var touchDownY = 0
    private var touchMoved = false
    private var desktopLocked = false
    private var controlsVisible = false
    private val hideControlsRunnable = Runnable { hideControlPanel() }

    override fun onCreate() {
        super.onCreate()
        if (!DesktopLyricsOverlayController.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            val micaApp = application as MicaApp
            stateStore = micaApp.desktopLyricsOverlayStateStore
            val uiSettings = AppUiSettings(this)
            windowManager = getSystemService(WindowManager::class.java)
            desktopLocked = LyricsPreferences.desktopLyricsLocked(this)
            val metrics = resources.displayMetrics
            val density = metrics.density
            val defaultY = (metrics.heightPixels * 0.78f).toInt()
            layoutParams = WindowManager.LayoutParams(
                desktopLyricsWindowWidthPx(metrics),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    if (desktopLocked || LyricsPreferences.externalLyricsMode(this) != ExternalLyricsMode.DESKTOP) {
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        0
                    },
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = LyricsPreferences.desktopLyricsX(this@DesktopLyricsOverlayService)
                y = LyricsPreferences.desktopLyricsY(this@DesktopLyricsOverlayService)
                    .takeIf { it >= 0 } ?: defaultY
            }
            statusBarLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                statusBarLyricsWindowFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarLyricsY(density)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            lifecycleOwner = DesktopLyricsOverlayLifecycleOwner().also { it.start() }
            overlayView = createComposeView(
                uiSettings = uiSettings,
                stateStore = stateStore,
                surface = ExternalLyricsSurface.DESKTOP,
                screenWidthDp = (metrics.widthPixels / density).dp,
            ).apply { setOnTouchListener(::handleTouch) }
            controlPanelView = createDesktopControlPanel()
            overlayRoot = LinearLayout(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(overlayView)
                addView(
                    controlPanelView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) },
                )
            }
            statusBarView = createComposeView(
                uiSettings = uiSettings,
                stateStore = stateStore,
                surface = ExternalLyricsSurface.STATUS_BAR,
                screenWidthDp = (metrics.widthPixels / density).dp,
            )
            windowManager.addView(overlayRoot, layoutParams)
            windowManager.addView(statusBarView, statusBarLayoutParams)

        } catch (error: Throwable) {
            startupFailed = true
            DiagnosticLog.event("DesktopLyrics", "overlay startup failed", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = LyricsPreferences.externalLyricsMode(this) != ExternalLyricsMode.OFF
        if (!startupFailed &&
            DesktopLyricsOverlayController.canDrawOverlays(this) &&
            enabled
        ) {
            updateDesktopWindowWidth()
            applyDesktopTouchMode(LyricsPreferences.desktopLyricsLocked(this))
            if (::statusBarLayoutParams.isInitialized && ::statusBarView.isInitialized) {
                statusBarLayoutParams.y = statusBarLyricsY(resources.displayMetrics.density)
                runCatching { windowManager.updateViewLayout(statusBarView, statusBarLayoutParams) }
            }
            return START_STICKY
        }
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::overlayRoot.isInitialized && overlayRoot.isAttachedToWindow) {
            windowManager.removeViewImmediate(overlayRoot)
        }
        if (::statusBarView.isInitialized && statusBarView.isAttachedToWindow) {
            windowManager.removeViewImmediate(statusBarView)
        }
        if (::lifecycleOwner.isInitialized) lifecycleOwner.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createComposeView(
        uiSettings: AppUiSettings,
        stateStore: DesktopLyricsOverlayStateStore,
        surface: ExternalLyricsSurface,
        screenWidthDp: androidx.compose.ui.unit.Dp,
    ): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            DesktopLyricsOverlayContent(
                stateStore = stateStore,
                uiSettings = uiSettings,
                surface = surface,
                screenWidthDp = screenWidthDp,
            )
        }
    }

    private fun statusBarLyricsY(density: Float): Int {
        return (LyricsPreferences.statusBarLyricsTopOffsetDp(this) * density).toInt()
    }

    private fun desktopLyricsWindowWidthPx(metrics: android.util.DisplayMetrics): Int =
        if (LyricsPreferences.externalLyricsMode(this) != ExternalLyricsMode.DESKTOP) {
            dp(1)
        } else {
            (metrics.widthPixels * LyricsPreferences.desktopLyricsWidthPercent(this) / 100f)
                .roundToInt()
                .coerceAtLeast(dp(1))
        }

    private fun updateDesktopWindowWidth() {
        if (!::layoutParams.isInitialized || !::overlayRoot.isInitialized) return
        val width = desktopLyricsWindowWidthPx(resources.displayMetrics)
        if (layoutParams.width == width) return
        layoutParams.width = width
        if (overlayRoot.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(overlayRoot, layoutParams) }
        }
    }

    private fun createDesktopControlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(dp(4), dp(2), dp(4), dp(2))

        addView(
            ImageButton(this@DesktopLyricsOverlayService).apply {
                setImageResource(com.mica.music.R.drawable.ic_desktop_lock)
                setColorFilter(AndroidColor.WHITE)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                contentDescription = "锁定桌面歌词"
                setOnClickListener { setDesktopLocked(true) }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun showControlPanel() {
        if (desktopLocked || !::controlPanelView.isInitialized || controlsVisible) return
        controlsVisible = true
        controlPanelView.visibility = View.VISIBLE
        controlPanelView.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(AndroidColor.argb(170, 18, 18, 18))
            setStroke(dp(1), AndroidColor.argb(80, 255, 255, 255))
        }
        controlPanelView.setPadding(dp(10), dp(6), dp(10), dp(6))
        overlayRoot.removeCallbacks(hideControlsRunnable)
        overlayRoot.postDelayed(hideControlsRunnable, CONTROL_PANEL_TIMEOUT_MS)
    }

    private fun hideControlPanel() {
        if (!::controlPanelView.isInitialized || !::overlayRoot.isInitialized) return
        controlsVisible = false
        overlayRoot.removeCallbacks(hideControlsRunnable)
        controlPanelView.visibility = View.GONE
        controlPanelView.background = null
        controlPanelView.setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun setDesktopLocked(locked: Boolean) {
        LyricsPreferences.setDesktopLyricsLocked(this, locked)
        applyDesktopTouchMode(locked)
    }

    private fun handleTouch(view: android.view.View, event: MotionEvent): Boolean {
        if (desktopLocked) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchDownX = layoutParams.x
                touchDownY = layoutParams.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchDownRawX
                val deltaY = event.rawY - touchDownRawY
                if (!touchMoved &&
                    (abs(deltaX) >= dp(4) || abs(deltaY) >= dp(4))
                ) {
                    touchMoved = true
                }
                if (!touchMoved) return true
                val metrics = resources.displayMetrics
                layoutParams.x = (touchDownX + deltaX)
                    .toInt()
                    .coerceIn(-metrics.widthPixels / 2, metrics.widthPixels / 2)
                layoutParams.y = (touchDownY + deltaY)
                    .toInt()
                    .coerceIn(0, metrics.heightPixels)
                windowManager.updateViewLayout(overlayRoot, layoutParams)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchMoved) {
                    LyricsPreferences.setDesktopLyricsX(this, layoutParams.x)
                    LyricsPreferences.setDesktopLyricsY(this, layoutParams.y)
                } else if (controlsVisible) {
                    hideControlPanel()
                } else {
                    showControlPanel()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchMoved) {
                    LyricsPreferences.setDesktopLyricsX(this, layoutParams.x)
                    LyricsPreferences.setDesktopLyricsY(this, layoutParams.y)
                }
                return true
            }
        }
        return false
    }

    private fun applyDesktopTouchMode(locked: Boolean) {
        desktopLocked = locked
        val touchDisabled = locked || LyricsPreferences.externalLyricsMode(this) != ExternalLyricsMode.DESKTOP
        if (touchDisabled) hideControlPanel()
        if (!::layoutParams.isInitialized || !::overlayRoot.isInitialized) return
        val oldFlags = layoutParams.flags
        layoutParams.flags = if (touchDisabled) {
            oldFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            oldFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (layoutParams.flags != oldFlags && overlayRoot.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(overlayRoot, layoutParams) }
        }
    }

}

private enum class ExternalLyricsSurface {
    DESKTOP,
    STATUS_BAR,
}

private data class ExternalLyricsOverlayRenderState(
    val surfaceState: ExternalLyricsSurfaceState = ExternalLyricsSurfaceState(),
    val style: ExternalLyricsStyle = ExternalLyricsStyle(),
    val appInForeground: Boolean = false,
)

internal fun statusBarLyricsWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

/** Lifecycle bridge required by ComposeView when it is attached to a Service-owned window. */
internal class DesktopLyricsOverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = registry
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        savedStateController.performSave(Bundle())
    }
}

@Composable
private fun DesktopLyricsOverlayContent(
    stateStore: DesktopLyricsOverlayStateStore,
    uiSettings: AppUiSettings,
    surface: ExternalLyricsSurface,
    screenWidthDp: androidx.compose.ui.unit.Dp,
) {
    val renderState by remember(stateStore, surface) {
        stateStore.state
            .map { state ->
                ExternalLyricsOverlayRenderState(
                    surfaceState = if (surface == ExternalLyricsSurface.DESKTOP) state.desktop else state.statusBar,
                    style = state.style,
                    appInForeground = state.appInForeground,
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = ExternalLyricsOverlayRenderState())
    val surfaceState = renderState.surfaceState
    val style = renderState.style
    val hiddenInApp = style.visibilityMode == ExternalLyricsVisibilityMode.HIDE_WHEN_APP_FOREGROUND &&
        renderState.appInForeground
    val visible = surfaceState.visible && !hiddenInApp
    val originalFontSize = if (surface == ExternalLyricsSurface.DESKTOP) {
        style.desktopOriginalFontSizeSp
    } else {
        style.statusBarOriginalFontSizeSp
    }
    val translationFontSize = if (surface == ExternalLyricsSurface.DESKTOP) {
        style.desktopTranslationFontSizeSp
    } else {
        style.statusBarTranslationFontSizeSp
    }
    val widthPercent = if (surface == ExternalLyricsSurface.DESKTOP) {
        style.desktopWidthPercent
    } else {
        style.statusBarWidthPercent
    }
    val maxWidthDp = screenWidthDp * (widthPercent / 100f)
    val textAlignment = if (surface == ExternalLyricsSurface.STATUS_BAR) {
        style.statusBarTextAlignment
    } else {
        LyricsPageAlignment.CENTER
    }
    val bilingualDisplayMode = if (surface == ExternalLyricsSurface.DESKTOP) {
        style.desktopBilingualDisplayMode
    } else {
        style.statusBarBilingualDisplayMode
    }

    MicaTheme(
        darkTheme = uiSettings.isDarkTheme(),
        accentColor = uiSettings.accentColor,
        customAccentColorArgb = uiSettings.customAccentColorArgb,
        micaBackgroundPreset = uiSettings.micaBackgroundPreset,
        customMicaBackground = CustomMicaBackground(
            startArgb = uiSettings.customMicaStartArgb,
            endArgb = uiSettings.customMicaEndArgb,
            singleColor = uiSettings.customMicaSingleColor,
        ),
        customWallpaperPath = uiSettings.customWallpaperPath,
        coverDisplayMode = uiSettings.coverDisplayMode,
        lyricSplitEnabled = uiSettings.lyricSplitEnabled,
        lyricLineFillEnabled = uiSettings.lyricLineFillEnabled,
        globalFont = uiSettings.globalFont,
        lyricFont = uiSettings.lyricFont,
    ) {
        Box(
            modifier = if (visible) {
                Modifier
                    .then(if (surface == ExternalLyricsSurface.DESKTOP) {
                        Modifier.width(maxWidthDp)
                    } else {
                        Modifier.widthIn(max = maxWidthDp).fillMaxWidth()
                    })
                    .padding(horizontal = if (surface == ExternalLyricsSurface.DESKTOP) 5.dp else 8.dp, vertical = 2.dp)
            } else {
                Modifier.size(1.dp)
            },
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
                // AnimatedVisibility may keep this content alive during its exit transition,
                // after a song change has already cleared the current lyric line.
                surfaceState.line?.forExternalDisplay(bilingualDisplayMode)?.let { line ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = textAlignment.toHorizontalAlignment(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        line.original?.let { text ->
                            key(line.lineIndex, line.startMs, text.text) {
                                ExternalLyricsLineText(
                                    text = text,
                                    line = line,
                                    positionMs = surfaceState.positionMs,
                                    fontSizeSp = originalFontSize,
                                    style = style,
                                    marquee = true,
                                    textAlign = textAlignment.toTextAlign(),
                                )
                            }
                        }
                        line.translation?.let { text ->
                            key(line.lineIndex, line.startMs, text.text) {
                                ExternalLyricsLineText(
                                    text = text,
                                    line = line,
                                    positionMs = surfaceState.positionMs,
                                    fontSizeSp = translationFontSize,
                                    style = style,
                                    marquee = true,
                                    textAlign = textAlignment.toTextAlign(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExternalLyricsLineText(
    text: ExternalLyricsText,
    line: ExternalLyricsLine,
    positionMs: Int,
    fontSizeSp: Int,
    style: com.mica.music.data.ExternalLyricsStyle,
    marquee: Boolean,
    textAlign: TextAlign,
) {
    val textStyle = MicaTheme.typography.lyricCurrent.copy(
        fontSize = fontSizeSp.sp,
        textAlign = textAlign,
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            blurRadius = 8f,
        ),
    )
    val fillFraction = externalLyricsFillFraction(text, line, positionMs)
    // The coordinator supplies position samples at 10 Hz. Interpolate only real cue timelines
    // on the display clock; line-timed fallback remains an instant full-line reveal.
    val renderedFillFraction by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = tween(
            durationMillis = if (text.cues.isEmpty()) 0 else 100,
            easing = LinearEasing,
        ),
        label = "externalLyricsFill",
    )
    var textLayout by remember(text.text, fontSizeSp) { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val baseColor = Color(style.normalizedColors.first()).copy(alpha = 0.42f)

    val marqueeModifier = if (marquee) {
        Modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_200,
                repeatDelayMillis = 1_200,
            )
    } else {
        Modifier
    }
    Box(
        modifier = if (marquee) {
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .marqueeHorizontalEdgeFade()
        } else {
            Modifier
        },
    ) {
        Text(
            text = text.text,
            style = textStyle.copy(color = baseColor),
            maxLines = if (marquee) 1 else 3,
            softWrap = !marquee,
            overflow = if (marquee) TextOverflow.Visible else TextOverflow.Ellipsis,
            modifier = marqueeModifier,
        )
        Text(
            text = text.text,
            style = textStyle.copy(color = Color.White),
            maxLines = if (marquee) 1 else 3,
            softWrap = !marquee,
            overflow = if (marquee) TextOverflow.Visible else TextOverflow.Ellipsis,
            onTextLayout = { textLayout = it },
            modifier = marqueeModifier.drawWithContent {
                val layout = textLayout ?: return@drawWithContent
                if (renderedFillFraction <= 0f) return@drawWithContent
                var remainingFillPx = (0 until layout.lineCount).sumOf { index ->
                    (layout.getLineRight(index) - layout.getLineLeft(index)).toDouble()
                }.toFloat() * renderedFillFraction
                val filledPath = androidx.compose.ui.graphics.Path()
                var hasFilledArea = false
                for (index in 0 until layout.lineCount) {
                    val left = layout.getLineLeft(index)
                    val right = layout.getLineRight(index)
                    val width = (right - left).coerceAtLeast(0f)
                    if (width <= 0f || remainingFillPx <= 0f) continue
                    filledPath.addRect(
                        androidx.compose.ui.geometry.Rect(
                            left = left,
                            top = layout.getLineTop(index),
                            right = left + remainingFillPx.coerceAtMost(width),
                            bottom = layout.getLineBottom(index),
                        ),
                    )
                    hasFilledArea = true
                    remainingFillPx -= width
                }
                if (!hasFilledArea) return@drawWithContent
                val layerBounds = androidx.compose.ui.geometry.Rect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                )
                clipPath(filledPath) {
                    drawContext.canvas.saveLayer(layerBounds, androidx.compose.ui.graphics.Paint())
                    this@drawWithContent.drawContent()
                    drawRect(
                        brush = externalLyricsBrush(style, size),
                        blendMode = BlendMode.SrcIn,
                    )
                    drawContext.canvas.restore()
                }
            },
        )
    }
}

private fun externalLyricsBrush(
    style: com.mica.music.data.ExternalLyricsStyle,
    size: androidx.compose.ui.geometry.Size,
): Brush {
    val colors = style.normalizedColors.map(::Color)
    if (style.colorMode == ExternalLyricsColorMode.SINGLE || colors.size < 2) {
        return SolidColor(colors.first())
    }
    val radians = style.gradientAngleDegrees / 180f * Math.PI.toFloat()
    val direction = Offset(cos(radians), sin(radians))
    val center = Offset(size.width / 2f, size.height / 2f)
    // Project the actual text bounds onto the requested direction. This keeps all color stops
    // visible for both a wide desktop line and a compact two-line status-bar surface.
    val halfLength = (
        kotlin.math.abs(size.width * direction.x) +
            kotlin.math.abs(size.height * direction.y)
        ) / 2f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(center.x - direction.x * halfLength, center.y - direction.y * halfLength),
        end = Offset(center.x + direction.x * halfLength, center.y + direction.y * halfLength),
    )
}

private fun LyricsPageAlignment.toTextAlign(): TextAlign = when (this) {
    LyricsPageAlignment.START -> TextAlign.Start
    LyricsPageAlignment.CENTER -> TextAlign.Center
    LyricsPageAlignment.END -> TextAlign.End
}

private fun LyricsPageAlignment.toHorizontalAlignment(): Alignment.Horizontal = when (this) {
    LyricsPageAlignment.START -> Alignment.Start
    LyricsPageAlignment.CENTER -> Alignment.CenterHorizontally
    LyricsPageAlignment.END -> Alignment.End
}

internal fun externalLyricsFillFraction(
    text: ExternalLyricsText,
    line: ExternalLyricsLine,
    positionMs: Int,
): Float {
    val shiftedPosition = positionMs + LyricsSync.LEAD_MS
    if (text.cues.isEmpty()) {
        // A line-timed lyric is not a sequence of character cues. Once the line is active,
        // reveal the whole line at once so the fallback cannot look like word-by-word karaoke.
        return if (shiftedPosition >= line.startMs) 1f else 0f
    }
    if (shiftedPosition < text.cues.first().timeMs) return 0f
    val cueIndex = text.cues.indexOfLast { it.timeMs <= shiftedPosition }.coerceAtLeast(0)
    val cue = text.cues[cueIndex]
    val cueEnd = text.cues.getOrNull(cueIndex + 1)?.timeMs
        ?: line.endMs?.takeIf { it > cue.timeMs }
        ?: cue.timeMs + 500
    val progress = if (cueEnd <= cue.timeMs) 1f else {
        ((shiftedPosition - cue.timeMs).toFloat() / (cueEnd - cue.timeMs)).coerceIn(0f, 1f)
    }
    val totalCharacters = text.text.length.coerceAtLeast(1)
    val completedCharacters = text.cues.take(cueIndex).sumOf { it.text.length }
    return ((completedCharacters + cue.text.length * progress) / totalCharacters)
        .coerceIn(0f, 1f)
}
