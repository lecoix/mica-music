package com.mica.music.media.usbhybrid

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.afalphy.sylvakru.UsbDacQuirks
import com.afalphy.sylvakru.UsbExclusiveAudioTransport
import com.mica.music.usb.UsbHybridDiagnosticsPort
import java.io.File
import java.security.MessageDigest

object UsbHybridDiagnosticsReport {
    fun build(context: Context, facts: UsbPlaybackFacts): String = buildString {
        appendLine("USB Exclusive Hybrid")
        appendLine("apkSha256=${sha256(File(context.applicationInfo.sourceDir))}")
        appendLine("requestEpoch=${facts.requestEpoch}")
        appendLine("requestedMode=${facts.requestedMode}")
        appendLine("activeMode=${facts.activeMode ?: "none"}")
        appendLine("sessionId=${facts.sessionId ?: "none"}")
        appendLine("permission=${facts.permission}")
        appendLine("claimed=${facts.claimed}")
        appendLine("exclusive=${facts.exclusive}")
        appendLine("transportExact=${facts.transportExact}")
        appendLine("signalExact=${facts.signalExact}")
        facts.identity?.let {
            appendLine("stableIdentity=${hex(it.vendorId)}:${hex(it.productId)}:bcd=${it.bcdDevice ?: "unknown"}")
            appendLine("descriptorDigest=${it.descriptorDigest}")
        }
        facts.runtimeHandle?.let {
            appendLine("runtimeHandle=deviceId:${it.deviceId},name:${it.deviceName}")
        }
        appendUsbDeviceEvidence(context, facts)
        facts.identity?.let { identity ->
            appendLine("quirkMatch=${UsbDacQuirks.matchDescription(context, identity.vendorId, identity.productId) ?: "none (defaults)"}")
            appendLine("quirkEffective=${UsbDacQuirks.forDevice(context, identity.vendorId, identity.productId)}")
        }
        appendLine("quirkLoadErrors=${UsbDacQuirks.loadErrors(context).joinToString("; ").ifEmpty { "none" }}")
        appendLine("quirkSource=apk:usb_dac_quirks.json + files:${UsbDacQuirks.OVERRIDE_FILE_NAME}; override-first")
        appendLine(
            "negotiated=format:${facts.streamFormat ?: "unknown"},encoding:${facts.sourceEncoding ?: "unknown"}," +
                "usbBits:${facts.usbBitResolution ?: "unknown"}," +
                "rate:${facts.sampleRate ?: "unknown"},channels:${facts.channels ?: "unknown"}",
        )
        facts.telemetry?.let {
            appendLine(
                "urbTelemetry=pendingIso:${it.pendingIsoPackets},totalIso:${it.totalIsoPackets}," +
                    "pendingOutputUrbs:${it.pendingOutputUrbs},errors:${it.isoErrorCount}",
            )
        } ?: appendLine("urbTelemetry=unavailable")
        appendLine("sessionDiagnostics=${facts.sessionDiagnostics ?: "unavailable"}")
        facts.failure?.let { appendLine("lastError=${it.code}:${it.message}") }
        appendLine("serial=not-exported")
    }

    private fun StringBuilder.appendUsbDeviceEvidence(context: Context, facts: UsbPlaybackFacts) {
        val manager = context.getSystemService(UsbManager::class.java) ?: run {
            appendLine("usbDeviceEvidence=UsbManager unavailable")
            return
        }
        val target = manager.deviceList.values.firstOrNull { device ->
            val identity = facts.identity ?: return@firstOrNull false
            device.vendorId == identity.vendorId && device.productId == identity.productId
        } ?: run {
            appendLine("usbDeviceEvidence=target not currently attached")
            return
        }
        appendLine("usbDevice=vid:${hex(target.vendorId)},pid:${hex(target.productId)},version:${target.version ?: "unknown"},manufacturer:${safe { target.manufacturerName } ?: "unknown"},product:${safe { target.productName } ?: "unknown"}")
        appendLine("androidTopology:")
        appendAndroidTopology(target)
        appendLine("hardwareVolumeDiagnostics=${UsbExclusiveAudioTransport.collectHardwareVolumeDiagnostics(context, manager, target)}")
        if (!manager.hasPermission(target)) {
            appendLine("rawDescriptors=unavailable: USB permission not granted")
            return
        }
        val connection = runCatching { manager.openDevice(target) }.getOrNull()
        if (connection == null) {
            appendLine("rawDescriptors=unavailable: openDevice failed")
            return
        }
        try {
            val raw = connection.rawDescriptors
            appendLine("rawDescriptorsBytes=${raw?.size ?: 0}")
            appendLine("rawDescriptorsHex=")
            if (raw == null || raw.isEmpty()) appendLine("<empty>") else appendHex(raw)
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun StringBuilder.appendAndroidTopology(device: UsbDevice) {
        for (configurationIndex in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(configurationIndex)
            appendLine("  config id=${configuration.id} interfaces=${configuration.interfaceCount}")
            for (interfaceIndex in 0 until configuration.interfaceCount) {
                val intf = configuration.getInterface(interfaceIndex)
                appendLine("    interface id=${intf.id} alt=${intf.alternateSetting} class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}")
                for (endpointIndex in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(endpointIndex)
                    appendLine("      endpoint address=0x${endpoint.address.toString(16)} direction=${endpoint.direction} type=${endpoint.type} maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}")
                }
            }
        }
    }

    private fun StringBuilder.appendHex(bytes: ByteArray) {
        bytes.asList().chunked(32).forEach { row ->
            appendLine(row.joinToString(" ") { "%02x".format(it.toInt() and 0xff) })
        }
    }

    private fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

    private fun hex(value: Int): String = "0x%04x".format(value)
}
internal class MediaUsbHybridDiagnosticsPort(
    private val context: Context,
) : UsbHybridDiagnosticsPort {
    override fun buildReport(): String =
        UsbHybridDiagnosticsReport.build(context, UsbHybridRuntimeMonitor.facts.value)
}