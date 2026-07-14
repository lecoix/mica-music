package com.mica.music.testutil

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.media.MicaMediaService
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

object ContractTestSupport {
    const val SAMPLE_RATE = 8_000
    private const val BYTES_PER_SAMPLE = 2

    fun createSilentWav(directory: File, id: String, durationSeconds: Int): File {
        val dataSize = SAMPLE_RATE * durationSeconds * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        return File(directory, "mica-contract-$id.wav").also { file ->
            FileOutputStream(file).use { output ->
                output.write(header)
                output.write(ByteArray(dataSize))
            }
        }
    }

    fun await(label: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    @UnstableApi
    fun connectMediaService(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, MicaMediaService::class.java))
        return MediaController.Builder(context, token)
            .buildAsync()
            .get(10, TimeUnit.SECONDS)
    }

    fun stopMediaServiceAndAwaitDestruction(context: Context) {
        context.stopService(Intent(context, MicaMediaService::class.java))
        await("MicaMediaService destruction", timeoutMs = 10_000L) {
            !isMediaServiceRunning(context)
        }
    }

    @Suppress("DEPRECATION")
    fun isMediaServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
            service.service.className == MicaMediaService::class.java.name
        }
    }
}
