package com.ar.pcloud

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ar.pcloud.ui.AudioPlayerScreen
import com.ar.pcloud.ui.FolderBrowserScreen
import com.ar.pcloud.ui.ImageViewerScreen
import com.ar.pcloud.ui.LoginScreen
import com.ar.pcloud.ui.MainViewModel
import com.ar.pcloud.ui.Screen
import com.ar.pcloud.ui.VideoPlayerScreen
import com.ar.pcloud.ui.theme.PCloudColors
import com.ar.pcloud.ui.theme.PcloudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nobody wants the screen dimming halfway through a film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PcloudTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PCloudColors.Background)
                ) {
                    PcloudTvApp()
                }
            }
        }
    }
}

@Composable
fun PcloudTvApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    // Each player screen handles its own Back; this one is the browser's "up a
    // folder" (and "dismiss the error"). It is deliberately disabled at the root
    // with nothing to dismiss, so Back there exits to the TV home screen instead
    // of trapping the user in the app.
    BackHandler(enabled = uiState.canHandleBack) {
        viewModel.navigateBack()
    }

    when (val screen = uiState.currentScreen) {
        Screen.Login -> LoginScreen(
            uiState = uiState,
            onRegionSelected = viewModel::setRegion,
            onLoginCredentials = { user, pass, code ->
                viewModel.loginWithCredentials(user, pass, code)
            },
            onLoginToken = viewModel::loginWithToken
        )

        Screen.Browser -> FolderBrowserScreen(
            uiState = uiState,
            onItemClick = viewModel::onItemSelected,
            onBreadcrumbClick = { viewModel.openFolder(it) },
            onRefreshClick = viewModel::refreshCurrentFolder,
            onToggleListView = viewModel::toggleListView,
            onLogoutClick = viewModel::logout
        )

        is Screen.VideoPlayer -> VideoPlayerScreen(
            item = screen.item,
            link = screen.link,
            subtitles = screen.subtitles,
            onRequestHlsFallback = { onUnavailable ->
                viewModel.retryVideoWithHls(screen.item, onUnavailable)
            },
            onBack = { viewModel.navigateBack() }
        )

        is Screen.AudioPlayer -> AudioPlayerScreen(
            queue = screen.queue,
            index = screen.index,
            link = screen.link,
            isTranscoded = screen.isTranscoded,
            onSkip = viewModel::skipTrack,
            onLoadArt = viewModel::loadAlbumArt,
            onRequestTranscode = { onUnavailable ->
                viewModel.retryAudioWithTranscode(onUnavailable)
            },
            onBack = { viewModel.navigateBack() }
        )

        is Screen.ImageViewer -> ImageViewerScreen(
            items = screen.items,
            initialIndex = screen.index,
            onResolveUrl = viewModel::resolveImage,
            onBack = { viewModel.navigateBack() }
        )
    }
}
