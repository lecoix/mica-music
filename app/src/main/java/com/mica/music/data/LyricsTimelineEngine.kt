package com.mica.music.data

class LyricsTimelineEngine(
    private val document: LyricsDocument,
) {
    fun snapshotAt(positionMs: Int): LyricsTimelineSnapshot {
        val lines = document.lines
        if (lines.isEmpty()) return LyricsTimelineSnapshot(LyricsTimelinePhase.BeforeFirstLine)

        val syncMs = positionMs + LyricsSync.LEAD_MS

        val activeIndex = lines.indexOfLast { line ->
            line.startMs <= syncMs && (line.endMs == null || syncMs < line.endMs)
        }
        if (activeIndex >= 0) {
            val line = lines[activeIndex]
            val inferredEnd = line.endMs ?: lines.getOrNull(activeIndex + 1)?.startMs
            return LyricsTimelineSnapshot(
                LyricsTimelinePhase.Line(
                    index = activeIndex,
                    progress = progressBetween(line.startMs, inferredEnd, syncMs),
                ),
            )
        }

        val nextIndex = lines.indexOfFirst { it.startMs > syncMs }
        if (nextIndex == 0) return LyricsTimelineSnapshot(LyricsTimelinePhase.BeforeFirstLine)
        if (nextIndex > 0) {
            val previousIndex = nextIndex - 1
            val previousEnd = lines[previousIndex].endMs
            if (previousEnd != null && previousEnd <= syncMs) {
                return LyricsTimelineSnapshot(
                    LyricsTimelinePhase.Gap(
                        previousIndex = previousIndex,
                        nextIndex = nextIndex,
                        progress = progressBetween(previousEnd, lines[nextIndex].startMs, syncMs),
                        durationMs = lines[nextIndex].startMs - previousEnd,
                    ),
                )
            }
        }

        return LyricsTimelineSnapshot(LyricsTimelinePhase.AfterLastLine)
    }

    private fun progressBetween(startMs: Int, endMs: Int?, positionMs: Int): Float {
        if (endMs == null || endMs <= startMs) return 0f
        return ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}

data class LyricsTimelineSnapshot(
    val phase: LyricsTimelinePhase,
)

sealed interface LyricsTimelinePhase {
    data class Line(val index: Int, val progress: Float) : LyricsTimelinePhase
    data class Gap(
        val previousIndex: Int,
        val nextIndex: Int,
        val progress: Float,
        val durationMs: Int,
    ) : LyricsTimelinePhase

    data object BeforeFirstLine : LyricsTimelinePhase
    data object AfterLastLine : LyricsTimelinePhase
}
