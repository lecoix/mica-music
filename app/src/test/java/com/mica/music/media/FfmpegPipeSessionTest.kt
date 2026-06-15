package com.mica.music.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegPipeSessionTest {

    @Test
    fun destroyClosesStdoutAndTerminatesProcess() {
        val stdout = TrackingInputStream(byteArrayOf(1, 2, 3))
        val process = FakeProcess(stdout)
        val session = FfmpegRunner.RunningSession(process, StringBuilder(), stdout)

        session.destroy()

        assertTrue(stdout.closed)
        assertFalse(session.isAlive)
    }

    @Test
    fun seekedPcmCommandStillTargetsStdoutPipe() {
        val input = File("source.m4a")
        val args = AlacFfmpegHelper.buildArgs(
            seekMs = 12_345,
            input = input,
            output = "pipe:1",
            muxerFormat = "s16le",
            extra = { listOf("-c:a", "pcm_s16le") },
        ).toList()

        assertEquals(input.absolutePath, args[args.indexOf("-i") + 1])
        assertEquals("12.345", args[args.indexOf("-ss") + 1])
        assertEquals("pipe:1", args.last())
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeProcess(
        private val stdout: InputStream,
    ) : Process() {
        private var alive = true
        private val stderr = ByteArrayInputStream(ByteArray(0))
        private val stdin = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun exitValue(): Int {
            check(!alive)
            return 0
        }
        override fun destroy() {
            alive = false
        }
        override fun destroyForcibly(): Process {
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
