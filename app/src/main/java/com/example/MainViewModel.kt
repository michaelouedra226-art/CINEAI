package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.ApiClient
import com.example.api.ApiResponse
import com.example.api.RateLimiter
import com.example.api.TechLog
import com.example.api.TechnicalLogManager
import com.example.api.UsageTracker
import com.example.data.database.AgnesDatabase
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.QueueItemEntity
import com.example.data.model.SceneItem
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity
import com.example.data.repository.AgnesRepository
import com.example.ui.components.AgnesScreen
import com.example.ui.screens.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AgnesDatabase.getInstance(application)
    private val rateLimiter = RateLimiter()
    private val usageTracker = UsageTracker(database.usageDao())
    private val apiClient = ApiClient(rateLimiter, usageTracker)

    val repository = AgnesRepository(
        creationDao = database.creationDao(),
        filmDao = database.filmDao(),
        usageDao = database.usageDao(),
        settingsDao = database.settingsDao(),
        queueDao = database.queueDao(),
        apiClient = apiClient,
        rateLimiter = rateLimiter,
        usageTracker = usageTracker
    )

    // Écran actuel
    private val _currentScreen = MutableStateFlow(AgnesScreen.IMAGES)
    val currentScreen: StateFlow<AgnesScreen> = _currentScreen.asStateFlow()

    private val screenBackStack = mutableListOf(AgnesScreen.IMAGES)

    // Données réactives Room
    val allCreations: StateFlow<List<CreationEntity>> = repository.allCreations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFilms: StateFlow<List<FilmEntity>> = repository.allFilms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueItems: StateFlow<List<QueueItemEntity>> = repository.queueItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<SettingsEntity> = repository.settings
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsEntity())

    val todayUsage: StateFlow<UsageEntity?> = repository.getTodayUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val technicalLogs: StateFlow<List<TechLog>> = TechnicalLogManager.logs

    // États de génération
    private val _isImageGenerating = MutableStateFlow(false)
    val isImageGenerating: StateFlow<Boolean> = _isImageGenerating.asStateFlow()

    private val _isVideoGenerating = MutableStateFlow(false)
    val isVideoGenerating: StateFlow<Boolean> = _isVideoGenerating.asStateFlow()

    private val _isFilmGenerating = MutableStateFlow(false)
    val isFilmGenerating: StateFlow<Boolean> = _isFilmGenerating.asStateFlow()

    private val _filmProgress = MutableStateFlow(0)
    val filmProgress: StateFlow<Int> = _filmProgress.asStateFlow()

    private val _filmStepText = MutableStateFlow("Initialisation...")
    val filmStepText: StateFlow<String> = _filmStepText.asStateFlow()

    private val _filmElapsedSeconds = MutableStateFlow(0)
    val filmElapsedSeconds: StateFlow<Int> = _filmElapsedSeconds.asStateFlow()

    private val _currentFilmScenes = MutableStateFlow<List<SceneItem>>(emptyList())
    val currentFilmScenes: StateFlow<List<SceneItem>> = _currentFilmScenes.asStateFlow()

    private var filmJob: Job? = null
    private var filmTimerJob: Job? = null

    // Chat
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = "agnes",
                text = "Bienvenue dans Agnes Studio. Je suis votre directrice artistique IA. Décrivez-moi une scène ou un concept cinématographique, et je structurerai vos plans et prompts."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isSendingChat = MutableStateFlow(false)
    val isSendingChat: StateFlow<Boolean> = _isSendingChat.asStateFlow()

    // Toast notification
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _hasGalleryBadge = MutableStateFlow(false)
    val hasGalleryBadge: StateFlow<Boolean> = _hasGalleryBadge.asStateFlow()

    // Modal Logs
    private val _showLogsModal = MutableStateFlow(false)
    val showLogsModal: StateFlow<Boolean> = _showLogsModal.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initDefaultSettingsIfEmpty()
        }
    }

    fun navigateTo(screen: AgnesScreen) {
        if (_currentScreen.value != screen) {
            screenBackStack.add(screen)
            _currentScreen.value = screen
            if (screen == AgnesScreen.GALLERY) {
                _hasGalleryBadge.value = false
            }
        }
    }

    fun handleBackPress(): Boolean {
        if (screenBackStack.size > 1) {
            screenBackStack.removeAt(screenBackStack.size - 1)
            _currentScreen.value = screenBackStack.last()
            return true
        }
        return false
    }

    fun showLogs(show: Boolean) {
        _showLogsModal.value = show
    }

    fun clearLogs() {
        TechnicalLogManager.clear()
    }

    fun dismissToast() {
        _toastMessage.value = null
    }

    fun saveSettings(updatedSettings: SettingsEntity) {
        viewModelScope.launch {
            repository.updateSettings(updatedSettings)
            _toastMessage.value = "Réglages enregistrés"
        }
    }

    fun toggleFavorite(id: String, isFav: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(id, isFav)
        }
    }

    fun deleteCreation(id: String) {
        viewModelScope.launch {
            repository.deleteCreation(id)
            _toastMessage.value = "Élément supprimé"
        }
    }

    fun deleteFilm(id: String) {
        viewModelScope.launch {
            repository.deleteFilm(id)
            _toastMessage.value = "Film supprimé"
        }
    }

    /**
     * Génération d'images
     */
    fun generateImages(
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int
    ) {
        if (_isImageGenerating.value) return
        _isImageGenerating.value = true

        viewModelScope.launch {
            repository.createAndGenerateImages(
                prompt = prompt,
                style = style,
                size = size,
                ratio = ratio,
                variations = variations
            ) { success ->
                _isImageGenerating.value = false
                if (success) {
                    _toastMessage.value = "Nouvelle image prête dans la Galerie"
                    _hasGalleryBadge.value = true
                } else {
                    _toastMessage.value = "Échec de génération d'image"
                }
            }
        }
    }

    /**
     * Génération vidéo
     */
    fun generateVideo(
        prompt: String,
        mode: String,
        startImg: String?,
        durationSeconds: Int,
        resolution: String
    ) {
        if (_isVideoGenerating.value) return
        _isVideoGenerating.value = true

        viewModelScope.launch {
            repository.createAndGenerateVideo(
                prompt = prompt,
                startImageUrl = startImg,
                durationSeconds = durationSeconds,
                resolution = resolution
            ) { success ->
                _isVideoGenerating.value = false
                if (success) {
                    _toastMessage.value = "Vidéo prête dans la Galerie"
                    _hasGalleryBadge.value = true
                } else {
                    _toastMessage.value = "Échec ou interruption du rendu vidéo"
                }
            }
        }
    }

    /**
     * Génération d'un film complet
     */
    fun startNewFilm(title: String, prompt: String, style: String, numScenes: Int) {
        if (_isFilmGenerating.value) return
        _isFilmGenerating.value = true
        _filmProgress.value = 10
        _filmStepText.value = "Préparation du script..."
        _filmElapsedSeconds.value = 0
        _currentFilmScenes.value = emptyList()

        // Démarre le chronomètre
        filmTimerJob?.cancel()
        filmTimerJob = viewModelScope.launch {
            while (isActive && _isFilmGenerating.value) {
                delay(1000)
                _filmElapsedSeconds.value += 1
            }
        }

        val filmId = "film_" + UUID.randomUUID().toString().replace("-", "").take(10)
        filmJob = viewModelScope.launch {
            try {
                repository.createAndGenerateFilm(
                    filmId = filmId,
                    title = title,
                    logline = prompt,
                    prompt = prompt,
                    filmStyle = style,
                    numScenes = numScenes,
                    startImage = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800",
                    onSceneUpdate = { scenes, progress, stepText ->
                        _currentFilmScenes.value = scenes
                        _filmProgress.value = progress
                        _filmStepText.value = stepText
                    }
                )
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = "Production terminée ! Film disponible en Galerie"
                _hasGalleryBadge.value = true
            } catch (_: Exception) {
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = "Génération du film interrompue"
            }
        }
    }

    fun cancelFilmGeneration() {
        filmJob?.cancel()
        filmTimerJob?.cancel()
        _isFilmGenerating.value = false
        _toastMessage.value = "Génération du film annulée"
    }

    /**
     * Chat
     */
    fun sendChatMessage(text: String) {
        if (text.isBlank() || _isSendingChat.value) return
        val userMsg = ChatMessage(sender = "user", text = text)
        _chatMessages.value = _chatMessages.value + userMsg
        _isSendingChat.value = true

        viewModelScope.launch {
            val settings = settings.value
            when (val res = apiClient.sendChatMessage(settings.apiKey, settings.defaultTextModel, text)) {
                is ApiResponse.Success -> {
                    val reply = ChatMessage(sender = "agnes", text = res.data)
                    _chatMessages.value = _chatMessages.value + reply
                }
                else -> {
                    val reply = ChatMessage(sender = "agnes", text = "Désolé, une erreur est survenue lors de la communication.")
                    _chatMessages.value = _chatMessages.value + reply
                }
            }
            _isSendingChat.value = false
        }
    }

    fun sendPromptToImages(prompt: String) {
        navigateTo(AgnesScreen.IMAGES)
    }

    fun sendPromptToVideos(prompt: String) {
        navigateTo(AgnesScreen.VIDEOS)
    }
}
