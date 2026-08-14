package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Build
import com.mica.music.data.scanner.AlbumArtCache

/**
 * Compatibility boundary for artwork published through MediaSession.
 *
 * Mica keeps its managed content URI as the canonical artwork reference. On the one Huawei
 * Android 12 device where publishing that URI is known to trigger Keyguard, only the session-facing
 * URI is replaced with the resident backing file. Missing files deliberately produce no artwork;
 * never fall back to the offending managed content URI on the affected device.
 */
internal object SystemMediaArtworkResolver {
    data class DeviceProfile(
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
    ) {
        companion object {
            fun current(): DeviceProfile = DeviceProfile(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
            )
        }
    }

    fun resolve(
        context: Context,
        rawArtworkUri: String?,
        device: DeviceProfile = DeviceProfile.current(),
    ): Uri? {
        if (rawArtworkUri.isNullOrBlank()) return null
        val original = runCatching { Uri.parse(rawArtworkUri) }.getOrNull() ?: return null
        val appContext = context.applicationContext
        if (AlbumArtCache.parseManagedArtworkUri(appContext, rawArtworkUri) == null) {
            return original
        }
        if (!requiresPrivateFileWorkaround(device)) return original

        return AlbumArtCache.fileForManagedArtwork(appContext, rawArtworkUri)
            ?.takeIf { file -> file.isFile && file.length() > 0L }
            ?.let(Uri::fromFile)
    }

    internal fun requiresPrivateFileWorkaround(device: DeviceProfile): Boolean =
        device.sdkInt == Build.VERSION_CODES.S &&
            device.manufacturer.equals("HUAWEI", ignoreCase = true) &&
            device.model.equals("OXF-AN10", ignoreCase = true)
}
