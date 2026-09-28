package com.mica.music.data.remote

import java.security.MessageDigest
import java.util.Locale

internal data class RemoteLyricsSidecarCandidate(
    val fileName: String,
    val resourceId: String,
    val contentRevision: String,
    val sizeBytes: Long,
)

internal fun isRemoteLyricsSidecarFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("lrc", "ttml")

/** Revision fingerprint only; lyric payloads remain strictly on-demand. */
internal fun remoteTrackLyricsRevision(
    fileName: String,
    resourceId: String,
    contentRevision: String,
    sizeBytes: Long,
    candidates: List<RemoteLyricsSidecarCandidate>,
): String {
    val stem = fileName.substringBeforeLast('.', fileName)
    val matching = candidates
        .filter { it.fileName.substringBeforeLast('.', it.fileName).equals(stem, ignoreCase = true) }
        .sortedWith(
            compareBy<RemoteLyricsSidecarCandidate> {
                if (it.fileName.endsWith(".ttml", ignoreCase = true)) 0 else 1
            }.thenBy { it.fileName.lowercase(Locale.ROOT) }
                .thenBy(RemoteLyricsSidecarCandidate::resourceId),
        )
    val separator = 0.toChar()
    val material = if (matching.isNotEmpty()) {
        buildString {
            append("sidecar-v1")
            matching.forEach { candidate ->
                append(separator).append(candidate.resourceId)
                append(separator).append(candidate.contentRevision)
                append(separator).append(candidate.sizeBytes)
            }
        }
    } else {
        buildString {
            append("embedded-v1")
            append(separator).append(resourceId)
            append(separator).append(contentRevision)
            append(separator).append(sizeBytes)
        }
    }
    return "lyrics-v1:" + sha256(material)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
