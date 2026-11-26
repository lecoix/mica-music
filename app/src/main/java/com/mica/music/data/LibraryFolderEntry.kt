package com.mica.music.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.mica.music.data.scanner.ExcludedScanDirectories

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
     * Duplicate root labels are disambiguated in canonical URI order so manual scans (user
     * selection order) and AUTO sync (sorted URI order) assign identical prefixes to each tree.
     */
    fun rootPrefixes(context: Context, treeUris: List<Uri>): List<String> {
        if (treeUris.size <= 1) return List(treeUris.size) { "" }
        val keyedLabels = treeUris.map { uri ->
            val raw = externalStorageRootLabel(uri)
                ?: LibraryFolderStore.displayName(context, uri)
                    .trim('/')
                    .ifBlank { fallbackLabelFromUri(uri.toString()) }
            uri.toString() to raw
        }
        return disambiguateRootLabelsByKey(keyedLabels)
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

    internal fun disambiguateRootLabelsByKey(items: List<Pair<String, String>>): List<String> {
        if (items.size <= 1) return List(items.size) { "" }
        val seenCounts = mutableMapOf<String, Int>()
        val prefixByKey = HashMap<String, String>(items.size)
        items.distinctBy { it.first }
            .sortedBy { it.first }
            .forEach { (key, raw) ->
                val base = raw.trim().trim('/').ifEmpty { "文件夹" }
                val count = (seenCounts[base] ?: 0) + 1
                seenCounts[base] = count
                prefixByKey[key] = if (count == 1) base else "$base ($count)"
            }
        return items.map { (key, _) -> prefixByKey.getValue(key) }
    }

    /**
     * Migrates `excluded_scan_directories` across SAF single<->multi folder transitions (and
     * multi->multi disambiguation prefix shifts) so previously excluded folders stay excluded.
     */
    fun migrateExcludedDirectories(
        context: Context,
        excludedDirectories: List<String>,
        previousTreeUris: List<Uri>,
        nextTreeUris: List<Uri>,
    ): List<String> {
        if (excludedDirectories.isEmpty() || previousTreeUris.isEmpty() || nextTreeUris.isEmpty()) {
            return ExcludedScanDirectories.normalizeAll(excludedDirectories)
        }
        return migrateExcludedDirectoriesWithPrefixes(
            excludedDirectories = excludedDirectories,
            previousTreeUris = previousTreeUris.map(Uri::toString),
            previousPrefixes = rootPrefixes(context, previousTreeUris),
            nextTreeUris = nextTreeUris.map(Uri::toString),
            nextPrefixes = rootPrefixes(context, nextTreeUris),
        )
    }

    internal fun migrateExcludedDirectoriesWithPrefixes(
        excludedDirectories: List<String>,
        previousTreeUris: List<String>,
        previousPrefixes: List<String>,
        nextTreeUris: List<String>,
        nextPrefixes: List<String>,
    ): List<String> {
        val normalized = ExcludedScanDirectories.normalizeAll(excludedDirectories)
        if (normalized.isEmpty() || previousTreeUris.isEmpty() || nextTreeUris.isEmpty()) {
            return normalized
        }
        val prevByUri = previousTreeUris.zip(previousPrefixes).toMap()
        val nextByUri = nextTreeUris.zip(nextPrefixes).toMap()
        if (prevByUri.keys == nextByUri.keys && prevByUri == nextByUri) {
            return normalized
        }

        val migrated = when {
            // Single -> Multi: qualify relative single-folder rules with that tree's new prefix.
            previousTreeUris.size == 1 && nextTreeUris.size > 1 -> {
                val oldUri = previousTreeUris.first()
                val newPrefix = nextByUri[oldUri]?.trim('/').orEmpty()
                if (newPrefix.isEmpty()) {
                    normalized
                } else {
                    val knownNextPrefixes = nextPrefixes
                        .map { it.trim('/') }
                        .filter(String::isNotEmpty)
                    normalized.map { rule ->
                        val alreadyQualified = knownNextPrefixes.any { prefix ->
                            rule.equals(prefix, ignoreCase = true) ||
                                rule.startsWith("$prefix/", ignoreCase = true)
                        }
                        if (alreadyQualified) rule else "$newPrefix/$rule"
                    }
                }
            }
            // Multi -> Single: strip the remaining tree's former multi-folder prefix.
            previousTreeUris.size > 1 && nextTreeUris.size == 1 -> {
                val remainingUri = nextTreeUris.first()
                val oldPrefix = prevByUri[remainingUri]?.trim('/').orEmpty()
                if (oldPrefix.isEmpty()) {
                    normalized
                } else {
                    normalized.map { rule ->
                        if (rule.startsWith("$oldPrefix/", ignoreCase = true)) {
                            rule.substring(oldPrefix.length + 1)
                        } else {
                            rule
                        }
                    }
                }
            }
            // Multi -> Multi: remap rules whose tree changed disambiguated prefix (e.g. "Music (2)" -> "Music").
            previousTreeUris.size > 1 && nextTreeUris.size > 1 -> {
                val renames = prevByUri.entries.mapNotNull { (uri, oldRaw) ->
                    val newRaw = nextByUri[uri] ?: return@mapNotNull null
                    val oldPrefix = oldRaw.trim('/')
                    val newPrefix = newRaw.trim('/')
                    if (oldPrefix.isNotEmpty() && newPrefix.isNotEmpty() && !oldPrefix.equals(newPrefix, ignoreCase = true)) {
                        oldPrefix to newPrefix
                    } else {
                        null
                    }
                }
                if (renames.isEmpty()) {
                    normalized
                } else {
                    normalized.map { rule ->
                        val matched = renames.firstOrNull { (oldPrefix, _) ->
                            rule.equals(oldPrefix, ignoreCase = true) ||
                                rule.startsWith("$oldPrefix/", ignoreCase = true)
                        }
                        when {
                            matched == null -> rule
                            rule.equals(matched.first, ignoreCase = true) -> matched.second
                            else -> "${matched.second}/${rule.substring(matched.first.length + 1)}"
                        }
                    }
                }
            }
            else -> normalized
        }
        return ExcludedScanDirectories.normalizeAll(migrated)
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
