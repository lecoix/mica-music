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

internal class RemoteLyricsSidecarIndex private constructor(
    private val byLowerStem: Map<String, List<RemoteLyricsSidecarCandidate>>,
) {
    fun matchingFor(fileName: String): List<RemoteLyricsSidecarCandidate> {
        if (byLowerStem.isEmpty()) return emptyList()
        val stem = fileName.substringBeforeLast('.', fileName).lowercase(Locale.ROOT)
        return byLowerStem[stem].orEmpty()
    }

    companion object {
        val EMPTY = RemoteLyricsSidecarIndex(emptyMap())

        fun from(candidates: List<RemoteLyricsSidecarCandidate>): RemoteLyricsSidecarIndex {
            if (candidates.isEmpty()) return EMPTY
            val grouped = HashMap<String, MutableList<RemoteLyricsSidecarCandidate>>(candidates.size)
            candidates.forEach { candidate ->
                val stem = candidate.fileName.substringBeforeLast('.', candidate.fileName).lowercase(Locale.ROOT)
                grouped.getOrPut(stem) { ArrayList(2) }.add(candidate)
            }
            return RemoteLyricsSidecarIndex(grouped)
        }
    }
}

/** Revision fingerprint only; lyric payloads remain strictly on-demand. */
internal fun remoteTrackLyricsRevision(
    fileName: String,
    resourceId: String,
    contentRevision: String,
    sizeBytes: Long,
    candidates: List<RemoteLyricsSidecarCandidate>,
): String = remoteTrackLyricsRevisionFromMatches(
    resourceId = resourceId,
    contentRevision = contentRevision,
    sizeBytes = sizeBytes,
    matchingCandidates = if (candidates.isEmpty()) {
        emptyList()
    } else {
        val stem = fileName.substringBeforeLast('.', fileName)
        candidates.filter { it.fileName.substringBeforeLast('.', it.fileName).equals(stem, ignoreCase = true) }
    },
)

/** O(1) stem-indexed overload for large flat directories. Produces identical hashes to the list overload. */
internal fun remoteTrackLyricsRevision(
    fileName: String,
    resourceId: String,
    contentRevision: String,
    sizeBytes: Long,
    index: RemoteLyricsSidecarIndex,
): String = remoteTrackLyricsRevisionFromMatches(
    resourceId = resourceId,
    contentRevision = contentRevision,
    sizeBytes = sizeBytes,
    matchingCandidates = index.matchingFor(fileName),
)

private fun remoteTrackLyricsRevisionFromMatches(
    resourceId: String,
    contentRevision: String,
    sizeBytes: Long,
    matchingCandidates: List<RemoteLyricsSidecarCandidate>,
): String {
    val matching = if (matchingCandidates.size <= 1) {
        matchingCandidates
    } else {
        matchingCandidates.sortedWith(
            compareBy<RemoteLyricsSidecarCandidate> {
                if (it.fileName.endsWith(".ttml", ignoreCase = true)) 0 else 1
            }.thenBy { it.fileName.lowercase(Locale.ROOT) }
                .thenBy(RemoteLyricsSidecarCandidate::resourceId),
        )
    }
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

private val HEX_DIGITS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)

private val SHA256_THREAD_LOCAL = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

private fun sha256(value: String): String {
    val digest = SHA256_THREAD_LOCAL.get()!!.run {
        reset()
        digest(value.toByteArray(Charsets.UTF_8))
    }
    val chars = CharArray(digest.size * 2)
    for (i in digest.indices) {
        val b = digest[i].toInt() and 0xff
        chars[i * 2] = HEX_DIGITS[b ushr 4]
        chars[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
    }
    return String(chars)
}
