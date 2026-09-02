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
    const val TOGGLE_DESKTOP_LYRICS_ACTION =
        "com.mica.music.action.TOGGLE_DESKTOP_LYRICS"
    const val TOGGLE_DESKTOP_LOCK_ACTION =
        "com.mica.music.action.TOGGLE_DESKTOP_LYRICS_LOCK"

    val toggleDesktopLyrics: SessionCommand by lazy {
        SessionCommand(TOGGLE_DESKTOP_LYRICS_ACTION, Bundle.EMPTY)
    }

    val toggleDesktopLock: SessionCommand by lazy {
        SessionCommand(TOGGLE_DESKTOP_LOCK_ACTION, Bundle.EMPTY)
    }

    internal fun nextModeAfterDesktopToggle(mode: ExternalLyricsMode): ExternalLyricsMode =
        if (mode == ExternalLyricsMode.DESKTOP) ExternalLyricsMode.OFF else ExternalLyricsMode.DESKTOP

    internal fun shouldExposeDesktopLock(mode: ExternalLyricsMode): Boolean =
        mode == ExternalLyricsMode.DESKTOP

    internal fun desktopToggleButtonLabel(mode: ExternalLyricsMode): String =
        if (mode == ExternalLyricsMode.DESKTOP) "关闭桌面歌词" else "开启桌面歌词"

    internal fun desktopToggleButtonIcon(mode: ExternalLyricsMode): Int =
        if (mode == ExternalLyricsMode.DESKTOP) {
            R.drawable.ic_desktop_lyrics_on
        } else {
            R.drawable.ic_desktop_lyrics_off
        }

    internal fun desktopLockButtonLabel(locked: Boolean): String =
        if (locked) "解锁桌面歌词" else "锁定桌面歌词"

    internal fun desktopLockButtonIcon(locked: Boolean): Int =
        if (locked) R.drawable.ic_desktop_unlock else R.drawable.ic_desktop_lock

    fun mediaButtonPreferences(
        context: Context,
        overlayAvailable: Boolean,
    ): ImmutableList<CommandButton> {
        if (!overlayAvailable) {
            return ImmutableList.of()
        }
        val mode = LyricsPreferences.externalLyricsMode(context)
        val buttons = ImmutableList.builder<CommandButton>()
            .add(
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(desktopToggleButtonIcon(mode))
                    .setDisplayName(desktopToggleButtonLabel(mode))
                    .setSessionCommand(toggleDesktopLyrics)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        if (shouldExposeDesktopLock(mode)) {
            val locked = LyricsPreferences.desktopLyricsLocked(context)
            buttons.add(
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(desktopLockButtonIcon(locked))
                    .setDisplayName(desktopLockButtonLabel(locked))
                    .setSessionCommand(toggleDesktopLock)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }
        return buttons.build()
    }
}
