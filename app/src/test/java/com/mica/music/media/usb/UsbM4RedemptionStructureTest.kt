package com.mica.music.media.usb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbM4RedemptionStructureTest {
    @Test
    fun usbStacksStartUnavailableAndDoNotGuessAGenerationAtBuild() {
        val factory = source("app/src/main/java/com/mica/music/media/ExoPlaybackStack.kt")
        val build = factory.substringAfter("fun build(").substringBefore("val renderersFactory")
        assertOrdered(
            build,
            "outputPath.usbOutputRequest == null",
            "OutputTarget.SharedPcm",
            "OutputTarget.Unavailable",
            "playbackCoordinator.createStack(initialOutputTarget)",
        )
        assertFalse(build.contains("OutputTarget.UsbBound"))
        assertFalse(build.contains("UsbOutputRuntime.owner.facts.generation"))
    }

    @Test
    fun pcmConfigureAndHandleBufferRedeemP2BeforeDelegateUsbIo() {
        val sink = source("app/src/main/java/com/mica/music/media/dsd/TransitionAwarePcmAudioSink.kt")
        val configure = sink.substringAfter("override fun configure(")
            .substringBefore("override fun handleBuffer")
        assertOrdered(
            configure,
            "usbP2RedemptionContext.prepareProtocolBinding()",
            "preparePcmConfigure(",
            "usbP2RedemptionContext.ensurePermitTarget(permit.outputTarget)",
            "configureWithProtocol(",
        )
        val handleBuffer = sink.substringAfter("override fun handleBuffer(")
            .substringBefore("override fun play()")
        assertTrue(handleBuffer.contains("usbP2RedemptionContext.prepareProtocolBinding()"))
        val usbWrite = handleBuffer.substringAfter("usbP2RedemptionContext.withProtocolWrite(")
        assertOrdered(
            usbWrite,
            "lease,",
            "WriteKind.PCM_DATA,",
            "super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)",
        )
    }

    @Test
    fun pcmProviderOpenConsumesReservedLeaseAndWriteRequiresProtocolScope() {
        val provider = source(
            "app/src/main/java/com/mica/music/media/usbprototype/UsbSk02AudioOutputProvider.kt",
        )
        val open = provider.substringAfter("fun open(")
            .substringBefore("private fun selectTarget(")
        assertOrdered(
            open,
            "redemptionContext.consumeCurrent { binding, lease ->",
            "binding.ensureRequestLease(lease)",
            "UsbSk02AudioOutput.open(",
        )
        val write = provider.substringAfter("override fun write(")
            .substringBefore("override fun flush()")
        assertOrdered(
            write,
            "UsbOutputRuntime.owner.withActiveSession(this) { lease ->",
            "redemptionBinding.ensureActiveSession(this, lease)",
            "redemptionContext.requireProtocolWrite(redemptionBinding.target, WriteKind.PCM_DATA)",
        )
    }

    @Test
    fun directCreateReservesBeforeOpenAndFactoryRejectsLeaseLessOpen() {
        val renderer = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val create = renderer.substringAfter("private fun openPumpIfNeeded")
            .substringBefore("private fun observeShadowPrefillIfReady")
        assertOrdered(
            create,
            "usbP2RedemptionContext.prepareProtocolBinding()",
            "prepareDirectStage(",
            "usbP2RedemptionContext.ensurePermitTarget(createPermit.outputTarget)",
            "sessionFactory.open(facts, writeAuthority)",
            "commitDirectStage(",
        )
        val factory = source(
            "app/src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        )
        assertTrue(
            factory.contains("error(\"Direct USB transport requires the explicit M4 write authority\")"),
        )
        val open = factory.substringAfter("override fun open(")
            .substringAfter("writeAuthority: DirectDsdWriteAuthority")
            .substringBefore("internal class UsbDirectDsdTransportSession")
        assertOrdered(
            open,
            "redemptionContext.requireCurrentBinding()",
            "redemptionContext.consumeCurrent { binding, lease ->",
            "binding.ensureRequestLease(lease)",
            "UsbDirectDsdTransportSession.open(",
        )
        assertFalse(open.contains("UsbOutputRuntime.owner.replace("))
    }

    @Test
    fun directContentAndGapWritesEnterProtocolScopeBeforeNativeIo() {
        val renderer = source("app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt")
        val write = renderer.substringAfter("override fun withWrite(kind: WriteKind, block: () -> T): T")
            .substringBefore("override fun withRetainedHandoff")
        assertOrdered(
            write,
            "redemptionContext.prepareProtocolBinding()",
            "playbackAdapter.tryEnterWrite(occurrence, kind)",
            "redemptionContext.withProtocolWrite(target, lease, kind, block)",
        )
        val native = renderer.substringAfter("override fun requireNativeIoAllowed()")
            .substringBefore("private fun ensureRuntimeCurrent()")
        assertOrdered(
            native,
            "redemptionContext.prepareProtocolBinding()",
            "redemptionContext.requireProtocolWrite(",
        )
    }

    private fun assertOrdered(body: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = body.indexOf(token)
            assertTrue("missing $token", index >= 0)
            assertTrue("$token out of order", index > previous)
            previous = index
        }
    }

    private fun source(path: String): String {
        val candidates = listOf(path, path.removePrefix("app/"), "../$path", "../../$path")
        return candidates.asSequence().map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("source file missing: ${candidates.joinToString()}")
    }
}
