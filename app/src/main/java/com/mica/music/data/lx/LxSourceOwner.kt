package com.mica.music.data.lx

import com.mica.music.data.remote.*
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsDocumentMemoryCache
import com.mica.music.data.LyricsCacheKey
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.mica.music.util.DiagnosticLog
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class LxMetadata(val name: String, val description: String, val author: String, val homepage: String, val version: String) {
    companion object {
        fun parse(raw: String): LxMetadata {
            require(raw.toByteArray().size <= 512 * 1024) { "脚本超过 512 KiB 限制" }
            val header = Regex("^/\\*[\\s\\S]+?\\*/").find(raw)?.value
                ?: error("需要以 LX 元数据注释块开头的 .js 文件")
            val fields = Regex("(?m)^\\s?\\*\\s?@(\\w+)\\s(.+)$").findAll(header)
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
            fun field(name: String, limit: Int) = fields[name].orEmpty().let { if (it.length > limit) it.take(limit) + "..." else it }
            return LxMetadata(field("name", 24).ifBlank { "未命名 LX 音源" }, field("description", 36), field("author", 56), field("homepage", 1024), field("version", 36))
        }
    }
}

internal data class LxStoredScript(val id: String, val raw: String, val enabled: Boolean) {
    val metadata: LxMetadata = LxMetadata.parse(raw)
}
internal interface LxScriptStore {
    fun read(): LxStoredScript?
    /** Atomic replacement or deletion. Called only inside the owner's publication lock. */
    fun write(value: LxStoredScript?)
}
internal interface LxRuntime : Closeable {
    suspend fun initialize(script: LxStoredScript): Map<String, List<String>>
    suspend fun musicUrl(source: String, quality: String, musicInfo: String): String
}
internal data class LxSourceState(
    val script: LxStoredScript? = null,
    val platforms: Map<String, List<String>> = emptyMap(),
    val busy: Boolean = false,
    val message: String = "尚未导入音源",
)

internal data class LxOnlineSearchState(
    val query: String = "",
    val page: Int = 1,
    val results: List<LxOnlineResult> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val platform: String = "tx",
    val hasMore: Boolean = false,
)

internal data class LxArtworkResource(
    val ref: RemoteArtworkRef,
    val bytes: ByteArray,
    val generation: Long,
    val revision: Long,
)

/** Script and online admission share one publication gate. Never writes the local/remote catalog. */
internal class LxSourceOwner(
    private val store: LxScriptStore,
    private val onlineStore: LxOnlineStore = MemoryLxOnlineStore(),
    private val runtimeFactory: () -> LxRuntime,
) : RemoteHttpPlaybackRequestResolver, Closeable {
    private val gate = Any()
    private val onlineRecoveryMutex = Mutex()
    // Resource caches and source mutations share one publication gate.
    private val lyricCache = LyricsDocumentMemoryCache()
    private val artworkCache = object : LruCache<String, LxArtworkResource>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LxArtworkResource) = value.bytes.size.coerceAtLeast(16 * 1024)
    }
    private val resourcePermits = Semaphore(2)
    private val prefetchPermits = Semaphore(1)
    private var resourceRevision = 0L
    private fun invalidateResources() {
        resourceRevision++
        lyricCache.clear()
        artworkCache.evictAll()
    }
    private var generation = 0L
    private var requestId = 0L
    private var restored = false
    private var released = false
    private var runtime: LxRuntime? = null
    private data class Trial(val track: RemoteTrackSummary, val source: String, val quality: String, val info: String, val generation: Long)
    private var trial: Trial? = null
    private val mutableState = MutableStateFlow(LxSourceState())
    val state = mutableState.asStateFlow()
    private var onlineLoaded = false
    private var searchRequest = 0L
    private val mutableSearch = MutableStateFlow(LxOnlineSearchState())
    val onlineSearch = mutableSearch.asStateFlow()
    private val mutableTracks = MutableStateFlow<Map<String, RemoteTrackSummary>>(emptyMap())
    val onlineTracks = mutableTracks.asStateFlow()

    suspend fun loadOnline() = withContext(Dispatchers.IO) {
        synchronized(gate) {
            check(!released)
            if (!onlineLoaded) {
                val rows = onlineStore.summaries()
                mutableTracks.value = rows.associateBy { it.mediaId }
                onlineLoaded = true
            }
        }
    }
    fun findOnline(ref: RemoteTrackRef): RemoteTrackSummary? = synchronized(gate) {
        if (released) null else mutableTracks.value[RemoteMediaIdCodec.encode(ref)]
    }
    internal suspend fun onlineLyrics(
        ref: RemoteTrackRef,
        isPrefetch: Boolean = false,
        loader: suspend (LxOnlineRecord) -> LyricsDocument = LxOnlineLyrics::load,
    ): LyricsDocument? {
        if (lxOnlinePlatformForSource(ref.sourceInstanceId) == null) return null
        suspend fun load(): LyricsDocument? = resourcePermits.withPermit {
            withContext(Dispatchers.IO) {
                val capture = synchronized(gate) {
                    check(!released)
                    val record = onlineStore.read(ref) ?: return@withContext null
                    Triple(generation, resourceRevision, record)
                }
                val key = LyricsCacheKey(RemoteMediaIdCodec.encode(ref), "lx:" + capture.first + ":" + capture.second, 1)
                synchronized(gate) {
                    current(capture.first)
                    check(resourceRevision == capture.second)
                    lyricCache.get(key)?.let { return@withContext it }
                }
                val loaded = loader(capture.third)
                currentCoroutineContext().ensureActive()
                synchronized(gate) {
                    current(capture.first)
                    check(resourceRevision == capture.second) { "在线歌曲元数据已变更" }
                    lyricCache.put(key, loaded)
                    loaded
                }
            }
        }
        return if (isPrefetch) prefetchPermits.withPermit { load() } else load()
    }

    internal suspend fun onlineArtwork(
        ref: RemoteArtworkRef,
        loader: suspend (String) -> ByteArray,
    ): LxArtworkResource? = resourcePermits.withPermit {
        withContext(Dispatchers.IO) {
            val key = ref.sourceInstanceId + ":" + ref.opaqueArtworkId
            val capture = synchronized(gate) {
                check(!released)
                artworkCache.get(key)?.let { return@withContext it }
                val url = onlineArtworkUrl(ref) ?: return@withContext null
                Triple(generation, resourceRevision, url)
            }
            val loaded = loader(capture.third)
            require(loaded.size <= 8 * 1024 * 1024) { "LX artwork exceeds 8 MiB" }
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                current(capture.first)
                check(resourceRevision == capture.second) { "在线歌曲元数据已变更" }
                artworkCache.get(key) ?: LxArtworkResource(ref, loaded, capture.first, capture.second).also {
                    artworkCache.put(key, it)
                }
            }
        }
    }

    /** Cache eviction is a memory policy, not source invalidation: accepted streams retain their token. */
    internal fun <T> publishOnlineArtwork(ref: RemoteArtworkRef, resource: LxArtworkResource, write: () -> T): T = synchronized(gate) {
        current(resource.generation)
        check(resource.ref == ref && resource.revision == resourceRevision) {
            "在线封面已失效"
        }
        write()
    }
    internal fun onlineArtworkUrl(ref: RemoteArtworkRef): String? = synchronized(gate) {
        if (released || lxOnlinePlatformForSource(ref.sourceInstanceId) == null) return@synchronized null
        val trackRef = RemoteTrackRef(ref.sourceInstanceId, ref.opaqueArtworkId)
        val record = onlineStore.read(trackRef) ?: return@synchronized null
        if (record.track.artworkOpaqueId != ref.opaqueArtworkId) return@synchronized null
        lxOnlineArtworkUrl(record.platform, record.musicInfo)
    }
    fun leaveOnline() = synchronized(gate) {
        searchRequest++
        mutableSearch.value = LxOnlineSearchState()
    }
    suspend fun searchOnline(query: String, page: Int, fetch: suspend (String, Int) -> List<LxOnlineResult>) =
        searchOnline("tx", query, page, append = false, fetch = fetch)

    suspend fun searchOnline(
        platform: String,
        query: String,
        page: Int,
        fetch: suspend (String, Int) -> List<LxOnlineResult>,
    ) = searchOnline(platform, query, page, append = false, fetch = fetch)

    suspend fun searchOnline(
        platform: String,
        query: String,
        page: Int,
        append: Boolean,
        fetch: suspend (String, Int) -> List<LxOnlineResult>,
    ) {
        require(platform in LX_ONLINE_PLATFORMS)
        require(query.isNotBlank() && query.length <= 100 && page in 1..50)
        val token = synchronized(gate) {
            check(!released && mutableState.value.platforms[platform].orEmpty().isNotEmpty()) {
                "请先启用支持 ${platform.uppercase()} 的音源"
            }
            val previous = mutableSearch.value
            if (append) {
                check(
                    previous.query == query &&
                        previous.platform == platform &&
                        previous.results.isNotEmpty() &&
                        previous.hasMore &&
                        !previous.busy &&
                        page == previous.page + 1
                ) { "追加搜索页已失效" }
            }
            val token = generation to ++searchRequest
            mutableSearch.value = if (append) {
                previous.copy(busy = true, error = null)
            } else {
                LxOnlineSearchState(query = query, page = page, busy = true, platform = platform)
            }
            token
        }
        try {
            val rows = fetch(query, page).take(20)
            currentCoroutineContext().ensureActive()
            check(rows.all { it.platform == platform }) { "搜索结果平台不一致" }
            synchronized(gate) {
                current(token.first)
                check(token.second == searchRequest) { "搜索已失效" }
                val currentSearch = mutableSearch.value
                val results = if (append) {
                    (currentSearch.results + rows).distinctBy { it.id }
                } else {
                    rows
                }
                mutableSearch.value = LxOnlineSearchState(
                    query = query,
                    page = page,
                    results = results,
                    platform = platform,
                    hasMore = rows.size == 20 && page < 50,
                )
            }
        } catch (e: Exception) {
            synchronized(gate) {
                if (generation == token.first && searchRequest == token.second) {
                    mutableSearch.value = if (append) {
                        mutableSearch.value.copy(busy = false, error = "加载更多失败，请检查网络后重试")
                    } else {
                        LxOnlineSearchState(
                            query = query,
                            page = page,
                            error = "搜索失败，请检查网络后重试",
                            platform = platform,
                        )
                    }
                }
            }
            throw e
        }
    }
    /** Row identity is a capability for this exact visible page, not caller-provided MusicInfo. */
    fun admitOnline(row: LxOnlineResult, quality: String): RemoteTrackSummary = synchronized(gate) {
        check(!released && mutableSearch.value.results.any { it === row }) { "搜索结果已失效" }
        check(mutableSearch.value.platform == row.platform && quality in mutableState.value.platforms[row.platform].orEmpty()) { "音源不支持所选平台或品质" }
        val candidate = row.record(quality)
        onlineStore.write(candidate)
        invalidateResources()
        val record = onlineStore.read(candidate.track.ref) ?: error("在线歌曲元数据保存失败")
        mutableTracks.value = mutableTracks.value + (record.track.mediaId to record.track)
        record.track
    }
    /** Recheck after IO/main dispatcher handoff before queue/playlist side effects. */
    fun useOnline(row: LxOnlineResult, quality: String, action: (RemoteTrackSummary) -> Unit) = synchronized(gate) {
        check(!released && mutableSearch.value.results.any { it === row }) { "搜索结果已失效" }
        check(mutableSearch.value.platform == row.platform && quality in mutableState.value.platforms[row.platform].orEmpty()) { "音源不支持所选平台或品质" }
        val track = mutableTracks.value[row.record(quality).track.mediaId] ?: error("歌曲尚未保存")
        action(track)
    }

    private fun invalidate(): Long {
        check(!released) { "LX owner 已释放" }
        generation++
        invalidateResources()
        requestId++
        leaveOnline()
        trial = null
        runtime?.close()
        runtime = null
        // Runtime invalidation is immediate even if the subsequent durable edit fails.
        // Keep the last committed script configuration, but never advertise retired capabilities.
        mutableState.value = mutableState.value.copy(platforms = emptyMap(), busy = false, message = "运行时已停止")
        return generation
    }
    private fun current(token: Long) {
        check(!released && token == generation) { "音源已变更，旧操作已失效" }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        loadOnline()
        val token = synchronized(gate) {
            if (restored) return@withContext
            restored = true
            val token = invalidate()
            try {
                val saved = store.read()
                mutableState.value = LxSourceState(saved, busy = saved?.enabled == true, message = "已读取音源")
            } catch (_: Exception) {
                mutableState.value = LxSourceState(message = "音源存储损坏；请重新导入")
            }
            token
        }
        initialize(token)
    }

    suspend fun importScript(read: suspend () -> String) = withContext(Dispatchers.IO) {
        val token = synchronized(gate) {
            restored = true
            invalidate().also { mutableState.value = mutableState.value.copy(platforms = emptyMap(), busy = true, message = "正在导入…") }
        }
        try {
            val raw = read().removePrefix("\uFEFF")
            LxMetadata.parse(raw)
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                current(token)
                val value = LxStoredScript("lx-experiment-${UUID.randomUUID()}", raw, true)
                store.write(value)
                mutableState.value = LxSourceState(value, busy = true, message = "正在初始化…")
            }
            initialize(token)
        } catch (failure: Exception) {
            report(token, "导入失败：${safeError(failure)}")
            throw failure
        }
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val token = synchronized(gate) {
            val value = mutableState.value.script ?: error("尚未导入音源")
            val token = invalidate()
            store.write(value.copy(enabled = enabled))
            mutableState.value = LxSourceState(value.copy(enabled = enabled), busy = enabled, message = if (enabled) "正在初始化…" else "已停用")
            token
        }
        if (enabled) initialize(token)
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        synchronized(gate) {
            invalidate()
            store.write(null)
            mutableState.value = LxSourceState(message = "音源已删除")
        }
    }

    private suspend fun initialize(token: Long) {
        val pair = synchronized(gate) {
            current(token)
            val saved = mutableState.value.script?.takeIf { it.enabled } ?: return
            val next = runtimeFactory()
            runtime = next
            saved to next
        }
        try {
            val platforms = withTimeout(15_000) { pair.second.initialize(pair.first) }
            synchronized(gate) {
                current(token)
                check(platforms.isNotEmpty()) { "脚本未声明支持的平台 / musicUrl 品质" }
                mutableState.value = mutableState.value.copy(platforms = platforms, busy = false, message = "初始化成功")
            }
        } catch (failure: Exception) {
            synchronized(gate) { if (token == generation) { runtime?.close(); runtime = null } }
            report(token, "初始化失败：${safeError(failure)}")
            throw failure
        }
    }

    fun prepareTrial(source: String, quality: String, musicInfo: String): RemoteTrackSummary = synchronized(gate) {
        require(musicInfo.toByteArray().size <= 32 * 1024) { "MusicInfo 超过 32 KiB" }
        val info = JSONObject(musicInfo)
        require(!info.has("source") || info.getString("source") == source) { "MusicInfo.source 与所选平台不一致" }
        val saved = mutableState.value.script ?: error("未导入音源")
        check(saved.enabled && runtime != null && quality in mutableState.value.platforms[source].orEmpty()) { "平台或品质不可用" }
        requestId++
        val track = RemoteTrackSummary(
            RemoteTrackRef(saved.id, UUID.randomUUID().toString()),
            title = info.optString("name").ifBlank { "LX 测试播放" }.take(256),
            artist = info.optString("singer").take(256),
        )
        trial = Trial(track, source, quality, info.toString(), generation)
        track
    }

    fun findTrial(ref: RemoteTrackRef): RemoteTrackSummary? = synchronized(gate) {
        trial?.takeIf { !released && it.generation == generation && it.track.ref == ref }?.track
    }

    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        val ref = RemoteMediaIdCodec.decode(mediaId) ?: return null
        if (lxOnlinePlatformForSource(ref.sourceInstanceId) != null) return resolveOnline(ref)
        if (!ref.sourceInstanceId.startsWith("lx-experiment-")) return null
        val captured = synchronized(gate) {
            val item = trial?.takeIf { it.track.ref == ref && it.generation == generation }
                ?: throw IOException("LX 测试曲目已失效，请重新测试播放")
            val active = runtime ?: throw IOException("LX 音源未就绪")
            Triple(item, active, ++requestId)
        }
        try {
            val url = withTimeout(20_000) { captured.second.musicUrl(captured.first.source, captured.first.quality, captured.first.info) }
            return synchronized(gate) {
                current(captured.first.generation)
                check(captured.third == requestId && trial === captured.first) { "测试请求已失效" }
                val parsed = java.net.URI(url)
                require(url.length <= 2048 && parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.userInfo == null) { "脚本返回无效 HTTP 地址" }
                mutableState.value = mutableState.value.copy(message = "解析成功，已请求 ${captured.first.quality}；实际音频以播放器为准")
                RemoteHttpPlaybackRequest(url, ref.sourceInstanceId, generation, 0)
            }
        } catch (failure: Exception) {
            synchronized(gate) {
                if (captured.third == requestId) report(captured.first.generation, "解析失败：${safeError(failure)}")
            }
            throw IOException("LX 音源解析失败或请求已失效")
        }
    }

    private data class OnlineResolveCapture(
        val item: LxOnlineRecord,
        val runtime: LxRuntime,
        val generation: Long,
        val scriptId: String,
        val platform: String,
    )

    private suspend fun captureOnlineResolve(ref: RemoteTrackRef): OnlineResolveCapture = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val item = onlineStore.read(ref) ?: throw IOException("在线歌曲记录不存在")
            val platform = lxOnlinePlatformForSource(ref.sourceInstanceId) ?: throw IOException("在线歌曲平台不存在")
            check(item.platform == platform) { "在线歌曲记录平台不一致" }
            val script = mutableState.value.script?.takeIf { it.enabled }
                ?: throw IOException("音源已停用")
            check(item.quality in mutableState.value.platforms[platform].orEmpty()) { "音源不支持保存的平台或品质" }
            OnlineResolveCapture(item, runtime ?: throw IOException("音源未就绪"), generation, script.id, platform)
        }
    }

    private suspend fun onlineMusicUrl(captured: OnlineResolveCapture): String =
        withTimeout(20_000) {
            captured.runtime.musicUrl(captured.platform, captured.item.quality, captured.item.musicInfo)
        }

    private suspend fun recoverOnlineRuntimeOnce(failed: OnlineResolveCapture) {
        onlineRecoveryMutex.withLock {
            val token = synchronized(gate) {
                check(!released) { "LX owner 已释放" }
                val state = mutableState.value
                val script = state.script?.takeIf { it.enabled } ?: throw IOException("音源已停用")
                check(script.id == failed.scriptId) { "音源已变更，旧在线请求已失效" }
                if (generation != failed.generation || runtime !== failed.runtime) {
                    check(runtime != null && !state.busy && failed.item.quality in state.platforms[failed.platform].orEmpty()) {
                        "音源已变更，旧在线请求已失效"
                    }
                    null
                } else {
                    // Rebuilding the runtime for the same committed script is not a source mutation.
                    // Keep source generation stable so an independent online-search HTTP request is
                    // not fenced as stale. Playback publication is additionally fenced by runtime
                    // identity below, so a URL produced by the retired runtime still cannot publish.
                    requestId++
                    trial = null
                    runCatching { runtime?.close() }
                    runtime = null
                    mutableState.value = state.copy(
                        platforms = emptyMap(),
                        busy = true,
                        message = "正在恢复音源…",
                    )
                    generation
                }
            }
            if (token != null) initialize(token)
        }
    }

    private fun publishOnlineResolve(
        ref: RemoteTrackRef,
        captured: OnlineResolveCapture,
        url: String,
    ): RemoteHttpPlaybackRequest = synchronized(gate) {
        current(captured.generation)
        check(runtime === captured.runtime) { "音源运行时已重建，旧在线请求已失效" }
        check(mutableState.value.script?.id == captured.scriptId && mutableState.value.script?.enabled == true) {
            "音源已变更，旧在线请求已失效"
        }
        val uri = java.net.URI(url)
        require(url.length <= 2048 && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        RemoteHttpPlaybackRequest(url, ref.sourceInstanceId, captured.generation, 0)
    }

    private suspend fun resolveOnline(ref: RemoteTrackRef): RemoteHttpPlaybackRequest {
        restore()
        withTimeout(16_000) { state.first { !it.busy } }
        var captured = captureOnlineResolve(ref)
        val firstUrl = try {
            onlineMusicUrl(captured)
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            null.also {
                DiagnosticLog.important("LXOnline", "musicUrl timeout; rebuilding current runtime once")
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            DiagnosticLog.important(
                "LXOnline",
                "musicUrl failed; rebuilding current runtime once (${safeError(failure)})",
            )
            null
        }
        if (firstUrl != null) {
            currentCoroutineContext().ensureActive()
            return publishOnlineResolve(ref, captured, firstUrl)
        }

        try {
            recoverOnlineRuntimeOnce(captured)
            captured = captureOnlineResolve(ref)
            val retryUrl = onlineMusicUrl(captured)
            currentCoroutineContext().ensureActive()
            return publishOnlineResolve(ref, captured, retryUrl)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            DiagnosticLog.important(
                "LXOnline",
                "lazy runtime recovery failed (${safeError(failure)})",
            )
            throw IOException("在线歌曲解析失败；请检查网络和音源，或重新选择品质")
        }
    }

    private fun report(token: Long, message: String) = synchronized(gate) {
        if (!released && token == generation) mutableState.value = mutableState.value.copy(busy = false, message = message)
    }
    override fun close() = synchronized(gate) {
        if (!released) { invalidate(); released = true; mutableState.value = LxSourceState(message = "已释放") }
    }
    private fun safeError(failure: Exception): String = when (failure) {
        is LxWorkerUnavailable -> "脚本同步执行超过 5 秒，需彻底结束并重启应用"
        is kotlinx.coroutines.TimeoutCancellationException -> "请求超时（若脚本无限循环，需重启应用）"
        is kotlinx.coroutines.CancellationException -> "操作已取消"
        is IOException -> "脚本或网络请求失败，请重新初始化后重试"
        is IllegalArgumentException -> "输入格式或容量不符合要求"
        is IllegalStateException -> "音源已失效，或未声明支持的平台和品质"
        else -> "脚本执行失败（${failure.javaClass.simpleName}）" // Never expose URLs, tokens or payloads.
    }
}
