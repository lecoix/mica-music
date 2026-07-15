package com.mica.music.data

import java.util.Locale

enum class ArtistSeparator(
    val storageValue: String,
    val settingsLabel: String,
) {
    COMMA("comma", ", 半角逗号"),
    FULL_WIDTH_COMMA("full_width_comma", "， 全角逗号"),
    SEMICOLON("semicolon", "; 半角分号"),
    FULL_WIDTH_SEMICOLON("full_width_semicolon", "； 全角分号"),
    AMPERSAND("ampersand", "& 与号"),
    MULTIPLICATION_SIGN("multiplication_sign", "× 乘号"),
    SLASH("slash", "/ 半角斜杠"),
    FULL_WIDTH_SLASH("full_width_slash", "／ 全角斜杠"),
    IDEOGRAPHIC_COMMA("ideographic_comma", "、 顿号"),
    PIPE("pipe", "| 竖线"),
    FEAT("feat", "feat."),
    FT("ft", "ft."),
    ;

    val token: String
        get() = when (this) {
            COMMA -> ","
            FULL_WIDTH_COMMA -> "，"
            SEMICOLON -> ";"
            FULL_WIDTH_SEMICOLON -> "；"
            AMPERSAND -> "&"
            MULTIPLICATION_SIGN -> "×"
            SLASH -> "/"
            FULL_WIDTH_SLASH -> "／"
            IDEOGRAPHIC_COMMA -> "、"
            PIPE -> "|"
            FEAT -> "feat."
            FT -> "ft."
        }

    companion object {
        val defaults: Set<ArtistSeparator> = setOf(SLASH, FULL_WIDTH_SLASH, PIPE)

        fun fromStorage(value: String): ArtistSeparator? =
            entries.firstOrNull { it.storageValue == value }
    }
}

data class ArtistSplitConfig(
    val enabledSeparators: Set<ArtistSeparator> = ArtistSeparator.defaults,
    val whitelist: List<String> = emptyList(),
)

/** 原始标签保存在 [Song.artist]；这里只负责按当前设置拆分、匹配与展示。 */
object ArtistNames {

    private const val UNKNOWN = "未知艺术家"
    internal const val MAX_ARTISTS_PER_TAG = 32
    private val LEGACY_SEPARATOR_PATTERN = Regex("""\s*(?:/|／|\|)\s*""")

    private data class Rules(
        val config: ArtistSplitConfig,
        val splitPattern: Regex?,
        val whitelistKeys: Set<String>,
        val legacyWhitelistKeys: Set<String>,
    )

    @Volatile
    private var rules = buildRules(ArtistSplitConfig())

    fun configure(config: ArtistSplitConfig) {
        rules = buildRules(config.normalized())
    }

    fun currentConfig(): ArtistSplitConfig = rules.config

    /** 规范展示：已启用的分隔符统一显示为 `A / B`。 */
    fun normalizeDisplay(raw: String): String = split(raw).joinToString(" / ")

    fun split(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return listOf(UNKNOWN)
        val current = rules
        val key = trimmed.lowercase(Locale.ROOT)
        if (
            key in current.whitelistKeys ||
            (" / " in trimmed && legacyWhitelistKey(trimmed) in current.legacyWhitelistKeys)
        ) {
            return listOf(trimmed)
        }
        val pattern = current.splitPattern ?: return listOf(trimmed)
        return trimmed.split(pattern, limit = MAX_ARTISTS_PER_TAG)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty { listOf(UNKNOWN) }
    }

    fun contains(raw: String, artistName: String): Boolean =
        split(raw).any { it.equals(artistName, ignoreCase = true) }

    fun primary(raw: String): String = split(raw).first()

    fun matchesSearch(raw: String, queryLower: String): Boolean {
        if (raw.lowercase(Locale.ROOT).contains(queryLower)) return true
        return split(raw).any { it.lowercase(Locale.ROOT).contains(queryLower) }
    }

    private fun buildRules(config: ArtistSplitConfig): Rules {
        val punctuation = config.enabledSeparators
            .filterNot { it == ArtistSeparator.FEAT || it == ArtistSeparator.FT }
            .joinToString("|") { Regex.escape(it.token) }
            .takeIf { it.isNotEmpty() }
            ?.let { "\\s*(?:$it)\\s*" }
        val words = buildList {
            if (ArtistSeparator.FEAT in config.enabledSeparators) add("feat\\.")
            if (ArtistSeparator.FT in config.enabledSeparators) add("ft\\.")
        }.joinToString("|")
            .takeIf { it.isNotEmpty() }
            ?.let { "\\s+(?i:$it)\\s*" }
        val pattern = listOfNotNull(words, punctuation)
            .joinToString("|")
            .takeIf { it.isNotEmpty() }
            ?.let(::Regex)
        return Rules(
            config = config,
            splitPattern = pattern,
            whitelistKeys = config.whitelist.mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) },
            // 旧版本曾把 /、／、| 都写成 " / "；兼容这些已无法无损还原的缓存值。
            legacyWhitelistKeys = config.whitelist.mapTo(linkedSetOf(), ::legacyWhitelistKey),
        )
    }

    private fun legacyWhitelistKey(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(LEGACY_SEPARATOR_PATTERN, "/")

    private fun ArtistSplitConfig.normalized(): ArtistSplitConfig = copy(
        enabledSeparators = enabledSeparators.toSet(),
        whitelist = whitelist
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) },
    )
}
