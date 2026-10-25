package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricRuby
import com.mica.music.data.lyricRubyAlignment
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.toLegacyLyricLines
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal object TtmlLyricsParser {
    private const val MAX_DOCUMENT_CHARS = 2_000_000
    private const val MAX_PARAGRAPHS = 5_000
    private const val MAX_CUES = 50_000
    private val forbiddenDeclaration = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)
    private val romanizationRoles = setOf("x-roman", "x-romanization")

    fun looksLikeTtml(text: String): Boolean {
        val normalized = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return normalized.startsWith('<') &&
            Regex("""<\s*(?:\w+:)?tt\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    fun parse(text: String): List<LyricLine> {
        return parseDocument(text).toLegacyLyricLines()
    }

    fun parseDocument(text: String): LyricsDocument =
        parseDocumentWithFactory(text, DocumentBuilderFactory.newInstance())

    internal fun parseWithFactory(text: String, factory: DocumentBuilderFactory): List<LyricLine> {
        return parseDocumentWithFactory(text, factory).toLegacyLyricLines()
    }

    internal fun parseDocumentWithFactory(text: String, factory: DocumentBuilderFactory): LyricsDocument {
        if (!looksLikeTtml(text) || text.length > MAX_DOCUMENT_CHARS || forbiddenDeclaration.containsMatchIn(text)) {
            return LyricsDocument(format = LyricsFormat.TTML)
        }
        return runCatching {
            factory.apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { isXIncludeAware = false }
                runCatching { setExpandEntityReferences(false) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = builder.parse(InputSource(StringReader(text)))
            // AMLL / Apple Music: keyed head tracks are preferred; inline roles are fallbacks.
            val headRomanizations = parseItunesRomanizations(document.documentElement)
            val headTranslations = parseItunesTranslations(document.documentElement)
            val paragraphs = document.getElementsByTagNameNS("*", "p")
            if (paragraphs.length !in 1..MAX_PARAGRAPHS) return LyricsDocument(format = LyricsFormat.TTML)

            var totalCues = 0
            val lines = buildList {
                for (index in 0 until paragraphs.length) {
                    val paragraph = paragraphs.item(index) as? Element ?: continue
                    val rendered = renderParagraph(paragraph)
                    val itunesKey = paragraph.itunesKey()
                    val headReading = itunesKey?.let { headRomanizations[it] }
                    val readingTokens = if (rendered.rubyReading.isNotEmpty()) rendered.rubyTokens else headReading?.tokens
                        ?.takeIf { headReading.text.isNotBlank() }
                        ?: rendered.romanizationTokens
                    val headTranslation = itunesKey?.let { headTranslations[it] }
                    val translationTokens = headTranslation?.tokens
                        ?.takeIf { headTranslation.text.isNotBlank() }
                        ?: rendered.translationTokens
                    val lineTokens = readingTokens + rendered.originalTokens + translationTokens
                    totalCues += lineTokens.size + rendered.ruby.size
                    if (totalCues > MAX_CUES) return LyricsDocument(format = LyricsFormat.TTML)
                    val normalizedOriginal = MetadataTextFix.normalize(rendered.text)
                    val originalText = normalizedOriginal.trim()
                    val trimStart = normalizedOriginal.length - normalizedOriginal.trimStart().length
                    val ruby = if (normalizedOriginal == rendered.text) rendered.ruby.map {
                        it.copy(start = it.start - trimStart, endExclusive = it.endExclusive - trimStart)
                    }.filter { it.start >= 0 && it.endExclusive <= originalText.length } else emptyList()
                    val translationText = MetadataTextFix.normalize(
                        headTranslation?.text?.ifBlank { rendered.translation } ?: rendered.translation,
                    ).trim()
                    val readingText = MetadataTextFix.normalize(
                        rendered.rubyReading.ifBlank {
                            headReading?.text?.ifBlank { rendered.romanization } ?: rendered.romanization
                        },
                    ).trim()
                    if (originalText.isEmpty() && translationText.isEmpty() && readingText.isEmpty()) continue
                    val lineStart = parseTime(paragraph.getAttribute("begin"))
                        ?: rendered.originalTokens.firstOrNull()?.startMs
                        ?: continue
                    val lineStartMs = lineStart.coerceAtLeast(0)
                    val lineEndMs = parseTime(paragraph.getAttribute("end"))
                        ?: parseTime(paragraph.getAttribute("dur"))?.let { durationMs -> lineStartMs + durationMs }
                    val endMs = lineEndMs?.takeIf { it > lineStartMs }
                    add(
                        LyricLineNode(
                            id = "$index-$lineStartMs",
                            startMs = lineStartMs,
                            endMs = endMs,
                            parts = buildList {
                                if (readingText.isNotEmpty()) {
                                    add(LyricTextPart(LyricTextRole.READING, readingText))
                                }
                                if (originalText.isNotEmpty()) {
                                    add(LyricTextPart(LyricTextRole.ORIGINAL, originalText, ruby))
                                }
                                if (translationText.isNotEmpty()) {
                                    add(LyricTextPart(LyricTextRole.TRANSLATION, translationText))
                                }
                            }.let { parts ->
                                val alignment = lyricRubyAlignment(parts, lineTokens, allowSourceTimingMatch = true)
                                totalCues += (alignment.size - rendered.ruby.size).coerceAtLeast(0)
                                if (totalCues > MAX_CUES) return LyricsDocument(format = LyricsFormat.TTML)
                                parts.map { if (it.role == LyricTextRole.ORIGINAL) it.copy(ruby = alignment) else it }
                            },
                            tokens = normalizeTokensByRole(
                                tokens = lineTokens,
                                lineStartMs = lineStartMs,
                                lineEndMs = endMs,
                            ),
                        ),
                    )
                }
            }.sortedBy { it.startMs }
            LyricsDocument(format = LyricsFormat.TTML, lines = lines)
        }.getOrDefault(LyricsDocument(format = LyricsFormat.TTML))
    }

    private data class RenderedParagraph(
        val text: String,
        val translation: String,
        val romanization: String,
        val originalTokens: List<LyricToken>,
        val translationTokens: List<LyricToken>,
        val romanizationTokens: List<LyricToken>,
        val ruby: List<LyricRuby>,
        val rubyReading: String,
        val rubyTokens: List<LyricToken>,
    )

    private data class ParsedTrack(
        val text: String,
        val tokens: List<LyricToken>,
    )

    private fun renderParagraph(paragraph: Element): RenderedParagraph {
        val text = StringBuilder()
        val translation = StringBuilder()
        val romanization = StringBuilder()
        val originalTokens = mutableListOf<LyricToken>()
        val translationTokens = mutableListOf<LyricToken>()
        val romanizationTokens = mutableListOf<LyricToken>()
        val ruby = mutableListOf<LyricRuby>()
        val rubyReading = StringBuilder()
        val rubyTokens = mutableListOf<LyricToken>()

        fun append(node: Node, inheritedBegin: Int? = null, inheritedEnd: Int? = null) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue.orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { text.append(MetadataTextFix.normalizeFragment(it)) }
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (element.rubyMode() == "container") {
                        val bases = element.children().filter { it.rubyMode() in setOf("base", "baseContainer") }
                        val readings = element.children().filter { it.rubyMode() in setOf("text", "textContainer") }
                        val baseText = element.rubyBaseText()
                        val tracks = readings.map { extractRoleTrack(it, LyricTextRole.READING, false) }
                        val baseTokens = bases.flatMap { extractRoleTrack(it, LyricTextRole.ORIGINAL, false).tokens }
                        val readingText = tracks.joinToString("") { it.text }
                        val offset = text.length
                        text.append(baseText)
                        rubyReading.append(readingText)
                        // Multiple parallel base/text containers require a richer TTML association;
                        // keep the full reading rather than inventing per-base pairings.
                        val unambiguous = bases.size == 1 && readings.size == 1 &&
                            baseText.isNotBlank() && readingText.isNotBlank()
                        if (unambiguous) {
                            ruby += LyricRuby(offset, text.length, readingText)
                            rubyTokens += tracks.flatMap { it.tokens }
                        }
                        val begin = parseTime(element.getAttribute("begin"))
                            ?: bases.singleOrNull()?.let { parseTime(it.getAttribute("begin")) }
                            ?: tracks.flatMap { it.tokens }.minOfOrNull { it.startMs } ?: inheritedBegin
                        val end = parseTime(element.getAttribute("end"))
                            ?: bases.singleOrNull()?.let { parseTime(it.getAttribute("end")) }
                            ?: tracks.flatMap { it.tokens }.mapNotNull { it.endMs }.maxOrNull() ?: inheritedEnd
                        if (baseTokens.isNotEmpty()) {
                            originalTokens += baseTokens
                        } else if (begin != null && baseText.isNotBlank()) {
                            originalTokens += LyricToken(baseText, begin, end)
                        }
                        return
                    }
                    if (element.rubyMode() in setOf("text", "textContainer", "delimiter")) return
                    when (element.localName?.lowercase() ?: element.tagName.substringAfter(':').lowercase()) {
                        "br" -> text.append('\n')
                        "span" -> {
                            val visible = element.textContent.orEmpty()
                            when {
                                element.isTranslationSpan() -> {
                                    val track = extractRoleTrack(
                                        element,
                                        role = LyricTextRole.TRANSLATION,
                                        separateTimedFragments = false,
                                    )
                                    translation.append(track.text)
                                    translationTokens += track.tokens
                                    return
                                }
                                element.isRomanizationSpan() -> {
                                    val track = extractRoleTrack(
                                        element,
                                        role = LyricTextRole.READING,
                                        separateTimedFragments = true,
                                    )
                                    if (romanization.isNotEmpty() && track.text.isNotBlank()) {
                                        romanization.append(' ')
                                    }
                                    romanization.append(track.text)
                                    romanizationTokens += track.tokens
                                    return
                                }
                            }
                            val begin = parseTime(element.getAttribute("begin"))
                            if (begin != null && visible.isNotEmpty() && !element.containsRuby()) {
                                text.append(MetadataTextFix.normalizeFragment(visible))
                                originalTokens += LyricToken(
                                    text = MetadataTextFix.normalizeFragment(visible),
                                    startMs = begin.coerceAtLeast(0),
                                    endMs = parseTime(element.getAttribute("end")),
                                    partRole = LyricTextRole.ORIGINAL,
                                )
                            } else {
                                var child = element.firstChild
                                while (child != null) {
                                    append(child, begin ?: inheritedBegin, parseTime(element.getAttribute("end")) ?: inheritedEnd)
                                    child = child.nextSibling
                                }
                            }
                        }
                        else -> {
                            var child = element.firstChild
                            while (child != null) {
                                append(child)
                                child = child.nextSibling
                            }
                        }
                    }
                }
            }
        }

        var child = paragraph.firstChild
        while (child != null) {
            append(child)
            child = child.nextSibling
        }
        return RenderedParagraph(
            text = text.toString(),
            translation = translation.toString(),
            romanization = romanization.toString(),
            originalTokens = originalTokens,
            translationTokens = translationTokens,
            romanizationTokens = romanizationTokens,
            ruby = ruby,
            rubyReading = rubyReading.toString(),
            rubyTokens = rubyTokens,
        )
    }

    private fun Element.children(): List<Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child is Element) add(child)
            child = child.nextSibling
        }
    }

    private fun Node.rubyBaseText(): String = when (nodeType) {
        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> nodeValue.orEmpty()
            .takeIf { it.isNotBlank() }?.let(MetadataTextFix::normalizeFragment).orEmpty()
        Node.ELEMENT_NODE -> {
            val element = this as Element
            if (element.rubyMode() in setOf("text", "textContainer", "delimiter")) "" else buildString {
                var child = firstChild
                while (child != null) {
                    append(child.rubyBaseText())
                    child = child.nextSibling
                }
            }
        }
        else -> ""
    }

    private fun Element.rubyMode(): String =
        getAttributeNS("http://www.w3.org/ns/ttml#styling", "ruby").ifBlank { getAttribute("tts:ruby") }

    private fun Element.containsRuby(): Boolean {
        val descendants = getElementsByTagNameNS("*", "span")
        return (0 until descendants.length).any { (descendants.item(it) as? Element)?.rubyMode() == "container" }
    }

    /**
     * Apple Music style:
     * `iTunesMetadata > transliterations > transliteration > text[for=Ln]`
     * Line-level plain text or timed spans; x-bg nested content is skipped for the main reading.
     */
    private fun parseItunesRomanizations(root: Element): Map<String, ParsedTrack> {
        return parseItunesTracks(
            root = root,
            containerLocalName = "transliteration",
            role = LyricTextRole.READING,
            separateTimedFragments = true,
        )
    }

    private fun parseItunesTranslations(root: Element): Map<String, ParsedTrack> {
        return parseItunesTracks(
            root = root,
            containerLocalName = "translation",
            role = LyricTextRole.TRANSLATION,
            separateTimedFragments = false,
        )
    }

    private fun parseItunesTracks(
        root: Element,
        containerLocalName: String,
        role: LyricTextRole,
        separateTimedFragments: Boolean,
    ): Map<String, ParsedTrack> {
        val result = linkedMapOf<String, ParsedTrack>()
        val metadataNodes = root.getElementsByTagNameNS("*", "iTunesMetadata")
        for (metaIndex in 0 until metadataNodes.length) {
            val metadata = metadataNodes.item(metaIndex) as? Element ?: continue
            val textNodes = metadata.getElementsByTagNameNS("*", "text")
            for (textIndex in 0 until textNodes.length) {
                val textEl = textNodes.item(textIndex) as? Element ?: continue
                if (!textEl.isUnderLocalName(containerLocalName)) continue
                val key = textEl.getAttribute("for").trim()
                if (key.isEmpty() || result.containsKey(key)) continue
                val track = extractRoleTrack(textEl, role, separateTimedFragments)
                if (track.text.isNotEmpty()) result[key] = track
            }
        }
        return result
    }

    private fun extractRoleTrack(
        root: Element,
        role: LyricTextRole,
        separateTimedFragments: Boolean,
    ): ParsedTrack {
        val parts = StringBuilder()
        val tokens = mutableListOf<LyricToken>()

        fun appendVisible(visible: String, timed: Boolean) {
            if (visible.isEmpty()) return
            if (timed && separateTimedFragments && parts.isNotEmpty() && !parts.last().isWhitespace()) {
                parts.append(' ')
            }
            parts.append(visible)
        }

        fun appendMain(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue.orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { appendVisible(it, timed = false) }
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (element.isBackgroundSpan()) return
                    val token = element.toTimedToken(role)
                    if (token != null) {
                        appendVisible(token.text, timed = true)
                        tokens += token
                        return
                    }
                    var child = element.firstChild
                    while (child != null) {
                        appendMain(child)
                        child = child.nextSibling
                    }
                }
            }
        }
        appendMain(root)
        return ParsedTrack(parts.toString().trim(), tokens)
    }

    private fun Element.toTimedToken(role: LyricTextRole): LyricToken? {
        val startMs = parseTime(getAttribute("begin")) ?: return null
        val visible = MetadataTextFix.normalizeFragment(textContent.orEmpty()).trim()
        if (visible.isEmpty()) return null
        return LyricToken(
            text = visible,
            startMs = startMs.coerceAtLeast(0),
            endMs = parseTime(getAttribute("end")),
            partRole = role,
        )
    }

    private fun Element.isUnderLocalName(localName: String): Boolean {
        var parent = parentNode
        while (parent != null) {
            if (parent is Element) {
                val name = parent.localName?.lowercase() ?: parent.tagName.substringAfter(':').lowercase()
                if (name == localName.lowercase()) return true
            }
            parent = parent.parentNode
        }
        return false
    }

    private fun Element.itunesKey(): String? {
        val keyed = getAttributeNS("http://music.apple.com/lyric-ttml-extensions", "key")
            .ifBlank { getAttributeNS("http://music.apple.com/itunes/ttml", "key") }
            .ifBlank { getAttribute("itunes:key") }
            .trim()
        return keyed.takeIf { it.isNotEmpty() }
    }

    private fun Element.ttmRoles(): List<String> =
        getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
            .ifBlank { getAttribute("ttm:role") }
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    private fun Element.isTranslationSpan(): Boolean =
        ttmRoles().any { it == "x-translation" }

    private fun Element.isRomanizationSpan(): Boolean =
        ttmRoles().any { it in romanizationRoles }

    private fun Element.isBackgroundSpan(): Boolean =
        ttmRoles().any { it == "x-bg" }

    private fun normalizeTokensByRole(
        tokens: List<LyricToken>,
        lineStartMs: Int,
        lineEndMs: Int?,
    ): List<LyricToken> {
        val tracks = linkedMapOf<LyricTextRole, MutableList<LyricToken>>()
        tokens.forEach { token ->
            val track = tracks.getOrPut(token.partRole) { mutableListOf() }
            if (token.startMs >= lineStartMs && (track.isEmpty() || token.startMs >= track.last().startMs)) {
                track += token
            }
        }
        return tracks.values.flatMap { track ->
            track.mapIndexed { index, token ->
                val inferredEndMs = track.getOrNull(index + 1)?.startMs ?: lineEndMs
                token.copy(
                    endMs = token.endMs?.takeIf { it > token.startMs }
                        ?: inferredEndMs?.takeIf { it > token.startMs },
                )
            }
        }
    }

    private fun parseTime(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.endsWith("ms", ignoreCase = true)) {
            return value.dropLast(2).toDoubleOrNull()?.roundToInt()
        }
        if (value.endsWith("s", ignoreCase = true)) {
            return value.dropLast(1).toDoubleOrNull()?.let { (it * 1_000).roundToInt() }
        }
        val parts = value.split(':')
        val seconds = parts.lastOrNull()?.toDoubleOrNull() ?: return null
        val millis = when (parts.size) {
            1 -> seconds * 1_000
            2 -> (parts[0].toLongOrNull() ?: return null) * 60_000 + seconds * 1_000
            3 -> (parts[0].toLongOrNull() ?: return null) * 3_600_000 +
                (parts[1].toLongOrNull() ?: return null) * 60_000 + seconds * 1_000
            else -> return null
        }
        return millis.roundToInt()
    }
}
