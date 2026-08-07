package com.mica.music.data.preferences

import android.content.Context
import android.os.Build

enum class AudioOffloadDisabledReason {
    BUILT_IN_DENYLIST,
    VERIFIED_RUNTIME_FAILURE,
}

data class AudioOffloadPreferenceState(
    val enabled: Boolean,
    val disabledReason: AudioOffloadDisabledReason? = null,
)

/** User choice plus the current-build offload circuit-breaker record. */
object AudioOffloadPreferences {
    private const val KEY_ENABLED = "audio_offload_enabled"
    private const val KEY_VERIFIED_FAILURE_BUILD = "audio_offload_verified_failure_build"
    private const val POLICY_VERSION = 1

    fun state(context: Context): AudioOffloadPreferenceState = state(
        context = context,
        buildToken = currentBuildToken(),
        builtInDenied = BuiltInAudioOffloadDenylist.matchesCurrentDevice(),
    )

    internal fun state(
        context: Context,
        buildToken: String,
        builtInDenied: Boolean,
    ): AudioOffloadPreferenceState {
        val preferences = MicaSettingsStore.prefs(context)
        val failureBuild = preferences.getString(KEY_VERIFIED_FAILURE_BUILD, null)
        if (failureBuild == buildToken) {
            return AudioOffloadPreferenceState(
                enabled = false,
                disabledReason = AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE,
            )
        }
        if (preferences.contains(KEY_ENABLED)) {
            return AudioOffloadPreferenceState(enabled = preferences.getBoolean(KEY_ENABLED, true))
        }
        // A firmware or policy update gets one fresh offload attempt when the user has not
        // made an explicit choice.
        return if (builtInDenied) {
            AudioOffloadPreferenceState(
                enabled = false,
                disabledReason = AudioOffloadDisabledReason.BUILT_IN_DENYLIST,
            )
        } else {
            AudioOffloadPreferenceState(enabled = true)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .remove(KEY_VERIFIED_FAILURE_BUILD)
            .apply()
    }

    fun recordVerifiedFailure(context: Context) {
        recordVerifiedFailure(context, currentBuildToken())
    }

    internal fun recordVerifiedFailure(context: Context, buildToken: String) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_VERIFIED_FAILURE_BUILD, buildToken)
            .apply()
    }

    fun registerChangeListener(
        context: Context,
        onChanged: (AudioOffloadPreferenceState) -> Unit,
    ): () -> Unit {
        val preferences = MicaSettingsStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED || key == KEY_VERIFIED_FAILURE_BUILD) {
                onChanged(state(context))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun currentBuildToken(): String {
        val fingerprint = Build.FINGERPRINT.takeIf(String::isNotBlank)
            ?: "${Build.MANUFACTURER}/${Build.MODEL}/${Build.VERSION.SDK_INT}"
        return "$POLICY_VERSION:$fingerprint"
    }
}

internal object BuiltInAudioOffloadDenylist {
    fun matchesCurrentDevice(): Boolean = matches(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
    )

    internal fun matches(
        manufacturer: String,
        brand: String,
        model: String,
        sdkInt: Int,
    ): Boolean {
        val manufacturerName = manufacturer.trim().lowercase()
        val brandName = brand.trim().lowercase()
        val isXiaomiFamily = manufacturerName == "xiaomi" ||
            brandName in setOf("xiaomi", "redmi", "poco")
        return isXiaomiFamily && model.trim().equals("22081212C", ignoreCase = true) && sdkInt == 31
    }
}
