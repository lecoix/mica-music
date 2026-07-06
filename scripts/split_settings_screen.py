from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app/src/main/java/com/mica/music/ui/screens"
settings = root / "settings"
color = settings / "color"
settings.mkdir(parents=True, exist_ok=True)
color.mkdir(parents=True, exist_ok=True)

src_lines = (root / "SettingsScreen.kt").read_text(encoding="utf-8").splitlines(keepends=True)

def lines(start: int, end: int) -> str:
    return "".join(src_lines[start - 1 : end])

def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")

PKG = "package com.mica.music.ui.screens.settings\n\n"
COLOR_PKG = "package com.mica.music.ui.screens.settings.color\n\n"

models = lines(100, 187).replace("private val", "internal val").replace(
    "private enum class", "internal enum class"
)
write(
    settings / "SettingsModels.kt",
    PKG
    + """import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.ui.theme.MicaPreset

"""
    + models,
)

def panel(path: str, func: str, start: int, end: int, imports: str) -> None:
    body = lines(start, end).replace(
        f"private fun {func}", f"@Composable\ninternal fun {func}", 1
    )
    write(settings / path, PKG + imports + body)

panel(
    "SettingsCategoryList.kt",
    "SettingsCategoryList",
    440,
    459,
    """import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.theme.HifiSpacing

""",
)

panel(
    "SettingsAppearancePanel.kt",
    "AppearanceSettingsPanel",
    461,
    579,
    """import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.screens.settings.color.formatAccentHex
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset

""",
)

panel(
    "SettingsPlaybackPanel.kt",
    "PlaybackSettingsPanel",
    581,
    739,
    """import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing

""",
)

panel(
    "SettingsLyricsPanel.kt",
    "LyricsSettingsPanel",
    741,
    819,
    """import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

""",
)

panel(
    "SettingsListInfoPanel.kt",
    "InfoLineSettingsPanel",
    821,
    883,
    """import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow

""",
)

panel(
    "SettingsLibraryPanel.kt",
    "LibraryScanSettingsPanel",
    885,
    938,
    """import androidx.compose.runtime.Composable
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle

""",
)

panel(
    "SettingsAdvancedPanel.kt",
    "AdvancedSettingsPanel",
    940,
    978,
    """import androidx.compose.runtime.Composable
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

""",
)

excluded = lines(980, 1129).replace("private fun ExcludedDirectoriesDialog", "@Composable\ninternal fun ExcludedDirectoriesDialog", 1)
excluded = excluded.replace("private fun DirectoryActionRow", "@Composable\ninternal fun DirectoryActionRow", 1)
excluded = excluded.replace("private fun scanDirectoryCandidates", "internal fun scanDirectoryCandidates", 1)
write(
    settings / "SettingsExcludedDirectoriesDialog.kt",
    PKG
    + """import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mica.music.data.Song
import com.mica.music.data.scanner.ExcludedScanDirectories
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

"""
    + excluded,
)

color_format = lines(1465, 1479).replace("private fun formatAccentHex", "internal fun formatAccentHex", 1)
color_format = color_format.replace("private fun parseAccentHex", "internal fun parseAccentHex", 1)
color_format = color_format.replace("private fun hueGradientColors", "internal fun hueGradientColors", 1)
write(
    color / "SettingsColorFormat.kt",
    COLOR_PKG
    + """import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import java.util.Locale

"""
    + color_format,
)

hsv_slider = lines(1384, 1463).replace("private fun HsvColorSlider", "@Composable\ninternal fun HsvColorSlider", 1)
write(
    color / "HsvColorSlider.kt",
    COLOR_PKG
    + """import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

"""
    + hsv_slider,
)

hsv_editor = lines(1230, 1344).replace("private fun HsvColorEditor", "@Composable\ninternal fun HsvColorEditor", 1)
write(
    color / "HsvColorEditor.kt",
    COLOR_PKG
    + """import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

"""
    + hsv_editor,
)

dialogs = lines(1131, 1228).replace("private fun CustomMicaBackgroundDialog", "@Composable\ninternal fun CustomMicaBackgroundDialog", 1)
dialogs = dialogs.replace("private fun MicaColorModeChip", "@Composable\ninternal fun MicaColorModeChip", 1)
dialogs += lines(1346, 1382).replace("private fun CustomAccentColorDialog", "@Composable\ninternal fun CustomAccentColorDialog", 1)
write(
    color / "SettingsColorDialogs.kt",
    COLOR_PKG
    + """import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

"""
    + dialogs,
)

shell = lines(189, 438)
shell = shell.replace(
    "fun SettingsScreen(",
    "package com.mica.music.ui.screens.settings\n\n@Composable\nfun SettingsScreen(",
    1,
)
write(settings / "SettingsScreen.kt", shell)
print("done")
