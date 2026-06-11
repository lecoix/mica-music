package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
