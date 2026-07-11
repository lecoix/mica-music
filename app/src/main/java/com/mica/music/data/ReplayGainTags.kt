package com.mica.music.data

/** ReplayGain values read from audio metadata. Blank values mean the file has no usable tag. */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    companion object {
        fun fromProperties(properties: Map<String, Array<String>>): ReplayGainTags = ReplayGainTags(
            trackGainDb = properties.value("REPLAYGAIN_TRACK_GAIN").toGainDb(),
            trackPeak = properties.value("REPLAYGAIN_TRACK_PEAK").toPeak(),
            albumGainDb = properties.value("REPLAYGAIN_ALBUM_GAIN").toGainDb(),
            albumPeak = properties.value("REPLAYGAIN_ALBUM_PEAK").toPeak(),
        )

        private fun Map<String, Array<String>>.value(key: String): String? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value?.firstOrNull()?.trim()

        private fun String?.toGainDb(): Float? =
            this?.removeSuffix("dB")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

        private fun String?.toPeak(): Float? =
            this?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    }
}
