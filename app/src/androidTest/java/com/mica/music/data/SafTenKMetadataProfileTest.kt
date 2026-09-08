package com.mica.music.data

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.ScanOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTenKMetadataProfileTest {
    @Test
    fun tenThousandDocumentMetadataWalkCompletesAndReportsExactDirectQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = TestDocumentsProvider.authorityForPackage(context.packageName)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            authority,
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
        TestDocumentsProvider.setScenario(
            TestDocumentsProvider.FixtureScenario.TEN_K_METADATA.name,
        )
        try {
            repeat(3) { index ->
                val startedMs = SystemClock.elapsedRealtime()
                val snapshot = FolderScanner.observeMetadata(
                    context = context.applicationContext,
                    treeUri = treeUri,
                    options = ScanOptions(),
                )
                val externalWallMs = SystemClock.elapsedRealtime() - startedMs
                Log.i(
                    TAG,
                    "iteration=" + (index + 1) +
                        " entries=" + snapshot.entries.size +
                        " completeness=" + snapshot.discoveryReport.aggregate +
                        " observedWallMs=" + snapshot.observationStats.wallTimeMs +
                        " externalWallMs=" + externalWallMs +
                        " providerQueries=" + snapshot.observationStats.providerQueryCount +
                        " directQueries=" + snapshot.observationStats.directQueryCount +
                        " fallbackListings=" + snapshot.observationStats.fallbackListingCount,
                )

                assertEquals(10_000, snapshot.entries.size)
                assertTrue(snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE))
                assertEquals(DiscoveryCompleteness.COMPLETE, snapshot.discoveryReport.aggregate)
                assertEquals(2, snapshot.observationStats.providerQueryCount)
                assertEquals(2, snapshot.observationStats.directQueryCount)
                assertEquals(0, snapshot.observationStats.fallbackListingCount)
            }
        } finally {
            TestDocumentsProvider.resetScenario()
            context.revokeUriPermission(treeUri, flags)
            context.revokeUriPermission(rootDocumentUri, flags)
        }
    }

    private companion object {
        const val TAG = "MICA_S4_10K"
    }
}
