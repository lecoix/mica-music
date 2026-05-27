package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.LyricLine
import java.io.File
/**
 * 读取与音频同目录的外挂 `.lrc`（同名或 displayName 去扩展名）。
 */
internal object ExternalLyricsReader {

    fun readDirectUri(context: Context, uriString: String?): List<LyricLine>? =
        readLrcByUri(context, uriString)

    fun read(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String,
        parentDirectory: DocumentFile? = null,
        directLyricsUri: String? = null,
    ): List<LyricLine> {
        val candidates = mutableListOf<List<LyricLine>>()
        readLrcByUri(context, directLyricsUri)?.let { candidates += it }
        for (base in basenameCandidates(displayName, filePath)) {
            parentDirectory
                ?.takeIf { it.isDirectory }
                ?.let { readLrcInDocumentParent(context, it, base) }
                ?.let { candidates += it }
            readLrcByAbsolutePath(filePath, base)?.let { candidates += it }
            readLrcViaDocumentTree(context, uri, base)?.let { candidates += it }
        }
        return LyricsSanitizer.pickBest(candidates) ?: emptyList()
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

    private fun readLrcByAbsolutePath(audioPath: String, baseName: String): List<LyricLine>? {
        if (audioPath.isBlank()) return null
        val audioFile = File(audioPath)
        val parent = when {
            audioFile.isFile -> audioFile.parentFile
            '/' in audioPath -> File(audioPath.substringBeforeLast('/'))
            else -> null
        } ?: return null
        return readLrcInDirectory(parent, baseName)
    }

    private fun readLrcViaDocumentTree(
        context: Context,
        audioUri: Uri,
        baseName: String,
    ): List<LyricLine>? {
        if (!DocumentsContract.isDocumentUri(context, audioUri)) return null
        val audioDoc = DocumentFile.fromSingleUri(context, audioUri) ?: return null
        val parent = audioDoc.parentFile ?: return null
        val docBase = audioDoc.name?.substringBeforeLast('.')?.trim().orEmpty()
        if (docBase.isNotEmpty() && docBase != baseName) {
            readLrcInDocumentParent(context, parent, docBase)?.let { return it }
        }
        return readLrcInDocumentParent(context, parent, baseName)
    }

    private fun readLrcInDocumentParent(
        context: Context,
        parent: DocumentFile,
        baseName: String,
    ): List<LyricLine>? {
        val direct = parent.findFile("$baseName.lrc")
            ?: parent.findFile("$baseName.LRC")
        if (direct != null && direct.isFile) {
            readLrcText(context, direct)?.let { return parseLrcFile(it) }
        }
        parent.listFiles()?.forEach { child ->
            if (!child.isFile) return@forEach
            val name = child.name ?: return@forEach
            if (name.equals("$baseName.lrc", ignoreCase = true)) {
                readLrcText(context, child)?.let { return parseLrcFile(it) }
            }
        }
        return null
    }

    private fun readLrcInDirectory(dir: File, baseName: String): List<LyricLine>? {
        if (!dir.isDirectory) return null
        val exact = File(dir, "$baseName.lrc")
        if (exact.isFile) {
            readLrcTextFromFile(exact)?.let { return parseLrcFile(it) }
        }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.equals("$baseName.lrc", ignoreCase = true)) {
                readLrcTextFromFile(file)?.let { return parseLrcFile(it) }
            }
        }
        return null
    }

    private fun readLrcText(context: Context, doc: DocumentFile): String? =
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                decodeLrcBytes(stream.readBytes())
            }
        }.getOrNull()

    private fun readLrcTextFromFile(file: File): String? =
        runCatching { decodeLrcBytes(file.readBytes()) }.getOrNull()

    private fun readLrcByUri(context: Context, uriString: String?): List<LyricLine>? {
        if (uriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseLrcFile(decodeLrcBytes(stream.readBytes()))
            }
        }.getOrNull()
    }

    private fun decodeLrcBytes(bytes: ByteArray): String =
        LyricsEncoding.decodeBytes(bytes)

    private fun parseLrcFile(text: String): List<LyricLine>? {
        if (text.isBlank()) return null
        val normalized = MetadataTextFix.normalize(text)
        LyricsSanitizer.parseFiltered(normalized).takeIf { it.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalize(LrcParser.parse(normalized)).takeIf { it.isNotEmpty() }
    }
}
