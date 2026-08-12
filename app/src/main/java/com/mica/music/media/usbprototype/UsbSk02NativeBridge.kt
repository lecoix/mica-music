package com.mica.music.media.usbprototype

import com.mica.music.media.usb.UsbOutputRuntime
import java.nio.ByteBuffer

/** JNI surface shared by Debug, Perf, and Release SK02 output providers. */
internal object UsbSk02NativePrototype {
    init {
        System.loadLibrary("usb_sk02_prototype")
    }

    external fun queryInterfaceDriver(fd: Int, interfaceNumber: Int): String
    external fun connectKernelDriver(fd: Int, interfaceNumber: Int): Int
    external fun reconnectKernelDrivers(fd: Int): Int
    external fun readFeedbackOnce(fd: Int): String
    external fun writeSilentPcm16Once(fd: Int): String
    external fun runSilentPcm16Queue(fd: Int, durationMs: Int): String
    external fun publishGeneration(generation: Long)
    external fun runSilentPcm16QueueGeneration(fd: Int, durationMs: Int, generation: Long): String
    external fun runPcm16Queue(fd: Int, durationMs: Int, pcm: ByteArray): String
    external fun runPcm24Queue(fd: Int, durationMs: Int, pcm: ByteArray, sampleRateHz: Int): String
    external fun createMedia3Stream(
        fd: Int,
        sampleRateHz: Int,
        bytesPerFrame: Int,
        maxPacketBytes: Int,
        generation: Long,
    ): Long
    external fun writeMedia3Stream(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
    ): Int
    external fun setMedia3StreamPlaying(handle: Long, playing: Boolean)
    external fun getMedia3CompletedFrames(handle: Long): Long
    external fun getMedia3BufferedFrames(handle: Long): Long
    external fun getMedia3BufferCapacityFrames(handle: Long): Long
    external fun getMedia3MinimumBufferedFrames(handle: Long): Long
    external fun getMedia3AcceptedPcmBytes(handle: Long): Long
    external fun getMedia3PreviousSuccessfulWriteGapUs(handle: Long): Long
    external fun getMedia3MaximumSuccessfulWriteGapUs(handle: Long): Long
    external fun getMedia3PreviousDataCompletionGapUs(handle: Long): Long
    external fun getMedia3MaximumDataCompletionGapUs(handle: Long): Long
    external fun getMedia3PreviousFeedbackCompletionGapUs(handle: Long): Long
    external fun getMedia3MaximumFeedbackCompletionGapUs(handle: Long): Long
    external fun getMedia3TotalPollTimeouts(handle: Long): Long
    external fun getMedia3MaximumConsecutivePollTimeouts(handle: Long): Long
    external fun getMedia3InvalidFeedbackPacketCount(handle: Long): Long
    external fun getMedia3DataPacketErrorCount(handle: Long): Long
    external fun getMedia3CurrentFeedbackQ16(handle: Long): Long
    external fun getMedia3MinimumFeedbackQ16(handle: Long): Long
    external fun getMedia3MaximumFeedbackQ16(handle: Long): Long
    external fun getMedia3MaximumFeedbackStepQ16(handle: Long): Long
    external fun getMedia3TrustedFeedbackQ16(handle: Long): Long
    external fun getMedia3FeedbackFilterInterventionCount(handle: Long): Long
    external fun getMedia3DiagnosticMetrics(handle: Long): LongArray
    external fun getMedia3UnderrunBytes(handle: Long): Long
    external fun getMedia3ErrorCode(handle: Long): Int
    external fun destroyMedia3Stream(handle: Long)
}

internal object UsbPrototypeGenerationOwner {
    val gate = UsbOutputRuntime.owner
}
