package com.mica.music.data.remote

/**
 * Per-source lifecycle owner for remote configuration and in-flight operation fencing.
 *
 * A source edit or explicit invalidation advances [operationGeneration], invalidating every
 * in-flight operation. Starting a catalog refresh advances [catalogRefreshGeneration], fencing
 * older same-config refreshes without interrupting concurrent playback, artwork, or lyrics reads.
 */
class RemoteSourceOwner(
    initial: RemoteSourceInstance,
    initialConfigRevision: Long = 1L,
    initialOperationGeneration: Long = 1L,
) {
    init {
        require(initialConfigRevision >= 1L) { "initialConfigRevision must be positive" }
        require(initialOperationGeneration >= 1L) { "initialOperationGeneration must be positive" }
    }

    private val lock = Any()
    private var instance: RemoteSourceInstance = initial
    private var configRevision: Long = initialConfigRevision
    private var operationGeneration: Long = initialOperationGeneration
    private var catalogRefreshGeneration: Long = 0L

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

    fun beginCatalogRefresh(): RemoteOperationToken = synchronized(lock) {
        catalogRefreshGeneration += 1L
        RemoteOperationToken(
            sourceInstanceId = instance.id,
            configRevision = configRevision,
            operationGeneration = operationGeneration,
            catalogRefreshGeneration = catalogRefreshGeneration,
        )
    }

    fun beginCatalogRefreshSnapshot(): RemoteOperationSnapshot = synchronized(lock) {
        catalogRefreshGeneration += 1L
        val source = RemoteSourceSnapshot(instance, configRevision, operationGeneration)
        RemoteOperationSnapshot(
            source = source,
            token = RemoteOperationToken(
                sourceInstanceId = instance.id,
                configRevision = configRevision,
                operationGeneration = operationGeneration,
                catalogRefreshGeneration = catalogRefreshGeneration,
            ),
        )
    }

    fun isCurrent(token: RemoteOperationToken): Boolean = synchronized(lock) {
        token.sourceInstanceId == instance.id &&
            token.configRevision == configRevision &&
            token.operationGeneration == operationGeneration &&
            (token.catalogRefreshGeneration == null || token.catalogRefreshGeneration == catalogRefreshGeneration)
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
