package com.mica.music.data

import java.text.Collator
import java.util.Locale

/**
 * 解析与展示多艺术家字段（常见分隔：`/`、`／`、`|`）。
 * 原始标签仍保存在 [Song.artist]；分组与匹配走拆分逻辑。
 */
object ArtistNames {

    private const val UNKNOWN = "未知艺术家"

    /** 半角/全角斜杠、竖线，两侧可有空格 */
    private val SPLIT_PATTERN = Regex("""\s*(?:/|／|\|)\s*""")

    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    /** 规范展示：统一为 `A / B` 形式 */
    fun normalizeDisplay(raw: String): String =
        split(raw).joinToString(" / ")

    fun split(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return listOf(UNKNOWN)
        return trimmed.split(SPLIT_PATTERN)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .ifEmpty { listOf(UNKNOWN) }
    }

    fun contains(raw: String, artistName: String): Boolean =
        split(raw).any { it.equals(artistName, ignoreCase = true) }

    fun primary(raw: String): String = split(raw).first()

    fun matchesSearch(raw: String, queryLower: String): Boolean {
        if (raw.lowercase(Locale.getDefault()).contains(queryLower)) return true
        return split(raw).any { it.lowercase(Locale.getDefault()).contains(queryLower) }
    }
}
