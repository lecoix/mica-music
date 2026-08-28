package com.mica.music.data.remote

import com.mica.music.data.remote.navidrome.NavidromeException
import com.mica.music.data.remote.navidrome.NavidromeHttpExecutor
import com.mica.music.data.remote.navidrome.NavidromeProtocolCatalogApi
import com.mica.music.data.remote.navidrome.NavidromeRequestFactory
import com.mica.music.data.remote.navidrome.NavidromeSourceSync
import com.mica.music.data.remote.navidrome.NavidromeSyncResult
import java.net.URI
import java.util.UUID

/**
 * Application-facing owner for remote source configuration and explicit synchronization.
 *
 * Credential rotation always writes a brand-new credentialRef before atomically switching the
 * source row to that ref. The currently published source therefore never observes new credential
 * material paired with its old endpoint/config revision.
 */
internal class RemoteSourceManager internal constructor(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: MutableSecureRemoteCredentialStore,
    private val navidromeExecutor: NavidromeHttpExecutor,
    private val navidromeRequestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
    private val sourceIdProvider: () -> String = { "navidrome-${UUID.randomUUID()}" },
    private val credentialRefProvider: (String) -> String = { sourceId ->
        "remote-credential/$sourceId/${UUID.randomUUID()}"
    },
) {
    constructor(
        catalogRepository: RemoteCatalogRepository,
        credentialStore: MutableSecureRemoteCredentialStore,
    ) : this(
        catalogRepository = catalogRepository,
        credentialStore = credentialStore,
        navidromeExecutor = com.mica.music.data.remote.navidrome.UrlConnectionNavidromeHttpExecutor(),
    )

    suspend fun statuses(): List<RemoteSourceStatus> = catalogRepository.sourceStatuses()

    suspend fun createNavidrome(
        displayName: String,
        endpoint: String,
        username: String,
        password: String,
        enabled: Boolean = true,
    ): RemoteSourceInstance {
        val sourceId = sourceIdProvider().trim()
        require(sourceId.isNotBlank()) { "Generated remote source id must not be blank" }
        val credentialRef = credentialRefProvider(sourceId).trim()
        require(credentialRef.isNotBlank()) { "Generated credentialRef must not be blank" }
        val instance = RemoteSourceInstance(
            id = sourceId,
            type = RemoteSourceType.NAVIDROME,
            displayName = normalizeDisplayName(displayName),
            endpoint = normalizeHttpEndpoint(endpoint),
            credentialRef = credentialRef,
            enabled = enabled,
        )
        credentialStore.put(
            credentialRef,
            RemoteCredentialMaterial.UsernamePassword(
                username = normalizeUsername(username),
                password = normalizePassword(password),
            ),
        )
        catalogRepository.upsertSource(instance)
        return instance
    }

    suspend fun updateSourceConfig(
        sourceInstanceId: String,
        displayName: String,
        endpoint: String,
        enabled: Boolean,
    ): RemoteSourceInstance {
        val current = requireSource(sourceInstanceId)
        val updated = current.copy(
            displayName = normalizeDisplayName(displayName),
            endpoint = normalizeHttpEndpoint(endpoint),
            enabled = enabled,
        )
        catalogRepository.upsertSource(updated)
        return updated
    }

    suspend fun setEnabled(sourceInstanceId: String, enabled: Boolean): RemoteSourceInstance {
        val current = requireSource(sourceInstanceId)
        if (current.enabled == enabled) return current
        val updated = current.copy(enabled = enabled)
        catalogRepository.upsertSource(updated)
        return updated
    }

    suspend fun rotateNavidromeCredentials(
        sourceInstanceId: String,
        username: String,
        password: String,
    ): RemoteSourceInstance {
        val current = requireNavidromeSource(sourceInstanceId)
        val nextCredentialRef = credentialRefProvider(sourceInstanceId).trim()
        require(nextCredentialRef.isNotBlank()) { "Generated credentialRef must not be blank" }
        require(nextCredentialRef != current.credentialRef) {
            "Credential rotation must allocate a new credentialRef"
        }
        credentialStore.put(
            nextCredentialRef,
            RemoteCredentialMaterial.UsernamePassword(
                username = normalizeUsername(username),
                password = normalizePassword(password),
            ),
        )
        val updated = current.copy(credentialRef = nextCredentialRef)
        catalogRepository.upsertSource(updated)
        return updated
    }

    suspend fun testConnection(sourceInstanceId: String) {
        val source = requireNavidromeSource(sourceInstanceId)
        require(source.enabled) { "Remote source is disabled" }
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: error("Remote source disappeared before connection test")
        NavidromeProtocolCatalogApi(
            sourceOwner = owner,
            credentialStore = credentialStore,
            executor = navidromeExecutor,
            requestFactory = navidromeRequestFactory,
        ).ping()
    }

    suspend fun syncNavidrome(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): NavidromeSyncResult {
        val source = requireNavidromeSource(sourceInstanceId)
        require(source.enabled) { "Remote source is disabled" }
        return NavidromeSourceSync(
            catalogRepository = catalogRepository,
            credentialStore = credentialStore,
            executor = navidromeExecutor,
            requestFactory = navidromeRequestFactory,
        ).sync(sourceInstanceId, limit)
    }

    private suspend fun requireSource(sourceInstanceId: String): RemoteSourceInstance =
        catalogRepository.source(sourceInstanceId)
            ?: throw IllegalArgumentException("Unknown remote source id=$sourceInstanceId")

    private suspend fun requireNavidromeSource(sourceInstanceId: String): RemoteSourceInstance =
        requireSource(sourceInstanceId).also { source ->
            require(source.type == RemoteSourceType.NAVIDROME) {
                "Source $sourceInstanceId is not Navidrome"
            }
        }

    companion object {
        internal fun normalizeHttpEndpoint(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Server address must not be blank" }
            val uri = runCatching { URI(trimmed) }
                .getOrElse { throw IllegalArgumentException("Invalid server address", it) }
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                "Server address must start with http:// or https://"
            }
            require(!uri.host.isNullOrBlank()) { "Server address must include a host" }
            require(uri.userInfo == null) { "Server address must not contain username/password" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Server address must not contain query or fragment"
            }
            return trimmed
        }

        private fun normalizeDisplayName(value: String): String =
            value.trim().also { require(it.isNotBlank()) { "Display name must not be blank" } }

        private fun normalizeUsername(value: String): String =
            value.trim().also { require(it.isNotBlank()) { "Username must not be blank" } }

        private fun normalizePassword(value: String): String =
            value.also { require(it.isNotBlank()) { "Password must not be blank" } }
    }
}

