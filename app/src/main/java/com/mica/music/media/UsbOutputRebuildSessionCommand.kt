package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal object UsbOutputRebuildSessionCommand {
    const val ACTION = "com.mica.music.action.REBUILD_USB_OUTPUT"
    val command = SessionCommand(ACTION, Bundle.EMPTY)
}
