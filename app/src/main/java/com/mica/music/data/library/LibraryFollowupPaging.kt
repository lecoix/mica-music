package com.mica.music.data.library

internal data class LibraryFollowupOutboxCursor(
    val createdAtMs: Long = Long.MIN_VALUE,
    val eventId: String = "",
) {
    companion object {
        val Start = LibraryFollowupOutboxCursor()
    }
}

internal data class LibraryFollowupOutboxPage(
    val items: List<LibraryFollowupOutboxItem>,
    val nextCursor: LibraryFollowupOutboxCursor?,
)

internal object LibraryFollowupPaging {
    const val PAGE_SIZE = 64
    const val MAX_PAGES_PER_DRAIN = 8
}
