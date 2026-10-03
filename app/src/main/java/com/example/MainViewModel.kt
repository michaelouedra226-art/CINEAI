package com.example

import android.app.Application
import android.content.Context
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
import com.example.util.OfflineVideoManager
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.File
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
        usageTracker = usageTracker,
        context = application
    )

    // Écran actif
    private val _currentScreen = MutableStateFlow(AgnesScreen.IMAGES)
    val currentScreen: StateFlow<AgnesScreen> = _currentScreen.asStateFlow()

    private val screenBackStack = mutableListOf(AgnesScreen.IMAGES)

    // Room StateFlows
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

    private var filmStopRequested = false
    private var filmJob: Job? = null
    private var filmTimerJob: Job? = null

    // Chat
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = "agnes",
                text = "Agnes Studio connecté au moteur d'IA générative. Renseignez votre clé API dans les Réglages pour démarrer."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isSendingChat = MutableStateFlow(false)
    val isSendingChat: StateFlow<Boolean> = _isSendingChat.asStateFlow()

    // Notification toast & modal
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _hasGalleryBadge = MutableStateFlow(false)
    val hasGalleryBadge: StateFlow<Boolean> = _hasGalleryBadge.asStateFlow()

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

    fun selectTab(screen: AgnesScreen) {
        screenBackStack.clear()
        screenBackStack.add(screen)
        _currentScreen.value = screen
        if (screen == AgnesScreen.GALLERY) {
            _hasGalleryBadge.value = false
        }
    }

    fun handleBackPress(): Boolean {
        if (screenBackStack.size > 1) {
            screenBackStack.removeAt(screenBackStack.size - 1)
            _currentScreen.value = screenBackStack.last()
            return true
        }
        if (_currentScreen.value != AgnesScreen.IMAGES) {
            selectTab(AgnesScreen.IMAGES)
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

    fun deleteApiKey() {
        viewModelScope.launch {
            repository.deleteSettingsKey()
            _toastMessage.value = "Clé API supprimée"
        }
    }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(id, favorite)
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

    fun generateImages(
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int
    ) {
        if (_isImageGenerating.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        _isImageGenerating.value = true

        viewModelScope.launch {
            repository.createAndGenerateImages(
                prompt = prompt,
                style = style,
                size = size,
                ratio = ratio,
                variations = variations
            ) { success, errorMsg ->
                _isImageGenerating.value = false
                if (success) {
                    _toastMessage.value = "Image générée avec succès"
                    _hasGalleryBadge.value = true
                } else {
                    _toastMessage.value = errorMsg ?: "Échec de génération d'image"
                }
            }
        }
    }

    fun generateVideo(
        prompt: String,
        mode: String,
        startImg: String?,
        durationSeconds: Int,
        resolution: String,
        numFrames: Int = 121
    ) {
        if (_isVideoGenerating.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        _isVideoGenerating.value = true

        viewModelScope.launch {
            repository.createAndGenerateVideo(
                prompt = prompt,
                startImageUrl = startImg,
                durationSeconds = durationSeconds,
                resolution = resolution,
                numFrames = numFrames
            ) { success, errorMsg ->
                _isVideoGenerating.value = false
                if (success) {
                    _toastMessage.value = "Vidéo générée avec succès"
                    _hasGalleryBadge.value = true
                } else {
                    _toastMessage.value = errorMsg ?: "Échec de la génération vidéo"
                }
            }
        }
    }

    /**
     * Démarrage d'un nouveau film studio réel
     */
    fun startNewFilm(
        title: String,
        prompt: String,
        style: String,
        requestedDurationSeconds: Double,
        manualScenes: Int?,
        startImage: String = "",
        initialScenes: List<SceneItem>? = null,
        dialogueLanguage: String = "fr",
        audioPresence: String = "dialogue"
    ) {
        if (_isFilmGenerating.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        _isFilmGenerating.value = true
        filmStopRequested = false
        _filmProgress.value = 5
        _filmStepText.value = "Initialisation du pipeline..."
        _filmElapsedSeconds.value = 0
        _currentFilmScenes.value = initialScenes ?: emptyList()

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
                    prompt = prompt,
                    filmStyle = style,
                    requestedDuration = requestedDurationSeconds,
                    manualScenes = manualScenes,
                    startImage = startImage,
                    initialScenes = initialScenes,
                    dialogueLanguage = dialogueLanguage,
                    audioPresence = audioPresence,
                    stopRequested = { filmStopRequested },
                    onSceneUpdate = { scenes, progress, stepText ->
                        _currentFilmScenes.value = scenes
                        _filmProgress.value = progress
                        _filmStepText.value = stepText
                    }
                )
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = "Film achevé avec succès"
                _hasGalleryBadge.value = true
            } catch (e: Exception) {
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = if (filmStopRequested) "Production annulée" else (e.message ?: "Génération interrompue")
            }
        }
    }

    fun generateScriptDrafts(
        prompt: String,
        style: String,
        numScenes: Int,
        dialogueLanguage: String = "fr",
        audioPresence: String = "dialogue",
        onSuccess: (String, List<SceneItem>) -> Unit,
        onError: (String) -> Unit
    ) {
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }
        viewModelScope.launch {
            try {
                val (draftTitle, scenes) = repository.generateScriptDrafts(prompt, style, numScenes, dialogueLanguage, audioPresence)
                onSuccess(draftTitle, scenes)
            } catch (e: Exception) {
                onError(e.message ?: "Erreur de découpage")
            }
        }
    }

    fun resumeFilm(filmId: String) {
        if (_isFilmGenerating.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        navigateTo(AgnesScreen.FILM)
        _isFilmGenerating.value = true
        filmStopRequested = false
        _filmProgress.value = 20
        _filmStepText.value = "Reprise de la production..."
        _filmElapsedSeconds.value = 0

        // Préchargement immédiat de l'état des scènes
        viewModelScope.launch {
            val film = repository.getFilmDirect(filmId)
            if (film != null) {
                val scs = SceneItem.parseList(film.scenesJson)
                _currentFilmScenes.value = scs
                val doneCount = scs.count { it.status == "done" && !it.videoUrl.isNullOrBlank() }
                val pct = if (scs.isNotEmpty()) (doneCount * 100 / scs.size).coerceAtLeast(20) else 20
                _filmProgress.value = pct
                _filmStepText.value = "Reprise à partir du plan ${doneCount + 1}/${scs.size}..."
            }
        }

        filmTimerJob?.cancel()
        filmTimerJob = viewModelScope.launch {
            while (isActive && _isFilmGenerating.value) {
                delay(1000)
                _filmElapsedSeconds.value += 1
            }
        }

        filmJob = viewModelScope.launch {
            try {
                repository.resumeFilm(
                    filmId = filmId,
                    stopRequested = { filmStopRequested },
                    onSceneUpdate = { scenes, progress, stepText ->
                        _currentFilmScenes.value = scenes
                        _filmProgress.value = progress
                        _filmStepText.value = stepText
                    }
                )
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = "Production du film reprise et finalisée"
                _hasGalleryBadge.value = true
            } catch (e: Exception) {
                _isFilmGenerating.value = false
                filmTimerJob?.cancel()
                _toastMessage.value = if (filmStopRequested) "Reprise interrompue" else (e.message ?: "Échec de reprise")
            }
        }
    }

    fun restartFilm(filmId: String) {
        if (_isFilmGenerating.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        viewModelScope.launch {
            _toastMessage.value = "Réinitialisation et re-lancement de la production..."
            repository.resetAndRestartFilm(filmId)
            resumeFilm(filmId)
        }
    }

    fun toggleFilmFavorite(filmId: String, favorite: Boolean) {
        viewModelScope.launch {
            repository.updateFilmFavorite(filmId, favorite)
        }
    }

    fun cancelFilmGeneration() {
        filmStopRequested = true
        filmJob?.cancel()
        filmTimerJob?.cancel()
        _isFilmGenerating.value = false
        _toastMessage.value = "Génération du film interrompue"
    }

    fun sendChatMessage(text: String) {
        if (text.isBlank() || _isSendingChat.value) return
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            navigateTo(AgnesScreen.SETTINGS)
            return
        }

        val userMsg = ChatMessage(sender = "user", text = text)
        _chatMessages.value = _chatMessages.value + userMsg
        _isSendingChat.value = true

        viewModelScope.launch {
            when (val res = apiClient.sendChatMessage(currentKey, text)) {
                is ApiResponse.Success -> {
                    _chatMessages.value = _chatMessages.value + ChatMessage(sender = "agnes", text = res.data)
                }
                is ApiResponse.Error -> {
                    _chatMessages.value = _chatMessages.value + ChatMessage(sender = "agnes", text = res.message)
                }
                else -> {
                    _chatMessages.value = _chatMessages.value + ChatMessage(sender = "agnes", text = "Erreur de communication avec agnes-2.5-flash.")
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

    // ─── AXE 2 : RESHOOT D'UNE SCÈNE ISOLÉE ───
    private val _isReshootingScene = MutableStateFlow(false)
    val isReshootingScene: StateFlow<Boolean> = _isReshootingScene.asStateFlow()

    private val _reshootingSceneNumber = MutableStateFlow<Int?>(null)
    val reshootingSceneNumber: StateFlow<Int?> = _reshootingSceneNumber.asStateFlow()

    fun reshootScene(
        filmId: String,
        sceneNumber: Int,
        updatedAction: String? = null,
        updatedDialogue: String? = null,
        updatedCamera: String? = null,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val currentKey = settings.value.apiKey
        if (currentKey.isBlank()) {
            _toastMessage.value = "Veuillez configurer votre clé API dans les Réglages."
            onDone?.invoke(false)
            return
        }
        _isReshootingScene.value = true
        _reshootingSceneNumber.value = sceneNumber
        _toastMessage.value = "Re-tournage de la scène $sceneNumber démarré..."

        viewModelScope.launch {
            try {
                repository.reshootScene(
                    filmId = filmId,
                    sceneNumber = sceneNumber,
                    updatedAction = updatedAction,
                    updatedDialogue = updatedDialogue,
                    updatedCamera = updatedCamera,
                    onSceneUpdate = { scenes, _, stepText ->
                        _currentFilmScenes.value = scenes
                        _filmStepText.value = stepText
                    }
                )
                _toastMessage.value = "Scène $sceneNumber re-tournée avec succès !"
                onDone?.invoke(true)
            } catch (e: Exception) {
                _toastMessage.value = "Échec du reshoot: ${e.message}"
                onDone?.invoke(false)
            } finally {
                _isReshootingScene.value = false
                _reshootingSceneNumber.value = null
            }
        }
    }

    // ─── AXE 3 : CASTING & LOOKBOOK PORTRAITS ───
    fun generateCastingPortrait(
        characterBible: String,
        filmStyle: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val url = repository.generateCastingPortrait(characterBible, filmStyle)
                onResult(url)
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }

    // ─── AXE 1 : EXPORT MP4 STITCHING DU FILM COMPLET ───
    private val _isStitchingFilm = MutableStateFlow(false)
    val isStitchingFilm: StateFlow<Boolean> = _isStitchingFilm.asStateFlow()

    private val _stitchProgress = MutableStateFlow(0)
    val stitchProgress: StateFlow<Int> = _stitchProgress.asStateFlow()

    fun stitchAndExportFilm(
        context: Context,
        film: FilmEntity,
        onComplete: (File?) -> Unit
    ) {
        val scenes = SceneItem.parseList(film.scenesJson)
        val videoUrls = scenes.mapNotNull { it.videoUrl }.filter { it.isNotBlank() }
        if (videoUrls.isEmpty()) {
            _toastMessage.value = "Aucun plan vidéo terminé à assembler."
            onComplete(null)
            return
        }

        _isStitchingFilm.value = true
        _stitchProgress.value = 5
        _toastMessage.value = "Assemblage du film complet en cours..."

        viewModelScope.launch {
            try {
                val resultFile = OfflineVideoManager.stitchFilmScenes(
                    context = context,
                    filmTitle = film.title,
                    sceneVideoUrls = videoUrls,
                    onProgress = { pct -> _stitchProgress.value = pct }
                )
                if (resultFile != null && resultFile.exists()) {
                    _toastMessage.value = "Film MP4 unifié prêt : ${resultFile.name}"
                    onComplete(resultFile)
                } else {
                    _toastMessage.value = "Échec de l'assemblage vidéo"
                    onComplete(null)
                }
            } catch (e: Exception) {
                _toastMessage.value = "Erreur: ${e.message}"
                onComplete(null)
            } finally {
                _isStitchingFilm.value = false
                _stitchProgress.value = 0
            }
        }
    }

    // ─── AXE 5 : GESTION DU STOCKAGE LRU ───
    fun cleanVideoCache(context: Context, onResult: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = OfflineVideoManager.clearCache(context)
            withContext(Dispatchers.Main) {
                _toastMessage.value = "Cache nettoyé : ${(freed / (1024 * 1024))} Mo libérés"
                onResult(freed)
            }
        }
    }
}
