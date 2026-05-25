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
    /** HR / SQ / HQ / 其他（合并原采样率与码率分布） */
    val qualityTierBreakdown: List<LabeledCount>,
)

object LibraryAnalyzer {

    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "APE", "DSD", "AIFF")

    const val TIER_HR = "HR"
    const val TIER_SQ = "SQ"
    const val TIER_HQ = "HQ"
    const val TIER_OTHER = "其他"

    private val QUALITY_TIER_ORDER = listOf(TIER_HR, TIER_SQ, TIER_HQ, TIER_OTHER)

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
                qualityTierBreakdown = emptyList(),
            )
        }

        val totalSize = songs.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val hiRes = songs.count { it.isHiRes }
        val lossless = songs.count { it.metadata.containerName.uppercase() in LOSSLESS }
        val tierCounts = songs.groupingBy { qualityTierLabel(it) }.eachCount()

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
            qualityTierBreakdown = QUALITY_TIER_ORDER.mapNotNull { tier ->
                tierCounts[tier]?.takeIf { it > 0 }?.let { LabeledCount(tier, it) }
            },
        )
    }

    /**
     * 合并采样率与码率：
     * - **HR**：Hi-Res（>48 kHz 或 >16 bit）
     * - **SQ**：无损容器且非 Hi-Res
     * - **HQ**：有损且码率 ≥ 320 kbps
     * - **其他**：有损且码率低于 320 kbps，或元数据未知
     */
    fun qualityTierLabel(song: Song): String = qualityTierLabel(song.metadata)

    fun qualityTierLabel(metadata: TrackMetadata): String {
        if (metadata.isHiRes) return TIER_HR
        val lossless = metadata.containerName.uppercase() in LOSSLESS
        if (lossless) return TIER_SQ
        if (metadata.bitrateKbps >= 320) return TIER_HQ
        return TIER_OTHER
    }
}
