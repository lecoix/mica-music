package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal object DirectDsdPrototypeSessionCommand {
    const val ACTION = "com.mica.music.action.DEBUG_DIRECT_DSD_PROTOTYPE"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val RESULT_ACTION_SUFFIX = ".debug.USB_DIRECT_DSD_REBUILD_RESULT"

    val command = SessionCommand(ACTION, Bundle.EMPTY)

    fun resultAction(packageName: String): String = packageName + RESULT_ACTION_SUFFIX
}
