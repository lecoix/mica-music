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
import com.mica.music.media.dsd.DsdContainerReader
import com.mica.music.media.dsd.DsdContainerType
import com.mica.music.media.usb.AndroidUsbAudioControlIo
import com.mica.music.media.usb.AndroidUsbRuntimeFactsProvider
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.ExactCarrierFeeder
import com.mica.music.media.usb.ExactCarrierFeederSnapshot
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

internal object UsbDoPContentProbeRunner {
    fun run(context: Context, device: UsbDevice, publish: (String) -> Unit) {
        val selected = UsbDoPContentSourceSelector.select(context, publish)
        if (selected == null) {
            publish("dopContentProbe=result status=SOURCE_UNAVAILABLE stage=source")
            return
        }
        var readerClosed = false
        try {
            val token = UsbPrototypeGenerationOwner.gate.beginHarnessRequest()
            if (token == null) {
                publish("dopContentProbe=result status=FAIL stage=ownership detail=production_session_active")
                return
            }
            UsbSk02NativePrototype.publishGeneration(token.value)
            val result = UsbPrototypeGenerationOwner.gate.withTransport(token) { lease ->
                runLocked(context, device, selected.reader, lease, publish)
            }
            if (result == null) {
                publish("dopContentProbe=result status=FAIL stage=ownership detail=stale_or_busy")
                return
            }
            val sourceClosed = runCatching { selected.reader.close() }.isSuccess
            readerClosed = true
            publish("dopContentProbe=sourceClose closed=$sourceClosed")
            if (result.transportValidated && result.cleanupGreen && sourceClosed) {
                publish("dopContentProbe=result status=PASS stage=cleanup")
            } else {
                publish("dopContentProbe=result status=FAIL stage=cleanup transportValidated=${result.transportValidated} usbCleanup=${result.cleanupGreen} sourceClosed=$sourceClosed")
            }
        } finally {
            if (!readerClosed) {
                val closed = runCatching { selected.reader.close() }.isSuccess
                publish("dopContentProbe=sourceClose closed=$closed fallback=true")
            }
        }
    }

    private fun runLocked(context: Context, device: UsbDevice, reader: DsdContainerReader, lease: UsbOutputRequestLease, publish: (String) -> Unit): LockedResult {
        val info = reader.info
        check(info.container == DsdContainerType.DSF)
        check(info.channelCount == CHANNEL_COUNT)
        check(info.sampleRateHz == DSD64_RATE_HZ || info.sampleRateHz == DSD128_RATE_HZ)
        check(info.sampleRateHz % 16 == 0)
        val carrierRateHz = info.sampleRateHz / 16
        publish("dopContentProbe=readerInfo stableId=${reader.sourceIdentity.stableId} generation=${reader.sourceIdentity.generation} container=${info.container} sampleRate=${info.sampleRateHz} channels=${info.channelCount} sampleCount=${info.sampleCountPerChannel} byteFrames=${info.byteFrameCount} durationUs=${info.durationUs} bitOrder=${info.sourceBitOrder} carrierRate=$carrierRateHz")

        lease.ensureCurrent()
        val manager = context.getSystemService(UsbManager::class.java)
        check(manager.hasPermission(device))
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
        var cleanupGreen = false
        try {
            val runtime = when (val r = AndroidUsbRuntimeFactsProvider.acquire(device, connection)) {
                is UsbRuntimeFactsResult.Ready -> r.facts
                is UsbRuntimeFactsResult.Rejected -> error("runtime facts rejected ${r.rejection.code}:${r.rejection.detail}")
            }
            publish("dopContentProbe=device runtimeDeviceId=${runtime.runtimeHandle.runtimeDeviceId} vendorId=${runtime.identity.vendorId} productId=${runtime.identity.productId} bcdDevice=${runtime.identity.bcdDevice} bus=${runtime.descriptorSet.busSpeed}")
            val parsed = when (val r = StandardUacDescriptorParser.parse(runtime.descriptorSet)) {
                is UsbAudioDescriptorParseResult.Parsed -> r.facts
                is UsbAudioDescriptorParseResult.Rejected -> error("descriptor rejected ${r.rejection}")
            }
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val selectedControl = interfaces.firstOrNull { it.id == parsed.audioFunction.controlInterfaceNumber && it.alternateSetting == 0 } ?: error("AudioControl alt0 missing")
            controlInterface = selectedControl
            controlClaimed = connection.claimInterface(selectedControl, true)
            check(controlClaimed)
            val controlIo = AndroidUsbAudioControlIo(connection)
            val clockEvidence = when (val r = Uac2RuntimeClockEvidenceReader.read(parsed, controlIo)) {
                is Uac2RuntimeClockEvidenceReadResult.Ready -> r.evidence
                is Uac2RuntimeClockEvidenceReadResult.Rejected -> error("clock evidence rejected ${r.rejection}")
            }
            val carrierFormat = UsbPcmFormat(carrierRateHz, info.channelCount, UsbPcmEncoding.PCM_24_PACKED)
            val selection = when (val r = UsbGenericPcmSelection.select(carrierFormat, runtime.identity, parsed, clockEvidence)) {
                is UsbGenericPcmSelectionResult.Ready -> r
                is UsbGenericPcmSelectionResult.Rejected -> error("generic exact carrier rejected ${r.rejection}")
            }
            val decision = selection.decision
            check(decision.signalExact && decision.requestedFormat == decision.deviceFormat && decision.deviceFormat == carrierFormat)
            val profile = decision.streamingProfile
            check(profile.subslotBytes == 3 && profile.bitResolution == 24 && profile.channelCount == CHANNEL_COUNT)
            val claimPlan = checkNotNull(profile.claimPlan)
            val selectedStreamingAlt0 = interfaces.firstOrNull { it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == 0 } ?: error("streaming alt0 missing")
            val streamingTarget = interfaces.firstOrNull { it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == claimPlan.alternateSetting } ?: error("streaming target missing")
            validateStreamingEndpoints(streamingTarget, profile)
            streamingAlt0 = selectedStreamingAlt0
            streamingClaimed = connection.claimInterface(selectedStreamingAlt0, true)
            check(streamingClaimed)
            val selectedClock = profile.clockPlan as? UsbClockPlan.Uac2Entity ?: error("UAC2 clock proof required")
            clockSourceId = selectedClock.sourceEntityId
            val rateController = Uac2ClockRateController(controlIo, selectedControl.id)
            clockController = rateController
            originalClockHz = readClockCurrentHz(controlIo, selectedControl.id, selectedClock.sourceEntityId) ?: error("original clock read failed")
            when (val applied = rateController.setAndVerify(selectedClock.sourceEntityId, carrierRateHz)) {
                is UsbRateControlResult.Applied -> check(applied.sampleRateHz == carrierRateHz)
                is UsbRateControlResult.Rejected -> error("carrier clock rejected ${applied.rejection}")
            }
            altSelected = connection.setInterface(streamingTarget)
            check(altSelected)
            val bridge = UsbDoPCarrierBridge.planDoP(
                decision = decision,
                transport = selection.transportConfig,
                source = DsdCarrierSourceFacts(info.sampleRateHz.toLong(), info.channelCount),
            )
            val plannerResult = bridge as? UsbDoPCarrierBridgeResult.PlannerResult
                ?: error("bridge rejected ${(bridge as UsbDoPCarrierBridgeResult.Rejected).rejection}")
            val ready = plannerResult.result as? DoPCarrierPlanningResult.Ready
                ?: error("DoP planner rejected ${(plannerResult.result as DoPCarrierPlanningResult.Rejected).rejection}")
            val plan = ready.plan
            check(plan.packing == DoPCarrierPacking.PACKED_24_LE)
            check(plan.runtimeFrameRateHz == carrierRateHz.toLong())
            check(plan.dsdBitRateHz == info.sampleRateHz.toLong())
            check(plan.channelCount == info.channelCount)
            check(plan.bytesPerRuntimeFrame == 6)
            publish("dopContentProbe=selection sourceRate=${info.sampleRateHz} carrierRate=${plan.runtimeFrameRateHz} packing=${plan.packing} channels=${plan.channelCount} bytesPerFrame=${plan.bytesPerRuntimeFrame} interface=${profile.interfaceNumber} alt=${profile.alternateSetting} dataEndpoint=0x${selection.transportConfig.dataEndpointAddress.toString(16)} feedbackEndpoint=${selection.transportConfig.feedback?.endpointAddress?.let { "0x${it.toString(16)}" } ?: "none"} maxBytesPerInterval=${selection.transportConfig.dataMaxBytesPerServiceInterval} servicePeriod=${selection.transportConfig.dataServicePeriodSeconds.numerator}/${selection.transportConfig.dataServicePeriodSeconds.denominator}")

            nativeHandle = UsbSk02NativePrototype.createMedia3Stream(
                connection.fileDescriptor,
                selection.transportConfig,
                lease.token.value,
                UsbNativePayloadPolicy.EXACT_FRAMES_ONLY,
            )
            check(nativeHandle != 0L)
            check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            val requiredBytes = UsbSk02NativePrototype.getExactCarrierStartupPrefillBytes(nativeHandle)
            val requiredFrames = UsbSk02NativePrototype.getExactCarrierStartupPrefillFrames(nativeHandle)
            val capacityFrames = UsbSk02NativePrototype.getMedia3BufferCapacityFrames(nativeHandle)
            check(requiredBytes > 0 && requiredFrames > 0 && capacityFrames >= requiredFrames)
            check(requiredBytes == requiredFrames * plan.bytesPerRuntimeFrame)
            check(UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) == 0L)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            publish("dopContentProbe=prefill requiredBytes=$requiredBytes requiredFrames=$requiredFrames capacityFrames=$capacityFrames initialBufferedFrames=0 initialError=0")

            val underArm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
            check(underArm == UsbExactCarrierArmResult.RETRY_INSUFFICIENT_PREFILL)
            check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            check(UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) == 0L)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            publish("dopContentProbe=armUnderThreshold result=$underArm bufferedFrames=0 error=0")

            val carrierSession = DoPCarrierSession(plan)
            val sink = UsbDoPIdleNativeSink(plan.bytesPerRuntimeFrame) { buffer, length ->
                UsbSk02NativePrototype.writeMedia3Stream(nativeHandle, buffer, 0, length)
            }
            val feeder = ExactCarrierFeeder(carrierSession, sink, stagingFrameCapacity = FEEDER_STAGING_FRAMES)
            val sourcePump = UsbDoPContentPump(reader, feeder, chunkFrames = SOURCE_CHUNK_FRAMES)
            val refillTarget = refillTargetFrames(carrierRateHz.toLong(), capacityFrames, requiredFrames)
            fillNativeWithContent(sourcePump, nativeHandle, refillTarget)
            settleSourcePumpAtCleanBoundary(sourcePump, nativeHandle)
            val beforeArm = nativeSnapshot(nativeHandle)
            check(beforeArm.bufferedFrames >= requiredFrames) {
                "source cannot satisfy startup prefill buffered=${beforeArm.bufferedFrames} required=$requiredFrames"
            }
            check(beforeArm.errorCode == 0)
            val accountingBeforeArm = carrierSession.accounting()
            val sourceBeforeArm = sourcePump.snapshot()
            check(accountingBeforeArm.canonicalBytesConsumed > 0L)
            check(accountingBeforeArm.contentRuntimeFramesPacked > 0L)
            check(accountingBeforeArm.idleRuntimeFramesPacked == 0L)
            check(sourceBeforeArm.canonicalBytesConsumed == accountingBeforeArm.canonicalBytesConsumed)
            check(sourcePump.isCleanBoundary())
            publish("dopContentProbe=beforeArm completed=${beforeArm.completedFrames} buffered=${beforeArm.bufferedFrames} error=${beforeArm.errorCode} readerFrames=${sourceBeforeArm.readerFramesRead} readerBytes=${sourceBeforeArm.readerCanonicalBytesRead} canonicalConsumed=${accountingBeforeArm.canonicalBytesConsumed} contentPacked=${accountingBeforeArm.contentRuntimeFramesPacked} idlePacked=${accountingBeforeArm.idleRuntimeFramesPacked} lastMarker=${accountingBeforeArm.lastPackedMarker} nextMarker=${accountingBeforeArm.nextMarker}")

            val arm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
            check(arm == UsbExactCarrierArmResult.ARMED)
            check(UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
            publish("dopContentProbe=arm result=$arm armed=true")
            val active = runActiveContent(
                sourcePump,
                nativeHandle,
                refillTarget,
                requiredFrames,
                lease,
                publish,
            )
            settleSourcePumpAtCleanBoundary(sourcePump, nativeHandle)
            val after = nativeSnapshot(nativeHandle)
            val accountingAfter = carrierSession.accounting()
            val feederAfter = feeder.snapshot()
            val sourceAfter = sourcePump.snapshot()
            validatePhysicalEvidence(
                active.samples + after,
                after,
                accountingAfter,
                feederAfter,
                sourceAfter,
                reader,
            )
            publish("dopContentProbe=sourcePump readerFrames=${sourceAfter.readerFramesRead} readerBytes=${sourceAfter.readerCanonicalBytesRead} canonicalConsumed=${sourceAfter.canonicalBytesConsumed} pendingCanonical=${sourceAfter.pendingCanonicalBytes} eof=${sourceAfter.readerEof} feederStaged=${sourceAfter.feederStagedBytes} feederUpstreamPending=${sourceAfter.feederUpstreamPendingBytes} feederError=${sanitize(sourceAfter.feederContractError)}")
            publish("dopContentProbe=accounting canonicalBytes=${accountingAfter.canonicalBytesConsumed} canonicalFrames=${accountingAfter.canonicalFramesConsumed} contentPacked=${accountingAfter.contentRuntimeFramesPacked} idlePacked=${accountingAfter.idleRuntimeFramesPacked} contentCarrierBytes=${accountingAfter.contentCarrierBytesEmitted} carrierEmitted=${accountingAfter.carrierBytesEmitted} pendingPacked=${accountingAfter.pendingPackedCarrierBytes} pendingPartial=${accountingAfter.pendingPartialCanonicalFrameBytes} pendingHalf=${accountingAfter.hasPendingCanonicalHalfFrame} lastMarker=${accountingAfter.lastPackedMarker} nextMarker=${accountingAfter.nextMarker}")
            publish("dopContentProbe=feeder stagedBytes=${feederAfter.stagedCarrierBytes.size} upstreamPending=${feederAfter.upstreamPendingPackedCarrierBytes} error=${feederAfter.contractError}")
            publish("dopContentProbe=transportFinal actualDurationMs=${active.actualDurationMs} eof=${sourceAfter.readerEof} ${after.logFields()}")
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
                runCatching { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, it.id) }.getOrNull()
            }
            val streamingDriver = streamingAlt0?.let {
                runCatching { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, it.id) }.getOrNull()
            }
            val driversBound = controlDriver?.contains("driver=snd-usb-audio") == true &&
                streamingDriver?.contains("driver=snd-usb-audio") == true
            cleanupGreen = nativeDestroyed && altRestored && clockRestored &&
                streamingReleased && controlReleased && reconnectErrno == 0 && driversBound
            publish("dopContentProbe=cleanup nativeDestroyed=$nativeDestroyed altRestored=$altRestored clockRestored=$clockRestored streamingReleased=$streamingReleased controlReleased=$controlReleased reconnectErrno=$reconnectErrno driversBound=$driversBound cleanupGreen=$cleanupGreen controlDriver=${sanitize(controlDriver)} streamingDriver=${sanitize(streamingDriver)}")
            connection.close()
        }
        return LockedResult(transportValidated, cleanupGreen)
    }
    private fun runActiveContent(
        sourcePump: UsbDoPContentPump,
        nativeHandle: Long,
        refillTarget: Long,
        requiredPrefillFrames: Long,
        lease: UsbOutputRequestLease,
        publish: (String) -> Unit,
    ): ActiveResult {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + ACTIVE_FEED_DURATION_MS
        var nextSample = start
        val samples = mutableListOf<NativeSnapshot>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lease.ensureCurrent()
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            val buffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
            val sourceState = sourcePump.snapshot()
            if (buffered < refillTarget && !sourceState.readerEof) {
                fillNativeWithContent(sourcePump, nativeHandle, refillTarget)
            } else if (!sourceState.readerEof) {
                val step = sourcePump.step()
                check(step.status != ExactCarrierFeedStatus.FAILED)
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSample) {
                val snapshot = nativeSnapshot(nativeHandle)
                samples += snapshot
                val source = sourcePump.snapshot()
                publish("dopContentProbe=sample tMs=${now - start} ${snapshot.logFields()} readerFrames=${source.readerFramesRead} canonicalConsumed=${source.canonicalBytesConsumed} sourceEof=${source.readerEof}")
                nextSample = now + SAMPLE_INTERVAL_MS
            }
            val state = sourcePump.snapshot()
            if (state.readerEof && sourcePump.isCleanBoundary()) {
                publish("dopContentProbe=sourceEof tMs=${now - start} buffered=${UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)}")
                break
            }
            Thread.sleep(FEED_LOOP_SLEEP_MS)
        }
        settleSourcePumpAtCleanBoundary(sourcePump, nativeHandle)
        val end = SystemClock.elapsedRealtime()
        val finalBuffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
        if (!sourcePump.snapshot().readerEof) {
            check(finalBuffered >= minOf(requiredPrefillFrames, refillTarget / 4L)) {
                "insufficient Native reserve at planned content stop: buffered=$finalBuffered"
            }
        }
        return ActiveResult(samples, end - start)
    }

    private fun fillNativeWithContent(
        sourcePump: UsbDoPContentPump,
        nativeHandle: Long,
        targetBufferedFrames: Long,
    ): Boolean {
        var guard = 0
        while (UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle) < targetBufferedFrames) {
            check(++guard < MAX_FEED_ITERATIONS)
            val step = sourcePump.step(allowReaderRead = true)
            check(step.status != ExactCarrierFeedStatus.FAILED)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            val state = sourcePump.snapshot()
            if (state.readerEof && sourcePump.isCleanBoundary()) return false
            if (step.canonicalBytesConsumed == 0 && step.sinkBytesAccepted == 0 &&
                step.status == ExactCarrierFeedStatus.NO_PROGRESS
            ) {
                Thread.sleep(FEED_LOOP_SLEEP_MS)
            }
        }
        return true
    }

    private fun settleSourcePumpAtCleanBoundary(
        sourcePump: UsbDoPContentPump,
        nativeHandle: Long,
    ) {
        var guard = 0
        while (!sourcePump.isCleanBoundary()) {
            check(++guard < MAX_FEED_ITERATIONS)
            val step = sourcePump.step(allowReaderRead = true)
            check(step.status != ExactCarrierFeedStatus.FAILED)
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0)
            if (step.canonicalBytesConsumed == 0 && step.sinkBytesAccepted == 0 &&
                step.status == ExactCarrierFeedStatus.NO_PROGRESS
            ) {
                check(!sourcePump.snapshot().readerEof) { "EOF left non-clean P5 source state" }
                Thread.sleep(FEED_LOOP_SLEEP_MS)
            }
        }
    }

    private fun validatePhysicalEvidence(
        samples: List<NativeSnapshot>,
        after: NativeSnapshot,
        accounting: DoPPipelineAccounting,
        feeder: ExactCarrierFeederSnapshot,
        source: UsbDoPContentPumpSnapshot,
        reader: DsdContainerReader,
    ) {
        check(samples.size >= 2)
        check(samples.zipWithNext().any { (before, next) -> next.completedFrames > before.completedFrames })
        check(samples.all { it.bufferedFrames >= 0L && it.errorCode == 0 })
        check(after.errorCode == 0)
        check(after.underrunBytes == 0L)
        check(after.invalidFeedbackPacketCount == 0L)
        check(after.dataPacketErrorCount == 0L)
        check(after.pcmContentMetrics.all { it == 0L })
        check(accounting.canonicalBytesConsumed > 0L)
        check(accounting.contentRuntimeFramesPacked > 0L)
        check(accounting.idleRuntimeFramesPacked == 0L)
        check(accounting.contentCarrierBytesEmitted > 0L)
        check(accounting.pendingPackedCarrierBytes == 0)
        check(accounting.pendingPartialCanonicalFrameBytes == 0)
        check(!accounting.hasPendingCanonicalHalfFrame)
        check(source.readerCanonicalBytesRead == source.canonicalBytesConsumed)
        check(source.canonicalBytesConsumed == accounting.canonicalBytesConsumed)
        check(source.readerFramesRead == reader.framePosition)
        check(source.pendingCanonicalBytes == 0)
        check(feeder.contractError == null)
        check(feeder.stagedCarrierBytes.isEmpty())
        check(feeder.upstreamPendingPackedCarrierBytes == 0)
    }

    private fun refillTargetFrames(carrierRateHz: Long, bufferCapacityFrames: Long, requiredPrefillFrames: Long): Long {
        require(carrierRateHz > 0 && bufferCapacityFrames > 0 && requiredPrefillFrames > 0)
        return minOf(bufferCapacityFrames, maxOf(requiredPrefillFrames * 2L, carrierRateHz))
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

    private fun validateStreamingEndpoints(streamingInterface: UsbInterface, profile: UsbAudioStreamingProfile) {
        val endpoints = (0 until streamingInterface.endpointCount).map(streamingInterface::getEndpoint).map {
            UsbAudioEndpointShape(it.address, it.type, it.maxPacketSize, it.interval)
        }
        when (val result = UsbRuntimeStreamingProfileValidator.validate(profile, endpoints)) {
            UsbStreamingProfileValidation.Valid -> Unit
            is UsbStreamingProfileValidation.Rejected -> error("runtime topology rejected ${result.reason}")
        }
    }

    private fun readClockCurrentHz(io: UsbAudioControlIo, audioControlInterface: Int, clockSourceId: Int): Int? {
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

    private data class LockedResult(val transportValidated: Boolean, val cleanupGreen: Boolean)
    private data class ActiveResult(val samples: List<NativeSnapshot>, val actualDurationMs: Long)
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

    private const val DSD64_RATE_HZ = 2_822_400
    private const val DSD128_RATE_HZ = 5_644_800
    private const val CHANNEL_COUNT = 2
    private const val UAC2_CUR = 0x01
    private const val SAMPLING_FREQ_CONTROL = 0x01
    private const val FEEDER_STAGING_FRAMES = 4_096
    private const val SOURCE_CHUNK_FRAMES = 4_096
    private const val MAX_FEED_ITERATIONS = 200_000
    private const val ACTIVE_FEED_DURATION_MS = 3_200L
    private const val SAMPLE_INTERVAL_MS = 500L
    private const val FEED_LOOP_SLEEP_MS = 4L
}