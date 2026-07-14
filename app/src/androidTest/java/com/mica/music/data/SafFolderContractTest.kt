package com.mica.music.data

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.ScanOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafFolderContractTest {
    @Test
    fun persistedTreeGrantSurvivesResolverReentryAndScansProviderCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            TestDocumentsProvider.ROOT_ID,
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        context.grantUriPermission(context.packageName, treeUri, flags)
        context.grantUriPermission(context.packageName, rootDocumentUri, flags)
        try {
            LibraryFolderStore.persistTreeAccess(context, treeUri)

            val persisted = context.contentResolver.persistedUriPermissions
                .single { it.uri == treeUri }
            assertTrue(persisted.isReadPermission)
            assertTrue(persisted.isWritePermission)
            val root = DocumentFile.fromTreeUri(context.applicationContext, treeUri)
            assertNotNull(root)
            val queriedType = context.contentResolver.query(
                requireNotNull(root).uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, queriedType)
            assertTrue(root?.isDirectory == true)
            assertTrue("root is not readable uri=${root?.uri}", root?.canRead() == true)
            assertTrue(LibraryFolderStore.canReadTree(context.applicationContext, treeUri))

            val result = FolderScanner.scan(
                context = context.applicationContext,
                treeUri = treeUri,
                options = ScanOptions(deepMetadataProbe = false),
            )

            assertEquals(1, result.songs.size)
            assertEquals("Music", result.songs.single().folderPath)
            assertTrue(result.songs.single().mediaUri.contains(TestDocumentsProvider.AUTHORITY))
        } finally {
            LibraryFolderStore.releaseTreeAccess(context, treeUri)
            context.revokeUriPermission(treeUri, flags)
            context.revokeUriPermission(rootDocumentUri, flags)
        }

        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == treeUri })
    }
}
