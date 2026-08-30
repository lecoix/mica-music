package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mica.music.MicaApp
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceManager
import com.mica.music.data.remote.RemoteSourceStatus
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun RemoteMusicSettingsPanel() {
    val context = LocalContext.current
    val manager = remember(context) { (context.applicationContext as MicaApp).remoteSourceManager }
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<RemoteSourceStatus>>(emptyList()) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busySourceId by remember { mutableStateOf<String?>(null) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    var addSourceType by remember { mutableStateOf<RemoteSourceType?>(null) }
    var editSource by remember { mutableStateOf<RemoteSourceInstance?>(null) }
    var credentialSource by remember { mutableStateOf<RemoteSourceInstance?>(null) }
    var deleteSource by remember { mutableStateOf<RemoteSourceInstance?>(null) }

    LaunchedEffect(refreshRevision) {
        loading = true
        statuses = runCatching { manager.statuses() }
            .onFailure { transientMessage = it.remoteSettingsMessage("读取远程曲库失败") }
            .getOrDefault(statuses)
        loading = false
    }

    fun runSourceAction(
        sourceId: String,
        successMessage: String,
        action: suspend () -> Unit,
    ) {
        if (busySourceId != null) return
        busySourceId = sourceId
        scope.launch {
            runCatching { action() }
                .onSuccess {
                    transientMessage = successMessage
                    refreshRevision++
                }
                .onFailure { failure ->
                    DiagnosticLog.event(
                        "RemoteMusic",
                        "source-action failed sourceId=$sourceId type=${failure.javaClass.name} message=${failure.message.orEmpty()}",
                    )
                    transientMessage = failure.remoteSettingsMessage("操作失败")
                }
            busySourceId = null
        }
    }

    SettingsSectionTitle("远程曲库")
    SettingsTipRow("远程来源与本地曲库分开同步；播放时只保存稳定曲目 ID，认证地址不会写入队列或曲库数据库。")

    SettingsActionRow(
        title = "添加 Navidrome / OpenSubsonic",
        subtitle = "使用 Subsonic API；默认请求原始音频，不主动转码",
        onClick = { addSourceType = RemoteSourceType.NAVIDROME },
        enabled = busySourceId == null,
    )
    SettingsActionRow(
        title = "添加 WebDAV",
        subtitle = "递归枚举 WebDAV 目录；播放使用严格 Range 语义",
        onClick = { addSourceType = RemoteSourceType.WEBDAV },
        enabled = busySourceId == null,
    )
    SettingsActionRow(
        title = "添加 SMB",
        subtitle = "SMB2/SMB3 共享；使用协议级随机读，不启用 SMB1",
        onClick = { addSourceType = RemoteSourceType.SMB },
        enabled = busySourceId == null,
    )

    if (loading && statuses.isEmpty()) {
        SettingsTipRow("正在读取远程来源…")
    } else if (statuses.isEmpty()) {
        SettingsTipRow("尚未配置远程曲库。")
    } else {
        statuses.forEach { status ->
            val source = status.instance
            val busy = busySourceId == source.id
            SettingsSectionTitle(source.displayName)
            SettingsActionRow(
                title = source.displayName,
                subtitle = buildSourceSubtitle(status),
                onClick = { editSource = source },
                enabled = !busy,
            )
            SettingsToggleRow(
                title = "启用",
                subtitle = if (source.enabled) "允许同步和播放该来源" else "已停用；保留上次同步的曲库快照",
                checked = source.enabled,
                onCheckedChange = { enabled ->
                    runSourceAction(
                        sourceId = source.id,
                        successMessage = if (enabled) "已启用 ${source.displayName}" else "已停用 ${source.displayName}",
                    ) {
                        manager.setEnabled(source.id, enabled)
                    }
                },
            )
            SettingsActionRow(
                title = "测试连接",
                subtitle = if (busy) "正在执行…" else connectionTestSubtitle(source.type),
                onClick = {
                    runSourceAction(source.id, "${source.displayName} 连接正常") {
                        manager.testConnection(source.id)
                    }
                },
                enabled = source.enabled && !busy,
            )
            SettingsActionRow(
                title = "同步曲库",
                subtitle = if (busy) "正在同步…" else "重新枚举该来源并原子替换它自己的曲库快照",
                onClick = {
                    runSourceAction(source.id, "${source.displayName} 同步完成") {
                        when (source.type) {
                            RemoteSourceType.NAVIDROME -> manager.syncNavidrome(source.id)
                            RemoteSourceType.WEBDAV -> manager.syncWebDav(source.id)
                            RemoteSourceType.SMB -> manager.syncSmb(source.id)
                        }
                    }
                },
                enabled = source.enabled && !busy,
            )
            SettingsActionRow(
                title = "更新登录信息",
                subtitle = "密码不会回显；保存时切换到新的加密 credentialRef",
                onClick = { credentialSource = source },
                enabled = !busy,
            )
            SettingsActionRow(
                title = "删除来源",
                subtitle = "移除来源、已同步曲目与加密登录信息",
                onClick = { deleteSource = source },
                enabled = !busy,
            )
        }
    }

    transientMessage?.let { message ->
        SettingsTipRow(message)
    }

    addSourceType?.let { sourceType ->
        RemoteSourceDialog(
            sourceType = sourceType,
            title = when (sourceType) {
                RemoteSourceType.NAVIDROME -> "添加 Navidrome"
                RemoteSourceType.WEBDAV -> "添加 WebDAV"
                RemoteSourceType.SMB -> "添加 SMB"
            },
            initialName = when (sourceType) {
                RemoteSourceType.NAVIDROME -> "Navidrome"
                RemoteSourceType.WEBDAV -> "WebDAV"
                RemoteSourceType.SMB -> "SMB"
            },
            initialEndpoint = "",
            includeCredentials = true,
            confirmLabel = "添加",
            onDismiss = { addSourceType = null },
            onConfirm = { name, endpoint, username, password ->
                if (busySourceId != null) return@RemoteSourceDialog
                busySourceId = NEW_SOURCE_BUSY_ID
                scope.launch {
                    runCatching {
                        when (sourceType) {
                            RemoteSourceType.NAVIDROME -> manager.createNavidrome(name, endpoint, username, password)
                            RemoteSourceType.WEBDAV -> manager.createWebDav(name, endpoint, username, password)
                            RemoteSourceType.SMB -> manager.createSmb(name, endpoint, username, password)
                        }
                    }.onSuccess {
                        addSourceType = null
                        transientMessage = "已添加 ${it.displayName}"
                        refreshRevision++
                    }.onFailure {
                        transientMessage = it.remoteSettingsMessage("添加失败")
                    }
                    busySourceId = null
                }
            },
        )
    }

    editSource?.let { source ->
        RemoteSourceDialog(
            sourceType = source.type,
            title = "编辑来源",
            initialName = source.displayName,
            initialEndpoint = source.endpoint,
            includeCredentials = false,
            confirmLabel = "保存",
            onDismiss = { editSource = null },
            onConfirm = { name, endpoint, _, _ ->
                runSourceAction(source.id, "已保存 ${source.displayName}") {
                    manager.updateSourceConfig(
                        sourceInstanceId = source.id,
                        displayName = name,
                        endpoint = endpoint,
                        enabled = source.enabled,
                    )
                    editSource = null
                }
            },
        )
    }

    deleteSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteSource = null },
            shape = RectangleShape,
            title = { Text("删除远程来源", style = MicaTheme.typography.titleMd, color = MicaTheme.colors.textPrimary) },
            text = {
                Text(
                    "将删除“${source.displayName}”及其已同步远端曲目和加密登录信息。队列或歌单中已保存的该来源歌曲 ID 不会被改写，但之后将无法解析播放，除非重新添加对应来源。",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runSourceAction(source.id, "已删除 ${source.displayName}") {
                            manager.deleteSource(source.id)
                            deleteSource = null
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteSource = null }) { Text("取消") } },
        )
    }
    credentialSource?.let { source ->
        RemoteCredentialDialog(
            sourceName = source.displayName,
            onDismiss = { credentialSource = null },
            onConfirm = { username, password ->
                runSourceAction(source.id, "${source.displayName} 登录信息已更新") {
                    when (source.type) {
                        RemoteSourceType.NAVIDROME -> manager.rotateNavidromeCredentials(source.id, username, password)
                        RemoteSourceType.WEBDAV -> manager.rotateWebDavCredentials(source.id, username, password)
                        RemoteSourceType.SMB -> manager.rotateSmbCredentials(source.id, username, password)
                    }
                    credentialSource = null
                }
            },
        )
    }
}

@Composable
private fun RemoteSourceDialog(
    sourceType: RemoteSourceType,
    title: String,
    initialName: String,
    initialEndpoint: String,
    includeCredentials: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, endpoint: String, username: String, password: String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var endpoint by remember(initialEndpoint) { mutableStateOf(initialEndpoint) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.7f).coerceIn(300.dp, 600.dp)
    val canSubmit = name.isNotBlank() && endpoint.isNotBlank() &&
        (!includeCredentials || (username.isNotBlank() && password.isNotBlank()))

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text(title, style = MicaTheme.typography.titleMd, color = MicaTheme.colors.textPrimary) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("服务器地址") },
                    placeholder = {
                        Text(
                            if (sourceType == RemoteSourceType.SMB) {
                                "smb://nas.local/Music 或 smb://192.168.1.2/share/folder"
                            } else {
                                "https://music.example 或 http://192.168.1.2:4533"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (includeCredentials) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = if (sourceType == RemoteSourceType.SMB) {
                        "SMB 地址必须为 smb://主机/共享[/目录]；仅支持 SMB2/SMB3。用户名可写为 DOMAIN\\user。"
                    } else {
                        "地址必须包含 http:// 或 https://，且不能在 URL 中嵌入用户名、密码或 token。"
                    },
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                )
                if (sourceType != RemoteSourceType.SMB && endpoint.trim().startsWith("http://", ignoreCase = true)) {
                    Text(
                        text = "HTTP 不加密：登录参数和音频可能被同一网络中的设备看到或篡改。仅在可信局域网中使用；公网连接建议使用 HTTPS。",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(name, endpoint, username, password) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RemoteCredentialDialog(
    sourceName: String,
    onDismiss: () -> Unit,
    onConfirm: (username: String, password: String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = { Text("更新登录信息", style = MicaTheme.typography.titleMd, color = MicaTheme.colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.md)) {
                Text(
                    text = sourceName,
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank(),
                onClick = { onConfirm(username, password) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun connectionTestSubtitle(type: RemoteSourceType): String = when (type) {
    RemoteSourceType.NAVIDROME -> "发送一次 Subsonic ping，不修改曲库"
    RemoteSourceType.WEBDAV -> "发送一次 PROPFIND Depth 0，不修改曲库"
    RemoteSourceType.SMB -> "连接 SMB2/SMB3 共享并枚举配置目录，不修改曲库"
}

private fun buildSourceSubtitle(status: RemoteSourceStatus): String {
    val source = status.instance
    val typeLabel = when (source.type) {
        RemoteSourceType.NAVIDROME -> "Navidrome / OpenSubsonic"
        RemoteSourceType.WEBDAV -> "WebDAV"
        RemoteSourceType.SMB -> "SMB"
    }
    val syncLabel = if (status.lastSyncAtMs > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSyncAtMs))
    } else {
        "尚未同步"
    }
    return "$typeLabel · ${status.trackCount} 首 · $syncLabel\n${source.endpoint}"
}

private fun Throwable.remoteSettingsMessage(prefix: String): String {
    val detail = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    return "$prefix：$detail"
}

private const val NEW_SOURCE_BUSY_ID = "__new_remote_source__"
