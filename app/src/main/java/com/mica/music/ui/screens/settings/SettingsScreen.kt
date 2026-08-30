package com.mica.music.ui.screens.settings

import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.mica.music.ui.screens.tutorial.UsageTutorialDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.ArtistNames
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.MusicLibrary
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.logBackFlow
import com.mica.music.util.openAppSettings
import kotlinx.coroutines.launch

private const val BackRootDebugTag = "DEBUG-BACK-ROOT-1A2B"

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    onBack: () -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenSpatialAudio: () -> Unit,
    onOpenSoundFx: () -> Unit,
    canOpenCustomPlayerLayoutEditor: Boolean = true,
    onOpenCustomPlayerLayoutEditor: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
    playerOverlayOpen: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var scanState by remember { mutableStateOf(SettingsScanState.initial(context)) }
    var artistSplitConfig by remember { mutableStateOf(LibraryBrowseSettings.artistSplitConfig(context)) }
    var overlays by remember { mutableStateOf(SettingsOverlayState()) }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var showUsageTutorial by rememberSaveable { mutableStateOf(false) }
    if (showUsageTutorial) {
        UsageTutorialDialog(onDismiss = { showUsageTutorial = false })
    }
    var usbHybridSubpageOpen by remember { mutableStateOf(false) }
    var remoteMusicSubpageOpen by remember { mutableStateOf(false) }
    var settingsSearchOpen by remember { mutableStateOf(false) }
    var settingsSearchQuery by remember { mutableStateOf("") }
    var audioOffloadState by remember { mutableStateOf(AudioOffloadPreferences.state(context)) }
    val settingsSubpageBackEnabled =
        (usbHybridSubpageOpen && !playerOverlayOpen) ||
            (remoteMusicSubpageOpen && !playerOverlayOpen) ||
            canSettingsSubpageBack(selectedCategory, playerOverlayOpen)
    val settingsSearchBackEnabled = selectedCategory == null && settingsSearchOpen
    val settingsBackEnabled = settingsSubpageBackEnabled || settingsSearchBackEnabled
    val settingsSearchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    DisposableEffect(context) {
        val unregister = AudioOffloadPreferences.registerChangeListener(context) {
            audioOffloadState = it
        }
        onDispose(unregister)
    }

    LaunchedEffect(settingsSearchOpen) {
        if (settingsSearchOpen) {
            settingsSearchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun closeSettingsSearch() {
        settingsSearchOpen = false
        settingsSearchQuery = ""
    }

    LaunchedEffect(
        selectedCategory,
        usbHybridSubpageOpen,
        remoteMusicSubpageOpen,
        playerOverlayOpen,
        settingsBackEnabled,
        settingsSearchOpen,
    ) {
        logBackFlow(
            "page settings category=${selectedCategory?.name ?: "none"} " +
                "usbHybrid=$usbHybridSubpageOpen " +
                "playerOverlayOpen=$playerOverlayOpen searchOpen=$settingsSearchOpen " +
                "backEnabled=$settingsBackEnabled",
        )
        DiagnosticLog.event(
            "BackRoot",
            "$BackRootDebugTag settings-state category=${selectedCategory?.name ?: "none"} " +
                "usbHybrid=$usbHybridSubpageOpen " +
                "playerOverlayOpen=$playerOverlayOpen searchOpen=$settingsSearchOpen " +
                "enabled=$settingsBackEnabled",
        )
    }

    BackHandler(enabled = settingsBackEnabled) {
        if (settingsSearchBackEnabled) {
            closeSettingsSearch()
            return@BackHandler
        }
        if (usbHybridSubpageOpen) {
            logBackFlow("back-consume source=settings-usb-hybrid")
            usbHybridSubpageOpen = false
            return@BackHandler
        }
        if (remoteMusicSubpageOpen) {
            logBackFlow("back-consume source=settings-remote-music")
            remoteMusicSubpageOpen = false
            return@BackHandler
        }
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
        artistSplitConfig = artistSplitConfig,
        onDismissCustomAccent = { overlays = overlays.copy(showCustomAccent = false) },
        onDismissCustomMica = { overlays = overlays.copy(showCustomMica = false) },
        onDismissCustomWallpaperCrop = {
            overlays = overlays.copy(showCustomWallpaperCrop = false)
            val pendingPath = uiSettings.pendingCustomWallpaperPath
            if (pendingPath != null) {
                scope.launch { uiSettings.cancelPendingCustomWallpaper(pendingPath) }
            }
        },
        onConfirmCustomWallpaperCrop = { crop ->
            overlays = overlays.copy(showCustomWallpaperCrop = false)
            val pendingPath = uiSettings.pendingCustomWallpaperPath
            if (pendingPath != null) {
                scope.launch {
                    val applied = uiSettings.applyPendingCustomWallpaper(crop, pendingPath)
                    Toast.makeText(
                        context,
                        if (applied) "已应用自定义壁纸" else "壁纸应用失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                uiSettings.updateCustomWallpaperCrop(crop)
            }
        },
        onDismissExcludedDirectories = { overlays = overlays.copy(showExcludedDirectories = false) },
        onConfirmExcludedDirectories = ::updateExcludedDirectories,
        onDismissArtistSplit = { overlays = overlays.copy(showArtistSplit = false) },
        onConfirmArtistSplit = { updated: ArtistSplitConfig ->
            LibraryBrowseSettings.setArtistSplitConfig(context, updated)
            library.updateArtistSplitConfig(updated)
            artistSplitConfig = ArtistNames.currentConfig()
        },
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
                    if (selectedCategory == null && settingsSearchOpen) {
                        closeSettingsSearch()
                    } else if (usbHybridSubpageOpen) {
                        logBackFlow("back-consume source=settings-topbar-usb-hybrid")
                        usbHybridSubpageOpen = false
                    } else if (remoteMusicSubpageOpen) {
                        logBackFlow("back-consume source=settings-topbar-remote-music")
                        remoteMusicSubpageOpen = false
                    } else {
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
            if (selectedCategory == null && settingsSearchOpen) {
                TextField(
                    value = settingsSearchQuery,
                    onValueChange = { settingsSearchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(settingsSearchFocusRequester),
                    placeholder = {
                        Text(
                            text = "搜索设置项，例如 ReplayGain、字体、歌词",
                            style = MicaTheme.typography.bodyMd,
                            color = MicaTheme.colors.textTertiary,
                        )
                    },
                    textStyle = MicaTheme.typography.bodyMd.copy(
                        color = MicaTheme.colors.textPrimary,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions.Default,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = if (settingsSearchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { settingsSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "清除",
                                    tint = MicaTheme.colors.textSecondary,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            } else {
                Text(
                    text = settingsScreenTitle(
                        selectedCategory = selectedCategory,
                        usbHybridSubpageOpen = usbHybridSubpageOpen,
                        remoteMusicSubpageOpen = remoteMusicSubpageOpen,
                    ),
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (selectedCategory == null) {
                    IconButton(
                        onClick = { settingsSearchOpen = true },
                        modifier = Modifier.size(HifiSize.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索设置",
                            tint = MicaTheme.colors.textPrimary,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (selectedCategory == null) {
                SettingsCategoryList(
                    query = settingsSearchQuery,
                    onOpenUsageTutorial = { showUsageTutorial = true },
                    onSelectCategory = { category ->
                        logBackFlow("page-action settings-open-category category=${category.name}")
                        closeSettingsSearch()
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
                            onShowCustomWallpaperCrop = {
                                overlays = overlays.copy(showCustomWallpaperCrop = true)
                            },
                        )
                    }

                    SettingsCategory.PLAYBACK -> {
                        PlaybackSettingsPanel(
                            uiSettings = uiSettings,
                            canOpenCustomPlayerLayoutEditor = canOpenCustomPlayerLayoutEditor,
                            onOpenCustomPlayerLayoutEditor = onOpenCustomPlayerLayoutEditor,
                        )
                    }

                    SettingsCategory.LYRICS -> {
                        LyricsSettingsPanel(uiSettings = uiSettings)
                    }

                    SettingsCategory.LIBRARY -> {
                        if (remoteMusicSubpageOpen) {
                            RemoteMusicSettingsPanel()
                        } else {
                            LibraryScanSettingsPanel(
                                library = library,
                                excludedDirectories = scanState.excludedDirectories,
                                minDurationSec = scanState.minDurationSec,
                                deepProbe = scanState.deepProbe,
                                artistSplitConfig = artistSplitConfig,
                                onChooseLibraryFolder = libraryAccess.onChooseLibraryFolder,
                                onRescan = libraryAccess.onRescan,
                                onScanAllMusic = libraryAccess.onScanAllMusic,
                                onDeepProbeChange = {
                                    scanState = scanState.withDeepProbe(context, it)
                                },
                                onEditExcludedDirectories = {
                                    overlays = overlays.copy(showExcludedDirectories = true)
                                },
                                onMinDurationSelected = { sec ->
                                    scanState = scanState.withMinDurationSec(context, sec)
                                },
                                onEditArtistSplit = {
                                    overlays = overlays.copy(showArtistSplit = true)
                                },
                                onOpenRemoteMusic = { remoteMusicSubpageOpen = true },
                            )
                        }
                    }

                    SettingsCategory.AUDIO -> {
                        if (usbHybridSubpageOpen) {
                            UsbHybridSettingsPanel()
                        } else {
                            AudioSettingsPanel(
                                uiSettings = uiSettings,
                                library = library,
                                onOpenUsbExclusive = { usbHybridSubpageOpen = true },
                                onOpenSoundFx = onOpenSoundFx,
                            )
                        }
                    }

                    SettingsCategory.DIAGNOSTICS -> {
                        DiagnosticsSettingsPanel(
                            hasSongs = library.songs.isNotEmpty(),
                            audioOffloadState = audioOffloadState,
                            onAudioOffloadChanged = { enabled ->
                                AudioOffloadPreferences.setEnabled(context, enabled)
                                audioOffloadState = AudioOffloadPreferences.state(context)
                            },
                            onOpenMetadataDebug = onOpenMetadataDebug,
                            onOpenSpatialAudio = onOpenSpatialAudio,
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
