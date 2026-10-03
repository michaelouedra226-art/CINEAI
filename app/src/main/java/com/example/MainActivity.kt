package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.util.DownloadHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.example.util.AgnesVoiceManager.init(applicationContext)

        setContent {
            MyApplicationTheme {
                AgnesStudioApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun AgnesStudioApp(viewModel: MainViewModel) {
    val context = LocalContext.current
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
    val isStitchingFilm by viewModel.isStitchingFilm.collectAsStateWithLifecycle()
    val stitchProgress by viewModel.stitchProgress.collectAsStateWithLifecycle()
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
                        recentVideos = allCreations.filter { it.type == "video" },
                        onSelectCreation = { creation ->
                            activePreviewCreation = creation
                        },
                        onGenerateVideo = { prompt, mode, startImg, duration, resolution, numFrames ->
                            viewModel.generateVideo(prompt, mode, startImg, duration, resolution, numFrames)
                        }
                    )
                }
                AgnesScreen.FILM -> {
                    FilmScreen(
                        currentFilm = allFilms.firstOrNull(),
                        recentFilms = allFilms,
                        availableCreations = allCreations,
                        isGenerating = isFilmGenerating,
                        generationProgress = filmProgress,
                        currentStepText = filmStepText,
                        elapsedSeconds = filmElapsedSeconds,
                        currentScenes = currentFilmScenes,
                        onStartNewFilm = { title, prompt, style, requestedDurationSeconds, manualScenes, startImage, initialScenes, dialogueLanguage, audioPresence ->
                            viewModel.startNewFilm(
                                title = title,
                                prompt = prompt,
                                style = style,
                                requestedDurationSeconds = requestedDurationSeconds,
                                manualScenes = manualScenes,
                                startImage = startImage,
                                initialScenes = initialScenes,
                                dialogueLanguage = dialogueLanguage,
                                audioPresence = audioPresence
                            )
                        },
                        onCancelGeneration = {
                            viewModel.cancelFilmGeneration()
                        },
                        onPrepareDrafts = { prompt, style, numScenes, dialogueLanguage, audioPresence, onReady ->
                            viewModel.generateScriptDrafts(
                                prompt = prompt,
                                style = style,
                                numScenes = numScenes,
                                dialogueLanguage = dialogueLanguage,
                                audioPresence = audioPresence,
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
                        },
                        onReshootScene = { filmId, sceneNumber, action, diag, cam ->
                            viewModel.reshootScene(filmId, sceneNumber, action, diag, cam)
                        },
                        onExportStitchedFilm = { filmToExport ->
                            viewModel.stitchAndExportFilm(context, filmToExport) { file ->
                                if (file != null && file.exists()) {
                                    DownloadHelper.shareFile(context, file, filmToExport.title)
                                }
                            }
                        },
                        onGenerateCastingPortrait = { bible, style, onResult ->
                            viewModel.generateCastingPortrait(bible, style, onResult)
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

            // Visualiseur de projet film complet Studio (Axes 1, 2, 3, 5)
            val currentActiveFilm = allFilms.firstOrNull { it.id == activePreviewFilm?.id } ?: activePreviewFilm
            if (currentActiveFilm != null) {
                com.example.ui.components.MediaViewerModal(
                    creation = null,
                    film = currentActiveFilm,
                    onDismiss = { activePreviewFilm = null },
                    onToggleFilmFavorite = { id, isFav ->
                        viewModel.toggleFilmFavorite(id, isFav)
                    },
                    onDeleteFilm = { id ->
                        viewModel.deleteFilm(id)
                        activePreviewFilm = null
                    },
                    onResumeFilm = { id ->
                        viewModel.resumeFilm(id)
                        activePreviewFilm = null
                    },
                    onRestartFilm = { id ->
                        viewModel.restartFilm(id)
                        activePreviewFilm = null
                    },
                    onReshootScene = { filmId, sceneNumber, action, diag, cam ->
                        viewModel.reshootScene(filmId, sceneNumber, action, diag, cam)
                    },
                    onExportStitchedFilm = { filmToExport ->
                        viewModel.stitchAndExportFilm(context, filmToExport) { file ->
                            if (file != null && file.exists()) {
                                DownloadHelper.shareFile(context, file, filmToExport.title)
                            }
                        }
                    },
                    onGenerateCastingPortrait = { bible, style, onResult ->
                        viewModel.generateCastingPortrait(bible, style, onResult)
                    }
                )
            }

            // Modal de progression de l'assemblage MP4 (Post-production studio - Axe 1)
            if (isStitchingFilm) {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Text("Post-Production Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Assemblage et encodage du film MP4 complet...",
                                color = Color(0xFFBBBBD0),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { stitchProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$stitchProgress%",
                                color = Color(0xFF34D399),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    confirmButton = {},
                    containerColor = Color(0xFF13131A),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}
