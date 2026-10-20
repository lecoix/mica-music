package com.mica.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mica.music.MicaApp
import com.mica.music.data.lx.readBytesBounded
import com.mica.music.data.preferences.OnlineMusicDefaultQuality
import com.mica.music.data.preferences.OnlineMusicPreferences
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LxSourceSettingsPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as MicaApp
    val owner = remember(app) { app.lxSourceOwner }
    val state by owner.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showTrial by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var defaultQuality by remember { mutableStateOf(OnlineMusicPreferences.defaultQuality(context)) }
    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try { action(); inputError = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { inputError = "操作未完成，请查看音源状态；检查输入后重试。" }
        }
    }
    LaunchedEffect(owner) { runCatching { owner.restore() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchAction {
            owner.importScript {
                context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(512 * 1024).toString(Charsets.UTF_8) }
                    ?: error("无法读取文件")
            }
        }
    }
    SettingsSectionTitle("播放")
    SettingsDropdownRow(
        title = "默认播放音质",
        choices = OnlineMusicDefaultQuality.entries.map { it.value to it.label },
        selectedValue = defaultQuality.value,
        onSelect = { value ->
            defaultQuality = OnlineMusicDefaultQuality.fromValue(value)
            OnlineMusicPreferences.setDefaultQuality(context, defaultQuality)
        },
        subtitle = "固定档位不可用时会回到每次询问，不会静默降低品质",
    )

    SettingsSectionTitle("音源")
    SettingsTipRow("支持 TX / KW / WY / KG。音源脚本可以发起网络请求，仅导入可信来源。")
    val script = state.script
    if (script == null) {
        SettingsActionRow(
            title = "导入音源",
            subtitle = "选择一个 .js 音源脚本",
            onClick = { confirmImport = true },
            enabled = !state.busy,
        )
    } else {
        SettingsTipRow("当前：${script.metadata.name} · ${script.metadata.version} · ${script.metadata.author}")
        script.metadata.description.takeIf { it.isNotBlank() }?.let { SettingsTipRow(it) }
        SettingsActionRow(
            title = "替换音源",
            subtitle = "选择新的 .js 脚本并替换当前音源",
            onClick = { confirmImport = true },
            enabled = !state.busy,
        )
        SettingsToggleRow(
            title = "启用音源",
            subtitle = if (script.enabled) "已启用；允许新的在线歌曲解析" else "已停用；保留脚本和已保存的歌曲信息",
            checked = script.enabled,
            onCheckedChange = { enabled -> launchAction { owner.setEnabled(enabled) } },
        )
        SettingsActionRow(
            title = "重新初始化",
            subtitle = if (state.busy) "正在初始化…" else "重新启动脚本运行时并刷新平台与品质能力",
            onClick = { launchAction { owner.setEnabled(true) } },
            enabled = !state.busy,
        )
        SettingsActionRow(
            title = "删除音源",
            subtitle = "移除应用内脚本副本；原始 .js 文件不会被删除",
            onClick = { confirmDelete = true },
            enabled = !state.busy,
        )
    }
    SettingsTipRow(state.message)
    state.platforms.forEach { (source, qualities) -> SettingsTipRow("${source.uppercase()} · ${qualities.joinToString(" / ")}") }
    inputError?.let { SettingsTipRow(it) }

    SettingsSectionTitle("调试")
    SettingsActionRow(
        title = "调试试听",
        subtitle = "手动填写 MusicInfo 并验证音源解析；正常使用无需进入",
        enabled = state.platforms.isNotEmpty() && !state.busy,
        onClick = { showTrial = true },
    )

    if (confirmImport) AlertDialog(
        onDismissRequest = { confirmImport = false }, shape = RectangleShape,
        title = { Text(if (state.script == null) "导入音源" else "替换当前音源") },
        text = { Text(if (state.script == null) {
            "脚本可发起网络请求，请只使用可信来源。导入后将立即初始化音源。"
        } else {
            "将用新脚本替换当前音源并重新初始化。原始 .js 文件不会被修改。"
        }) },
        confirmButton = { TextButton(onClick = { confirmImport = false; importer.launch(arrayOf("*/*")) }) { Text("选择 .js 文件") } },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, shape = RectangleShape,
        title = { Text("删除当前音源？") },
        text = { Text("删除应用内的脚本副本，原始 .js 文件不受影响。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; launchAction { owner.delete() } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
    if (showTrial) {
        var source by remember { mutableStateOf("") }
        var quality by remember { mutableStateOf("") }
        var info by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showTrial = false }, shape = RectangleShape,
            title = { Text("音源测试播放") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("填写脚本所需的平台字段，例如 songmid、hash 或 id。不会自动降低品质。")
                    Text(state.platforms.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" })
                    state.platforms.keys.forEach { platform ->
                        TextButton(onClick = { source = platform; quality = "" }, shape = RectangleShape) { Text((if (source == platform) "✓ " else "") + platform) }
                    }
                    state.platforms[source].orEmpty().forEach { q ->
                        TextButton(onClick = { quality = q }, shape = RectangleShape) { Text((if (quality == q) "✓ " else "") + q) }
                    }
                    OutlinedTextField(info, { if (it.length <= 32768) info = it }, label = { Text("LX MusicInfo JSON") }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, minLines = 4)
                    error?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val track = owner.prepareTrial(source, quality, info)
                    app.playerController.connectIfNeeded()
                    app.playerController.playSingleSong(track.toPlaybackSong())
                    showTrial = false
                } catch (_: Exception) { error = "请检查平台、品质和 MusicInfo JSON，确认音源初始化成功。" }
            }) { Text("替换队列并播放") } },
            dismissButton = { TextButton(onClick = { showTrial = false }) { Text("取消") } },
        )
    }
}
