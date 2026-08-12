package com.mica.music.media.usbprototype

import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbFeedbackRawTimeUnit
import com.mica.music.media.usb.UsbTransportConfig
import java.nio.ByteBuffer

internal data class UsbNativeTransportArgs(
    val nominalRuntimeFrameRateHz: Long,
    val dataEndpointAddress: Int,
    val bytesPerRuntimeFrame: Int,
    val dataMaxBytesPerServiceInterval: Int,
    val dataServicePeriodNumerator: Long,
    val dataServicePeriodDenominator: Long,
    val packetsPerTransfer: Int,
    val dataQueueDepth: Int,
    val feedbackEndpointAddress: Int,
    val feedbackEndpointCapacityBytesPerServiceInterval: Int,
    val feedbackExpectedPayloadBytes: Int,
    val feedbackFractionalBits: Int,
    val feedbackRawTimeUnit: Int,
    val feedbackRawToDataScaleNumerator: Long,
    val feedbackRawToDataScaleDenominator: Long,
    val feedbackPollPeriodNumerator: Long,
    val feedbackPollPeriodDenominator: Long,
    val feedbackRequiredZeroMask: Long,
) {
    companion object {
        const val RAW_TIME_UNIT_NONE = -1
        const val RAW_TIME_UNIT_BUS_FRAME = 0
        const val RAW_TIME_UNIT_MICROFRAME = 1

        fun from(config: UsbTransportConfig): UsbNativeTransportArgs {
            val feedback = config.feedback
            return UsbNativeTransportArgs(
                nominalRuntimeFrameRateHz = config.nominalRuntimeFrameRateHz,
                dataEndpointAddress = config.dataEndpointAddress,
                bytesPerRuntimeFrame = config.bytesPerRuntimeFrame,
                dataMaxBytesPerServiceInterval = config.dataMaxBytesPerServiceInterval,
                dataServicePeriodNumerator = config.dataServicePeriodSeconds.numerator,
                dataServicePeriodDenominator = config.dataServicePeriodSeconds.denominator,
                packetsPerTransfer = config.packetsPerTransfer,
                dataQueueDepth = config.dataQueueDepth,
                feedbackEndpointAddress = feedback?.endpointAddress ?: 0,
                feedbackEndpointCapacityBytesPerServiceInterval =
                    feedback?.endpointCapacityBytesPerServiceInterval ?: 0,
                feedbackExpectedPayloadBytes = feedback?.expectedPayloadBytes ?: 0,
                feedbackFractionalBits = feedback?.fractionalBits ?: 0,
                feedbackRawTimeUnit = when (feedback?.rawTimeUnit) {
                    UsbFeedbackRawTimeUnit.FRAMES_PER_USB_FRAME -> RAW_TIME_UNIT_BUS_FRAME
                    UsbFeedbackRawTimeUnit.FRAMES_PER_USB_MICROFRAME -> RAW_TIME_UNIT_MICROFRAME
                    null -> RAW_TIME_UNIT_NONE
                },
                feedbackRawToDataScaleNumerator =
                    feedback?.rawToDataServiceIntervalScale?.numerator ?: 0,
                feedbackRawToDataScaleDenominator =
                    feedback?.rawToDataServiceIntervalScale?.denominator ?: 0,
                feedbackPollPeriodNumerator = feedback?.pollPeriodSeconds?.numerator ?: 0,
                feedbackPollPeriodDenominator = feedback?.pollPeriodSeconds?.denominator ?: 0,
                feedbackRequiredZeroMask = feedback?.requiredZeroMask ?: 0,
            )
        }
    }
}

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
    fun createMedia3Stream(
        fd: Int,
        config: UsbTransportConfig,
        generation: Long,
    ): Long {
        val args = UsbNativeTransportArgs.from(config)
        return createMedia3StreamNative(
            fd = fd,
            nominalRuntimeFrameRateHz = args.nominalRuntimeFrameRateHz,
            dataEndpointAddress = args.dataEndpointAddress,
            bytesPerRuntimeFrame = args.bytesPerRuntimeFrame,
            dataMaxBytesPerServiceInterval = args.dataMaxBytesPerServiceInterval,
            dataServicePeriodNumerator = args.dataServicePeriodNumerator,
            dataServicePeriodDenominator = args.dataServicePeriodDenominator,
            packetsPerTransfer = args.packetsPerTransfer,
            dataQueueDepth = args.dataQueueDepth,
            feedbackEndpointAddress = args.feedbackEndpointAddress,
            feedbackEndpointCapacityBytesPerServiceInterval =
                args.feedbackEndpointCapacityBytesPerServiceInterval,
            feedbackExpectedPayloadBytes = args.feedbackExpectedPayloadBytes,
            feedbackFractionalBits = args.feedbackFractionalBits,
            feedbackRawTimeUnit = args.feedbackRawTimeUnit,
            feedbackRawToDataScaleNumerator = args.feedbackRawToDataScaleNumerator,
            feedbackRawToDataScaleDenominator = args.feedbackRawToDataScaleDenominator,
            feedbackPollPeriodNumerator = args.feedbackPollPeriodNumerator,
            feedbackPollPeriodDenominator = args.feedbackPollPeriodDenominator,
            feedbackRequiredZeroMask = args.feedbackRequiredZeroMask,
            generation = generation,
        )
    }

    private external fun createMedia3StreamNative(
        fd: Int,
        nominalRuntimeFrameRateHz: Long,
        dataEndpointAddress: Int,
        bytesPerRuntimeFrame: Int,
        dataMaxBytesPerServiceInterval: Int,
        dataServicePeriodNumerator: Long,
        dataServicePeriodDenominator: Long,
        packetsPerTransfer: Int,
        dataQueueDepth: Int,
        feedbackEndpointAddress: Int,
        feedbackEndpointCapacityBytesPerServiceInterval: Int,
        feedbackExpectedPayloadBytes: Int,
        feedbackFractionalBits: Int,
        feedbackRawTimeUnit: Int,
        feedbackRawToDataScaleNumerator: Long,
        feedbackRawToDataScaleDenominator: Long,
        feedbackPollPeriodNumerator: Long,
        feedbackPollPeriodDenominator: Long,
        feedbackRequiredZeroMask: Long,
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
