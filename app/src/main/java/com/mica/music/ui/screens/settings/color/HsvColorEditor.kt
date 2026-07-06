package com.mica.music.ui.screens.settings.color

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun HsvColorEditor(
    title: String,
    initialColorArgb: Int,
    onColorChange: (Int) -> Unit,
) {
    val initialHsv = remember(initialColorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColorArgb, it) }
    }
    var hue by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[2]) }
    var hexValue by remember(initialColorArgb) { mutableStateOf(formatAccentHex(initialColorArgb)) }
    val parsedColorArgb = parseAccentHex(hexValue)
    val previewColorArgb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    val previewColor = Color(previewColorArgb)

    fun setHsv(newHue: Float = hue, newSaturation: Float = saturation, newBrightness: Float = brightness) {
        hue = newHue.coerceIn(0f, 360f)
        saturation = newSaturation.coerceIn(0f, 1f)
        brightness = newBrightness.coerceIn(0f, 1f)
        val updated = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
        hexValue = formatAccentHex(updated)
        onColorChange(updated)
    }

    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
        Text(
            text = title,
            style = MicaTheme.typography.titleSm,
            color = MicaTheme.colors.textPrimary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(previewColor)
                    .border(1.dp, MicaTheme.colors.divider),
            )
            Text(
                text = formatAccentHex(previewColorArgb),
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textSecondary,
            )
        }

        HsvColorSlider(
            label = "色相",
            value = hue,
            valueRange = 0f..360f,
            valueText = "${hue.toInt()}°",
            colors = hueGradientColors(),
            onValueChange = { setHsv(newHue = it) },
        )
        HsvColorSlider(
            label = "饱和度",
            value = saturation,
            valueRange = 0f..1f,
            valueText = "${(saturation * 100).toInt()}%",
            colors = listOf(
                Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, brightness))),
                Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, brightness))),
            ),
            onValueChange = { setHsv(newSaturation = it) },
        )
        HsvColorSlider(
            label = "明度",
            value = brightness,
            valueRange = 0f..1f,
            valueText = "${(brightness * 100).toInt()}%",
            colors = listOf(
                Color.Black,
                Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f))),
            ),
            onValueChange = { setHsv(newBrightness = it) },
        )

        OutlinedTextField(
            value = hexValue,
            onValueChange = { value ->
                hexValue = value
                parseAccentHex(value)?.let { updated ->
                    val hsv = FloatArray(3)
                    AndroidColor.colorToHSV(updated, hsv)
                    hue = hsv[0]
                    saturation = hsv[1]
                    brightness = hsv[2]
                    onColorChange(updated)
                }
            },
            singleLine = true,
            isError = parsedColorArgb == null,
            label = {
                Text(
                    text = "#RRGGBB",
                    style = MicaTheme.typography.caption,
                )
            },
            supportingText = {
                if (parsedColorArgb == null) {
                    Text(
                        text = "请输入 6 位十六进制颜色",
                        style = MicaTheme.typography.caption,
                    )
                }
            },
            textStyle = MicaTheme.typography.bodyMd.copy(color = MicaTheme.colors.textPrimary),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
