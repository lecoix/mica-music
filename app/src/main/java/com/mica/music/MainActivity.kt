package com.mica.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.ui.components.UserMessageHost
import com.mica.music.ui.navigation.AppNavigationMain
import com.mica.music.ui.navigation.PlayerSheetOverlay
import com.mica.music.ui.navigation.AppNavigationCoordinator
import com.mica.music.ui.system.StatusBarController
import com.mica.music.ui.theme.AnimatedMicaAppBackground
import com.mica.music.ui.theme.LocalMicaBlurTarget
import com.mica.music.ui.theme.MicaAppRoot
import eightbitlab.com.blurview.BlurTarget

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var navigationCoordinator: AppNavigationCoordinator

    private companion object {
        const val KEY_PLAYER_EXPANDED = "player_expanded"
        const val KEY_LOCATE_REQUEST = "locate_request"
    }

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

    private fun bindComposeViewOwners(composeView: ComposeView) {
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ScanCacheManager.runStartupCacheCleanup(this)
        applyWindowStatusBar()

        val library = viewModel.library
        val playerController = viewModel.playerController
        val uiSettings = viewModel.uiSettings

        navigationCoordinator = AppNavigationCoordinator(
            playerExpandedState = mutableStateOf(
                savedInstanceState?.getBoolean(KEY_PLAYER_EXPANDED) ?: false,
            ),
            locateState = mutableIntStateOf(
                savedInstanceState?.getInt(KEY_LOCATE_REQUEST) ?: 0,
            ),
        )

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val blurTarget = BlurTarget(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val mainCompose = ComposeView(this)
        val overlayCompose = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            clipChildren = false
            clipToPadding = false
        }

        bindComposeViewOwners(mainCompose)
        bindComposeViewOwners(overlayCompose)

        blurTarget.addView(
            mainCompose,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(blurTarget)
        root.addView(overlayCompose)
        setContentView(root)

        mainCompose.setContent {
            val coordinator = navigationCoordinator
            val snackbarHostState = remember { SnackbarHostState() }

            MicaAppRoot(uiSettings = uiSettings) {
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

                val libraryQueueIds = remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(library.songs) {
                    val songs = library.songs
                    if (songs.isEmpty()) return@LaunchedEffect

                    val previousLibraryIds = libraryQueueIds.value
                    val currentQueueIds = playerController.songQueue.map { it.id }
                    val currentQueueWasLibrary = previousLibraryIds.isNotEmpty() &&
                        currentQueueIds == previousLibraryIds
                    libraryQueueIds.value = songs.map { it.id }

                    if (currentQueueIds.isEmpty() || currentQueueWasLibrary) {
                        playerController.setQueue(songs)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
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
                            AppNavigationMain(
                                coordinator = coordinator,
                                library = library,
                                playerController = playerController,
                                uiSettings = uiSettings,
                            )
                        }
                    }
                }
            }
        }

        overlayCompose.setContent {
            val coordinator = navigationCoordinator
            val overlayFullScreen = coordinator.overlayFullScreen

            SideEffect {
                val lp = overlayCompose.layoutParams as FrameLayout.LayoutParams
                if (overlayFullScreen) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.gravity = Gravity.NO_GRAVITY
                } else {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.BOTTOM
                }
                overlayCompose.layoutParams = lp
            }

            MicaAppRoot(uiSettings = uiSettings) {
                CompositionLocalProvider(LocalMicaBlurTarget provides blurTarget) {
                    PlayerSheetOverlay(
                        coordinator = coordinator,
                        library = library,
                        playerController = playerController,
                        sleepTimer = viewModel.sleepTimer,
                        uiSettings = uiSettings,
                        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::navigationCoordinator.isInitialized) {
            outState.putBoolean(KEY_PLAYER_EXPANDED, navigationCoordinator.playerExpanded)
            outState.putInt(KEY_LOCATE_REQUEST, navigationCoordinator.locateCurrentSongRequest)
        }
    }
}
