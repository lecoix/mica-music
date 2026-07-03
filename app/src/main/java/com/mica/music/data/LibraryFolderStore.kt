package com.mica.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.util.DiagnosticLog

/**
 * 通过 SAF 持久化访问用户选择的曲库目录树。
 */
object LibraryFolderStore {

    fun persistTreeAccess(context: Context, treeUri: Uri) {
        val resolver = context.contentResolver
        val requestedFlags = treeAccessFlags()
        val persistedFlags = runCatching {
            resolver.takePersistableUriPermission(treeUri, requestedFlags)
            requestedFlags
        }.getOrElse { error ->
            DiagnosticLog.event(
                "LibraryFolder",
                "persist read-write failed uri=$treeUri; falling back to read-only",
                error,
            )
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        DiagnosticLog.event(
            "LibraryFolder",
            "persist uri=$treeUri flags=${permissionFlagsLabel(persistedFlags)}",
        )
    }

    fun releaseTreeAccess(context: Context, treeUri: Uri) {
        val resolver = context.contentResolver
        val flags = resolver.persistedUriPermissions
            .firstOrNull { it.uri == treeUri }
            ?.let { permission ->
                var heldFlags = 0
                if (permission.isReadPermission) heldFlags = heldFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) heldFlags = heldFlags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                heldFlags
            }
            ?: 0
        if (flags == 0) {
            DiagnosticLog.event("LibraryFolder", "release skipped no persisted grant uri=$treeUri")
            return
        }
        runCatching {
            resolver.releasePersistableUriPermission(treeUri, flags)
        }.onSuccess {
            DiagnosticLog.event(
                "LibraryFolder",
                "release uri=$treeUri flags=${permissionFlagsLabel(flags)}",
            )
        }.onFailure { error ->
            DiagnosticLog.event(
                "LibraryFolder",
                "release failed uri=$treeUri flags=${permissionFlagsLabel(flags)}",
                error,
            )
        }
    }

    fun displayName(context: Context, treeUri: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        return doc?.name?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "已选文件夹"
    }

    fun canReadTree(context: Context, treeUri: Uri): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        return root.canRead() && root.isDirectory
    }

    internal fun treeAccessFlags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun permissionFlagsLabel(flags: Int): String = buildList {
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) add("read")
        if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) add("write")
    }.ifEmpty { listOf("none") }.joinToString("+")
}
