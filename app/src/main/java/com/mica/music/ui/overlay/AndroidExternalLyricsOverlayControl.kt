package com.mica.music.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.externallyrics.ExternalLyricsOverlayControl

internal class AndroidExternalLyricsOverlayControl(
    context: Context,
) : ExternalLyricsOverlayControl {
    private val appContext = context.applicationContext

    override fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    override fun start(): Boolean {
        if (!canDrawOverlays()) return false
        appContext.startService(Intent(appContext, DesktopLyricsOverlayService::class.java))
        return true
    }

    override fun sync(): Boolean {
        val enabled = LyricsPreferences.externalLyricsMode(appContext) != ExternalLyricsMode.OFF
        return if (enabled) start() else {
            stop()
            true
        }
    }

    override fun refreshPosition() {
        if (canDrawOverlays()) {
            appContext.startService(Intent(appContext, DesktopLyricsOverlayService::class.java))
        }
    }

    override fun refreshSettings() {
        if (canDrawOverlays()) {
            appContext.startService(
                Intent(appContext, DesktopLyricsOverlayService::class.java)
                    .setAction(DesktopLyricsOverlayService.ACTION_APPLY_SETTINGS),
            )
        }
    }

    override fun stop() {
        appContext.stopService(Intent(appContext, DesktopLyricsOverlayService::class.java))
    }

    override fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure {
                Toast.makeText(appContext, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
            }
    }
}
