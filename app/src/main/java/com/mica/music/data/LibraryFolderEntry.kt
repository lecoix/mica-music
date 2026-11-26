package com.mica.music.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Authorized SAF tree entry persisted for FOLDER library scans. */
data class LibraryFolderEntry(
    val treeUri: String,
    val label: String,
)

internal enum class LibraryFolderAddOutcome {
    ADDED,
    ALREADY_PRESENT,
    COVERED_BY_EXISTING,
    REPLACED_SUBFOLDERS,
}

internal data class LibraryFolderAddResult(
    val folders: List<LibraryFolderEntry>,
    val outcome: LibraryFolderAddOutcome,
    val coveringParentLabel: String? = null,
)

internal object LibraryFolderSet {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun normalize(entries: List<LibraryFolderEntry>): List<LibraryFolderEntry> {
        val unique = LinkedHashMap<String, LibraryFolderEntry>()
        for (entry in entries) {
            val uri = entry.treeUri.trim()
            if (uri.isEmpty()) continue
            val cleanLabel = entry.label.trim().ifEmpty { fallbackLabelFromUri(uri) }
            if (uri !in unique) {
                unique[uri] = LibraryFolderEntry(treeUri = uri, label = cleanLabel)
            }
        }
        val list = unique.values.toList()
        if (list.size <= 1) return list
        return list.filter { candidate ->
            list.none { other ->
                other.treeUri != candidate.treeUri && coversTree(other.treeUri, candidate.treeUri)
            }
        }
    }

    fun add(
        existing: List<LibraryFolderEntry>,
        candidate: LibraryFolderEntry,
    ): LibraryFolderAddResult {
        val current = normalize(existing)
        val uri = candidate.treeUri.trim()
        if (uri.isEmpty()) {
            return LibraryFolderAddResult(current, LibraryFolderAddOutcome.ALREADY_PRESENT)
        }
        val clean = LibraryFolderEntry(
            treeUri = uri,
            label = candidate.label.trim().ifEmpty { fallbackLabelFromUri(uri) },
        )
        if (current.any { it.treeUri == clean.treeUri }) {
            return LibraryFolderAddResult(current, LibraryFolderAddOutcome.ALREADY_PRESENT)
        }
        val coveringParent = current.firstOrNull { coversTree(it.treeUri, clean.treeUri) }
        if (coveringParent != null) {
            return LibraryFolderAddResult(
                folders = current,
                outcome = LibraryFolderAddOutcome.COVERED_BY_EXISTING,
                coveringParentLabel = coveringParent.label,
            )
        }
        val hadCoveredChildren = current.any { coversTree(clean.treeUri, it.treeUri) }
        val updated = normalize(current + clean)
        return LibraryFolderAddResult(
            folders = updated,
            outcome = if (hadCoveredChildren) {
                LibraryFolderAddOutcome.REPLACED_SUBFOLDERS
            } else {
                LibraryFolderAddOutcome.ADDED
            },
        )
    }

    fun remove(
        existing: List<LibraryFolderEntry>,
        treeUri: String,
    ): List<LibraryFolderEntry> = normalize(existing.filterNot { it.treeUri == treeUri })

    fun formatLabel(entries: List<LibraryFolderEntry>): String? = when (entries.size) {
        0 -> null
        1 -> entries[0].label
        2 -> "${entries[0].label}、${entries[1].label}"
        else -> "${entries[0].label}、${entries[1].label} 等 ${entries.size} 个文件夹"
    }

    /**
     * Deterministic per-tree folderPath prefixes for SAF scanning.
     * Single-folder libraries keep `""` so existing folderPath/exclusion rules stay unchanged.
     */
    fun rootPrefixes(context: Context, treeUris: List<Uri>): List<String> {
        if (treeUris.size <= 1) return List(treeUris.size) { "" }
        val rawLabels = treeUris.map { uri ->
            externalStorageRootLabel(uri)
                ?: LibraryFolderStore.displayName(context, uri)
                    .trim('/')
                    .ifBlank { fallbackLabelFromUri(uri.toString()) }
        }
        return disambiguateRootLabels(rawLabels)
    }

    internal fun disambiguateRootLabels(rawLabels: List<String>): List<String> {
        if (rawLabels.size <= 1) return List(rawLabels.size) { "" }
        val seenCounts = mutableMapOf<String, Int>()
        return rawLabels.map { raw ->
            val base = raw.trim().trim('/').ifEmpty { "文件夹" }
            val count = (seenCounts[base] ?: 0) + 1
            seenCounts[base] = count
            if (count == 1) base else "$base ($count)"
        }
    }

    internal fun coversTree(parentUriString: String, childUriString: String): Boolean {
        if (parentUriString == childUriString) return true
        val parent = parseExternalStorageTree(parentUriString) ?: return false
        val child = parseExternalStorageTree(childUriString) ?: return false
        if (parent.volume != child.volume) return false
        if (parent.path.isEmpty()) return true
        return child.path == parent.path || child.path.startsWith("${parent.path}/")
    }

    private data class ExternalStorageTree(
        val volume: String,
        val path: String,
    )

    private fun parseExternalStorageTree(uriString: String): ExternalStorageTree? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = extractTreeDocumentId(uri) ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        val path = docId.substring(sep + 1)
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("/")
        return ExternalStorageTree(volume = volume, path = path)
    }

    private fun externalStorageRootLabel(uri: Uri): String? {
        val parsed = parseExternalStorageTree(uri.toString()) ?: return null
        return when {
            parsed.path.isNotEmpty() && parsed.volume == "primary" -> parsed.path
            parsed.path.isNotEmpty() -> "${parsed.volume}/${parsed.path}"
            else -> parsed.volume
        }
    }

    private fun extractTreeDocumentId(uri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: run {
                val segments = uri.pathSegments
                val treeIdx = segments.indexOf("tree")
                if (treeIdx >= 0 && treeIdx + 1 < segments.size) segments[treeIdx + 1] else null
            }

    private fun fallbackLabelFromUri(uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        val parsed = parseExternalStorageTree(uriString)
        if (parsed != null) {
            return parsed.path.substringAfterLast('/').ifBlank { parsed.volume }
        }
        return uri?.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "已选文件夹"
    }
}
