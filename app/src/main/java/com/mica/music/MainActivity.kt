package com.mica.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.data.scanner.ScanCacheManager
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.mica.music.ui.components.UserMessageHost
import com.mica.music.ui.navigation.AppNavigation
import com.mica.music.ui.system.StatusBarController
import com.mica.music.ui.system.StatusBarEffect
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberReduceMotion
import com.mica.music.ui.theme.AnimatedMicaAppBackground
import com.mica.music.ui.theme.MicaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        applyWindowStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindowStatusBar()
    }

    private fun applyWindowStatusBar() {
        applyEdgeToEdgeSystemBars()
        StatusBarController.applyFromPreferences(this, window)
    }

    private fun applyEdgeToEdgeSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ScanCacheManager.runStartupCacheCleanup(this)
        applyWindowStatusBar()

        val library = viewModel.library
        val playerController = viewModel.playerController
        val uiSettings = viewModel.uiSettings

        setContent {
            val darkTheme = uiSettings.isDarkTheme()
            val reduceMotion = rememberReduceMotion()
            CompositionLocalProvider(MicaMotion.LocalEnabled provides !reduceMotion) {
            MicaTheme(
                darkTheme = darkTheme,
                accentColor = uiSettings.accentColor,
                micaBackgroundPreset = uiSettings.micaBackgroundPreset,
                coverDisplayMode = uiSettings.coverDisplayMode,
                lyricSplitEnabled = uiSettings.lyricSplitEnabled,
            ) {
                StatusBarEffect(
                    hideStatusBar = uiSettings.hideStatusBar,
                    darkTheme = darkTheme,
                )
                val snackbarHostState = remember { SnackbarHostState() }

                val postNotificationsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    playerController.connectIfNeeded()
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@LaunchedEffect
                        }
                    }
                    playerController.connectIfNeeded()
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, playerController) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            playerController.persistPlaybackSessionNow()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(library.songs) {
                    if (library.songs.isEmpty()) return@LaunchedEffect
                    playerController.setQueue(library.songs)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    // 仅处理导航栏；状态栏 inset 由主页在「隐藏状态栏」时自行预留
                    contentWindowInsets = WindowInsets.navigationBars,
                    snackbarHost = {
                        UserMessageHost(
                            playerController = playerController,
                            snackbarHostState = snackbarHostState,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AnimatedMicaAppBackground(Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize()) {
                            AppNavigation(
                                library = library,
                                playerController = playerController,
                                uiSettings = uiSettings,
                                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
