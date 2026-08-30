package com.mica.music.data.preferences

import android.content.Context
import java.io.File

/** Main-thread UI writes only; no delayed jobs or library state. All tutorial writes share this owner. */
internal object UsageTutorialPreferences {
    private const val NAME = "mica_usage_tutorial"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Called before startup initializes ordinary settings. Existing installations are not interrupted. */
    @Synchronized
    fun initialize(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains("completed")) return
        val existingSettings = File(context.applicationInfo.dataDir, "shared_prefs/mica_settings.xml").exists()
        prefs.edit().putBoolean("completed", existingSettings).apply()
    }

    @Synchronized
    fun isCompleted(context: Context): Boolean = prefs(context).getBoolean("completed", false)

    /** Claim once when the UI observes an actual running scan, never on app launch or permission request. */
    @Synchronized
    fun claimScanInvitation(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean("completed", false) || prefs.getBoolean("scan_invitation_shown", false)) return false
        prefs.edit().putBoolean("scan_invitation_shown", true).apply()
        return true
    }

    @Synchronized
    fun page(context: Context): Int = prefs(context).getInt("page", 0).coerceAtLeast(0)

    @Synchronized
    fun savePage(context: Context, page: Int) {
        // A late page callback cannot undo completion; manual replay never writes progress.
        if (!isCompleted(context)) prefs(context).edit().putInt("page", page.coerceAtLeast(0)).apply()
    }

    @Synchronized
    fun complete(context: Context) {
        prefs(context).edit().putBoolean("completed", true).remove("page").apply()
    }
}
