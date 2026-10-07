package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteCatalogRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SmbFolderLoadingState(
    val requestId: Long = 0,
    val entriesReady: Boolean = false,
    val active: Boolean = false,
    val message: String = "",
)

/** One process-owned user import. No Activity callbacks or song payloads are retained. */
internal class SmbFolderLibraryLoader(
    private val repository: RemoteCatalogRepository,
    private val indexer: SmbFolderLibraryIndexer,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SmbFolderLoadingState())
    val state = mutableState.asStateFlow()
    private var request: SmbFolderIndexRequest? = null
    private var job: Job? = null

    suspend fun start(sourceId: String, directory: String, includeChildren: Boolean): Long = withContext(NonCancellable) {
        mutex.withLock {
            request?.let { repository.cancelSmbFolderIndex(it) }
            job?.cancel()
            val next = repository.beginSmbFolderIndex(sourceId)
            if (next == null) {
                request = null
                mutableState.value = mutableState.value.copy(active = false, message = "连接不可用，请检查后重试")
                error("SMB source unavailable")
            }
            request = next
            val accepted = repository.publishSmbFolderIndexState(next) {
                mutableState.value = SmbFolderLoadingState(next.id, active = true, message = "正在读取目录…")
            }
            if (!accepted) {
                request = null
                mutableState.value = SmbFolderLoadingState(message = "连接已改变，请刷新目录重试")
                error("Stale folder request")
            }
            job = scope.launch {
                try {
                    val result = indexer.index(next, directory, includeChildren) { count ->
                        update(next) { it.copy(entriesReady = true, message = "已加入 $count 首，正在补齐歌曲信息…") }
                    }
                    update(next) {
                        it.copy(active = false, message = when {
                            !result.complete -> "目录读取不完整，未修改曲库"
                            !result.published -> "连接已改变，请刷新目录重试"
                            result.metadataFailedCount > 0 -> "已加入 ${result.trackCount} 首，${result.metadataFailedCount} 首信息未补齐；可刷新目录重试"
                            else -> "歌曲信息已更新，共 ${result.trackCount} 首"
                        })
                    }
                } catch (failure: Exception) {
                    // A stale request may report its own termination, but never replace a newer state.
                    mutex.withLock {
                        if (request === next) mutableState.value = mutableState.value.copy(
                            active = false,
                            message = if (mutableState.value.entriesReady) "信息补齐已停止，已加入条目保留；可刷新目录重试" else "目录读取已停止，请检查连接后重试",
                        )
                    }
                    if (failure is CancellationException) throw failure
                }
            }
            next.id
        }
    }

    private suspend fun update(next: SmbFolderIndexRequest, transform: (SmbFolderLoadingState) -> SmbFolderLoadingState) {
        mutex.withLock {
            if (request !== next) return
            check(repository.publishSmbFolderIndexState(next) { mutableState.value = transform(mutableState.value) })
        }
    }

    suspend fun cancel() = withContext(NonCancellable) {
        mutex.withLock {
            request?.let { repository.cancelSmbFolderIndex(it) }
            request = null
            job?.cancel()
            job = null
            mutableState.value = mutableState.value.copy(active = false, message = "已停止；已加入条目保留，可刷新目录补齐信息")
        }
    }
}
