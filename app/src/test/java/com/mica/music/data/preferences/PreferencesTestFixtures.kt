package com.mica.music.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import kotlinx.coroutines.runBlocking

internal object PreferencesTestFixtures {
    fun context(): Context = ApplicationProvider.getApplicationContext()

    fun clearMicaSettings(context: Context) {
        context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun clearAll(context: Context) {
        listOf(
            "mica_settings",
            "mica_playback_session",
            "mica_eq_profiles",
            "mica_playlists",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        MicaDatabase.resetForTests()
        runBlocking { MicaDatabase.get(context).playlistDao().deleteAll() }
    }
}
