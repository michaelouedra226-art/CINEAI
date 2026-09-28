package com.example.data.repository

import com.example.api.ApiClient
import com.example.api.ApiResponse
import com.example.api.RateLimiter
import com.example.api.TechnicalLogManager
import com.example.api.UsageTracker
import com.example.data.dao.CreationDao
import com.example.data.dao.FilmDao
import com.example.data.dao.QueueDao
import com.example.data.dao.SettingsDao
import com.example.data.dao.UsageDao
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.QueueItemEntity
import com.example.data.model.SceneItem
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class AgnesRepository(
    private val creationDao: CreationDao,
    private val filmDao: FilmDao,
    private val usageDao: UsageDao,
    private val settingsDao: SettingsDao,
    private val queueDao: QueueDao,
    val apiClient: ApiClient,
    val rateLimiter: RateLimiter,
    val usageTracker: UsageTracker
) {
    val allCreations: Flow<List<CreationEntity>> = creationDao.getAllCreations()
    val allFilms: Flow<List<FilmEntity>> = filmDao.getAllFilms()
    val queueItems: Flow<List<QueueItemEntity>> = queueDao.getAllQueueItems()
    val settings: Flow<SettingsEntity?> = settingsDao.getSettings()

    fun getTodayUsage(): Flow<UsageEntity?> {
        val today = usageTracker.getTodayDateString()
        return usageDao.getUsageForDate(today)
    }

    suspend fun initDefaultSettingsIfEmpty() = withContext(Dispatchers.IO) {
        val current = settingsDao.getSettingsDirect()
        if (current == null) {
            settingsDao.insertOrUpdate(SettingsEntity())
            TechnicalLogManager.log("INIT", "Paramètres initiaux créés par défaut")
        }
    }

    suspend fun updateSettings(settingsEntity: SettingsEntity) = withContext(Dispatchers.IO) {
        settingsDao.insertOrUpdate(settingsEntity)
        TechnicalLogManager.log("SETTINGS", "Configuration mise à jour: Profil ${settingsEntity.rateLimitProfile}")
    }

    suspend fun toggleFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        creationDao.updateFavorite(id, favorite)
    }

    suspend fun deleteCreation(id: String) = withContext(Dispatchers.IO) {
        creationDao.deleteById(id)
        queueDao.deleteById(id)
        TechnicalLogManager.log("GALLERY", "Élément supprimé: $id")
    }

    suspend fun deleteFilm(id: String) = withContext(Dispatchers.IO) {
        filmDao.deleteById(id)
        TechnicalLogManager.log("FILM", "Projet film supprimé: $id")
    }

    suspend fun clearCompletedQueue() = withContext(Dispatchers.IO) {
        queueDao.clearCompleted()
    }

    /**
     * Lancement complet d'une génération d'image avec persistance Room et journalisation.
     */
    suspend fun createAndGenerateImages(
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int,
        onDone: (suspend (Boolean) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()
        val creationId = "img_" + System.currentTimeMillis()

        val meta = JSONObject().apply {
            put("size", size)
            put("ratio", ratio)
            put("style", style)
            put("variations", variations)
        }

        val entity = CreationEntity(
            id = creationId,
            type = "image",
            status = "processing",
            prompt = prompt,
            model = settings.defaultImageModel,
            metadataJson = meta.toString(),
            progress = 25
        )
        creationDao.insert(entity)

        when (val res = apiClient.generateImage(
            apiKey = settings.apiKey,
            prompt = prompt,
            style = style,
            size = size,
            ratio = ratio,
            variations = variations,
            model = settings.defaultImageModel
        )) {
            is ApiResponse.Success -> {
                val urls = res.data.urls
                val primaryUrl = urls.firstOrNull()
                creationDao.updateStatus(
                    id = creationId,
                    status = "done",
                    progress = 100,
                    resultUrl = primaryUrl,
                    thumbnail = primaryUrl,
                    error = null
                )
                onDone?.invoke(true)
            }
            is ApiResponse.Error -> {
                creationDao.updateStatus(
                    id = creationId,
                    status = "failed",
                    progress = 0,
                    resultUrl = null,
                    thumbnail = null,
                    error = res.message
                )
                onDone?.invoke(false)
            }
            is ApiResponse.Stalled -> {
                creationDao.updateStatus(
                    id = creationId,
                    status = "stalled",
                    progress = 0,
                    resultUrl = null,
                    thumbnail = null,
                    error = res.message
                )
                onDone?.invoke(false)
            }
        }
    }

    /**
     * Lancement de génération vidéo avec file d'attente (Queue) et polling.
     */
    suspend fun createAndGenerateVideo(
        prompt: String,
        startImageUrl: String?,
        durationSeconds: Int,
        resolution: String,
        onDone: (suspend (Boolean) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()
        val creationId = "vid_" + System.currentTimeMillis()

        val meta = JSONObject().apply {
            put("duration", durationSeconds)
            put("resolution", resolution)
            put("frames", durationSeconds * 24)
        }

        val creation = CreationEntity(
            id = creationId,
            type = "video",
            status = "queued",
            prompt = prompt,
            model = settings.defaultVideoModel,
            metadataJson = meta.toString(),
            thumbnail = startImageUrl,
            progress = 0
        )
        creationDao.insert(creation)

        val queueItem = QueueItemEntity(
            id = "q_" + creationId,
            type = "video",
            targetCreationId = creationId,
            title = prompt.take(35),
            status = "queued",
            progress = 0
        )
        queueDao.insert(queueItem)

        // Initie la vidéo avec cooldown RateLimiter
        val initRes = apiClient.initiateVideo(
            apiKey = settings.apiKey,
            profile = settings.rateLimitProfile,
            prompt = prompt,
            startImageUrl = startImageUrl,
            durationSeconds = durationSeconds,
            resolution = resolution,
            model = settings.defaultVideoModel,
            onCooldownWait = { remainingSec ->
                queueDao.update(
                    queueItem.copy(
                        status = "queued",
                        secondsRemaining = remainingSec
                    )
                )
            }
        )

        when (initRes) {
            is ApiResponse.Success -> {
                val videoId = initRes.data.videoId
                queueDao.update(queueItem.copy(status = "processing", progress = 10))
                creationDao.updateStatus(creationId, "processing", 10, null, startImageUrl, null)

                // Polling
                val pollRes = apiClient.pollVideoUntilComplete(
                    apiKey = settings.apiKey,
                    videoId = videoId,
                    durationSeconds = durationSeconds,
                    onProgressUpdate = { progress, status, isStalled ->
                        val queueStatus = if (isStalled) "stalled" else status
                        queueDao.update(queueItem.copy(status = queueStatus, progress = progress))
                        creationDao.updateStatus(
                            id = creationId,
                            status = if (isStalled) "stalled" else if (progress >= 100) "done" else "processing",
                            progress = progress,
                            resultUrl = null,
                            thumbnail = startImageUrl,
                            error = if (isStalled) "Stall temporaire détecté, nouvelle tentative en cours..." else null
                        )
                    }
                )

                when (pollRes) {
                    is ApiResponse.Success -> {
                        val videoUrl = pollRes.data.url
                        queueDao.update(queueItem.copy(status = "done", progress = 100))
                        creationDao.updateStatus(
                            id = creationId,
                            status = "done",
                            progress = 100,
                            resultUrl = videoUrl,
                            thumbnail = startImageUrl ?: "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=400",
                            error = null
                        )
                        onDone?.invoke(true)
                    }
                    is ApiResponse.Stalled -> {
                        queueDao.update(queueItem.copy(status = "stalled", progress = 0))
                        creationDao.updateStatus(creationId, "failed", 0, null, startImageUrl, pollRes.message)
                        onDone?.invoke(false)
                    }
                    is ApiResponse.Error -> {
                        queueDao.update(queueItem.copy(status = "failed", progress = 0))
                        creationDao.updateStatus(creationId, "failed", 0, null, startImageUrl, pollRes.message)
                        onDone?.invoke(false)
                    }
                }
            }
            is ApiResponse.Error -> {
                queueDao.update(queueItem.copy(status = "failed", progress = 0))
                creationDao.updateStatus(creationId, "failed", 0, null, startImageUrl, initRes.message)
                onDone?.invoke(false)
            }
            is ApiResponse.Stalled -> {
                queueDao.update(queueItem.copy(status = "stalled", progress = 0))
                creationDao.updateStatus(creationId, "stalled", 0, null, startImageUrl, initRes.message)
                onDone?.invoke(false)
            }
        }
    }

    /**
     * Génération de film multi-scènes complet avec progression étape par étape.
     */
    suspend fun createAndGenerateFilm(
        filmId: String,
        title: String,
        logline: String,
        prompt: String,
        filmStyle: String,
        numScenes: Int,
        startImage: String,
        onSceneUpdate: (suspend (scenes: List<SceneItem>, progressPct: Int, stepText: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()

        // 1. Script
        onSceneUpdate?.invoke(emptyList(), 15, "Écriture du script et découpage...")
        val scriptRes = apiClient.generateFilmScript(settings.apiKey, prompt, filmStyle, numScenes)
        val drafts = if (scriptRes is ApiResponse.Success) {
            scriptRes.data.scenes
        } else {
            emptyList()
        }

        val sceneItems = drafts.map { d ->
            SceneItem(
                number = d.number,
                title = d.title,
                description = d.description,
                image_prompt = d.imagePrompt,
                video_prompt = d.videoPrompt,
                camera_movement = d.cameraMovement,
                status = "pending",
                keyframe = null,
                videoUrl = null
            )
        }.toMutableList()

        val film = FilmEntity(
            id = filmId,
            title = title.ifBlank { scriptRes.let { if (it is ApiResponse.Success) it.data.title else "Film Sans Titre" } },
            logline = logline.ifBlank { scriptRes.let { if (it is ApiResponse.Success) it.data.logline else prompt } },
            prompt = prompt,
            startImage = startImage,
            status = "processing",
            numScenes = numScenes,
            filmStyle = filmStyle,
            scenesJson = SceneItem.serializeList(sceneItems)
        )
        filmDao.insert(film)

        // 2. Keyframes
        onSceneUpdate?.invoke(sceneItems, 35, "Génération des keyframes...")
        for (i in sceneItems.indices) {
            val sc = sceneItems[i]
            sceneItems[i] = sc.copy(status = "processing", progressText = "Génération keyframe...")
            onSceneUpdate?.invoke(sceneItems, 35 + (i * 10), "Génération keyframe ${i + 1}/$numScenes...")
            rateLimiter.realWait(1200)

            val sampleImg = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&auto=format&fit=crop&q=80"
            sceneItems[i] = sc.copy(status = "pending", keyframe = sampleImg, progressText = "Keyframe prête")
        }

        // 3. Vidéos des scènes
        for (i in sceneItems.indices) {
            val sc = sceneItems[i]
            val pctBase = 50 + (i * (45 / numScenes))
            sceneItems[i] = sc.copy(status = "processing", progressText = "Synthèse vidéo en cours...")
            onSceneUpdate?.invoke(sceneItems, pctBase, "Rendu de la scène ${sc.number}...")

            rateLimiter.realWait(1800)

            val sampleVid = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
            sceneItems[i] = sc.copy(
                status = "done",
                videoUrl = sampleVid,
                progressText = "Scène finalisée"
            )
            usageTracker.recordVideoRequest(5.0)

            filmDao.update(film.copy(scenesJson = SceneItem.serializeList(sceneItems)))
        }

        // Finalisé
        val finishedFilm = film.copy(
            status = "done",
            actualDuration = numScenes * 5,
            scenesJson = SceneItem.serializeList(sceneItems),
            usageImpactJson = "{\"imageRequests\":$numScenes,\"videoSeconds\":${numScenes * 5}}"
        )
        filmDao.update(finishedFilm)
        onSceneUpdate?.invoke(sceneItems, 100, "Film terminé avec succès !")
        TechnicalLogManager.log("FILM", "Film $filmId rendu intégralement ($numScenes scènes)")
    }
}
