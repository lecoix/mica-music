package com.mica.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppPreferences
import com.mica.music.data.EqSelection
import com.mica.music.media.EqualizerSnapshot
import com.mica.music.media.MicaEqualizerManager
import com.mica.music.media.eq.EqBandConstants
import com.mica.music.ui.components.EqualizerBandBar
import com.mica.music.ui.components.EqualizerCurveChart
import com.mica.music.ui.components.EqualizerDbScale
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.components.formatEqBandLabel
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaBackground

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
            .micaBackground(MicaPreset.Fog)
            .padding(contentPadding),
    ) {
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsToggleRow(
                title = "启用均衡器",
                subtitle = buildString {
                    append("${EqBandConstants.BAND_COUNT} 段软件均衡 · ±12 dB")
                    if (!snapshot.sessionReady) {
                        append(" · 播放后系统预设列表更完整")
                    }
                },
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    MicaEqualizerManager.setEnabled(context, it)
                    revision++
                },
            )

            SettingsSectionTitle("预设")
            EqPresetDropdown(
                snapshot = snapshot,
                onSelect = { selection ->
                    MicaEqualizerManager.applySelection(context, selection)
                    revision++
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
            ) {
                Text(
                    text = "保存为自定义",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.accent,
                    modifier = Modifier.clickable { saveDialogOpen = true },
                )
                if (snapshot.selection is EqSelection.Saved) {
                    Text(
                        text = "删除当前配置",
                        style = MicaTheme.typography.bodyMd,
                        color = MicaTheme.colors.like,
                        modifier = Modifier.clickable {
                            MicaEqualizerManager.deleteSavedProfile(
                                context,
                                (snapshot.selection as EqSelection.Saved).name,
                            )
                            revision++
                        },
                    )
                }
            }

            SettingsSectionTitle("频响曲线")
            EqualizerCurveChart(
                bands = snapshot.bands,
                minMillibels = snapshot.levelMinMillibels,
                maxMillibels = snapshot.levelMaxMillibels,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )

            SettingsSectionTitle("频段")
            EqBandsPanel(
                snapshot = snapshot,
                enabled = enabled,
                onBandChanged = { band, level ->
                    MicaEqualizerManager.setBandLevel(context, band, level)
                    revision++
                },
            )

            SettingsActionRow(
                title = "重置为平直",
                subtitle = "所有频段回到 0 dB 附近",
                onClick = {
                    MicaEqualizerManager.resetFlat(context)
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
private fun EqPresetDropdown(
    snapshot: EqualizerSnapshot,
    onSelect: (EqSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = selectionLabel(snapshot)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Text(
            text = "当前预设",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
        Spacer(Modifier.height(HifiSpacing.xs))
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(vertical = HifiSpacing.sm),
            ) {
                Text(
                    text = currentLabel,
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MicaTheme.colors.textSecondary,
                )
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MicaTheme.colors.divider,
                thickness = 1.dp,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (snapshot.presets.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("播放后可读取系统预设") },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                } else {
                    snapshot.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                expanded = false
                                onSelect(EqSelection.System(preset.index))
                            },
                        )
                    }
                }
                if (snapshot.savedProfiles.isNotEmpty()) {
                    HorizontalDivider()
                    snapshot.savedProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text("自定义 · ${profile.name}") },
                            onClick = {
                                expanded = false
                                onSelect(EqSelection.Saved(profile.name))
                            },
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("自定义（当前编辑）") },
                    onClick = {
                        expanded = false
                        onSelect(EqSelection.Draft)
                    },
                )
            }
        }
    }
}

private fun selectionLabel(snapshot: EqualizerSnapshot): String = when (val sel = snapshot.selection) {
    is EqSelection.System -> snapshot.presets.firstOrNull { it.index == sel.index }?.name ?: "系统预设 ${sel.index}"
    EqSelection.Draft -> "自定义（当前编辑）"
    is EqSelection.Saved -> "自定义 · ${sel.name}"
}

@Composable
private fun EqBandsPanel(
    snapshot: EqualizerSnapshot,
    enabled: Boolean,
    onBandChanged: (bandIndex: Int, levelMillibels: Short) -> Unit,
) {
    val bandScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = HifiSpacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(bandScroll)
                .padding(horizontal = HifiSpacing.lg),
            verticalAlignment = Alignment.Bottom,
        ) {
            EqualizerDbScale(
                minMillibels = snapshot.levelMinMillibels,
                maxMillibels = snapshot.levelMaxMillibels,
            )
            Row(
                modifier = Modifier.padding(start = HifiSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xxs),
                verticalAlignment = Alignment.Bottom,
            ) {
                snapshot.bands.forEachIndexed { index, band ->
                    EqualizerBandBar(
                        freqLabel = formatEqBandLabel(band.centerHz),
                        levelMillibels = band.levelMillibels,
                        minMillibels = snapshot.levelMinMillibels,
                        maxMillibels = snapshot.levelMaxMillibels,
                        enabled = enabled,
                        onLevelChange = { level -> onBandChanged(index, level) },
                    )
                }
            }
        }
        if (snapshot.bands.size > 8) {
            Text(
                text = "← 左右滑动查看全部 ${snapshot.bands.size} 段 →",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
            )
        }
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
