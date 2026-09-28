package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AgnesBottomBar
import com.example.ui.components.AgnesHeader
import com.example.ui.components.AgnesScreen
import com.example.ui.components.AgnesTopToast
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.FilmScreen
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.ImagesScreen
import com.example.ui.screens.LogViewerModal
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VideosScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                AgnesStudioApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun AgnesStudioApp(viewModel: MainViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val allCreations by viewModel.allCreations.collectAsStateWithLifecycle()
    val allFilms by viewModel.allFilms.collectAsStateWithLifecycle()
    val queueItems by viewModel.queueItems.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val todayUsage by viewModel.todayUsage.collectAsStateWithLifecycle()
    val technicalLogs by viewModel.technicalLogs.collectAsStateWithLifecycle()

    val isImageGenerating by viewModel.isImageGenerating.collectAsStateWithLifecycle()
    val isVideoGenerating by viewModel.isVideoGenerating.collectAsStateWithLifecycle()
    val isFilmGenerating by viewModel.isFilmGenerating.collectAsStateWithLifecycle()
    val filmProgress by viewModel.filmProgress.collectAsStateWithLifecycle()
    val filmStepText by viewModel.filmStepText.collectAsStateWithLifecycle()
    val filmElapsedSeconds by viewModel.filmElapsedSeconds.collectAsStateWithLifecycle()
    val currentFilmScenes by viewModel.currentFilmScenes.collectAsStateWithLifecycle()

    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isSendingChat by viewModel.isSendingChat.collectAsStateWithLifecycle()

    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val hasGalleryBadge by viewModel.hasGalleryBadge.collectAsStateWithLifecycle()
    val showLogsModal by viewModel.showLogsModal.collectAsStateWithLifecycle()

    val hasActiveQueue = queueItems.any { it.status in listOf("queued", "processing", "stalled") } || isFilmGenerating

    BackHandler(enabled = currentScreen != AgnesScreen.IMAGES) {
        viewModel.handleBackPress()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0C0C11),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AgnesHeader(
                title = currentScreen.title,
                canNavigateBack = currentScreen != AgnesScreen.IMAGES,
                onBackClick = { viewModel.handleBackPress() },
                onSettingsClick = { viewModel.navigateTo(AgnesScreen.SETTINGS) },
                onLogsClick = { viewModel.showLogs(true) },
                hasActiveQueue = hasActiveQueue
            )
        },
        bottomBar = {
            AgnesBottomBar(
                currentScreen = currentScreen,
                onScreenSelected = { viewModel.navigateTo(it) },
                hasGalleryNewItems = hasGalleryBadge
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0C0C11))
        ) {
            when (currentScreen) {
                AgnesScreen.IMAGES -> {
                    ImagesScreen(
                        recentCreations = allCreations.filter { it.type == "image" },
                        isGenerating = isImageGenerating,
                        onGenerate = { prompt, style, size, ratio, variations ->
                            viewModel.generateImages(prompt, style, size, ratio, variations)
                        },
                        onSelectRecent = {
                            viewModel.navigateTo(AgnesScreen.GALLERY)
                        }
                    )
                }
                AgnesScreen.VIDEOS -> {
                    VideosScreen(
                        queueItems = queueItems,
                        isGenerating = isVideoGenerating,
                        onGenerateVideo = { prompt, mode, startImg, duration, resolution ->
                            viewModel.generateVideo(prompt, mode, startImg, duration, resolution)
                        }
                    )
                }
                AgnesScreen.FILM -> {
                    FilmScreen(
                        currentFilm = allFilms.firstOrNull(),
                        isGenerating = isFilmGenerating,
                        generationProgress = filmProgress,
                        currentStepText = filmStepText,
                        elapsedSeconds = filmElapsedSeconds,
                        currentScenes = currentFilmScenes,
                        onStartNewFilm = { title, prompt, style, numScenes ->
                            viewModel.startNewFilm(title, prompt, style, numScenes)
                        },
                        onCancelGeneration = {
                            viewModel.cancelFilmGeneration()
                        }
                    )
                }
                AgnesScreen.GALLERY -> {
                    GalleryScreen(
                        creations = allCreations,
                        films = allFilms,
                        onToggleFavorite = { id, isFav ->
                            viewModel.toggleFavorite(id, isFav)
                        },
                        onDeleteCreation = { id ->
                            viewModel.deleteCreation(id)
                        },
                        onDeleteFilm = { id ->
                            viewModel.deleteFilm(id)
                        }
                    )
                }
                AgnesScreen.CHAT -> {
                    ChatScreen(
                        messages = chatMessages,
                        isSending = isSendingChat,
                        onSendMessage = { viewModel.sendChatMessage(it) },
                        onSendToImage = { prompt ->
                            viewModel.sendPromptToImages(prompt)
                        },
                        onSendToVideo = { prompt ->
                            viewModel.sendPromptToVideos(prompt)
                        }
                    )
                }
                AgnesScreen.SETTINGS -> {
                    SettingsScreen(
                        settings = settings,
                        todayUsage = todayUsage,
                        onSaveSettings = { viewModel.saveSettings(it) },
                        onOpenLogs = { viewModel.showLogs(true) }
                    )
                }
            }

            // Toast glissant depuis le haut
            AgnesTopToast(
                message = toastMessage ?: "",
                visible = toastMessage != null,
                onDismiss = { viewModel.dismissToast() },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Modal des logs techniques
            if (showLogsModal) {
                LogViewerModal(
                    logs = technicalLogs,
                    onClose = { viewModel.showLogs(false) },
                    onClear = { viewModel.clearLogs() }
                )
            }
        }
    }
}
