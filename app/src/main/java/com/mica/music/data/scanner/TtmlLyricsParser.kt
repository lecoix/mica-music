package com.mica.music.data.scanner

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
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

    fun looksLikeTtml(text: String): Boolean {
        val normalized = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return normalized.startsWith('<') &&
            Regex("""<\s*(?:\w+:)?tt\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    fun parse(text: String): List<LyricLine> {
        return parseWithFactory(text, DocumentBuilderFactory.newInstance())
    }

    internal fun parseWithFactory(text: String, factory: DocumentBuilderFactory): List<LyricLine> {
        if (!looksLikeTtml(text) || text.length > MAX_DOCUMENT_CHARS || forbiddenDeclaration.containsMatchIn(text)) {
            return emptyList()
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
            val paragraphs = document.getElementsByTagNameNS("*", "p")
            if (paragraphs.length !in 1..MAX_PARAGRAPHS) return emptyList()

            var totalCues = 0
            buildList {
                for (index in 0 until paragraphs.length) {
                    val paragraph = paragraphs.item(index) as? Element ?: continue
                    val rendered = renderParagraph(paragraph)
                    totalCues += rendered.cues.size
                    if (totalCues > MAX_CUES) return emptyList()
                    val lineText = MetadataTextFix.normalize(rendered.text).trim()
                    if (lineText.isEmpty()) continue
                    val lineStart = parseTime(paragraph.getAttribute("begin"))
                        ?: rendered.cues.firstOrNull()?.timeMs
                        ?: continue
                    val cues = rendered.cues.takeIf { candidate ->
                        candidate.isNotEmpty() &&
                            candidate.first().timeMs >= lineStart &&
                            candidate.zipWithNext().none { (left, right) -> right.timeMs < left.timeMs }
                    }.orEmpty()
                    add(LyricLine(lineStart.coerceAtLeast(0), lineText, cues))
                }
            }.sortedBy { it.timeMs }
        }.onFailure { error ->
            DiagnosticLog.event(
                LYRICS_TRACE,
                "ttml-parser failed error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }.getOrDefault(emptyList())
    }

    private data class RenderedParagraph(val text: String, val cues: List<LyricCue>)

    private fun renderParagraph(paragraph: Element): RenderedParagraph {
        val text = StringBuilder()
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
        return RenderedParagraph(text.toString(), cues)
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
