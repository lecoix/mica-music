package com.mica.music.imaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares one asynchronous cover load between concurrent callers of the same request key. */
internal class CoverLoadCoordinator(
    private val scope: CoroutineScope,
) {
    private data class Decision(
        val result: CompletableDeferred<Boolean>,
        val isLeader: Boolean,
    )

    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Boolean>>()

    suspend fun execute(
        key: String,
        block: suspend () -> Boolean,
    ): Boolean {
        val decision = lock.withLock {
            inFlight[key]?.let { existing ->
                Decision(existing, isLeader = false)
            } ?: CompletableDeferred<Boolean>().let { created ->
                inFlight[key] = created
                Decision(created, isLeader = true)
            }
        }

        if (decision.isLeader) {
            scope.launch {
                try {
                    decision.result.complete(block())
                } catch (error: Throwable) {
                    decision.result.completeExceptionally(error)
                } finally {
                    lock.withLock {
                        if (inFlight[key] === decision.result) {
                            inFlight.remove(key)
                        }
                    }
                }
            }
        }
        return decision.result.await()
    }
}
