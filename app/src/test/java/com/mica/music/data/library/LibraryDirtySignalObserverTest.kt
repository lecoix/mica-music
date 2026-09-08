package com.mica.music.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryDirtySignalObserverTest {

    @Test
    fun foregroundRegistersShadowSignalsAndBackgroundUnregistersThem() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val causes = mutableListOf<LibraryOperationCause>()
        val observer = LibraryDirtySignalObserver(
            context = context,
            scope = this,
            markDirty = { cause ->
                causes += cause
                causes.size.toLong()
            },
            activeSource = { ScanSource.DEVICE },
            safVerifyIntervalMs = 60_000L,
        )

        observer.onForegroundChanged(true)
        assertEquals(listOf(LibraryOperationCause.FOREGROUND_CATCH_UP), causes)

        context.contentResolver.notifyChange(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            null,
        )
        context.contentResolver.notifyChange(
            MediaStore.Files.getContentUri("external"),
            null,
        )
        context.sendBroadcast(
            Intent(
                Intent.ACTION_MEDIA_SCANNER_FINISHED,
                Uri.parse("file:///storage/emulated/0"),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(
                LibraryOperationCause.FOREGROUND_CATCH_UP,
                LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
                LibraryOperationCause.MEDIA_SCANNER_FINISHED,
            ),
            causes,
        )

        observer.onForegroundChanged(false)
        val beforeBackgroundSignals = causes.size
        context.contentResolver.notifyChange(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            null,
        )
        context.sendBroadcast(
            Intent(
                Intent.ACTION_MEDIA_SCANNER_FINISHED,
                Uri.parse("file:///storage/emulated/0"),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(beforeBackgroundSignals, causes.size)
        observer.release()
    }

    @Test
    fun mountedStorageBroadcastBecomesStorageChangedDirtySignal() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val causes = mutableListOf<LibraryOperationCause>()
        val observer = LibraryDirtySignalObserver(
            context = context,
            scope = this,
            markDirty = { cause ->
                causes += cause
                causes.size.toLong()
            },
            activeSource = { ScanSource.DEVICE },
        )

        observer.onForegroundChanged(true)
        context.sendBroadcast(
            Intent(
                Intent.ACTION_MEDIA_MOUNTED,
                Uri.parse("file:///storage/1234-5678"),
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(
                LibraryOperationCause.FOREGROUND_CATCH_UP,
                LibraryOperationCause.STORAGE_CHANGED,
            ),
            causes,
        )
        observer.release()
    }

    @Test
    fun folderForegroundGetsPeriodicSafCompensationAndSourceSwitchStopsIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val causes = mutableListOf<LibraryOperationCause>()
        var source = ScanSource.FOLDER
        val observer = LibraryDirtySignalObserver(
            context = context,
            scope = this,
            markDirty = { cause ->
                causes += cause
                causes.size.toLong()
            },
            activeSource = { source },
            safVerifyIntervalMs = 1_000L,
        )

        observer.onForegroundChanged(true)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(
            listOf(
                LibraryOperationCause.FOREGROUND_CATCH_UP,
                LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
            causes,
        )

        source = ScanSource.DEVICE
        observer.onActiveSourceChanged()
        assertEquals(
            LibraryOperationCause.FOREGROUND_CATCH_UP,
            causes.last(),
        )
        val before = causes.size
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(before, causes.size)
        assertFalse(causes.isEmpty())
        observer.release()
    }

    @Test
    fun sourceActivationWhileForegroundReissuesCatchUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val causes = mutableListOf<LibraryOperationCause>()
        var source: ScanSource? = null
        val observer = LibraryDirtySignalObserver(
            context = context,
            scope = this,
            markDirty = { cause ->
                causes += cause
                causes.size.toLong()
            },
            activeSource = { source },
        )

        observer.onForegroundChanged(true)
        assertEquals(listOf(LibraryOperationCause.FOREGROUND_CATCH_UP), causes)

        source = ScanSource.DEVICE
        observer.onActiveSourceChanged()

        assertEquals(
            listOf(
                LibraryOperationCause.FOREGROUND_CATCH_UP,
                LibraryOperationCause.FOREGROUND_CATCH_UP,
            ),
            causes,
        )
        observer.release()
    }

    @Test
    fun releaseIsIdempotentAndSuppressesFutureForegroundSignals() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val causes = mutableListOf<LibraryOperationCause>()
        val observer = LibraryDirtySignalObserver(
            context = context,
            scope = this,
            markDirty = { cause ->
                causes += cause
                causes.size.toLong()
            },
            activeSource = { ScanSource.DEVICE },
        )

        observer.release()
        observer.release()
        observer.onForegroundChanged(true)

        assertTrue(causes.isEmpty())
    }
}
