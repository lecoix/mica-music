package com.mica.music.data.library

internal data class LibraryRetryCursor(
    val retryKey: String = "",
) {
    companion object {
        val Start = LibraryRetryCursor()
    }
}

internal data class LibraryRetryPage(
    val items: List<LibraryRetryItem>,
    val nextCursor: LibraryRetryCursor?,
)

internal object LibraryRetryPaging {
    const val PAGE_SIZE = 128
    const val STABLE_KEY_LOOKUP_BATCH_SIZE = 128
    const val DUE_WORK_BUDGET = 64
    const val DELETE_BATCH_SIZE = 400
}
