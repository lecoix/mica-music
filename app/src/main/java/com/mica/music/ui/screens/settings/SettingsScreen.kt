package com.mica.music.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.logBackFlow
import com.mica.music.util.openAppSettings

private const val BackRootDebugTag = "DEBUG-BACK-ROOT-1A2B"

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    onBack: () -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenParticleCoverPreview: () -> Unit,
    onOpenPhotoStackShadowPreview: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
    playerOverlayOpen: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var scanState by remember { mutableStateOf(SettingsScanState.initial(context)) }
    var overlays by remember { mutableStateOf(SettingsOverlayState()) }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val settingsSubpageBackEnabled = canSettingsSubpageBack(selectedCategory, playerOverlayOpen)

    LaunchedEffect(selectedCategory, playerOverlayOpen, settingsSubpageBackEnabled) {
        logBackFlow(
            "page settings category=${selectedCategory?.name ?: "none"} " +
                "playerOverlayOpen=$playerOverlayOpen " +
                "backEnabled=$settingsSubpageBackEnabled",
        )
        DiagnosticLog.event(
            "BackRoot",
            "$BackRootDebugTag settings-state category=${selectedCategory?.name ?: "none"} " +
                "playerOverlayOpen=$playerOverlayOpen enabled=$settingsSubpageBackEnabled",
        )
    }

    BackHandler(enabled = settingsSubpageBackEnabled) {
        logBackFlow(
            "back-consume source=settings-subpage category=${selectedCategory?.name ?: "none"} " +
                "playerOverlayOpen=$playerOverlayOpen",
        )
        DiagnosticLog.event(
            "BackRoot",
            "$BackRootDebugTag settings-consume category=${selectedCategory?.name ?: "none"} " +
                "playerOverlayOpen=$playerOverlayOpen",
        )
        selectedCategory = consumeSettingsBack(selectedCategory)
    }

    val libraryAccess = rememberSettingsLibraryAccess(library, activity)

    fun updateExcludedDirectories(directories: List<String>) {
        val updated = scanState.withExcludedDirectories(context, directories) ?: return
        scanState = updated
        libraryAccess.onRescan()
    }

    SettingsOverlays(
        overlays = overlays,
        uiSettings = uiSettings,
        library = library,
        excludedDirectories = scanState.excludedDirectories,
        onDismissCustomAccent = { overlays = overlays.copy(showCustomAccent = false) },
        onDismissCustomMica = { overlays = overlays.copy(showCustomMica = false) },
        onDismissExcludedDirectories = { overlays = overlays.copy(showExcludedDirectories = false) },
        onConfirmExcludedDirectories = ::updateExcludedDirectories,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(HifiSize.topBarHeight)
                .padding(horizontal = HifiSpacing.sm),
        ) {
            IconButton(
                onClick = {
                    when (resolveSettingsTopBarBackAction(selectedCategory)) {
                        SettingsTopBarBackAction.ExitSettings -> {
                            logBackFlow("back-consume source=settings-topbar category=none")
                            onBack()
                        }

                        SettingsTopBarBackAction.PopCategory -> {
                            logBackFlow(
                                "back-consume source=settings-topbar category=${selectedCategory?.name}",
                            )
                            selectedCategory = consumeSettingsBack(selectedCategory)
                        }
                    }
                },
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = settingsScreenTitle(selectedCategory),
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (selectedCategory == null) {
                SettingsCategoryList(
                    onSelectCategory = { category ->
                        logBackFlow("page-action settings-open-category category=${category.name}")
                        selectedCategory = category
                    },
                )
            } else {
                when (selectedCategory) {
                    SettingsCategory.APPEARANCE -> {
                        AppearanceSettingsPanel(
                            uiSettings = uiSettings,
                            onShowCustomAccentDialog = {
                                overlays = overlays.copy(showCustomAccent = true)
                            },
                            onShowCustomMicaDialog = {
                                overlays = overlays.copy(showCustomMica = true)
                            },
                        )
                    }

                    SettingsCategory.PLAYBACK -> {
                        PlaybackSettingsPanel(uiSettings = uiSettings)
                    }

                    SettingsCategory.LYRICS -> {
                        LyricsSettingsPanel(uiSettings = uiSettings)
                    }

                    SettingsCategory.LIBRARY -> {
                        LibraryScanSettingsPanel(
                            library = library,
                            excludedDirectories = scanState.excludedDirectories,
                            minDurationSec = scanState.minDurationSec,
                            onChooseLibraryFolder = libraryAccess.onChooseLibraryFolder,
                            onRescan = libraryAccess.onRescan,
                            onEditExcludedDirectories = {
                                overlays = overlays.copy(showExcludedDirectories = true)
                            },
                            onMinDurationSelected = { sec ->
                                scanState = scanState.withMinDurationSec(context, sec)
                            },
                        )
                    }

                    SettingsCategory.ADVANCED -> {
                        AdvancedSettingsPanel(
                            uiSettings = uiSettings,
                            includeNonMusic = scanState.includeNonMusic,
                            deepProbe = scanState.deepProbe,
                            hasSongs = library.songs.isNotEmpty(),
                            onIncludeNonMusicChange = {
                                scanState = scanState.withIncludeNonMusic(context, it)
                            },
                            onDeepProbeChange = {
                                scanState = scanState.withDeepProbe(context, it)
                            },
                            onOpenMetadataDebug = onOpenMetadataDebug,
                            onOpenAppSettings = { openAppSettings(context) },
                        )
                    }

                    else -> Unit
                }
            }

            Spacer(Modifier.height(HifiSpacing.lg))

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}
