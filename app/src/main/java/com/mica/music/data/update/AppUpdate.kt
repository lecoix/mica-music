package com.mica.music.data.update

import android.content.pm.PackageManager
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import com.mica.music.BuildConfig
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppVersion(
    val name: String,
    val code: Long,
)

data class AppUpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val changelog: String,
    val domesticUrl: String,
    val githubUrl: String,
) {
    companion object {
        fun fromJson(json: String): AppUpdateManifest {
            var versionName = ""
            var versionCode = -1L
            var changelog = ""
            var domesticUrl = ""
            var githubUrl = ""
            JsonReader(StringReader(json)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "versionName" -> versionName = reader.nextString().trim()
                        "versionCode" -> versionCode = reader.nextLong()
                        "changelog" -> changelog = reader.nextString().trim()
                        "domesticUrl" -> domesticUrl = reader.nextOptionalString()
                        "githubUrl" -> githubUrl = reader.nextOptionalString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            require(versionName.isNotEmpty()) { "versionName is missing" }
            require(versionCode > 0L) { "versionCode is invalid" }
            require(isOptionalHttpsUrl(domesticUrl)) { "domesticUrl is invalid" }
            require(isOptionalHttpsUrl(githubUrl)) { "githubUrl is invalid" }
            require(domesticUrl.isNotEmpty() || githubUrl.isNotEmpty()) {
                "at least one download URL is required"
            }

            return AppUpdateManifest(
                versionName = versionName,
                versionCode = versionCode,
                changelog = changelog,
                domesticUrl = domesticUrl,
                githubUrl = githubUrl,
            )
        }

        private fun isOptionalHttpsUrl(value: String): Boolean {
            if (value.isEmpty()) return true
            val uri = Uri.parse(value)
            return uri.scheme == "https" && !uri.host.isNullOrBlank()
        }

        private fun JsonReader.nextOptionalString(): String {
            if (peek() == JsonToken.NULL) {
                nextNull()
                return ""
            }
            return nextString().trim()
        }
    }
}

data class AppUpdateResult(
    val currentVersion: AppVersion,
    val manifest: AppUpdateManifest,
) {
    val hasUpdate: Boolean
        get() = manifest.versionCode > currentVersion.code
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Success(val result: AppUpdateResult) : AppUpdateState
    data class Failure(val message: String) : AppUpdateState
}

class AppUpdateRepository(
    private val domesticManifestUrl: String = BuildConfig.UPDATE_DOMESTIC_MANIFEST_URL,
    private val internationalManifestUrl: String = BuildConfig.UPDATE_INTERNATIONAL_MANIFEST_URL,
    private val fetchManifest: suspend (String) -> AppUpdateManifest = ::fetchManifestFromNetwork,
) {
    suspend fun check(currentVersion: AppVersion): AppUpdateResult {
        val urls = listOf(domesticManifestUrl, internationalManifestUrl)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(urls.isNotEmpty()) { "no update manifest URL is configured" }

        var lastFailure: Throwable? = null
        for (url in urls) {
            try {
                return AppUpdateResult(currentVersion, fetchManifest(url))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("all update manifest requests failed")
    }

    private companion object {
        suspend fun fetchManifestFromNetwork(url: String): AppUpdateManifest = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                AppUpdateManifest.fromJson(connection.inputStream.readUtf8Limited())
            } finally {
                connection.disconnect()
            }
        }

        fun java.io.InputStream.readUtf8Limited(maxBytes: Int = 256 * 1024): String {
            val bytes = ByteArray(maxBytes)
            var offset = 0
            while (offset < bytes.size) {
                val count = read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
            if (offset == bytes.size && read() >= 0) {
                throw IOException("update manifest is too large")
            }
            return bytes.copyOf(offset).toString(Charsets.UTF_8)
        }
    }
}

class AppUpdateCoordinator(
    private val checkUpdates: suspend () -> AppUpdateResult,
) {
    private val stateLock = Any()
    private var nextRequestId = 0L
    private var activeRequestId = 0L

    var state: AppUpdateState by mutableStateOf(AppUpdateState.Idle)
        private set

    fun check(scope: CoroutineScope) {
        val requestId = synchronized(stateLock) {
            nextRequestId += 1L
            activeRequestId = nextRequestId
            state = AppUpdateState.Checking
            activeRequestId
        }

        scope.launch {
            try {
                val result = checkUpdates()
                publishIfCurrent(requestId) { AppUpdateState.Success(result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                publishIfCurrent(requestId) {
                    AppUpdateState.Failure(
                        failure.message?.takeIf(String::isNotBlank) ?: "检查更新失败",
                    )
                }
            }
        }
    }

    private suspend fun publishIfCurrent(
        requestId: Long,
        newState: () -> AppUpdateState,
    ) {
        synchronized(stateLock) {
            if (requestId == activeRequestId) {
                state = newState()
            }
        }
    }
}

fun currentAppVersion(packageManager: PackageManager, packageName: String): AppVersion {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    return AppVersion(
        name = packageInfo.versionName.orEmpty(),
        code = PackageInfoCompat.getLongVersionCode(packageInfo),
    )
}
