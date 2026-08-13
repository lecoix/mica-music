package com.mica.music.media.usbprototype

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.mica.music.media.dsd.DirectDsdTransportSession
import com.mica.music.media.dsd.DirectDsdTransportSessionFactory
import com.mica.music.media.dsd.DirectDsdTransportWriteResult
import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import com.mica.music.media.usb.AndroidUsbAudioControlIo
import com.mica.music.media.usb.AndroidUsbRuntimeFactsProvider
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.ExactCarrierFeeder
import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.Sk02UsbContract
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
import com.mica.music.media.usb.UsbOutputCleanupLease
import com.mica.music.media.usb.UsbOutputRequest
import com.mica.music.media.usb.UsbOutputRequestLease
import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbOutputSession
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPcmFormat
import com.mica.music.media.usb.UsbPermissionState
import com.mica.music.media.usb.UsbRateControlResult
import com.mica.music.media.usb.UsbRuntimeFactsResult
import com.mica.music.media.usb.UsbRuntimeStreamingProfileValidator
import com.mica.music.media.usb.UsbStreamingProfileValidation
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class UsbDirectDsdTransportSessionFactory(
    context: Context,
    private val milestone: (String) -> Unit,
) : DirectDsdTransportSessionFactory {
    private val appContext = context.applicationContext

    override fun open(facts: DsfExtractorPacketFacts): DirectDsdTransportSession {
        require(facts.channelCount == 2)
        require(facts.sourceSampleRateHz == 2_822_400 || facts.sourceSampleRateHz == 5_644_800)
        require(facts.sourceSampleRateHz % 16 == 0)
        val manager = appContext.getSystemService(UsbManager::class.java)
        val identity = Sk02UsbContract.identity
        val device = manager.deviceList.values
            .filter { it.vendorId == identity.vendorId && it.productId == identity.productId }
            .singleOrNull() ?: error("Direct DSD requires exactly one SK02")
        check(manager.hasPermission(device)) { "Direct DSD requires existing SK02 USB permission" }

        UsbOutputRuntime.installGenerationPublisher(UsbSk02NativePrototype::publishGeneration)
        val carrierFormat = UsbPcmFormat(
            facts.sourceSampleRateHz / 16,
            facts.channelCount,
            UsbPcmEncoding.PCM_24_PACKED,
        )
        val request = UsbOutputRequest(device = identity, sourceFormat = carrierFormat)
        return UsbOutputRuntime.owner.replace(request) { lease ->
            UsbSk02NativePrototype.publishGeneration(lease.token.value)
            UsbDirectDsdTransportSession.open(
                appContext,
                device,
                facts,
                carrierFormat,
                lease,
                milestone,
            )
        }
    }
}

private class UsbDirectDsdTransportSession private constructor(
    override val facts: DsfExtractorPacketFacts,
    private val carrierFormat: UsbPcmFormat,
    private val runtimeHandle: com.mica.music.media.usb.UsbAudioRuntimeHandle,
    private val connection: android.hardware.usb.UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val streamingAlt0: UsbInterface,
    private val clockController: Uac2ClockRateController,
    private val clockSourceId: Int,
    private val originalClockHz: Int,
    private val nativeHandle: Long,
    private val requiredPrefillFrames: Long,
    private val carrierSession: DoPCarrierSession,
    private val feeder: ExactCarrierFeeder,
    private val milestone: (String) -> Unit,
) : DirectDsdTransportSession, UsbOutputSession {
    @Volatile
    override var playbackArmed: Boolean = false
        private set
    private var closed = false
    private var lastProgressMilestoneMs = 0L

    override val activeFacts: PlaybackOutputFacts = PlaybackOutputFacts(
        runtimeHandle = runtimeHandle,
        negotiatedFormat = carrierFormat,
        attached = true,
        permission = UsbPermissionState.GRANTED,
        claimed = true,
        exclusive = true,
        signalExact = true,
    )

    override fun writeCanonical(bytes: ByteArray, offset: Int, byteCount: Int): DirectDsdTransportWriteResult {
        check(!closed)
        val consumed = UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            lease.ensureCurrent()
            check(UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) == 0) { "Native exact transport failed" }
            val result = feeder.writeContentBytes(bytes, offset, byteCount)
            check(result.status != ExactCarrierFeedStatus.FAILED && result.error == null) {
                "ExactCarrierFeeder failed ${result.error}"
            }
            maybeArm(lease)
            publishProgressIfDue()
            result.canonicalBytesConsumed
        } ?: error("Direct DSD USB session became stale")
        return DirectDsdTransportWriteResult(consumed)
    }

    override fun finishEndOfStream(): Boolean {
        check(!closed)
        return UsbOutputRuntime.owner.withActiveSession(this) { lease ->
            lease.ensureCurrent()
            var guard = 0
            while (guard++ < 1024) {
                val snapshot = feeder.snapshot()
                if (snapshot.contractError != null) error("feeder contract failed at EOS")
                if (snapshot.stagedCarrierBytes.isEmpty() && snapshot.upstreamPendingPackedCarrierBytes == 0) break
                val result = feeder.pump()
                if (result.status == ExactCarrierFeedStatus.FAILED) error("feeder failed draining EOS")
                if (result.sinkBytesAccepted == 0 && result.carrierBytesFlushedFromSession == 0) return@withActiveSession false
            }
            val feederSnapshot = feeder.snapshot()
            val accounting = carrierSession.accounting()
            val clean = feederSnapshot.stagedCarrierBytes.isEmpty() &&
                feederSnapshot.upstreamPendingPackedCarrierBytes == 0 &&
                feederSnapshot.contractError == null &&
                accounting.pendingPackedCarrierBytes == 0 &&
                accounting.pendingPartialCanonicalFrameBytes == 0 &&
                !accounting.hasPendingCanonicalHalfFrame
            milestone("directDsd=eos clean=$clean canonical=${accounting.canonicalBytesConsumed} contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked}")
            clean
        } ?: false
    }

    override fun restart(lease: UsbOutputRequestLease) {
        lease.ensureCurrent()
        error("Direct DSD prototype restart is intentionally unsupported")
    }

    override fun release(lease: UsbOutputCleanupLease, reason: String) {
        if (closed) return
        closed = true
        val accounting = carrierSession.accounting()
        val feederSnapshot = feeder.snapshot()
        val finalCompleted = runCatching { UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle) }.getOrDefault(-1L)
        val finalError = runCatching { UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle) }.getOrDefault(-1)
        val underrun = runCatching { UsbSk02NativePrototype.getMedia3UnderrunBytes(nativeHandle) }.getOrDefault(-1L)
        val invalidFeedback = runCatching { UsbSk02NativePrototype.getMedia3InvalidFeedbackPacketCount(nativeHandle) }.getOrDefault(-1L)
        val dataErrors = runCatching { UsbSk02NativePrototype.getMedia3DataPacketErrorCount(nativeHandle) }.getOrDefault(-1L)
        val pcmMetrics = runCatching { UsbSk02NativePrototype.getMedia3DiagnosticMetrics(nativeHandle) }
            .getOrDefault(longArrayOf())
            .let { metrics -> (8..16).map { metrics.getOrElse(it) { 0L } } }

        val nativeDestroyed = runCatching { lease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) } }.isSuccess
        val altRestored = runCatching { lease.io { connection.setInterface(streamingAlt0) } }.getOrDefault(false)
        val clockRestored = runCatching {
            lease.io { clockController.setAndVerify(clockSourceId, originalClockHz) }
        }.getOrNull() == UsbRateControlResult.Applied(originalClockHz)
        val streamingReleased = runCatching { lease.io { connection.releaseInterface(streamingAlt0) } }.getOrDefault(false)
        val controlReleased = runCatching { lease.io { connection.releaseInterface(controlInterface) } }.getOrDefault(false)
        val reconnectErrno = runCatching {
            lease.io { UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor) }
        }.getOrNull()
        val controlDriver = runCatching {
            lease.io { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, controlInterface.id) }
        }.getOrNull()
        val streamingDriver = runCatching {
            lease.io { UsbSk02NativePrototype.queryInterfaceDriver(connection.fileDescriptor, streamingAlt0.id) }
        }.getOrNull()
        val driversBound = controlDriver?.contains("driver=snd-usb-audio") == true &&
            streamingDriver?.contains("driver=snd-usb-audio") == true
        val cleanupGreen = nativeDestroyed && altRestored && clockRestored && streamingReleased &&
            controlReleased && reconnectErrno == 0 && driversBound
        val transportGreen = playbackArmed && finalError == 0 && underrun == 0L &&
            invalidFeedback == 0L && dataErrors == 0L && pcmMetrics.all { it == 0L } &&
            accounting.canonicalBytesConsumed > 0L && accounting.contentRuntimeFramesPacked > 0L &&
            accounting.idleRuntimeFramesPacked == 0L && accounting.pendingPackedCarrierBytes == 0 &&
            accounting.pendingPartialCanonicalFrameBytes == 0 && !accounting.hasPendingCanonicalHalfFrame &&
            feederSnapshot.stagedCarrierBytes.isEmpty() &&
            feederSnapshot.upstreamPendingPackedCarrierBytes == 0 && feederSnapshot.contractError == null &&
            cleanupGreen
        connection.close()
        milestone(
            "directDsd=close reason=$reason completed=$finalCompleted error=$finalError underrun=$underrun " +
                "invalidFeedback=$invalidFeedback dataErrors=$dataErrors pcmContent=${pcmMetrics.joinToString(",")} " +
                "canonical=${accounting.canonicalBytesConsumed} contentPacked=${accounting.contentRuntimeFramesPacked} " +
                "idlePacked=${accounting.idleRuntimeFramesPacked} pendingPacked=${accounting.pendingPackedCarrierBytes} " +
                "pendingPartial=${accounting.pendingPartialCanonicalFrameBytes} pendingHalf=${accounting.hasPendingCanonicalHalfFrame} " +
                "feederStaged=${feederSnapshot.stagedCarrierBytes.size} feederPending=${feederSnapshot.upstreamPendingPackedCarrierBytes} " +
                "feederError=${feederSnapshot.contractError} nativeDestroyed=$nativeDestroyed altRestored=$altRestored " +
                "clockRestored=$clockRestored streamingReleased=$streamingReleased controlReleased=$controlReleased " +
                "reconnectErrno=$reconnectErrno driversBound=$driversBound cleanupGreen=$cleanupGreen transportGreen=$transportGreen",
        )
        milestone("directDsd=result status=${if (transportGreen) "PASS" else "FAIL"} reason=$reason")
    }

    override fun close() {
        if (closed) return
        UsbOutputRuntime.owner.release(this, "renderer-close")
    }

    private fun maybeArm(lease: UsbOutputRequestLease) {
        if (playbackArmed) return
        lease.ensureCurrent()
        val buffered = UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)
        if (buffered < requiredPrefillFrames) return
        val accounting = carrierSession.accounting()
        check(accounting.canonicalBytesConsumed > 0)
        check(accounting.contentRuntimeFramesPacked > 0)
        check(accounting.idleRuntimeFramesPacked == 0L)
        milestone(
            "directDsd=prefill buffered=$buffered required=$requiredPrefillFrames canonical=${accounting.canonicalBytesConsumed} " +
                "contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked}",
        )
        val arm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
        check(arm == UsbExactCarrierArmResult.ARMED) { "Native exact arm failed result=$arm" }
        check(UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
        playbackArmed = true
        milestone("directDsd=arm result=$arm armed=true")
    }

    private fun publishProgressIfDue() {
        if (!playbackArmed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressMilestoneMs < 250L) return
        lastProgressMilestoneMs = now
        val accounting = carrierSession.accounting()
        milestone(
            "directDsd=progress elapsedMs=$now completed=${UsbSk02NativePrototype.getMedia3CompletedFrames(nativeHandle)} " +
                "buffered=${UsbSk02NativePrototype.getMedia3BufferedFrames(nativeHandle)} " +
                "error=${UsbSk02NativePrototype.getMedia3ErrorCode(nativeHandle)} canonical=${accounting.canonicalBytesConsumed} " +
                "contentPacked=${accounting.contentRuntimeFramesPacked} idlePacked=${accounting.idleRuntimeFramesPacked}",
        )
    }

    companion object {
        fun open(
            context: Context,
            device: UsbDevice,
            facts: DsfExtractorPacketFacts,
            carrierFormat: UsbPcmFormat,
            lease: UsbOutputRequestLease,
            milestone: (String) -> Unit,
        ): UsbDirectDsdTransportSession {
            val manager = context.getSystemService(UsbManager::class.java)
            lease.ensureCurrent()
            check(manager.hasPermission(device))
            val connection = lease.io { manager.openDevice(device) } ?: error("unable to open Direct DSD target")
            var controlInterface: UsbInterface? = null
            var streamingAlt0: UsbInterface? = null
            var controlClaimed = false
            var streamingClaimed = false
            var altSelected = false
            var nativeHandle = 0L
            var clockController: Uac2ClockRateController? = null
            var clockSourceId: Int? = null
            var originalClockHz: Int? = null
            try {
                val runtime = when (val r = AndroidUsbRuntimeFactsProvider.acquire(device, connection)) {
                    is UsbRuntimeFactsResult.Ready -> r.facts
                    is UsbRuntimeFactsResult.Rejected -> error("runtime facts rejected ${r.rejection.code}:${r.rejection.detail}")
                }
                val parsed = when (val r = StandardUacDescriptorParser.parse(runtime.descriptorSet)) {
                    is UsbAudioDescriptorParseResult.Parsed -> r.facts
                    is UsbAudioDescriptorParseResult.Rejected -> error("descriptor rejected ${r.rejection}")
                }
                val interfaces = (0 until device.interfaceCount).map(device::getInterface)
                val selectedControl = interfaces.firstOrNull {
                    it.id == parsed.audioFunction.controlInterfaceNumber && it.alternateSetting == 0
                } ?: error("AudioControl alt0 missing")
                controlInterface = selectedControl
                controlClaimed = lease.io { connection.claimInterface(selectedControl, true) }
                check(controlClaimed)
                val controlIo = AndroidUsbAudioControlIo(connection)
                val clockEvidence = when (val r = Uac2RuntimeClockEvidenceReader.read(parsed, controlIo)) {
                    is Uac2RuntimeClockEvidenceReadResult.Ready -> r.evidence
                    is Uac2RuntimeClockEvidenceReadResult.Rejected -> error("clock evidence rejected ${r.rejection}")
                }
                val selection = when (
                    val r = UsbGenericPcmSelection.select(carrierFormat, runtime.identity, parsed, clockEvidence)
                ) {
                    is UsbGenericPcmSelectionResult.Ready -> r
                    is UsbGenericPcmSelectionResult.Rejected -> error("generic exact carrier rejected ${r.rejection}")
                }
                val decision = selection.decision
                check(decision.signalExact && decision.requestedFormat == carrierFormat && decision.deviceFormat == carrierFormat)
                val profile = decision.streamingProfile
                check(profile.subslotBytes == 3 && profile.bitResolution == 24 && profile.channelCount == facts.channelCount)
                val claimPlan = checkNotNull(profile.claimPlan)
                val alt0 = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == 0
                } ?: error("streaming alt0 missing")
                val streamingTarget = interfaces.firstOrNull {
                    it.id == claimPlan.streamingInterfaceNumber && it.alternateSetting == claimPlan.alternateSetting
                } ?: error("streaming target missing")
                validateStreamingEndpoints(streamingTarget, profile)
                streamingAlt0 = alt0
                streamingClaimed = lease.io { connection.claimInterface(alt0, true) }
                check(streamingClaimed)
                val selectedClock = profile.clockPlan as? UsbClockPlan.Uac2Entity ?: error("UAC2 clock proof required")
                clockSourceId = selectedClock.sourceEntityId
                val rateController = Uac2ClockRateController(controlIo, selectedControl.id)
                clockController = rateController
                originalClockHz = readClockCurrentHz(controlIo, selectedControl.id, selectedClock.sourceEntityId)
                    ?: error("original clock read failed")
                when (val applied = lease.io { rateController.setAndVerify(selectedClock.sourceEntityId, carrierFormat.sampleRateHz) }) {
                    is UsbRateControlResult.Applied -> check(applied.sampleRateHz == carrierFormat.sampleRateHz)
                    is UsbRateControlResult.Rejected -> error("carrier clock rejected ${applied.rejection}")
                }
                altSelected = lease.io { connection.setInterface(streamingTarget) }
                check(altSelected)
                val bridge = UsbDoPCarrierBridge.planDoP(
                    decision = decision,
                    transport = selection.transportConfig,
                    source = DsdCarrierSourceFacts(facts.sourceSampleRateHz.toLong(), facts.channelCount),
                )
                val plannerResult = bridge as? UsbDoPCarrierBridgeResult.PlannerResult
                    ?: error("bridge rejected ${(bridge as UsbDoPCarrierBridgeResult.Rejected).rejection}")
                val ready = plannerResult.result as? DoPCarrierPlanningResult.Ready
                    ?: error("DoP planner rejected ${(plannerResult.result as DoPCarrierPlanningResult.Rejected).rejection}")
                val plan = ready.plan
                check(plan.packing == DoPCarrierPacking.PACKED_24_LE)
                check(plan.runtimeFrameRateHz == carrierFormat.sampleRateHz.toLong())
                check(plan.dsdBitRateHz == facts.sourceSampleRateHz.toLong())
                check(plan.channelCount == facts.channelCount && plan.bytesPerRuntimeFrame == 6)
                milestone(
                    "directDsd=selection sourceRate=${facts.sourceSampleRateHz} carrierRate=${plan.runtimeFrameRateHz} " +
                        "packing=${plan.packing} channels=${plan.channelCount} interface=${profile.interfaceNumber} " +
                        "alt=${profile.alternateSetting} dataEndpoint=0x${selection.transportConfig.dataEndpointAddress.toString(16)}",
                )

                nativeHandle = lease.io {
                    UsbSk02NativePrototype.createMedia3Stream(
                        connection.fileDescriptor,
                        selection.transportConfig,
                        lease.token.value,
                        UsbNativePayloadPolicy.EXACT_FRAMES_ONLY,
                    )
                }
                check(nativeHandle != 0L)
                check(!UsbSk02NativePrototype.isExactCarrierSessionArmed(nativeHandle))
                val requiredBytes = UsbSk02NativePrototype.getExactCarrierStartupPrefillBytes(nativeHandle)
                val requiredFrames = UsbSk02NativePrototype.getExactCarrierStartupPrefillFrames(nativeHandle)
                val capacityFrames = UsbSk02NativePrototype.getMedia3BufferCapacityFrames(nativeHandle)
                check(requiredBytes > 0 && requiredFrames > 0 && capacityFrames >= requiredFrames)
                check(requiredBytes == requiredFrames * plan.bytesPerRuntimeFrame)
                val underArm = UsbSk02NativePrototype.armExactCarrierSession(nativeHandle)
                check(underArm == UsbExactCarrierArmResult.RETRY_INSUFFICIENT_PREFILL)
                milestone(
                    "directDsd=native dormant=true requiredBytes=$requiredBytes requiredFrames=$requiredFrames " +
                        "capacityFrames=$capacityFrames underArm=$underArm",
                )

                val carrierSession = DoPCarrierSession(plan)
                val handleForSink = nativeHandle
                val sink = UsbDoPIdleNativeSink(plan.bytesPerRuntimeFrame) { buffer, length ->
                    lease.ensureCurrent()
                    UsbSk02NativePrototype.writeMedia3Stream(handleForSink, buffer, 0, length)
                }
                val feeder = ExactCarrierFeeder(carrierSession, sink, stagingFrameCapacity = 4_096)
                return UsbDirectDsdTransportSession(
                    facts = facts,
                    carrierFormat = carrierFormat,
                    runtimeHandle = runtime.runtimeHandle,
                    connection = connection,
                    controlInterface = selectedControl,
                    streamingAlt0 = alt0,
                    clockController = rateController,
                    clockSourceId = selectedClock.sourceEntityId,
                    originalClockHz = checkNotNull(originalClockHz),
                    nativeHandle = nativeHandle,
                    requiredPrefillFrames = requiredFrames,
                    carrierSession = carrierSession,
                    feeder = feeder,
                    milestone = milestone,
                )
            } catch (error: Throwable) {
                val cleanupLease = lease.cleanupLease()
                if (nativeHandle != 0L) runCatching { cleanupLease.io { UsbSk02NativePrototype.destroyMedia3Stream(nativeHandle) } }
                if (altSelected && streamingAlt0 != null) runCatching { cleanupLease.io { connection.setInterface(checkNotNull(streamingAlt0)) } }
                if (clockController != null && clockSourceId != null && originalClockHz != null) {
                    runCatching { cleanupLease.io { checkNotNull(clockController).setAndVerify(checkNotNull(clockSourceId), checkNotNull(originalClockHz)) } }
                }
                if (streamingClaimed && streamingAlt0 != null) runCatching { cleanupLease.io { connection.releaseInterface(checkNotNull(streamingAlt0)) } }
                if (controlClaimed && controlInterface != null) runCatching { cleanupLease.io { connection.releaseInterface(checkNotNull(controlInterface)) } }
                runCatching { cleanupLease.io { UsbSk02NativePrototype.reconnectKernelDrivers(connection.fileDescriptor) } }
                connection.close()
                throw error
            }
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
                    request = 0x01,
                    value = 0x01 shl 8,
                    index = (clockSourceId shl 8) or audioControlInterface,
                    readLength = 4,
                ),
            )
            val success = result as? UsbControlIoResult.Success ?: return null
            if (success.transferredBytes != 4 || success.data.size != 4) return null
            return ByteBuffer.wrap(success.data).order(ByteOrder.LITTLE_ENDIAN).int.takeIf { it > 0 }
        }
    }
}
