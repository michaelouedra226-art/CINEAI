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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CreationEntity
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
    var activePreviewCreation by remember { mutableStateOf<com.example.data.model.CreationEntity?>(null) }
    var activePreviewFilm by remember { mutableStateOf<com.example.data.model.FilmEntity?>(null) }

    val hasActiveQueue = queueItems.any { it.status in listOf("queued", "processing", "stalled") } || isFilmGenerating

    BackHandler(enabled = currentScreen != AgnesScreen.IMAGES) {
        viewModel.handleBackPress()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0A0A0F),
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
                .background(Color(0xFF0A0A0F))
        ) {
            when (currentScreen) {
                AgnesScreen.IMAGES -> {
                    ImagesScreen(
                        recentCreations = allCreations.filter { it.type == "image" },
                        isGenerating = isImageGenerating,
                        onGenerate = { prompt, style, size, ratio, variations ->
                            viewModel.generateImages(prompt, style, size, ratio, variations)
                        },
                        onSelectRecent = { creation ->
                            activePreviewCreation = creation
                        }
                    )
                }
                AgnesScreen.VIDEOS -> {
                    VideosScreen(
                        queueItems = queueItems,
                        isGenerating = isVideoGenerating,
                        onGenerateVideo = { prompt, mode, startImg, duration, resolution, numFrames ->
                            viewModel.generateVideo(prompt, mode, startImg, duration, resolution, numFrames)
                        }
                    )
                }
                AgnesScreen.FILM -> {
                    FilmScreen(
                        currentFilm = allFilms.firstOrNull(),
                        recentFilms = allFilms,
                        isGenerating = isFilmGenerating,
                        generationProgress = filmProgress,
                        currentStepText = filmStepText,
                        elapsedSeconds = filmElapsedSeconds,
                        currentScenes = currentFilmScenes,
                        onStartNewFilm = { title, prompt, style, requestedDurationSeconds, manualScenes, startImage, initialScenes ->
                            viewModel.startNewFilm(title, prompt, style, requestedDurationSeconds, manualScenes, startImage, initialScenes)
                        },
                        onCancelGeneration = {
                            viewModel.cancelFilmGeneration()
                        },
                        onPrepareDrafts = { prompt, style, numScenes, onReady ->
                            viewModel.generateScriptDrafts(
                                prompt = prompt,
                                style = style,
                                numScenes = numScenes,
                                onSuccess = { draftTitle, scenes ->
                                    onReady(draftTitle, scenes)
                                },
                                onError = {
                                    // Géré via toast
                                }
                            )
                        },
                        onSelectFilm = { film ->
                            activePreviewFilm = film
                        },
                        onResumeFilm = { id ->
                            viewModel.resumeFilm(id)
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
                        onToggleFilmFavorite = { id, isFav ->
                            viewModel.toggleFilmFavorite(id, isFav)
                        },
                        onDeleteCreation = { id ->
                            viewModel.deleteCreation(id)
                        },
                        onDeleteFilm = { id ->
                            viewModel.deleteFilm(id)
                        },
                        onResumeFilm = { id ->
                            viewModel.resumeFilm(id)
                            viewModel.navigateTo(AgnesScreen.FILM)
                        },
                        onRestartFilm = { id ->
                            viewModel.resumeFilm(id)
                            viewModel.navigateTo(AgnesScreen.FILM)
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
                        onDeleteApiKey = { viewModel.deleteApiKey() },
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

            // Visualiseur de média récent
            if (activePreviewCreation != null) {
                com.example.ui.components.MediaViewerModal(
                    creation = activePreviewCreation,
                    film = null,
                    onDismiss = { activePreviewCreation = null },
                    onToggleFavorite = { id, isFav ->
                        viewModel.toggleFavorite(id, isFav)
                        activePreviewCreation = activePreviewCreation?.copy(favorite = isFav)
                    },
                    onDeleteCreation = { id ->
                        viewModel.deleteCreation(id)
                        activePreviewCreation = null
                    }
                )
            }

            // Visualiseur de projet film
            if (activePreviewFilm != null) {
                com.example.ui.components.MediaViewerModal(
                    creation = null,
                    film = activePreviewFilm,
                    onDismiss = { activePreviewFilm = null },
                    onToggleFilmFavorite = { id, isFav ->
                        viewModel.toggleFilmFavorite(id, isFav)
                        activePreviewFilm = activePreviewFilm?.copy(favorite = isFav)
                    },
                    onDeleteFilm = { id ->
                        viewModel.deleteFilm(id)
                        activePreviewFilm = null
                    },
                    onResumeFilm = { id ->
                        viewModel.resumeFilm(id)
                        activePreviewFilm = null
                    }
                )
            }
        }
    }
}
