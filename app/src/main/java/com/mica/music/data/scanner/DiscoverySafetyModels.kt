package com.mica.music.data.scanner

enum class DiscoveryCompleteness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

data class DiscoveryPartitionStatus(
    val partitionKey: String,
    val completeness: DiscoveryCompleteness,
    val detail: String = "",
)

data class DiscoveryReport(
    val partitions: Map<String, DiscoveryPartitionStatus> = emptyMap(),
) {
    val aggregate: DiscoveryCompleteness
        get() = when {
            partitions.isEmpty() -> DiscoveryCompleteness.PARTIAL
            partitions.values.all { it.completeness == DiscoveryCompleteness.COMPLETE } ->
                DiscoveryCompleteness.COMPLETE
            partitions.values.all { it.completeness == DiscoveryCompleteness.UNAVAILABLE } ->
                DiscoveryCompleteness.UNAVAILABLE
            else -> DiscoveryCompleteness.PARTIAL
        }

    fun isComplete(partitionKey: String): Boolean =
        partitions[partitionKey]?.completeness == DiscoveryCompleteness.COMPLETE

    fun plus(status: DiscoveryPartitionStatus): DiscoveryReport =
        copy(partitions = partitions + (status.partitionKey to status))

    companion object {
        fun of(vararg statuses: DiscoveryPartitionStatus): DiscoveryReport =
            DiscoveryReport(statuses.associateBy(DiscoveryPartitionStatus::partitionKey))
    }
}

internal object DiscoveryPartitions {
    const val MEDIASTORE_AUDIO = "mediastore:audio"
    const val MEDIASTORE_FILES_FALLBACK = "mediastore:files-fallback"
    const val MEDIASTORE_LYRICS_SIDECARS = "mediastore:lyrics-sidecars"
    const val SAF_TREE = "saf:tree"
}
