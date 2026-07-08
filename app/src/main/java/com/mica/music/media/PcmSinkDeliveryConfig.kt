package com.mica.music.media

/** Gate 3-1b: Exo [DefaultAudioSink] float-output flag fixed at player build time. */
internal data class PcmSinkDeliveryConfig(
    val enableFloatOutput: Boolean,
    val profileLabel: String,
) {
    companion object {
        val PRODUCTION = PcmSinkDeliveryConfig(
            enableFloatOutput = false,
            profileLabel = "production-int16-sink",
        )
    }
}
