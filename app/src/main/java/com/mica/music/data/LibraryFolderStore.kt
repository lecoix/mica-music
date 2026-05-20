package com.mica.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 通过 SAF 持久化访问用户选择的曲库目录树。
 */
object LibraryFolderStore {

    fun persistTreeAccess(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
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
}
