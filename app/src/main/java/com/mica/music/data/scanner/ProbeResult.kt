package com.mica.music.data.scanner

/**
 * 单轨增强探测结果。异常路径记 [Failed]；调用方仍用 [valueOrEmpty] 降级入库。
 */
internal sealed interface ProbeResult<out T> {
    data class Ok<T>(val value: T) : ProbeResult<T>
    data class Failed(val stage: String) : ProbeResult<Nothing>
}

internal fun ProbeResult<AudioTechnicalProbe.Result>.technicalValue(): AudioTechnicalProbe.Result =
    when (this) {
        is ProbeResult.Ok -> value
        is ProbeResult.Failed -> AudioTechnicalProbe.Result()
    }

data class ScanProbeStats(
    val technicalFailed: Int = 0,
    val lyricsReadFailed: Int = 0,
) {
    fun hasTechnicalFailures(): Boolean = technicalFailed > 0

    fun hasLyricsReadFailures(): Boolean = lyricsReadFailed > 0
}
