package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.util.DiagnosticLog
import java.io.File
/**
 * 读取与音频同目录的外挂 `.lrc`（同名或 displayName 去扩展名）。
 */
internal object ExternalLyricsReader {

    private const val LYRICS_TRACE = "DEBUG-LYRICS-7C31"
    private val sidecarExtensions = listOf("lrc", "ttml")

    fun readDirectUris(context: Context, uriStrings: List<String>): List<LyricLine>? =
        readDirectDocuments(context, uriStrings)?.toLegacyLyricLines()

    fun readDirectDocuments(context: Context, uriStrings: List<String>): LyricsDocument? {
        if (uriStrings.isEmpty()) return null
        DiagnosticLog.event(LYRICS_TRACE, "direct-read start uris=${uriStrings.size}")
        val candidates = uriStrings.mapNotNull { readLyricsByUri(context, it) }
        val selected = LyricsSanitizer.pickBestDocument(candidates)
        DiagnosticLog.event(
            LYRICS_TRACE,
            "direct-read finish candidates=${candidates.size} selectedLines=${selected?.lines?.size ?: 0} " +
                "selectedTokens=${selected?.lines?.sumOf { it.tokens.size } ?: 0}",
        )
        return selected
    }

    fun read(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String,
        parentDirectory: DocumentFile? = null,
        directLyricsUris: List<String> = emptyList(),
    ): List<LyricLine> = readDocument(
        context, uri, displayName, filePath, parentDirectory, directLyricsUris,
    ).toLegacyLyricLines()

    fun readDocument(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String,
        parentDirectory: DocumentFile? = null,
        directLyricsUris: List<String> = emptyList(),
    ): LyricsDocument {
        val candidates = mutableListOf<LyricsDocument>()
        directLyricsUris.mapNotNullTo(candidates) { readLyricsByUri(context, it) }
        for (base in basenameCandidates(displayName, filePath)) {
            parentDirectory
                ?.takeIf { it.isDirectory }
                ?.let { readLyricsInDocumentParent(context, it, base) }
                ?.let { candidates += it }
            readLyricsByAbsolutePath(filePath, base)?.let { candidates += it }
            readLyricsViaDocumentTree(context, uri, base)?.let { candidates += it }
        }
        return LyricsSanitizer.pickBestDocument(candidates) ?: LyricsDocument(origin = LyricsOrigin.EXTERNAL)
    }

    private fun basenameCandidates(
        displayName: String?,
        filePath: String,
    ): List<String> {
        val names = linkedSetOf<String>()
        fun addBase(name: String?) {
            val base = name
                ?.substringBeforeLast('.')
                ?.trim()
                .orEmpty()
            if (base.isNotEmpty()) names += base
        }
        addBase(displayName)
        if (filePath.isNotBlank()) {
            addBase(File(filePath).name)
            addBase(filePath.substringAfterLast('/'))
        }
        return names.toList()
    }

    private fun readLyricsByAbsolutePath(audioPath: String, baseName: String): LyricsDocument? {
        if (audioPath.isBlank()) return null
        val audioFile = File(audioPath)
        val parent = when {
            audioFile.isFile -> audioFile.parentFile
            '/' in audioPath -> File(audioPath.substringBeforeLast('/'))
            else -> null
        } ?: return null
        return readLyricsInDirectory(parent, baseName)
    }

    private fun readLyricsViaDocumentTree(
        context: Context,
        audioUri: Uri,
        baseName: String,
    ): LyricsDocument? {
        if (!DocumentsContract.isDocumentUri(context, audioUri)) return null
        val audioDoc = DocumentFile.fromSingleUri(context, audioUri) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val docBase = audioDoc.name?.substringBeforeLast('.')?.trim().orEmpty()
        if (docBase.isNotEmpty() && docBase != baseName) {
            readLyricsInDocumentParent(context, parent, docBase)?.let { return it }
        }
        return readLyricsInDocumentParent(context, parent, baseName)
    }

    private fun readLyricsInDocumentParent(
        context: Context,
        parent: DocumentFile,
        baseName: String,
    ): LyricsDocument? {
        val candidates = mutableListOf<LyricsDocument>()
        sidecarExtensions.forEach { extension ->
            val direct = parent.findFile("$baseName.$extension")
                ?: parent.findFile("$baseName.${extension.uppercase()}")
            if (direct != null && direct.isFile) {
                readLyricsText(context, direct)?.let(::parseLyricsFile)?.let { candidates += it }
            }
        }
        parent.listFiles()?.forEach { child ->
            if (!child.isFile) return@forEach
            val name = child.name ?: return@forEach
            if (sidecarExtensions.any { name.equals("$baseName.$it", ignoreCase = true) }) {
                readLyricsText(context, child)?.let(::parseLyricsFile)?.let { candidates += it }
            }
        }
        return LyricsSanitizer.pickBestDocument(candidates)
    }

    private fun readLyricsInDirectory(dir: File, baseName: String): LyricsDocument? {
        if (!dir.isDirectory) return null
        val candidates = mutableListOf<LyricsDocument>()
        sidecarExtensions.forEach { extension ->
            val exact = File(dir, "$baseName.$extension")
            if (exact.isFile) readLyricsTextFromFile(exact)?.let(::parseLyricsFile)?.let { candidates += it }
        }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (sidecarExtensions.any { file.name.equals("$baseName.$it", ignoreCase = true) }) {
                readLyricsTextFromFile(file)?.let(::parseLyricsFile)?.let { candidates += it }
            }
        }
        return LyricsSanitizer.pickBestDocument(candidates)
    }

    private fun readLyricsText(context: Context, doc: DocumentFile): String? =
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                decodeLyricsBytes(stream.readBytes())
            }
        }.getOrNull()

    private fun readLyricsTextFromFile(file: File): String? =
        runCatching { decodeLyricsBytes(file.readBytes()) }.getOrNull()

    private fun readLyricsByUri(context: Context, uriString: String?): LyricsDocument? {
        if (uriString.isNullOrBlank()) {
            DiagnosticLog.event(LYRICS_TRACE, "uri-read rejected blank-uri")
            return null
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            DiagnosticLog.event(LYRICS_TRACE, "uri-read rejected invalid-uri=$uriString")
            return null
        }
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                DiagnosticLog.event(LYRICS_TRACE, "uri-read open-null uri=$uri")
                return@runCatching null
            }
            stream.use {
                val bytes = it.readBytes()
                val decoded = decodeLyricsBytes(bytes)
                val parsed = parseLyricsFile(decoded)
                DiagnosticLog.event(
                    LYRICS_TRACE,
                    "uri-read parsed uri=$uri bytes=${bytes.size} chars=${decoded.length} " +
                        "format=${if (TtmlLyricsParser.looksLikeTtml(decoded)) "ttml" else "text"} " +
                        "lines=${parsed?.lines?.size ?: 0} tokens=${parsed?.lines?.sumOf { line -> line.tokens.size } ?: 0}",
                )
                parsed
            }
        }.onFailure { error ->
            DiagnosticLog.event(
                LYRICS_TRACE,
                "uri-read failed uri=$uri error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }.getOrNull()
    }

    private fun decodeLyricsBytes(bytes: ByteArray): String =
        LyricsEncoding.decodeBytes(bytes)

    private fun parseLyricsFile(text: String): LyricsDocument? {
        if (text.isBlank()) return null
        val normalized = MetadataTextFix.normalize(text)
        LyricsSanitizer.parseFilteredDocument(normalized, LyricsOrigin.EXTERNAL)
            .takeIf { it.lines.isNotEmpty() }
            ?.let { return it }
        return LyricsSanitizer.finalizeDocument(
            LrcParser.parseDocument(normalized).copy(origin = LyricsOrigin.EXTERNAL),
        ).takeIf { it.lines.isNotEmpty() }
    }
}
