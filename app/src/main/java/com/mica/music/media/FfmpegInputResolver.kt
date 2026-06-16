package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream

/**
 * FFmpeg 只支持文件路径输入；本对象在 copy 到 cache 之前，先尝试把播放 URI 解析为可读的真实路径。
 */
internal object FfmpegInputResolver {

    const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

    data class ResolvedInput(
        val file: File,
        /** 为 true 时播放结束应删除 [file]（copy 出的临时文件）。 */
        val deleteOnRelease: Boolean,
    )

    /**
     * 优先 [resolveDirectFile]；失败则将 URI 流式 copy 到 [tempFile]。
     */
    fun resolveForFfmpeg(
        context: Context,
        uri: Uri,
        tempFile: File,
    ): ResolvedInput? {
        resolveDirectFile(uri)?.let { direct ->
            return ResolvedInput(file = direct, deleteOnRelease = false)
        }
        return copyUriToTemp(context, uri, tempFile)?.let { copied ->
            ResolvedInput(file = copied, deleteOnRelease = true)
        }
    }

    /** 将可直连的 URI 解析为磁盘 [File]；无法解析或不可读时返回 null。 */
    fun resolveDirectFile(uri: Uri): File? {
        val candidate = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)
            "content" -> resolveExternalStorageDocument(uri)
            else -> null
        } ?: return null
        val readable = runCatching {
            candidate.isFile &&
                candidate.length() > 0L &&
                FileInputStream(candidate).use { it.read() >= 0 }
        }.getOrDefault(false)
        return candidate.takeIf { readable }
    }

    internal fun resolveExternalStorageDocument(
        uri: Uri,
        primaryStorageRoot: File = Environment.getExternalStorageDirectory(),
        volumeRoot: (String) -> File = { volume ->
            if (volume.equals("primary", ignoreCase = true)) {
                primaryStorageRoot
            } else {
                File("/storage", volume)
            }
        },
    ): File? {
        if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
        val documentId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return null
        return resolveExternalStorageDocumentId(
            documentId = documentId,
            primaryStorageRoot = primaryStorageRoot,
            volumeRoot = volumeRoot,
        )
    }

    /**
     * 将 `primary:Music/song.flac` 形式的 documentId 解析为规范路径，并校验不越出卷根目录。
     */
    internal fun resolveExternalStorageDocumentId(
        documentId: String,
        primaryStorageRoot: File,
        volumeRoot: (String) -> File = { volume ->
            if (volume.equals("primary", ignoreCase = true)) {
                primaryStorageRoot
            } else {
                File("/storage", volume)
            }
        },
    ): File? {
        val separator = documentId.indexOf(':')
        if (separator <= 0 || separator == documentId.lastIndex) return null
        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val root = runCatching { volumeRoot(volume).canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching {
            File(root, relativePath).canonicalFile
        }.getOrNull() ?: return null
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return candidate.takeIf { it.path.startsWith(rootPath) }
    }

    private fun copyUriToTemp(context: Context, uri: Uri, tempFile: File): File? {
        tempFile.parentFile?.mkdirs()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (tempFile.length() <= 0L) {
                tempFile.delete()
                null
            } else {
                tempFile
            }
        } catch (_: Exception) {
            tempFile.delete()
            null
        }
    }
}
