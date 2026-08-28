package com.mica.music.data.remote

/**
 * Per-source lifecycle owner for remote configuration and in-flight operation fencing.
 *
 * A source edit or explicit invalidation advances the operation generation. Results captured from
 * an older generation must not publish after the source has changed.
 */
class RemoteSourceOwner(initial: RemoteSourceInstance) {
    private val lock = Any()
    private var instance: RemoteSourceInstance = initial
    private var configRevision: Long = 1L
    private var operationGeneration: Long = 1L

    fun snapshot(): RemoteSourceSnapshot = synchronized(lock) {
        RemoteSourceSnapshot(instance, configRevision, operationGeneration)
    }

    fun beginOperation(): RemoteOperationToken = synchronized(lock) {
        RemoteOperationToken(instance.id, configRevision, operationGeneration)
    }
    fun beginOperationSnapshot(): RemoteOperationSnapshot = synchronized(lock) {
        val source = RemoteSourceSnapshot(instance, configRevision, operationGeneration)
        RemoteOperationSnapshot(
            source = source,
            token = RemoteOperationToken(instance.id, configRevision, operationGeneration),
        )
    }

    fun isCurrent(token: RemoteOperationToken): Boolean = synchronized(lock) {
        token.sourceInstanceId == instance.id &&
            token.configRevision == configRevision &&
            token.operationGeneration == operationGeneration
    }

    fun replace(next: RemoteSourceInstance): RemoteSourceSnapshot = synchronized(lock) {
        require(next.id == instance.id) { "RemoteSourceOwner cannot change source identity" }
        require(next.type == instance.type) { "RemoteSourceOwner cannot change source type" }
        instance = next
        configRevision += 1L
        operationGeneration += 1L
        RemoteSourceSnapshot(instance, configRevision, operationGeneration)
    }

    fun invalidateOperations(): RemoteSourceSnapshot = synchronized(lock) {
        operationGeneration += 1L
        RemoteSourceSnapshot(instance, configRevision, operationGeneration)
    }
}
