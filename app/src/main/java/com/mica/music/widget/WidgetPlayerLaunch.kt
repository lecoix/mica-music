package com.mica.music.widget

import android.content.Context
import android.content.Intent
import com.mica.music.MainActivity

internal const val ACTION_OPEN_PLAYER_FROM_WIDGET = "com.mica.music.action.OPEN_PLAYER_FROM_WIDGET"

internal fun widgetPlayerIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_PLAYER_FROM_WIDGET)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun isWidgetPlayerLaunch(intent: Intent?): Boolean =
    intent?.action == ACTION_OPEN_PLAYER_FROM_WIDGET
