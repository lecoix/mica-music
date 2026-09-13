package com.mica.music.data.scanner

import java.io.FileNotFoundException
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderScannerMissingFailureTest {
    @Test
    fun massDeletionVerificationDefaultBudgetIsBounded() {
        val budget = SafMissingVerificationBudget.Default
        assertTrue(budget.maxObjects in 1..64)
        assertTrue(budget.maxQueries in 1..64)
        assertTrue(budget.maxWallTimeMs in 1L..2_000L)
    }

    @Test
    fun tenKMassDeletionVerificationStopsAtSingleBatchBudgetAndReturnsCursor() = runTest {
        val songs = List(10_000) { index ->
            SongFixtures.song("mass-${index.toString().padStart(5, '0')}").copy(
                mediaUri = "content://provider.documents/document/$index",
            )
        }
        var queries = 0
        var clock = 0L

        val first = FolderScanner.verifyMissingObjectsBounded(
            treeAuthority = "provider.documents",
            songs = songs,
            budget = SafMissingVerificationBudget.Default,
            elapsedRealtimeMs = { clock++ },
            queryDocumentExists = {
                queries += 1
                false
            },
        )

        assertTrue(queries <= SafMissingVerificationBudget.DEFAULT_MAX_QUERIES)
        assertTrue(first.processedObjectCount <= SafMissingVerificationBudget.DEFAULT_MAX_OBJECTS)
        assertTrue(first.wallTimeMs <= SafMissingVerificationBudget.DEFAULT_MAX_WALL_TIME_MS)
        assertTrue(first.hasMore)
        assertTrue(first.budgetExhausted)
        assertTrue(first.nextCursor in 1..SafMissingVerificationBudget.DEFAULT_MAX_OBJECTS)

        val firstCursor = first.nextCursor
        val second = FolderScanner.verifyMissingObjectsBounded(
            treeAuthority = "provider.documents",
            songs = songs,
            startCursor = firstCursor,
            budget = SafMissingVerificationBudget.Default,
            elapsedRealtimeMs = { clock++ },
            queryDocumentExists = { false },
        )
        assertTrue(second.nextCursor > firstCursor)
        assertTrue(second.nextCursor <= firstCursor + SafMissingVerificationBudget.DEFAULT_MAX_OBJECTS)
    }



    @Test
    fun directFileNotFoundIsConfirmedForAnyProvider() {
        assertTrue(
            FolderScanner.isConfirmedMissingDocumentFailure(
                authority = "third.party.documents",
                error = FileNotFoundException("gone"),
            ),
        )
    }

    @Test
    fun builtInExternalStorageWrappedFileNotFoundIsConfirmed() {
        val error = IllegalArgumentException(
            "Failed to determine if primary:Music/test/Bench/D01/song.mp3 is child of " +
                "primary:Music/test: java.io.FileNotFoundException: Missing file for " +
                "primary:Music/test/Bench/D01/song.mp3 at /storage/emulated/0/Music/test/Bench/D01/song.mp3",
        )
        assertTrue(
            FolderScanner.isConfirmedMissingDocumentFailure(
                authority = "com.android.externalstorage.documents",
                error = error,
            ),
        )
    }

    @Test
    fun sameWrappedMessageFromThirdPartyProviderStaysIndeterminate() {
        val error = IllegalArgumentException(
            "Failed to determine if doc is child of tree: " +
                "java.io.FileNotFoundException: Missing file for doc at /somewhere/doc",
        )
        assertFalse(
            FolderScanner.isConfirmedMissingDocumentFailure(
                authority = "third.party.documents",
                error = error,
            ),
        )
    }

    @Test
    fun genericIllegalArgumentFromBuiltInProviderStaysIndeterminate() {
        assertFalse(
            FolderScanner.isConfirmedMissingDocumentFailure(
                authority = "com.android.externalstorage.documents",
                error = IllegalArgumentException("Malformed document id"),
            ),
        )
    }

    @Test
    fun securityFailureStaysIndeterminateEvenIfMessageMentionsMissing() {
        assertFalse(
            FolderScanner.isConfirmedMissingDocumentFailure(
                authority = "com.android.externalstorage.documents",
                error = SecurityException("Missing file for doc"),
            ),
        )
    }
}
