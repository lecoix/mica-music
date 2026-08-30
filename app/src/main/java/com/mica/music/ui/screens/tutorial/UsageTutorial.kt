package com.mica.music.ui.screens.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mica.music.data.preferences.UsageTutorialPreferences
import com.mica.music.ui.theme.MicaTheme

internal enum class UsageTip(val title: String, val instruction: String, val detail: String, val result: String) {
    DRAWER("向右滑，打开侧栏", "在主页单指向右滑动，展开侧栏。", "向左滑可收起。进入详情页后，先返回主页再使用。", "歌曲、专辑、文件夹，随手切换"),
    ZOOM("双指缩放，换个视角", "在歌曲列表上用双指张开或捏合。", "调整歌曲显示样式，让封面更醒目，或让列表更紧凑。", "同一份音乐，不同的浏览方式"),
    LOCATE("长按，定位当前歌曲", "长按底部的迷你播放栏。", "当前歌曲在曲库中时，会返回歌曲列表并滚动到它的位置。当前曲始终高亮。", "当前歌曲已定位"),
    MENU("封面里，还有更多操作", "在播放页长按专辑封面，打开菜单。", "睡眠定时、变速 / 变调等操作都在这里。下图以标准封面为例。", "更多播放操作，就在封面菜单里"),
    FOLDERS("左右滑，切换文件夹层级", "在「层级浏览」模式的文件夹统合页左右滑动。", "仅层级浏览模式支持；可切换不同深度的统合页，只有一层时无法继续切换。", "层级浏览：从同层文件夹，浏览到更深一层"),
    SORT("排好顺序，再锁定", "先选「自定义」，拖动把手调整歌曲顺序。", "再次点击「自定义」变为「自定义·锁定」，把手消失；再点可解锁。", "自定义顺序已锁定，拖动把手已隐藏"),
}

/** Native dialog window stays above the separate player ComposeView, without changing playback. */
@Composable
internal fun UsageTutorialDialog(firstRun: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf((if (firstRun) UsageTutorialPreferences.page(context) else 0).coerceIn(UsageTip.entries.indices)) }
    fun finish() {
        if (firstRun) UsageTutorialPreferences.complete(context)
        onDismiss()
    }
    Dialog(
        onDismissRequest = { finish() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        BackHandler {
            if (page > 0) {
                page--
                if (firstRun) UsageTutorialPreferences.savePage(context, page)
            } else finish()
        }
        UsageTutorialScreen(
            page = page.coerceIn(UsageTip.entries.indices),
            onPageChange = {
                page = it
                if (firstRun) UsageTutorialPreferences.savePage(context, it)
            },
            onFinish = ::finish,
            firstRun = firstRun,
        )
    }
}

@Composable
internal fun UsageTutorialScreen(page: Int, onPageChange: (Int) -> Unit, onFinish: () -> Unit, firstRun: Boolean = true) {
    val colors = MicaTheme.colors
    val tip = UsageTip.entries[page]
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(colors.surfaceCard, colors.accent.copy(alpha = 0.10f).compositeOver(colors.surfaceCard))),
        ).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.widthIn(max = 568.dp).fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text("MICA / 使用技巧", color = colors.textSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
            }
            Text("${(page + 1).toString().padStart(2, '0')} / ${UsageTip.entries.size.toString().padStart(2, '0')}", color = colors.accent, fontSize = 13.sp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = onFinish) { Text(if (firstRun) "跳过" else "关闭", color = colors.like) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).widthIn(max = 568.dp).fillMaxWidth()) {
            // Keep the ordinary portrait lesson within the screen; short windows / large text can scroll.
            val scrollContent = maxHeight < 480.dp || LocalDensity.current.fontScale > 1.3f
            key(page) {
                Column(
                    Modifier.fillMaxSize().then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                ) {
                    Text(tip.title, color = colors.textPrimary, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
                    Spacer(Modifier.height(12.dp))
                    Text(tip.instruction, color = colors.textPrimary, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(16.dp))
                    UsageTutorialIllustration(tip, modifier = (if (scrollContent) Modifier.height(440.dp) else Modifier.weight(1f)).padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(tip.detail, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        Column(Modifier.widthIn(max = 568.dp).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) {
                    Text("上一步", color = if (page > 0) colors.textPrimary else colors.textTertiary)
                }
                Button(
                    onClick = { if (page == UsageTip.entries.lastIndex) onFinish() else onPageChange(page + 1) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if (colors.accent.luminance() > 0.179f) Color.Black else Color.White),
                ) { Text(if (page == UsageTip.entries.lastIndex) "开始使用" else "下一步", fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                UsageTip.entries.forEachIndexed { index, _ ->
                    Box(Modifier.weight(1f).height(2.dp).background(if (index <= page) colors.accent else colors.divider))
                }
            }
        }
    }
}
