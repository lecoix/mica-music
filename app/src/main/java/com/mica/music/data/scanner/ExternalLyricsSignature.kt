package com.mica.music.data.scanner

internal data class ExternalLyricsRef(
    val uri: String,
    val sizeBytes: Long = 0L,
    val dateModifiedMs: Long = 0L,
    val extension: String = "",
)

private const val CURRENT_EXTERNAL_LYRICS_PARSE_VERSION = 2

internal fun List<ExternalLyricsRef>.externalLyricsUris(): List<String> =
    map { it.uri }.distinct()

internal fun List<ExternalLyricsRef>.externalLyricsUris(extension: String): List<String> =
    filter { it.extension.equals(extension, ignoreCase = true) }.map { it.uri }.distinct()

internal fun List<ExternalLyricsRef>.externalLyricsSignature(): String =
    sortedBy { it.uri }
        .takeIf { it.isNotEmpty() }
        ?.let { refs ->
            buildString {
                append(CURRENT_EXTERNAL_LYRICS_PARSE_VERSION)
                append('\u0003')
                append(refs.joinToString(separator = "\u0002") { ref ->
                    buildString {
                        append(ref.uri)
                        append('\u0001')
                        append(ref.sizeBytes.coerceAtLeast(0L))
                        append('\u0001')
                        append(ref.dateModifiedMs.coerceAtLeast(0L))
                    }
                })
            }
        }
        .orEmpty()
