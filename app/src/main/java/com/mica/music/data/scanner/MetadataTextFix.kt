package com.mica.music.data.scanner

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 修正 ID3 等标签被误按 Latin-1 解码导致的 UTF-8 乱码（如 ãã¬ã¹…）。
 */
internal object MetadataTextFix {

    private val latin1: Charset = Charsets.ISO_8859_1

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = LyricsEncoding.stripBomAndControls(text)
        if (looksLikeUtf8Mojibake(result)) {
            result = repairUtf8FromLatin1(result)
        }
        return LyricsEncoding.stripBomAndControls(result)
    }

    /** Normalizes visible text while retaining whitespace that separates timed lyric cues. */
    fun normalizeFragment(text: String): String {
        if (text.isEmpty() || text.all { it.isWhitespace() }) return text
        val leading = text.takeWhile { it.isWhitespace() }
        val trailing = text.takeLastWhile { it.isWhitespace() }
        val core = text.substring(leading.length, text.length - trailing.length)
        return leading + normalize(core) + trailing
    }

    fun looksLikeUtf8Mojibake(text: String): Boolean {
        if (text.any { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF }) return false
        var latinExtended = 0
        for (c in text) {
            when (c.code) {
                in 0x00C0..0x00FF, in 0x0080..0x00BF -> latinExtended++
            }
        }
        return latinExtended >= 3 && latinExtended * 2 >= text.length / 3
    }

    private fun repairUtf8FromLatin1(text: String): String {
        return runCatching {
            val bytes = text.toByteArray(latin1)
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (utf8.isNotBlank() && !utf8.contains('\uFFFD') && isPlausibleText(utf8)) utf8 else text
        }.getOrDefault(text)
    }

    private fun isPlausibleText(text: String): Boolean {
        var good = 0
        for (c in text) {
            when {
                c.isLetterOrDigit() -> good++
                c.isWhitespace() -> good++
                c in "，。！？、・～〜（）()[]【】「」『』…—-・:：;；'\".,!?&+" -> good++
                c.code in 0x3040..0x9FFF || c.code in 0xAC00..0xD7AF -> good += 2
            }
        }
        return good * 2 >= text.length
    }

    /** 解析 TRCK / TRACKNUMBER 等原始值（如 `5/12`）；无法解析时返回 0。 */
    fun parseTrackNumber(raw: String?): Int {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0
        val trackPart = trimmed.substringBefore('/').trim()
        return trackPart.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    /** 解析 DISCNUMBER / TPOS 等原始值（如 `1/2`）；无法解析时返回 0。 */
    fun parseDiscNumber(raw: String?): Int = parseTrackNumber(raw)

    fun titleFromTagsOrFilename(
        tagTitle: String?,
        displayName: String?,
        fallbackTitle: String,
    ): String {
        val fixedTag = tagTitle?.let { normalize(it) }?.takeIf { it.isNotBlank() }
        val fileStem = displayName
            ?.substringBeforeLast('.')
            ?.let { normalize(it) }
            ?.takeIf { it.isNotBlank() }

        return when {
            fixedTag != null && !looksLikeUtf8Mojibake(fixedTag) -> fixedTag
            fileStem != null && !looksLikeUtf8Mojibake(fileStem) -> fileStem
            fixedTag != null -> fixedTag
            fileStem != null -> fileStem
            else -> fallbackTitle
        }
    }
}
