package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun SettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MicaTheme.typography.monoSm,
        color = MicaTheme.colors.textTertiary,
        modifier = modifier.padding(
            horizontal = HifiSpacing.lg,
            vertical = HifiSpacing.sm,
        ),
    )
}

@Composable
fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MicaTheme.typography.bodyLg,
                color = if (enabled) MicaTheme.colors.textPrimary else MicaTheme.colors.textTertiary,
            )
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(top = HifiSpacing.xxs),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MicaTheme.colors.textTertiary,
            modifier = Modifier.size(HifiSize.iconMd),
        )
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(top = HifiSpacing.xxs),
            )
        }
        TextToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = {
            Text(
                text = placeholder,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        },
        textStyle = MicaTheme.typography.bodyMd.copy(color = MicaTheme.colors.textPrimary),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .padding(bottom = HifiSpacing.md),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsChoiceRow(
    title: String,
    choices: List<Pair<Int, String>>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(top = HifiSpacing.xxs, bottom = HifiSpacing.sm),
            )
        } else {
            Spacer(Modifier.height(HifiSpacing.sm))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        ) {
            choices.forEach { (value, label) ->
                AccentTextChoice(
                    label = label,
                    selected = value == selectedValue,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
fun SettingsDropdownRow(
    title: String,
    choices: List<Pair<Int, String>>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selectedValue }?.second ?: selectedValue.toString()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(top = HifiSpacing.xxs, bottom = HifiSpacing.sm),
            )
        } else {
            Spacer(Modifier.height(HifiSpacing.sm))
        }
        Box {
            AccentTextChoice(
                label = selectedLabel,
                selected = true,
                onClick = { expanded = true },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                choices.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                style = MicaTheme.typography.bodyMd,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTipRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "· $text",
        style = MicaTheme.typography.bodyMd,
        color = MicaTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
    )
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = if (enabled) MicaTheme.colors.textPrimary else MicaTheme.colors.textTertiary,
        )
        Text(
            text = subtitle,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}
