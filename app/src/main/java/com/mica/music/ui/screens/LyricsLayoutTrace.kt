package com.mica.music.ui.screens

import com.mica.music.util.DiagnosticDetailDomain
import com.mica.music.util.DiagnosticLog
import kotlin.math.roundToInt

/** Page-local, bounded tracing only; it never changes lyrics, scroll state, or preferences. */
internal class LyricsLayoutTrace(
    private val enabled: () -> Boolean = {
        DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.UI_RENDERING)
    },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sink: (String) -> Unit = { DiagnosticLog.event("LyricsRenderInput", it) },
) {
    private val session = Integer.toHexString(System.identityHashCode(this))
    private val rows = linkedMapOf<Int, () -> LyricsTracePlacement?>()
    private var closed = false
    private var lastSampleMs: Long? = null
    private var lastGeometry: String? = null

    fun isEnabled(): Boolean = !closed && enabled()

    fun placed(index: Int, placement: () -> LyricsTracePlacement?) {
        if (!isEnabled()) return
        if (index !in rows && rows.size >= MAX_ROWS) rows.remove(rows.keys.first())
        rows[index] = placement
    }

    fun removed(index: Int) {
        val previous = rows.remove(index) ?: return
        if (isEnabled()) emit("detach index=$index last=${previous()?.describe()}")
    }

    fun event(message: () -> String) {
        if (isEnabled()) emit(message())
    }

    fun sample(context: () -> String, force: Boolean = false) {
        if (!isEnabled()) {
            rows.clear()
            lastGeometry = null
            return
        }
        val now = clockMs()
        if (!force && lastSampleMs?.let { now - it < SAMPLE_INTERVAL_MS } == true) return
        val geometry = rows.entries.sortedBy { it.key }.joinToString(";") { (index, placement) ->
            "$index:${placement()?.describe() ?: "detached"}"
        }
        if (!force && geometry == lastGeometry) return
        lastSampleMs = now
        lastGeometry = geometry
        emit("geometry ${context()} rows=[$geometry]")
    }

    fun close() {
        if (isEnabled()) emit("close tracked=${rows.size}")
        closed = true
        rows.clear()
    }

    private fun emit(message: String) = sink("$TAG session=$session $message")

    companion object {
        const val TAG = "DEBUG-LYRICS-LAYOUT-4F2A"
        const val MAX_ROWS = 24
        private const val SAMPLE_INTERVAL_MS = 250L
    }
}

internal data class LyricsTracePlacement(
    val displayIndex: Int,
    val keyHash: Int,
    val timeMs: Int,
    val rootY: Float,
    val heightPx: Int,
    val lagPx: Float,
) {
    fun describe(): String =
        "d=$displayIndex,k=$keyHash,t=$timeMs,y=${rootY.roundToInt()},h=$heightPx,lag=${lagPx.roundToInt()}"
}
