package com.mica.music.data

data class LabeledCount(
    val label: String,
    val count: Int,
)

data class LibraryAnalysis(
    val totalSongs: Int,
    val totalSizeBytes: Long,
    val totalSizeMb: Int,
    val hiResCount: Int,
    val hiResPercent: Int,
    val losslessCount: Int,
    val losslessPercent: Int,
    val formatBreakdown: List<LabeledCount>,
    val sampleRateBreakdown: List<LabeledCount>,
    val bitrateBreakdown: List<LabeledCount>,
)

object LibraryAnalyzer {

    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "APE", "DSD", "AIFF")

    fun analyze(songs: List<Song>): LibraryAnalysis {
        if (songs.isEmpty()) {
            return LibraryAnalysis(
                totalSongs = 0,
                totalSizeBytes = 0,
                totalSizeMb = 0,
                hiResCount = 0,
                hiResPercent = 0,
                losslessCount = 0,
                losslessPercent = 0,
                formatBreakdown = emptyList(),
                sampleRateBreakdown = emptyList(),
                bitrateBreakdown = emptyList(),
            )
        }

        val totalSize = songs.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val hiRes = songs.count { it.isHiRes }
        val lossless = songs.count { it.metadata.containerName.uppercase() in LOSSLESS }

        return LibraryAnalysis(
            totalSongs = songs.size,
            totalSizeBytes = totalSize,
            totalSizeMb = (totalSize / (1024 * 1024)).toInt().coerceAtLeast(0),
            hiResCount = hiRes,
            hiResPercent = (hiRes * 100 / songs.size),
            losslessCount = lossless,
            losslessPercent = (lossless * 100 / songs.size),
            formatBreakdown = songs.groupingBy { it.metadata.containerName.uppercase() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { LabeledCount(it.key, it.value) },
            sampleRateBreakdown = songs.groupingBy { sampleRateBucket(it.metadata.sampleRateHz) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { LabeledCount(it.key, it.value) },
            bitrateBreakdown = songs.groupingBy { bitrateBucket(it.metadata.bitrateKbps, it.metadata.containerName) }
                .eachCount()
                .entries
                .sortedBy { bitrateBucketOrder(it.key) }
                .map { LabeledCount(it.key, it.value) },
        )
    }

    private fun sampleRateBucket(hz: Int): String = when {
        hz <= 0 -> "未知"
        hz < 44_100 -> "< 44.1 kHz"
        hz == 44_100 -> "44.1 kHz"
        hz < 48_000 -> "44.1–48 kHz"
        hz == 48_000 -> "48 kHz"
        hz < 96_000 -> "48–96 kHz"
        hz < 192_000 -> "96–192 kHz"
        else -> "≥ 192 kHz"
    }

    private fun bitrateBucket(kbps: Int, container: String): String {
        if (kbps <= 0) return "未知"
        val lossless = container.uppercase() in LOSSLESS
        return if (lossless) {
            when {
                kbps < 500 -> "< 500 kbps"
                kbps < 1000 -> "500–999 kbps"
                kbps < 2000 -> "1.0–1.9 Mbps"
                else -> "≥ 2.0 Mbps"
            }
        } else {
            when {
                kbps < 128 -> "< 128 kbps"
                kbps < 256 -> "128–255 kbps"
                kbps < 320 -> "256–319 kbps"
                else -> "≥ 320 kbps"
            }
        }
    }

    private fun bitrateBucketOrder(label: String): Int = when (label) {
        "未知" -> 0
        "< 128 kbps" -> 1
        "128–255 kbps" -> 2
        "256–319 kbps" -> 3
        "≥ 320 kbps" -> 4
        "< 500 kbps" -> 5
        "500–999 kbps" -> 6
        "1.0–1.9 Mbps" -> 7
        "≥ 2.0 Mbps" -> 8
        else -> 99
    }
}
