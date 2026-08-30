package com.mica.music.data.remote

data class RemoteAutoSyncResult(
    val attempted: Int,
    val succeeded: Int,
    val failedSourceIds: List<String>,
)

internal fun RemoteSourceStatus.needsAutomaticSync(
    nowMs: Long,
    staleAfterMs: Long,
): Boolean {
    if (!instance.enabled) return false
    if (lastSyncAtMs <= 0L) return true
    return nowMs - lastSyncAtMs >= staleAfterMs.coerceAtLeast(0L)
}