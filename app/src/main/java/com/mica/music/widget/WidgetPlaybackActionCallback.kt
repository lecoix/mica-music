package com.mica.music.widget

import android.content.Context
import android.content.Intent
import com.mica.music.media.MicaMediaService

internal object WidgetPlaybackActions {
    const val PREVIOUS = MicaMediaService.ACTION_WIDGET_PREVIOUS
    const val PLAY_PAUSE = MicaMediaService.ACTION_WIDGET_PLAY_PAUSE
    const val NEXT = MicaMediaService.ACTION_WIDGET_NEXT

    fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, MicaMediaService::class.java).setAction(action)
}
