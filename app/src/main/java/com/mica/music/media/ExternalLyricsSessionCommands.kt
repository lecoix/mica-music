package com.mica.music.media

import android.content.Context
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.mica.music.R
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.preferences.LyricsPreferences

internal object ExternalLyricsSessionCommands {
    const val TOGGLE_DESKTOP_LOCK_ACTION =
        "com.mica.music.action.TOGGLE_DESKTOP_LYRICS_LOCK"

    val toggleDesktopLock: SessionCommand by lazy {
        SessionCommand(TOGGLE_DESKTOP_LOCK_ACTION, Bundle.EMPTY)
    }

    internal fun shouldExposeDesktopLock(mode: ExternalLyricsMode): Boolean =
        mode == ExternalLyricsMode.DESKTOP

    internal fun desktopLockButtonLabel(locked: Boolean): String =
        if (locked) "解锁桌面歌词" else "锁定桌面歌词"

    internal fun desktopLockButtonIcon(locked: Boolean): Int =
        if (locked) R.drawable.ic_desktop_unlock else R.drawable.ic_desktop_lock

    fun mediaButtonPreferences(context: Context): ImmutableList<CommandButton> {
        if (!shouldExposeDesktopLock(LyricsPreferences.externalLyricsMode(context))) {
            return ImmutableList.of()
        }
        val locked = LyricsPreferences.desktopLyricsLocked(context)
        return ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(desktopLockButtonIcon(locked))
                .setDisplayName(
                    desktopLockButtonLabel(locked),
                )
                .setSessionCommand(toggleDesktopLock)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }
}
