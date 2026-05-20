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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.mica.music.ui.components.UserMessageHost
import com.mica.music.ui.navigation.AppNavigation
import com.mica.music.ui.system.StatusBarEffect
import com.mica.music.ui.theme.MicaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val library = viewModel.library
        val playerController = viewModel.playerController
        val uiSettings = viewModel.uiSettings

        setContent {
            val darkTheme = uiSettings.isDarkTheme()
            MicaTheme(darkTheme = darkTheme) {
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

                LaunchedEffect(library.songs, library.hasScanned) {
                    if (library.hasScanned || library.songs.isNotEmpty()) {
                        playerController.setQueue(library.songs)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // 仅处理导航栏；状态栏 inset 由主页在「隐藏状态栏」时自行预留
                    contentWindowInsets = WindowInsets.navigationBars,
                    snackbarHost = {
                        UserMessageHost(
                            playerController = playerController,
                            snackbarHostState = snackbarHostState,
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
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
