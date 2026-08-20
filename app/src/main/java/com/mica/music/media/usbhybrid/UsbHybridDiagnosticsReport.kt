package com.mica.music.media.usbhybrid

import android.content.Context
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
        appendLine("builtInQuirk=apk:usb_dac_quirks.json; runtimeOverride=disabled")
        appendLine(
            "negotiated=encoding:${facts.sourceEncoding ?: "unknown"}," +
                "usbBits:${facts.usbBitResolution ?: "unknown"}," +
                "rate:${facts.sampleRate ?: "unknown"},channels:${facts.channels ?: "unknown"}",
        )
        facts.telemetry?.let {
            appendLine(
                "urbTelemetry=pendingIso:${it.pendingIsoPackets},totalIso:${it.totalIsoPackets}," +
                    "pendingOutputUrbs:${it.pendingOutputUrbs},errors:${it.isoErrorCount}",
            )
        } ?: appendLine("urbTelemetry=unavailable")
        facts.failure?.let { appendLine("lastError=${it.code}:${it.message}") }
        appendLine("serial=not-exported")
    }

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
