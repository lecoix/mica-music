package com.mica.music.data

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Real provider + scanner + Room publication; no MediaStore insert or media-scan signal.
 * This is a component contract, not a DocumentsUI picker or OEM notification test.
 */
@RunWith(AndroidJUnit4::class)
class SafUnindexedDiscoveryContractTest {
    @get:Rule val deviceHost = ScannerDeviceHostRule()

    @Test
    fun emptyDirectoryThenUnindexedAudioIsDiscoveredPersistedAndRestored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa")) { "Requires isolated QA application" }
        val tree = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.authorityForPackage(context.packageName),
            TestDocumentsProvider.ROOT_ID,
        )
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, TestDocumentsProvider.ROOT_ID)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val lease = "unindexed-discovery-contract"
        TestDocumentsProvider.beginExclusiveLease(lease, "EMPTY_DIRECTORY")
        context.grantUriPermission(context.packageName, tree, flags)
        context.grantUriPermission(context.packageName, root, flags)
        var library: MusicLibrary? = null
        try {
            val empty = FolderScanner.observeMetadata(context, tree, ScanOptions())
            assertTrue(empty.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE))
            assertTrue(empty.entries.isEmpty())
            val first = withContext(Dispatchers.Main) { MusicLibrary(context) }
            library = first
            withContext(Dispatchers.Main) { first.setLibraryFolder(tree) }
            first.scanLibraryFolder()
            assertNull(first.lastScanError)
            assertTrue(first.hasScanned)
            assertTrue(first.songs.isEmpty())

            // Fixture bytes live in the QA app's private cache, never in a MediaStore collection.
            TestDocumentsProvider.setScenarioWithinLease(lease, "BASELINE")
            first.scanLibraryFolder()
            assertNull(first.lastScanError)
            val song = first.songs.single()
            assertEquals("Music", song.folderPath)
            assertTrue(song.mediaUri.startsWith("content://${tree.authority}/"))
            val durable = requireNotNull(RoomLibraryStore(context).loadCached())
            assertEquals(listOf(song.id), durable.songs.map { it.id })
            assertTrue(context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission
            })

            withContext(Dispatchers.Main) { first.release() }
            val restored = withContext(Dispatchers.Main) { MusicLibrary(context) }
            library = restored
            restored.loadCachedLibrary()
            assertEquals(tree.toString(), restored.libraryFolderUri)
            assertEquals(listOf(song.id), restored.songs.map { it.id })
            restored.scanLibraryFolder()
            assertNull(restored.lastScanError)
            assertEquals(listOf(song.id), restored.songs.map { it.id })
        } finally {
            withContext(Dispatchers.Main) { library?.release() }
            LibraryFolderStore.releaseTreeAccess(context, tree)
            context.revokeUriPermission(tree, flags)
            context.revokeUriPermission(root, flags)
            TestDocumentsProvider.endExclusiveLease(lease)
            TestDocumentsProvider.resetScenario()
        }
    }
}
