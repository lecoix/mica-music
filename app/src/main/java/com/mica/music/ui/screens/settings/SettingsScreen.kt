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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
import com.mica.music.usb.UsbHybridDiagnosticsPort
import com.mica.music.audio.loudness.LoudnessScanPort
import com.mica.music.data.ArtistNames
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.MusicLibrary
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.DetailedDiagnosticsPreferences
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.util.openAppSettings
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    loudnessScanPort: LoudnessScanPort,
    usbHybridDiagnosticsPort: UsbHybridDiagnosticsPort,
    onBack: () -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenSpatialAudio: () -> Unit,
    onOpenSoundFx: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onBrowseSmb: (String) -> Unit = {},
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
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var showUsageTutorial by rememberSaveable { mutableStateOf(false) }
    if (showUsageTutorial) {
        UsageTutorialDialog(onDismiss = { showUsageTutorial = false })
    }
    var detailPage by rememberSaveable { mutableStateOf<SettingsDetailPage?>(null) }
    var searchEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val searchEntry = SettingsSearchIndex.entries.firstOrNull { it.id == searchEntryId }
    val searchFocus = remember(searchEntryId) { SettingsSearchFocus(searchEntryId) }
    var settingsSearchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var audioOffloadState by remember { mutableStateOf(AudioOffloadPreferences.state(context)) }
    var detailedDiagnostics by remember { mutableStateOf(DetailedDiagnosticsPreferences.state(context)) }
    val settingsBackEnabled = !playerOverlayOpen &&
        (selectedCategory != null || settingsSearchOpen)
    val settingsSearchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    DisposableEffect(context) {
        val unregister = AudioOffloadPreferences.registerChangeListener(context) {
            audioOffloadState = it
        }
        onDispose(unregister)
    }

    LaunchedEffect(settingsSearchOpen, selectedCategory) {
        if (settingsSearchOpen && selectedCategory == null) {
            settingsSearchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun closeSettingsSearch() {
        settingsSearchOpen = false
        settingsSearchQuery = ""
    }
    fun navigateBack() {
        when {
            searchEntryId != null -> {
                searchEntryId = null
                detailPage = null
                selectedCategory = null
            }
            detailPage != null -> detailPage = null
            selectedCategory != null -> selectedCategory = null
            settingsSearchOpen -> closeSettingsSearch()
            else -> onBack()
        }
    }
    BackHandler(enabled = settingsBackEnabled) { navigateBack() }

    fun openSearchEntry(entry: SettingsIndexEntry) {
        keyboardController?.hide()
        when {
            entry.id == "help.tutorial" -> showUsageTutorial = true
            entry.target.surface == SettingsIndexSurface.EQUALIZER -> onOpenEqualizer()
            entry.id == "audio.sound-fx" -> onOpenSoundFx()
            else -> {
                selectedCategory = entry.target.category
                // Search must respect the same scan-time entry guard as the library panel.
                detailPage = entry.detailPage().takeUnless {
                    it == SettingsDetailPage.REMOTE && library.isUserVisibleScanning
                }
                searchEntryId = entry.id
            }
        }
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
                onClick = ::navigateBack,
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
                    text = detailPage?.title ?: settingsScreenTitle(selectedCategory),
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

        key(selectedCategory, detailPage, searchEntryId) {
            CompositionLocalProvider(LocalSettingsSearchFocus provides searchFocus) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val opensWholePage = detailPage == SettingsDetailPage.USB ||
                        detailPage == SettingsDetailPage.REMOTE ||
                        detailPage == SettingsDetailPage.EXTERNAL_LYRICS
                    if (searchEntry != null && detailPage == searchEntry.detailPage() &&
                        !opensWholePage && !searchFocus.present
                    ) {
                        SettingsTipRow(
                            "${searchEntry.title}：${searchEntry.availability ?: "当前条件下不可用"}",
                        )
                    }
                    if (selectedCategory == null) {
                        SettingsCategoryList(
                            query = settingsSearchQuery,
                            onOpenSearchEntry = ::openSearchEntry,
                            onOpenUsageTutorial = {
                                closeSettingsSearch()
                                showUsageTutorial = true
                            },
                            onOpenEqualizer = {
                                closeSettingsSearch()
                                onOpenEqualizer()
                            },
                            onSelectCategory = { category ->
                                closeSettingsSearch()
                                selectedCategory = category
                            },
                        )
                    } else {
                        when (detailPage) {
                            SettingsDetailPage.WALLPAPER -> WallpaperSettingsPanel(uiSettings) {
                                overlays = overlays.copy(showCustomWallpaperCrop = true)
                            }
                            SettingsDetailPage.MINI_PLAYER -> MiniPlayerSettingsPanel(uiSettings)
                            SettingsDetailPage.PLAYER_INFO -> PlayerInfoSettingsPanel(uiSettings)
                            SettingsDetailPage.USB -> UsbHybridSettingsPanel(usbHybridDiagnosticsPort)
                            SettingsDetailPage.REMOTE -> RemoteMusicSettingsPanel(onBrowseSmb)
                            SettingsDetailPage.EXTERNAL_LYRICS -> ExternalLyricsSettingsPanel(uiSettings)
                            null -> when (selectedCategory) {
                                SettingsCategory.APPEARANCE -> {
                                    AppearanceSettingsPanel(
                                        uiSettings = uiSettings,
                                        onShowCustomAccentDialog = {
                                            overlays = overlays.copy(showCustomAccent = true)
                                        },
                                        onShowCustomMicaDialog = {
                                            overlays = overlays.copy(showCustomMica = true)
                                        },
                                        onOpenWallpaper = { detailPage = SettingsDetailPage.WALLPAPER },
                                        onOpenMiniPlayer = { detailPage = SettingsDetailPage.MINI_PLAYER },
                                    )
                                }

                                SettingsCategory.PLAYBACK -> {
                                    PlaybackSettingsPanel(
                                        uiSettings = uiSettings,
                                        canOpenCustomPlayerLayoutEditor = canOpenCustomPlayerLayoutEditor,
                                        onOpenCustomPlayerLayoutEditor = onOpenCustomPlayerLayoutEditor,
                                        onOpenPlayerInfo = { detailPage = SettingsDetailPage.PLAYER_INFO },
                                    )
                                }

                                SettingsCategory.LYRICS -> {
                                    LyricsSettingsPanel(
                                        uiSettings = uiSettings,
                                        onOpenExternalLyrics = { detailPage = SettingsDetailPage.EXTERNAL_LYRICS },
                                    )
                                }

                                SettingsCategory.LIBRARY -> {
                                    LibraryScanSettingsPanel(
                                        library = library,
                                        excludedDirectories = scanState.excludedDirectories,
                                        minDurationSec = scanState.minDurationSec,
                                        deepProbe = scanState.deepProbe,
                                        artistSplitConfig = artistSplitConfig,
                                        remoteLibrarySidebarEnabled = uiSettings.remoteLibrarySidebarEnabled,
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
                                        onRemoteLibrarySidebarEnabledChange =
                                        uiSettings::updateRemoteLibrarySidebarEnabled,
                                        onOpenRemoteMusic = { detailPage = SettingsDetailPage.REMOTE },
                                    )
                                }

                                SettingsCategory.AUDIO -> {
                                    AudioSettingsPanel(
                                        uiSettings = uiSettings,
                                        library = library,
                                        loudnessScanPort = loudnessScanPort,
                                        onOpenUsbExclusive = { detailPage = SettingsDetailPage.USB },
                                        onOpenSoundFx = onOpenSoundFx,
                                    )
                                }

                                SettingsCategory.DIAGNOSTICS -> {
                                    DiagnosticsSettingsPanel(
                                        songs = library.songs,
                                        hasSongs = library.songs.isNotEmpty(),
                                        audioOffloadState = audioOffloadState,
                                        onAudioOffloadChanged = { enabled ->
                                            AudioOffloadPreferences.setEnabled(context, enabled)
                                            audioOffloadState = AudioOffloadPreferences.state(context)
                                        },
                                        detailedDiagnostics = detailedDiagnostics,
                                        onDetailedDiagnosticsChanged = { updated ->
                                            detailedDiagnostics = updated
                                            DetailedDiagnosticsPreferences.setState(context, updated)
                                            DiagnosticLog.configureDetailedDiagnostics(updated)
                                        },
                                        onOpenMetadataDebug = onOpenMetadataDebug,
                                        onOpenSpatialAudio = onOpenSpatialAudio,
                                        onOpenAppSettings = { openAppSettings(context) },
                                    )
                                }

                                else -> Unit
                            }
                        }
                    }

                    Spacer(Modifier.height(HifiSpacing.lg))

                    Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
                }
            }
        }
    }
}
