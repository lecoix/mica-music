package com.mica.music.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.LyricsBilingualDisplayMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsPreferencesMigrationTest {
    private val prefName = "mica_settings"
    private val splitKey = "lyric_split_enabled"
    private val modeKey = "lyrics_bilingual_display_mode"
    private val migrationKey = "lyrics_split_language_decoupled_v1"

    private fun withSavedSettings(block: (Context) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val before = listOf(splitKey, modeKey, migrationKey).associateWith { key ->
            if (pref.contains(key)) pref.all[key] else null
        }
        try {
            pref.edit().remove(splitKey).remove(modeKey).remove(migrationKey).commit()
            block(ctx)
        } finally {
            val e = pref.edit()
            for ((key, value) in before) when (value) {
                null -> e.remove(key)
                is Boolean -> e.putBoolean(key, value)
                is String -> e.putString(key, value)
                else -> error("unsupported preference type")
            }
            assertTrue(e.commit())
        }
    }

    @Test fun legacyOffWithDormantTranslationMigratesToAllExactlyOnce() = withSavedSettings { ctx ->
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        assertTrue(pref.edit().putBoolean(splitKey, false)
            .putString(modeKey, LyricsBilingualDisplayMode.TRANSLATION.storageValue).commit())
        assertEquals(LyricsBilingualDisplayMode.ALL, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
        assertFalse(LyricsPreferences.lyricSplitEnabled(ctx))
        assertTrue(pref.getBoolean(migrationKey, false))
        LyricsPreferences.setLyricSplitEnabled(ctx, true)
        assertEquals(LyricsBilingualDisplayMode.ALL, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
        LyricsPreferences.setLyricsBilingualDisplayMode(ctx, LyricsBilingualDisplayMode.TRANSLATION)
        LyricsPreferences.setLyricSplitEnabled(ctx, false)
        assertEquals(LyricsBilingualDisplayMode.TRANSLATION, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
    }

    @Test fun legacyEnabledOriginalSelectionIsPreserved() = withSavedSettings { ctx ->
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        assertTrue(pref.edit().putBoolean(splitKey, true)
            .putString(modeKey, LyricsBilingualDisplayMode.ORIGINAL.storageValue).commit())
        assertEquals(LyricsBilingualDisplayMode.ORIGINAL, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
        assertTrue(pref.getBoolean(migrationKey, false))
        LyricsPreferences.setLyricSplitEnabled(ctx, false)
        assertEquals(LyricsBilingualDisplayMode.ORIGINAL, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
    }

    @Test fun newInstallHasIndependentDefaults() = withSavedSettings { ctx ->
        assertTrue(LyricsPreferences.lyricSplitEnabled(ctx))
        assertEquals(LyricsBilingualDisplayMode.ALL, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
        LyricsPreferences.setLyricSplitEnabled(ctx, false)
        LyricsPreferences.setLyricsBilingualDisplayMode(ctx, LyricsBilingualDisplayMode.TRANSLATION)
        assertEquals(LyricsBilingualDisplayMode.TRANSLATION, LyricsPreferences.lyricsBilingualDisplayMode(ctx))
    }
}
