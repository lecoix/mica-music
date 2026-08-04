package com.mica.music.data.scanner

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.util.DiagnosticLog
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
    private const val LYRICS_TRACE = "DEBUG-LYRICS-7C31"
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
            // AMLL: head transliterations preferred; inline x-roman* is fallback only.
            val headRomanizations = parseItunesRomanizations(document.documentElement)
            val paragraphs = document.getElementsByTagNameNS("*", "p")
            if (paragraphs.length !in 1..MAX_PARAGRAPHS) return LyricsDocument(format = LyricsFormat.TTML)

            var totalCues = 0
            val lines = buildList {
                for (index in 0 until paragraphs.length) {
                    val paragraph = paragraphs.item(index) as? Element ?: continue
                    val rendered = renderParagraph(paragraph)
                    totalCues += rendered.cues.size
                    if (totalCues > MAX_CUES) return LyricsDocument(format = LyricsFormat.TTML)
                    val originalText = MetadataTextFix.normalize(rendered.text).trim()
                    val translationText = MetadataTextFix.normalize(rendered.translation).trim()
                    val itunesKey = paragraph.itunesKey()
                    val headReading = itunesKey?.let { headRomanizations[it] }.orEmpty()
                    val readingText = MetadataTextFix.normalize(
                        headReading.ifBlank { rendered.romanization },
                    ).trim()
                    if (originalText.isEmpty() && translationText.isEmpty() && readingText.isEmpty()) continue
                    val lineStart = parseTime(paragraph.getAttribute("begin"))
                        ?: rendered.cues.firstOrNull()?.timeMs
                        ?: continue
                    val lineStartMs = lineStart.coerceAtLeast(0)
                    val lineEndMs = parseTime(paragraph.getAttribute("end"))
                        ?: parseTime(paragraph.getAttribute("dur"))?.let { durationMs -> lineStartMs + durationMs }
                    val cues = rendered.cues.takeIf { candidate ->
                        candidate.isNotEmpty() &&
                            candidate.first().timeMs >= lineStartMs &&
                            candidate.zipWithNext().none { (left, right) -> right.timeMs < left.timeMs }
                    }.orEmpty()
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
                                    add(LyricTextPart(LyricTextRole.ORIGINAL, originalText))
                                }
                                if (translationText.isNotEmpty()) {
                                    add(LyricTextPart(LyricTextRole.TRANSLATION, translationText))
                                }
                            },
                            tokens = cues.mapIndexed { cueIndex, cue ->
                                LyricToken(
                                    text = cue.text,
                                    startMs = cue.timeMs,
                                    endMs = cues.getOrNull(cueIndex + 1)?.timeMs ?: endMs,
                                )
                            },
                        ),
                    )
                }
            }.sortedBy { it.startMs }
            LyricsDocument(format = LyricsFormat.TTML, lines = lines)
        }.onFailure { error ->
            DiagnosticLog.event(
                LYRICS_TRACE,
                "ttml-parser failed error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }.getOrDefault(LyricsDocument(format = LyricsFormat.TTML))
    }

    private data class RenderedParagraph(
        val text: String,
        val translation: String,
        val romanization: String,
        val cues: List<LyricCue>,
    )

    private fun renderParagraph(paragraph: Element): RenderedParagraph {
        val text = StringBuilder()
        val translation = StringBuilder()
        val romanization = StringBuilder()
        val cues = mutableListOf<LyricCue>()

        fun append(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    when (element.localName?.lowercase() ?: element.tagName.substringAfter(':').lowercase()) {
                        "br" -> text.append('\n')
                        "span" -> {
                            val visible = element.textContent.orEmpty()
                            when {
                                element.isTranslationSpan() -> {
                                    translation.append(visible)
                                    return
                                }
                                element.isRomanizationSpan() -> {
                                    if (romanization.isNotEmpty() && visible.isNotBlank()) {
                                        romanization.append(' ')
                                    }
                                    romanization.append(visible)
                                    return
                                }
                            }
                            val begin = parseTime(element.getAttribute("begin"))
                            if (begin != null && visible.isNotEmpty()) {
                                text.append(visible)
                                cues += LyricCue(begin.coerceAtLeast(0), MetadataTextFix.normalizeFragment(visible))
                            } else {
                                var child = element.firstChild
                                while (child != null) {
                                    append(child)
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
            cues = cues,
        )
    }

    /**
     * Apple Music style:
     * `iTunesMetadata > transliterations > transliteration > text[for=Ln]`
     * Line-level plain text or timed spans; x-bg nested content is skipped for the main reading.
     */
    private fun parseItunesRomanizations(root: Element): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val metadataNodes = root.getElementsByTagNameNS("*", "iTunesMetadata")
        for (metaIndex in 0 until metadataNodes.length) {
            val metadata = metadataNodes.item(metaIndex) as? Element ?: continue
            val textNodes = metadata.getElementsByTagNameNS("*", "text")
            for (textIndex in 0 until textNodes.length) {
                val textEl = textNodes.item(textIndex) as? Element ?: continue
                if (!textEl.isUnderLocalName("transliteration")) continue
                val key = textEl.getAttribute("for").trim()
                if (key.isEmpty() || result.containsKey(key)) continue
                val lineRoman = extractTransliterationText(textEl)
                if (lineRoman.isNotEmpty()) result[key] = lineRoman
            }
        }
        return result
    }

    private fun extractTransliterationText(textEl: Element): String {
        val parts = StringBuilder()
        fun appendMain(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> parts.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (element.isBackgroundSpan()) return
                    if (element.hasTimestamps()) {
                        val visible = element.textContent.orEmpty().trim()
                        if (visible.isNotEmpty()) {
                            if (parts.isNotEmpty()) parts.append(' ')
                            parts.append(visible)
                        }
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
        var child = textEl.firstChild
        while (child != null) {
            appendMain(child)
            child = child.nextSibling
        }
        return parts.toString().trim()
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

    private fun Element.hasTimestamps(): Boolean =
        getAttribute("begin").isNotBlank() && getAttribute("end").isNotBlank()

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
