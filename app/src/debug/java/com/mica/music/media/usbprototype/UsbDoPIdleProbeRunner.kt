package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DoPPipelineAccounting
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.usb.AndroidUsbAudioControlIo
import com.mica.music.media.usb.AndroidUsbRuntimeFactsProvider
import com.mica.music.media.usb.ExactCarrierFeeder
import com.mica.music.media.usb.ExactCarrierFeederSnapshot
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.StandardUacDescriptorParser
import com.mica.music.media.usb.Uac2ClockRateController
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReadResult
import com.mica.music.media.usb.Uac2RuntimeClockEvidenceReader
import com.mica.music.media.usb.UsbAudioControlIo
import com.mica.music.media.usb.UsbAudioDescriptorParseResult
import com.mica.music.media.usb.UsbAudioEndpointShape
import com.mica.music.media.usb.UsbAudioStreamingProfile
import com.mica.music.media.usb.UsbClockPlan
import com.mica.music.media.usb.UsbControlDirection
import com.mica.music.media.usb.UsbControlIoResult
import com.mica.music.media.usb.UsbControlRecipient
import com.mica.music.media.usb.UsbControlRequest
import com.mica.music.media.usb.UsbDoPCarrierBridge
import com.mica.music.media.usb.UsbDoPCarrierBridgeResult
import com.mica.music.media.usb.UsbGenericPcmSelection
import com.mica.music.media.usb.UsbGenericPcmSelectionResult
import com.mica.music.media.usb.UsbOutputRequestLease
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbRateControlResult
import com.mica.music.media.usb.UsbRuntimeFactsResult
import com.mica.music.media.usb.UsbRuntimeStreamingProfileValidator
import com.mica.music.media.usb.UsbStreamingProfileValidation
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object UsbDoPIdleProbeRunner {
    fun run(context: Context, device: UsbDevice, publish: (String) -> Unit) {
        val token = UsbPrototypeGenerationOwner.gate.beginHarnessRequest()
        if (token == null) {
            publish("dopIdleProbe=result status=FAIL stage=ownership detail=production_session_active")
            return
        }
        UsbSk02NativePrototype.publishGeneration(token.value)
        val result = UsbPrototypeGenerationOwner.gate.withTransport(token) { lease ->
            runLocked(context, device, lease, publish)
        }
        if (result == null) {
            publish("dopIdleProbe=result status=FAIL stage=ownership detail=stale_or_busy")
        }
    }

    private fun runLocked(
        context: Context,
        device: UsbDevice,
        lease: UsbOutputRequestLease,
        publish: (String) -> Unit,
    ) {
        lease.ensureCurrent()
        val manager = context.getSystemService(UsbManager::class.java)
        check(manager.hasPermission(device)) { "USB permission disappeared before exact probe" }
        val connection = manager.openDevice(device) ?: error("unable to open debug target")
        var controlInterface: UsbInterface? = null
        var streamingAlt0: UsbInterface? = null
        var controlClaimed = false
        var streamingClaimed = false
        var altSelected = false
        var nativeHandle = 0L
        var clockController: Uac2ClockRateController? = null
        var clockSourceId: Int? = null
        var originalClockHz: Int? = null
        var transportValidated = false
        try {
            val runtime = when (val result = AndroidUsbRuntimeFactsProvider.acquire(device, connection)) {
                is UsbRuntimeFactsResult.Ready -> result.facts
                is UsbRuntimeFactsResult.Rejected -> error(
                    "runtime facts rejected ${result.rejection.code}:${result.rejection.detail}",
                )
            }
            lease.ensureCurrent()
            publish(
                "dopIdleProbe=device runtimeDeviceId=${runtime.runtimeHandle.runtimeDeviceId} " +
                    "vendorId=${runtime.identity.vendorId} productId=${runtime.identity.productId} " +
                    "bcdDevice=${runtime.identity.bcdDevice} bus=${runtime.descriptorSet.busSpeed}",
            )
            val parsed = when (val result = StandardUacDescriptorParser.parse(runtime.descriptorSet)) {
                is UsbAudioDescriptorParseResult.Parsed -> result.facts
                is UsbAudioDescriptorParseResult.Rejected -> error("descriptor rejected ${result.rejection}")
            }
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val selectedControl = interfaces.firstOrNull {
                it.id == parsed.audioFunction.controlInterfaceNumber && it.alternateSetting == 0
            } ?: error("parsed AudioControl alt0 missing")
            controlInterface = selectedControl
            controlClaimed = connection.claimInterface(selectedControl, true)
            check(controlClaimed) { "unable to force-claim parsed AudioControl" }

            val controlIo = AndroidUsbAudioControlIo(connection)
            val clockEvidence = when (val result = Uac2RuntimeClockEvidenceReader.read(parsed, controlIo)) {
                is Uac2RuntimeClockEvidenceReadResult.Ready -> result.evidence
                is Uac2RuntimeClockEvidenceReadResult.Rejected -> error(
                    "clock evidence rejected ${result.rejection}",
                )
            }
            val carrierFormat = UsbPcmFormat(
                sampleRateHz = UsbDoPIdleProbePolicy.CARRIER_RATE_HZ,
                channelCount = UsbDoPIdleProbePolicy.CHANNEL_COUNT,
                encoding = UsbPcmEncoding.PCM_24_PACKED,
            )
            val selection = when (
                val result = UsbGenericPcmSelection.select(
                    source = carrierFormat,
                    identity = runtime.identity,
                    facts = parsed,
                    uac2ClockEvidence = clockEvidence,
                )
            ) {
                is UsbGenericPcmSelectionResult.Ready -> result
                is UsbGenericPcmSelectionResult.Rejected -> error(
                    "generic exact carrier rejected ${result.rejection}",
                )
            }
            val decision = selection.decision
            check(decision.signalExact && decision.requestedFormat == decision.deviceFormat)
            check(decision.deviceFormat == carrierFormat) {
                "accepted carrier differs requested=$carrierFormat device=${decision.deviceFormat}"
            }
            val profile = decision.streamingProfile
            check(profile.subslotBytes == 3 && profile.bitResolution == 24 && profile.channelCount == 2) {
                "carrier is not packed-24 stereo profile=$profile"
            }
            val claimPlan = checkNotNull(profile.claimPlan) { "carrier profile has no claim plan" }
            val selectedStreamingAlt0 = interfaces.firstOrNull {
                it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == 0
            } ?: error("selected AudioStreaming alt0 missing")
            val streamingTarget = interfaces.firstOrNull {
                it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == claimPlan.alternateSetting
            } ?: error("selected AudioStreaming alt ${claimPlan.alternateSetting} missing")
            validateStreamingEndpoints(streamingTarget, profile)
            streamingAlt0 = selectedStreamingAlt0
            streamingClaimed = connection.claimInterface(selectedStreamingAlt0, true)
            check(streamingClaimed) { "unable to force-claim selected AudioStreaming" }

            val selectedClock = profile.clockPlan as? UsbClockPlan.Uac2Entity
                ?: error("exact carrier requires a proven UAC2 ClockSource plan")
            clockSourceId = selectedClock.sourceEntityId
            val rateController = Uac2ClockRateController(controlIo, selectedControl.id)
            clockController = rateController
            originalClockHz = readClockCurrentHz(controlIo, selectedControl.id, selectedClock.sourceEntityId)
                ?: error("unable to read original ClockSource rate")
            when (val applied = rateController.setAndVerify(
                selectedClock.sourceEntityId,
                UsbDoPIdleProbePolicy.CARRIER_RATE_HZ,
            )) {
                is UsbRateControlResult.Applied -> check(
                    applied.sampleRateHz == UsbDoPIdleProbePolicy.CARRIER_RATE_HZ,
                )
                is UsbRateControlResult.Rejected -> error("carrier clock set rejected ${applied.rejection}")
            }
            lease.ensureCurrent()
            altSelected = connection.setInterface(streamingTarget)
            check(altSelected) { "unable to select exact carrier streaming alt" }

            val bridge = UsbDoPCarrierBridge.planDoP(
                decision = decision,
                transport = selection.transportConfig,
                source = DsdCarrierSourceFacts(
                    dsdBitRateHz = UsbDoPIdleProbePolicy.DSD64_BIT_RATE_HZ,
                    channelCount = UsbDoPIdleProbePolicy.CHANNEL_COUNT,
                ),
            )
            val plannerResult = bridge as? UsbDoPCarrierBridgeResult.PlannerResult
                ?: error("P3->P5 bridge rejected ${(bridge as UsbDoPCarrierBridgeResult.Rejected).rejection}")
            val ready = plannerResult.result as? DoPCarrierPlanningResult.Ready
                ?: error("P5 DoP planner rejected ${(plannerResult.result as DoPCarrierPlanningResult.Rejected).rejection}")
            val plan = ready.plan
            check(plan.packing == DoPCarrierPacking.PACKED_24_LE)
            check(plan.runtimeFrameRateHz == UsbDoPIdleProbePolicy.CARRIER_RATE_HZ.toLong())
            check(plan.bytesPerRuntimeFrame == 6)
            publish(
                "dopIdleProbe=selection carrierRate=${plan.runtimeFrameRateHz} packing=${plan.packing} " +
                    "channels=${plan.channelCount} bytesPerFrame=${plan.bytesPerRuntimeFrame} " +
                    "interface=${profile.interfaceNumber} alt=${profile.alternateSetting} " +
                    "dataEndpoint=0x${selection.transportConfig.dataEndpointAddress.toString(16)} " +
                    "feedbackEndpoint=${selection.transportConfig.feedback?.endpointAddress?.let { "0x${it.toString(16)}" } ?: "none"} " +
                    "maxBytesPerInterval=${selection.transportConfig.dataMaxBytesPerServiceInterval} " +
                    "servicePeriod=${selection.transportConfig.dataServicePeriodSeconds.numerator}/" +
                    selection.transportConfig.dataServicePeriodSeconds.denominator,
            )

            nativeHandle = UsbSk02NativePrototype.createMedia3Stream(
                fd = connection.fileDescriptor,
                config = selection.transportConfig,
                generation = lease.token.value,
                payloadPolicy = UsbNativePayloadPolicy.EXACT_FRAMES_ONLY,
            )
            check(nativeHandle != 0L) { "exact Native session creation failed" }
            check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            val requiredBytes = UsbSk02NativePrototype.getExactCarrierStartupPrefillBytes(nativeHandle)
            val requiredFrames = UsbSk02NativePrototype.getExactCarrierStartupPrefillFrames(nativeHandle)
            val capacityFrames = UsbSk02NativePrototype.getMedia3BufferCapacityFrames(nativeHandle)
            check(requiredBytes > 0 && requiredFrames > 0 && capacityFrames >= requiredFrames)
            check(requiredBytes == requiredFrames * plan.bytesPerRuntimeFrame)
            check(UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) == 0L)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            publish(
                "dopIdleProbe=prefill requiredBytes=$requiredBytes requiredFrames=$requiredFrames " +
                    "capacityFrames=$capacityFrames initialBufferedFrames=0 initialError=0",
            )

            val underArm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
            check(underArm == UsbExactCarrierArmResult.RETRY_INSUFFICIENT_PREFILL)
            check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            check(UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) == 0L)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            publish("dopIdleProbe=armUnderThreshold result=$underArm bufferedFrames=0 error=0")

            val carrierSession = DoPCarrierSession(plan)
            val sink = UsbDoPIdleNativeSink(plan.bytesPerRuntimeFrame) { buffer, length ->
                UsbSk02NativePrototype.writeMedia3Stream(nativeHandle, buffer, 0, length)
            }
            val feeder = ExactCarrierFeeder(
                session = carrierSession,
                sink = sink,
                stagingFrameCapacity = FEEDER_STAGING_FRAMES,
            )
            val refillTarget = UsbDoPIdleProbePolicy.refillTargetFrames(capacityFrames, requiredFrames)
            fillNativeToTarget(feeder, nativeHandle, refillTarget)
            val beforeArm = nativeSnapshot(nativeHandle)
            check(beforeArm.bufferedFrames >= requiredFrames)
            check(beforeArm.errorCode == 0)
            val accountingBeforeArm = carrierSession.accounting()
            check(accountingBeforeArm.contentRuntimeFramesPacked == 0L)
            check(accountingBeforeArm.idleRuntimeFramesPacked > 0L)
            check(feeder.snapshot().contractError == null)
            check(feeder.snapshot().stagedCarrierBytes.isEmpty())
            publish(
                "dopIdleProbe=beforeArm completed=${beforeArm.completedFrames} buffered=${beforeArm.bufferedFrames} " +
                    "error=${beforeArm.errorCode} idlePacked=${accountingBeforeArm.idleRuntimeFramesPacked} " +
                    "lastMarker=${accountingBeforeArm.lastPackedMarker} nextMarker=${accountingBeforeArm.nextMarker}",
            )

            val arm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
            check(arm == UsbExactCarrierArmResult.ARMED) { "exact arm failed result=$arm" }
            check(UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            publish("dopIdleProbe=arm result=$arm armed=true")

            val samples = runActiveIdle(feeder, nativeHandle, refillTarget, lease, publish)
            drainFeederStaging(feeder, nativeHandle)
            val after = nativeSnapshot(nativeHandle)
            val accountingAfter = carrierSession.accounting()
            val feederAfter = feeder.snapshot()
            validatePhysicalEvidence(samples + after, after, accountingAfter, feederAfter)
            publish(
                "dopIdleProbe=accounting canonicalBytes=${accountingAfter.canonicalBytesConsumed} " +
                    "contentPacked=${accountingAfter.contentRuntimeFramesPacked} " +
                    "idlePacked=${accountingAfter.idleRuntimeFramesPacked} " +
                    "carrierEmitted=${accountingAfter.carrierBytesEmitted} " +
                    "pendingPacked=${accountingAfter.pendingPackedCarrierBytes} " +
                    "lastMarker=${accountingAfter.lastPackedMarker} nextMarker=${accountingAfter.nextMarker}",
            )
            publish(
                "dopIdleProbe=feeder stagedBytes=${feederAfter.stagedCarrierBytes.size} " +
                    "upstreamPending=${feederAfter.upstreamPendingPackedCarrierBytes} " +
                    "error=${feederAfter.contractError}",
            )
            publish("dopIdleProbe=transportFinal ${after.logFields()}")
            transportValidated = true
        } finally {
            val nativeDestroyed = nativeHandle == 0L || runCatching {
                UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle)
            }.isSuccess
            val altRestored = !altSelected || streamingAlt0 == null || runCatching {
                connection.setInterface(checkNotNull(streamingAlt0))
            }.getOrDefault(false)
            val restoreController = clockController
            val restoreClockSource = clockSourceId
            val restoreRate = originalClockHz
            val clockRestored = if (
                restoreController != null && restoreClockSource != null && restoreRate != null
            ) {
                runCatching {
                    restoreController.setAndVerify(restoreClockSource, restoreRate)
                }.getOrNull() == UsbRateControlResult.Applied(restoreRate)
            } else {
                true
            }
            val streamingReleased = !streamingClaimed || streamingAlt0 == null || runCatching {
                connection.releaseInterface(checkNotNull(streamingAlt0))
            }.getOrDefault(false)
            val controlReleased = !controlClaimed || controlInterface == null || runCatching {
                connection.releaseInterface(checkNotNull(controlInterface))
            }.getOrDefault(false)
            val reconnectErrno = runCatching {
                UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor)
            }.getOrNull()
            val controlDriver = controlInterface?.let {
                runCatching { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, it.id) }
                    .getOrNull()
            }
            val streamingDriver = streamingAlt0?.let {
                runCatching { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, it.id) }
                    .getOrNull()
            }
            val driversBound = controlDriver?.contains("driver=snd-usb-audio") == true &&
                streamingDriver?.contains("driver=snd-usb-audio") == true
            val cleanupGreen = nativeDestroyed && altRestored && clockRestored &&
                streamingReleased && controlReleased && reconnectErrno == 0 && driversBound
            publish(
                "dopIdleProbe=cleanup nativeDestroyed=$nativeDestroyed altRestored=$altRestored " +
                    "clockRestored=$clockRestored streamingReleased=$streamingReleased " +
                    "controlReleased=$controlReleased reconnectErrno=$reconnectErrno " +
                    "driversBound=$driversBound cleanupGreen=$cleanupGreen " +
                    "controlDriver=${sanitize(controlDriver)} streamingDriver=${sanitize(streamingDriver)}",
            )
            connection.close()
            if (transportValidated) {
                if (cleanupGreen) {
                    publish("dopIdleProbe=result status=PASS stage=cleanup")
                } else {
                    publish("dopIdleProbe=result status=FAIL stage=cleanup detail=restore_contract")
                }
            }
        }
    }

    private fun runActiveIdle(
        feeder: ExactCarrierFeeder,
        nativeHandle: Long,
        refillTarget: Long,
        lease: UsbOutputRequestLease,
        publish: (String) -> Unit,
    ): List<NativeSnapshot> {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + UsbDoPIdleProbePolicy.ACTIVE_DURATION_MS
        var nextSample = start
        val samples = mutableListOf<NativeSnapshot>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lease.ensureCurrent()
            val error = UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
            check(error == 0) { "exact transport error during active feed=$error" }
            if (UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) < refillTarget) {
                fillNativeToTarget(feeder, nativeHandle, refillTarget)
            } else {
                val pump = feeder.pump()
                check(pump.status != ExactCarrierFeedStatus.FAILED) { "feeder pump failed ${pump.error}" }
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSample) {
                val snapshot = nativeSnapshot(nativeHandle)
                samples += snapshot
                publish("dopIdleProbe=sample tMs=${now - start} ${snapshot.logFields()}")
                nextSample = now + UsbDoPIdleProbePolicy.SAMPLE_INTERVAL_MS
            }
            Thread.sleep(UsbDoPIdleProbePolicy.FEED_LOOP_SLEEP_MS)
        }
        return samples
    }

    private fun fillNativeToTarget(
        feeder: ExactCarrierFeeder,
        nativeHandle: Long,
        targetBufferedFrames: Long,
    ) {
        var guard = 0
        while (UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) < targetBufferedFrames) {
            check(++guard < MAX_FEED_ITERATIONS) { "prefill/refill made no bounded progress" }
            val buffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
            val missing = (targetBufferedFrames - buffered).coerceAtLeast(1L)
            val requested = minOf(missing, FEEDER_STAGING_FRAMES.toLong()).toInt()
            val result = feeder.writeGapFrames(requested)
            check(result.status != ExactCarrierFeedStatus.FAILED) { "feeder gap failed ${result.error}" }
            check(result.blockedReason == null) { "idle-only gap unexpectedly blocked ${result.blockedReason}" }
            if (result.gapFramesAccepted == 0 && result.sinkBytesAccepted == 0) {
                val pump = feeder.pump()
                check(pump.status != ExactCarrierFeedStatus.FAILED) { "feeder pump failed ${pump.error}" }
                if (pump.sinkBytesAccepted == 0 && pump.carrierBytesFlushedFromSession == 0) {
                    Thread.sleep(UsbDoPIdleProbePolicy.FEED_LOOP_SLEEP_MS)
                }
            }
            val error = UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)
            check(error == 0) { "Native error while feeding=$error" }
        }
    }

    private fun drainFeederStaging(feeder: ExactCarrierFeeder, nativeHandle: Long) {
        var guard = 0
        while (true) {
            val snapshot = feeder.snapshot()
            if (snapshot.stagedCarrierBytes.isEmpty() && snapshot.upstreamPendingPackedCarrierBytes == 0) {
                return
            }
            check(++guard < MAX_FEED_ITERATIONS) { "feeder staging did not drain" }
            val pump = feeder.pump()
            check(pump.status != ExactCarrierFeedStatus.FAILED) { "feeder drain failed ${pump.error}" }
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            if (pump.sinkBytesAccepted == 0 && pump.carrierBytesFlushedFromSession == 0) {
                Thread.sleep(UsbDoPIdleProbePolicy.FEED_LOOP_SLEEP_MS)
            }
        }
    }

    private fun validatePhysicalEvidence(
        samples: List<NativeSnapshot>,
        after: NativeSnapshot,
        accounting: DoPPipelineAccounting,
        feeder: ExactCarrierFeederSnapshot,
    ) {
        check(samples.size >= 2)
        check(samples.zipWithNext().any { (before, next) -> next.completedFrames > before.completedFrames }) {
            "completed exact-carrier frames did not advance"
        }
        check(samples.all { it.bufferedFrames >= 0L && it.errorCode == 0 })
        check(after.underrunBytes == 0L)
        check(after.invalidFeedbackPacketCount == 0L)
        check(after.dataPacketErrorCount == 0L)
        check(after.pcmContentMetrics.all { it == 0L })
        check(accounting.contentRuntimeFramesPacked == 0L)
        check(accounting.idleRuntimeFramesPacked > 0L)
        check(accounting.pendingPackedCarrierBytes == 0)
        check(feeder.contractError == null)
        check(feeder.stagedCarrierBytes.isEmpty())
        check(feeder.upstreamPendingPackedCarrierBytes == 0)
    }

    private fun nativeSnapshot(handle: Long): NativeSnapshot {
        val metrics = UsbSk02NativePrototype.getMedia3DiagnosticMetrics(handle)
        return NativeSnapshot(
            completedFrames = UsbSk02NativePrototype.getMedia3CompletedFrames(handle),
            bufferedFrames = UsbSk02NativePrototype.getMedia3BufferedFrames(handle),
            errorCode = UsbSk02NativePrototype.getMedia3ErrorCode(handle),
            underrunBytes = UsbSk02NativePrototype.getMedia3UnderrunBytes(handle),
            invalidFeedbackPacketCount = UsbSk02NativePrototype.getMedia3InvalidFeedbackPacketCount(handle),
            dataPacketErrorCount = UsbSk02NativePrototype.getMedia3DataPacketErrorCount(handle),
            pcmContentMetrics = (8..16).map { metrics.getOrElse(it) { 0L } },
        )
    }

    private fun validateStreamingEndpoints(
        streamingInterface: UsbInterface,
        profile: UsbAudioStreamingProfile,
    ) {
        val endpoints = (0 until streamingInterface.endpointCount)
            .map(streamingInterface::getEndpoint)
            .map {
                UsbAudioEndpointShape(
                    address = it.address,
                    transferType = it.type,
                    maxPacketBytes = it.maxPacketSize,
                    interval = it.interval,
                )
            }
        when (val result = UsbRuntimeStreamingProfileValidator.validate(profile, endpoints)) {
            UsbStreamingProfileValidation.Valid -> Unit
            is UsbStreamingProfileValidation.Rejected -> error("runtime topology rejected ${result.reason}")
        }
    }

    private fun readClockCurrentHz(
        io: UsbAudioControlIo,
        audioControlInterface: Int,
        clockSourceId: Int,
    ): Int? {
        val result = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.IN,
                recipient = UsbControlRecipient.INTERFACE,
                request = UAC2_CUR,
                value = SAMPLING_FREQ_CONTROL shl 8,
                index = (clockSourceId shl 8) or audioControlInterface,
                readLength = 4,
            ),
        )
        val success = result as? UsbControlIoResult.Success ?: return null
        if (success.transferredBytes != 4 || success.data.size != 4) return null
        return ByteBuffer.wrap(success.data).order(ByteOrder.LITTLE_ENDIAN).int.takeIf { it > 0 }
    }

    private data class NativeSnapshot(
        val completedFrames: Long,
        val bufferedFrames: Long,
        val errorCode: Int,
        val underrunBytes: Long,
        val invalidFeedbackPacketCount: Long,
        val dataPacketErrorCount: Long,
        val pcmContentMetrics: List<Long>,
    ) {
        fun logFields(): String =
            "completed=$completedFrames buffered=$bufferedFrames error=$errorCode underrun=$underrunBytes " +
                "invalidFeedback=$invalidFeedbackPacketCount dataPacketErrors=$dataPacketErrorCount " +
                "pcmContent=${pcmContentMetrics.joinToString(",")}"
    }

    private fun sanitize(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(300)

    private const val UAC2_CUR = 0x01
    private const val SAMPLING_FREQ_CONTROL = 0x01
    private const val FEEDER_STAGING_FRAMES = 4_096
    private const val MAX_FEED_ITERATIONS = 100_000
}
