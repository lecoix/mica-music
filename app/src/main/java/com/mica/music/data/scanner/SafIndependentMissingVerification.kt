package com.mica.music.data.scanner

internal data class SafMissingVerificationBudget(
    val maxObjects: Int = DEFAULT_MAX_OBJECTS,
    val maxQueries: Int = DEFAULT_MAX_QUERIES,
    val maxWallTimeMs: Long = DEFAULT_MAX_WALL_TIME_MS,
) {
    init {
        require(maxObjects > 0)
        require(maxQueries > 0)
        require(maxWallTimeMs > 0L)
    }

    companion object {
        const val DEFAULT_MAX_OBJECTS = 64
        const val DEFAULT_MAX_QUERIES = 64
        const val DEFAULT_MAX_WALL_TIME_MS = 2_000L
        val Default = SafMissingVerificationBudget()
    }
}

internal data class SafIndependentMissingVerificationResult(
    val verifiedMissingStableObjectKeys: Set<String>,
    val presentStableObjectKeys: Set<String>,
    val indeterminateStableObjectKeys: Set<String>,
    val providerQueryCount: Int = 0,
    val wallTimeMs: Long = 0L,
    /** Absolute offset into the stable, sorted target set for the next continuation. */
    val nextCursor: Int = 0,
    /** True when unprocessed targets remain after this batch. */
    val hasMore: Boolean = false,
    /** True only when this batch stopped because an object/query/time budget was exhausted. */
    val budgetExhausted: Boolean = false,
) {
    init {
        require(providerQueryCount >= 0)
        require(wallTimeMs >= 0L)
        require(nextCursor >= 0)
        require(verifiedMissingStableObjectKeys.intersect(presentStableObjectKeys).isEmpty())
        require(verifiedMissingStableObjectKeys.intersect(indeterminateStableObjectKeys).isEmpty())
        require(presentStableObjectKeys.intersect(indeterminateStableObjectKeys).isEmpty())
        require(!budgetExhausted || hasMore)
    }

    val processedObjectCount: Int
        get() = verifiedMissingStableObjectKeys.size +
            presentStableObjectKeys.size +
            indeterminateStableObjectKeys.size

    val batchSucceeded: Boolean
        get() = presentStableObjectKeys.isEmpty() && indeterminateStableObjectKeys.isEmpty()

    /**
     * True when targets remained but none was processed, e.g. the very first provider query hit the
     * wall-time budget. Such a batch carries no evidence and must be treated as a failed attempt.
     */
    val madeNoProgress: Boolean
        get() = hasMore && processedObjectCount == 0
}
