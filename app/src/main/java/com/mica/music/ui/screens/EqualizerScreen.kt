package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppPreferences
import com.mica.music.data.EqSelection
import com.mica.music.media.EqualizerSnapshot
import com.mica.music.media.MicaEqualizerManager
import com.mica.music.media.eq.EqBandConstants
import com.mica.music.ui.components.AccentTextChoice
import com.mica.music.ui.components.EqualizerBandSlider
import com.mica.music.ui.components.EqualizerCurveChart
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.TextToggle
import com.mica.music.ui.components.formatEqBandLabel
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppPreferences.equalizerEnabled(context)) }
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) { MicaEqualizerManager.snapshot(context) }
    var saveDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { revision++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        EqualizerTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            EqualizerStatusPanel(
                snapshot = snapshot,
                enabled = enabled,
                onEnabledChange = {
                    enabled = it
                    MicaEqualizerManager.setEnabled(context, it)
                    revision++
                },
            )

            SettingsSectionTitle("频响曲线")
            EqualizerCurveChart(
                bands = snapshot.bands,
                minMillibels = snapshot.levelMinMillibels,
                maxMillibels = snapshot.levelMaxMillibels,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )

            SettingsSectionTitle("预设")
            EqPresetStrip(
                snapshot = snapshot,
                onSelect = { selection ->
                    MicaEqualizerManager.applySelection(context, selection)
                    revision++
                },
            )
            EqCommandStrip(
                canDelete = snapshot.selection is EqSelection.Saved,
                onSave = { saveDialogOpen = true },
                onReset = {
                    MicaEqualizerManager.resetFlat(context)
                    revision++
                },
                onDelete = {
                    (snapshot.selection as? EqSelection.Saved)?.let { saved ->
                        MicaEqualizerManager.deleteSavedProfile(context, saved.name)
                        revision++
                    }
                },
            )

            SettingsSectionTitle("10 段推子")
            EqBandsPanel(
                snapshot = snapshot,
                enabled = enabled,
                onBandChanged = { band, level ->
                    MicaEqualizerManager.setBandLevel(context, band, level)
                    revision++
                },
            )

            Spacer(Modifier.height(HifiSpacing.xxl))
        }
    }

    if (saveDialogOpen) {
        SaveProfileDialog(
            onDismiss = { saveDialogOpen = false },
            onConfirm = { name ->
                if (MicaEqualizerManager.saveCurrentAsProfile(context, name)) {
                    revision++
                }
                saveDialogOpen = false
            },
        )
    }
}

@Composable
private fun EqualizerTopBar(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(HifiSize.topBarHeight)
            .padding(horizontal = HifiSpacing.sm),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(HifiSize.touchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = MicaTheme.colors.textPrimary,
            )
        }
        Text(
            text = "均衡器",
            style = MicaTheme.typography.display,
            color = MicaTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun EqualizerStatusPanel(
    snapshot: EqualizerSnapshot,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm)
            .background(MicaTheme.colors.surfaceGlass)
            .padding(HifiSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectionLabel(snapshot),
                    style = MicaTheme.typography.titleMd,
                    color = MicaTheme.colors.textPrimary,
                )
                Text(
                    text = if (snapshot.sessionReady) {
                        "音频会话已连接，当前配置会实时应用"
                    } else {
                        "播放开始后可读取系统预设；软件 EQ 仍按当前配置生效"
                    },
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = HifiSpacing.xxs),
                )
            }
            TextToggle(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        Spacer(Modifier.height(HifiSpacing.md))
        HorizontalDivider(color = MicaTheme.colors.divider)
        Spacer(Modifier.height(HifiSpacing.md))

        Row(
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EqMetric(
                label = "处理",
                value = if (enabled) "开启" else "旁路",
                active = enabled,
                modifier = Modifier.weight(1f),
            )
            EqMetric(
                label = "频段",
                value = "${EqBandConstants.BAND_COUNT}",
                active = true,
                modifier = Modifier.weight(1f),
            )
            EqMetric(
                label = "范围",
                value = "±${snapshot.levelMaxMillibels / 100} dB",
                active = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EqMetric(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
        Text(
            text = value,
            style = MicaTheme.typography.monoMd,
            color = if (active) MicaTheme.colors.accent else MicaTheme.colors.textSecondary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

@Composable
private fun EqPresetStrip(
    snapshot: EqualizerSnapshot,
    onSelect: (EqSelection) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        if (snapshot.presets.isEmpty()) {
            EqPresetChip(
                label = "播放后读取系统预设",
                selected = false,
                enabled = false,
                onClick = {},
            )
        } else {
            snapshot.presets.forEach { preset ->
                val selection = EqSelection.System(preset.index)
                EqPresetChip(
                    label = preset.name,
                    selected = sameSelection(snapshot.selection, selection),
                    onClick = { onSelect(selection) },
                )
            }
        }
        EqPresetChip(
            label = "当前编辑",
            selected = snapshot.selection == EqSelection.Draft,
            onClick = { onSelect(EqSelection.Draft) },
        )
        snapshot.savedProfiles.forEach { profile ->
            val selection = EqSelection.Saved(profile.name)
            EqPresetChip(
                label = "自定义 · ${profile.name}",
                selected = sameSelection(snapshot.selection, selection),
                onClick = { onSelect(selection) },
            )
        }
    }
}

@Composable
private fun EqPresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AccentTextChoice(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        horizontalPadding = HifiSpacing.sm,
    )
}

@Composable
private fun EqCommandStrip(
    canDelete: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        EqCommand("保存自定义", onClick = onSave)
        EqCommand("重置平直", onClick = onReset)
        if (canDelete) {
            EqCommand(
                label = "删除配置",
                onClick = onDelete,
                color = MicaTheme.colors.like,
            )
        }
    }
}

@Composable
private fun EqCommand(
    label: String,
    onClick: () -> Unit,
    color: Color = MicaTheme.colors.accent,
) {
    Text(
        text = label,
        style = MicaTheme.typography.bodyMd,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.md, vertical = HifiSpacing.sm),
    )
}

private fun selectionLabel(snapshot: EqualizerSnapshot): String = when (val sel = snapshot.selection) {
    is EqSelection.System -> snapshot.presets.firstOrNull { it.index == sel.index }?.name ?: "系统预设 ${sel.index}"
    EqSelection.Draft -> "当前编辑"
    is EqSelection.Saved -> "自定义 · ${sel.name}"
}

private fun sameSelection(left: EqSelection, right: EqSelection): Boolean =
    when {
        left is EqSelection.System && right is EqSelection.System -> left.index == right.index
        left is EqSelection.Saved && right is EqSelection.Saved -> left.name == right.name
        left == EqSelection.Draft && right == EqSelection.Draft -> true
        else -> false
    }

@Composable
private fun EqBandsPanel(
    snapshot: EqualizerSnapshot,
    enabled: Boolean,
    onBandChanged: (bandIndex: Int, levelMillibels: Short) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = HifiSpacing.sm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        ) {
            snapshot.bands.forEachIndexed { index, band ->
                EqualizerBandSlider(
                    freqLabel = formatEqBandLabel(band.centerHz),
                    levelMillibels = band.levelMillibels,
                    minMillibels = snapshot.levelMinMillibels,
                    maxMillibels = snapshot.levelMaxMillibels,
                    enabled = enabled,
                    onLevelChange = { level -> onBandChanged(index, level) },
                )
            }
        }
        Text(
            text = "每一行从左到右对应 -12 dB 到 +12 dB；拖动任一推子会进入当前编辑配置",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
        )
    }
}

@Composable
private fun SaveProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存自定义配置") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("例如：人声增强") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
