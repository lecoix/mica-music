package com.mica.music.media.usb

import android.hardware.usb.UsbManager

/** Read-only Android adapter for stable-identity reconnect resolution. */
internal object AndroidUsbStableReconnectResolver {
    fun resolve(
        manager: UsbManager,
        expectedIdentity: UsbAudioDeviceIdentity,
    ): UsbStableReconnectResolution = UsbStableIdentityReconnectResolver.resolve(
        expectedIdentity = expectedIdentity,
        attached = AndroidUsbAudioDiscovery.attachedFacts(manager),
    ) { candidate ->
        val device = AndroidUsbAudioDiscovery.resolve(manager, candidate)
            ?: return@resolve UsbStableReconnectCandidateProof.Rejected(
                "candidate disappeared before runtime-fact proof",
            )
        if (!manager.hasPermission(device)) {
            return@resolve UsbStableReconnectCandidateProof.Rejected(
                "candidate permission disappeared before runtime-fact proof",
            )
        }
        val connection = manager.openDevice(device)
            ?: return@resolve UsbStableReconnectCandidateProof.Rejected(
                "unable to open candidate for runtime-fact proof",
            )
        try {
            when (val result = AndroidUsbRuntimeFactsProvider.acquire(device, connection)) {
                is UsbRuntimeFactsResult.Ready ->
                    UsbStableReconnectCandidateProof.Proven(result.facts.identity)

                is UsbRuntimeFactsResult.Rejected ->
                    UsbStableReconnectCandidateProof.Rejected(
                        "runtime facts rejected=${result.rejection.code}: ${result.rejection.detail}",
                    )
            }
        } finally {
            connection.close()
        }
    }
}
