package com.mica.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.EqSelection
import com.mica.music.audio.eq.EqualizerSnapshot
import com.mica.music.audio.eq.MicaEqualizerManager
import com.mica.music.audio.eq.EqBandConstants
import com.mica.music.ui.components.AccentTextChoice
import com.mica.music.ui.components.EqualizerBandSlider
import com.mica.music.ui.components.EqualizerCurveEditor
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.TextToggle
import com.mica.music.ui.components.formatEqFrequencyLabel
import com.mica.music.ui.components.formatEqLevelLabel
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(EqualizerPreferences.equalizerEnabled(context)) }
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) { MicaEqualizerManager.snapshot(context) }
    var selectedBandIndex by remember { mutableStateOf<Int?>(null) }
    var saveDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { revision++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        EqualizerTopBar(
            enabled = enabled,
            onBack = onBack,
            onEnabledChange = {
                enabled = it
                MicaEqualizerManager.setEnabled(context, it)
                revision++
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            EqualizerHeadline(snapshot = snapshot, enabled = enabled)

            Spacer(Modifier.height(HifiSpacing.xl))

            EqualizerCurvePanel(
                snapshot = snapshot,
                enabled = enabled,
                selectedBandIndex = selectedBandIndex,
                onBandTouched = { bandIndex, level ->
                    selectedBandIndex = bandIndex
                    if (level != snapshot.bands.getOrNull(bandIndex)?.levelMillibels) {
                        MicaEqualizerManager.setBandLevel(context, bandIndex, level)
                        revision++
                    }
                },
            )

            Spacer(Modifier.height(HifiSpacing.xl))

            SettingsSectionTitle("预设")
            EqPresetFlow(
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

            Spacer(Modifier.height(HifiSpacing.md))

            SettingsSectionTitle("全局增益")
            EqGlobalGainPanel(
                snapshot = snapshot,
                enabled = enabled,
                onGainChanged = { gain ->
                    MicaEqualizerManager.setGlobalGainMillibels(context, gain)
                    revision++
                },
            )

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
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
private fun EqualizerTopBar(
    enabled: Boolean,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
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
            modifier = Modifier.weight(1f),
        )
        TextToggle(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun EqualizerHeadline(snapshot: EqualizerSnapshot, enabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        Text(
            text = selectionLabel(snapshot),
            style = MicaTheme.typography.titleMd,
            color = MicaTheme.colors.textPrimary,
        )
        Text(
            text = listOf(
                "${EqBandConstants.BAND_COUNT} 段",
                "±${snapshot.levelMaxMillibels / 100} dB",
                if (enabled) "实时生效" else "已旁路",
            ).joinToString(" · "),
            style = MicaTheme.typography.monoMd,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

@Composable
private fun EqualizerCurvePanel(
    snapshot: EqualizerSnapshot,
    enabled: Boolean,
    selectedBandIndex: Int?,
    onBandTouched: (bandIndex: Int, levelMillibels: Short) -> Unit,
) {
    val selectedBand = selectedBandIndex?.let { snapshot.bands.getOrNull(it) }
    val readoutColor = when {
        selectedBand == null -> MicaTheme.colors.textTertiary
        enabled -> MicaTheme.colors.accent
        else -> MicaTheme.colors.textSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedBand?.let { formatEqFrequencyLabel(it.centerHz) } ?: "上下拖动调节频段",
                style = if (selectedBand == null) {
                    MicaTheme.typography.caption
                } else {
                    MicaTheme.typography.monoMd
                },
                color = readoutColor,
                modifier = Modifier.weight(1f),
            )
            if (selectedBand != null) {
                Text(
                    text = formatEqLevelLabel(selectedBand.levelMillibels),
                    style = MicaTheme.typography.monoMd,
                    color = readoutColor,
                )
            }
        }

        Spacer(Modifier.height(HifiSpacing.sm))

        EqualizerCurveEditor(
            bands = snapshot.bands,
            minMillibels = snapshot.levelMinMillibels,
            maxMillibels = snapshot.levelMaxMillibels,
            enabled = enabled,
            selectedBandIndex = selectedBandIndex,
            onBandTouched = onBandTouched,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqPresetFlow(
    snapshot: EqualizerSnapshot,
    onSelect: (EqSelection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.sm),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AccentTextChoice(
                label = "当前编辑",
                selected = snapshot.selection == EqSelection.Draft,
                onClick = { onSelect(EqSelection.Draft) },
            )
            snapshot.presets.forEach { preset ->
                val selection = EqSelection.System(preset.index)
                AccentTextChoice(
                    label = preset.name,
                    selected = sameSelection(snapshot.selection, selection),
                    onClick = { onSelect(selection) },
                )
            }
        }

        if (snapshot.presets.isEmpty()) {
            Text(
                text = "播放开始后可读取系统预设",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(
                    start = HifiSpacing.sm,
                    end = HifiSpacing.sm,
                    top = HifiSpacing.xxs,
                ),
            )
        }

        if (snapshot.savedProfiles.isNotEmpty()) {
            Spacer(Modifier.height(HifiSpacing.md))
            Text(
                text = "我的配置",
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(
                    start = HifiSpacing.sm,
                    end = HifiSpacing.sm,
                    bottom = HifiSpacing.xs,
                ),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                snapshot.savedProfiles.forEach { profile ->
                    val selection = EqSelection.Saved(profile.name)
                    AccentTextChoice(
                        label = profile.name,
                        selected = sameSelection(snapshot.selection, selection),
                        onClick = { onSelect(selection) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqCommandStrip(
    canDelete: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.sm),
    ) {
        EqCommand("保存为配置", onClick = onSave)
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
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.sm, vertical = HifiSpacing.md),
    )
}

@Composable
private fun EqGlobalGainPanel(
    snapshot: EqualizerSnapshot,
    enabled: Boolean,
    onGainChanged: (Short) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        EqualizerBandSlider(
            freqLabel = "增益",
            levelMillibels = snapshot.globalGainMillibels,
            minMillibels = snapshot.globalGainMinMillibels,
            maxMillibels = snapshot.globalGainMaxMillibels,
            enabled = enabled,
            onLevelChange = onGainChanged,
        )
        Text(
            text = "独立于预设保存；用于整体抬高或降低 EQ 输出，正增益会经过限幅保护。",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xs),
        )
    }
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
