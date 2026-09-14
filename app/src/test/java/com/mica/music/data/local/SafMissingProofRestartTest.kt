package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.library.LibraryAutoSyncStateMutation
import com.mica.music.data.library.LibraryRetryCursor
import com.mica.music.data.library.MassDeletionQuarantineReason
import com.mica.music.data.library.SafMassDeletionConfirmationPlanner
import com.mica.music.data.library.SourceIdentityKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafMissingProofRestartTest {
    @Test
    fun partialPresentRevokesProofAcrossDatabaseCloseAndReopenWithoutDroppingDebt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "saf-proof-restart-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, MicaDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val source = SourceIdentityKey.folder("content://provider/tree/music")
            val keys = (0 until 80).mapTo(linkedSetOf()) { "doc-${it.toString().padStart(3, '0')}" }
            val firstBatch = keys.take(64).toSet()
            val returned = firstBatch.first()
            val existing = SafMassDeletionConfirmationPlanner.plan(
                sourceIdentity = source, activationEpoch = 7L, nowMs = 1_000L,
                quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
                removedStableObjectKeys = keys, existing = null,
            ).retryUpserts.single().copy(
                continuationCursor = 64,
                confirmedMissingKeysPayload = SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(firstBatch),
                attemptCount = 4,
                nextRetryAtMs = 500_000L,
            )
            LibraryRepository(database).applyAutoSyncState(
                LibraryAutoSyncStateMutation(sourceIdentity = source, retryUpserts = listOf(existing)),
            )
            database.close()
            database = open()
            var repository = LibraryRepository(database)
            val loaded = repository.loadRetryItemsPage(source, LibraryRetryCursor.Start, 64).items.single()
            assertEquals(existing, loaded)
            val partial = SafMassDeletionConfirmationPlanner.plan(
                sourceIdentity = source, activationEpoch = 7L, nowMs = 100_000L,
                discoveryComplete = false, quarantineReason = null,
                removedStableObjectKeys = emptySet(), existing = loaded,
                observedPresentStableObjectKeys = setOf(returned),
            )
            repository.applyAutoSyncState(LibraryAutoSyncStateMutation(
                sourceIdentity = source, retryUpserts = partial.retryUpserts,
                retryDeleteKeys = partial.retryDeleteKeys,
            ))
            database.close()
            database = open()
            repository = LibraryRepository(database)
            val recovered = repository.loadRetryItemsPage(source, LibraryRetryCursor.Start, 64).items.single()
            assertEquals(4, recovered.attemptCount)
            assertEquals(500_000L, recovered.nextRetryAtMs)
            assertEquals(0, SafMassDeletionConfirmationPlanner.continuationCursor(recovered, 7L, keys))
            val proof = SafMassDeletionConfirmationPlanner.confirmedMissingKeys(recovered, 7L, keys)
            assertFalse(returned in proof)
            assertEquals(firstBatch - returned, proof)
            // Reusing only a late tail batch cannot authorize deletion after the positive fact.
            val accumulated = SafMassDeletionConfirmationPlanner.accumulateConfirmedMissingKeys(
                recovered, 7L, keys, keys - firstBatch,
            )
            assertFalse(accumulated.containsAll(keys))
            assertTrue(partial.retryDeleteKeys.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
