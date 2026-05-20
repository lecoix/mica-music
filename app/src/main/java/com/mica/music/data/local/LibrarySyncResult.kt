package com.mica.music.data.local

data class LibrarySyncResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
) {
    val hasChanges: Boolean get() = added > 0 || updated > 0 || removed > 0

    fun toSummary(): String = buildString {
        append("扫描完成")
        if (added > 0) append("，新增 $added")
        if (updated > 0) append("，更新 $updated")
        if (removed > 0) append("，移除 $removed")
        if (!hasChanges) append("，无变化")
    }
}
