package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteOperationToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SmbBrowserTest {
    @Test fun directoryReturningAfterCloseCannotPublish() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = SmbBrowseOwner { source, path ->
            entered.complete(Unit)
            release.await()
            SmbDirectorySnapshot(RemoteOperationToken(source, 1, 1), path, emptyList(), true)
        }
        val old = async { owner.load("smb", "old") }
        entered.await()
        owner.close()
        release.complete(Unit)
        old.await()
        assertEquals(SmbBrowseState(), owner.state.value)
    }

    @Test fun oldNonCancellableListingCannotReplaceNewDirectory() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = SmbBrowseOwner { source, path ->
            if (path == "old") { entered.complete(Unit); release.await() }
            SmbDirectorySnapshot(RemoteOperationToken(source, 1, 1), path, emptyList(), true)
        }
        val old = async { owner.load("smb", "old") }
        entered.await()
        owner.load("smb", "new")
        release.complete(Unit)
        old.await()
        assertEquals("new", owner.state.value.snapshot!!.path)
        owner.close()
        assertNull(owner.state.value.snapshot)
    }
}
