package com.mica.music.media

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.app.Service
import android.os.IBinder
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mica.music.MicaApp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CustomMicaBackground
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.ui.theme.MicaTheme

object DesktopLyricsOverlayController {
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun start(context: Context): Boolean {
        if (!canDrawOverlays(context)) return false
        context.startService(Intent(context, DesktopLyricsOverlayService::class.java))
        return true
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
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownX = 0
    private var touchDownY = 0

    override fun onCreate() {
        super.onCreate()
        if (!DesktopLyricsOverlayController.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val micaApp = application as MicaApp
        val uiSettings = AppUiSettings(this)
        windowManager = getSystemService(WindowManager::class.java)
        val metrics = resources.displayMetrics
        val density = metrics.density
        val defaultY = (metrics.heightPixels * 0.78f).toInt()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = LyricsPreferences.desktopLyricsX(this@DesktopLyricsOverlayService)
            y = LyricsPreferences.desktopLyricsY(this@DesktopLyricsOverlayService)
                .takeIf { it >= 0 } ?: defaultY
        }

        overlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setOnTouchListener(::handleTouch)
            setContent {
                DesktopLyricsOverlayContent(
                    stateStore = micaApp.desktopLyricsOverlayStateStore,
                    uiSettings = uiSettings,
                    maxWidthDp = (metrics.widthPixels / density * 0.86f).dp,
                )
            }
        }
        windowManager.addView(overlayView, layoutParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (DesktopLyricsOverlayController.canDrawOverlays(this) &&
            LyricsPreferences.desktopLyricsEnabled(this)
        ) {
            START_STICKY
        } else {
            stopSelfResult(startId)
            START_NOT_STICKY
        }

    override fun onDestroy() {
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeViewImmediate(overlayView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleTouch(view: android.view.View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchDownX = layoutParams.x
                touchDownY = layoutParams.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val metrics = resources.displayMetrics
                layoutParams.x = (touchDownX + event.rawX - touchDownRawX)
                    .toInt()
                    .coerceIn(-metrics.widthPixels / 2, metrics.widthPixels / 2)
                layoutParams.y = (touchDownY + event.rawY - touchDownRawY)
                    .toInt()
                    .coerceIn(0, metrics.heightPixels)
                windowManager.updateViewLayout(view, layoutParams)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                LyricsPreferences.setDesktopLyricsX(this, layoutParams.x)
                LyricsPreferences.setDesktopLyricsY(this, layoutParams.y)
                return true
            }
        }
        return false
    }
}

@Composable
private fun DesktopLyricsOverlayContent(
    stateStore: DesktopLyricsOverlayStateStore,
    uiSettings: AppUiSettings,
    maxWidthDp: androidx.compose.ui.unit.Dp,
) {
    val state by stateStore.state.collectAsState()
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
            modifier = if (state.visible) {
                Modifier
                    .widthIn(max = maxWidthDp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            } else {
                Modifier.size(1.dp)
            },
        ) {
            if (state.visible) {
                Text(
                    text = state.text.orEmpty(),
                    style = MicaTheme.typography.lyricCurrent.copy(
                        fontSize = uiSettings.lyricsPageFontSizeSp.sp,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.9f),
                            blurRadius = 8f,
                        ),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
