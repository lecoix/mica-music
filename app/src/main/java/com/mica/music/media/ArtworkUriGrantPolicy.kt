package com.mica.music.media

import android.net.Uri

/** Narrow policy for temporary read grants on Mica-owned MediaSession artwork URIs. */
internal object ArtworkUriGrantPolicy {
    const val SYSTEM_MEDIA_CONTROL_PERMISSION = "android.permission.MEDIA_CONTENT_CONTROL"

    fun targetPackages(requestingPackage: String): Set<String> =
        requestingPackage.takeIf(String::isNotBlank)?.let(::setOf).orEmpty()

    fun isGrantable(appPackageName: String, uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        return uri.authority == "$appPackageName.artwork" ||
            uri.authority == "$appPackageName.remoteart"
    }
}
