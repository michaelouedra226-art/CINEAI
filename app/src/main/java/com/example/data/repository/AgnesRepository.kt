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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FilmBreakdown(
    val numScenes: Int,
    val framesPerScene: Int,
    val durationPerScene: Double,
    val actualTotalDuration: Double
)

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

    companion object {
        // Frames valides respectant la contrainte 8n + 1 de l'API Agnes
        val ALLOWED_FRAMES = listOf(
            81 to 3.375,   // 81 / 24
            121 to 5.042,  // 121 / 24
            153 to 6.375,  // 153 / 24
            241 to 10.042, // 241 / 24
            441 to 18.375  // 441 / 24
        )

        fun calculateBreakdown(requestedSeconds: Double, manualScenes: Int?): FilmBreakdown {
            if (manualScenes != null && manualScenes >= 1) {
                val targetPerScene = requestedSeconds / manualScenes
                val closest = ALLOWED_FRAMES.minByOrNull { kotlin.math.abs(it.second - targetPerScene) } ?: ALLOWED_FRAMES[1]
                return FilmBreakdown(
                    numScenes = manualScenes,
                    framesPerScene = closest.first,
                    durationPerScene = closest.second,
                    actualTotalDuration = manualScenes * closest.second
                )
            } else {
                val idealSec = 5.042
                val computedScenes = (requestedSeconds / idealSec).toInt().coerceIn(2, 250)
                return FilmBreakdown(
                    numScenes = computedScenes,
                    framesPerScene = 121,
                    durationPerScene = idealSec,
                    actualTotalDuration = computedScenes * idealSec
                )
            }
        }
    }

    fun getTodayUsage(): Flow<UsageEntity?> {
        val today = usageTracker.getTodayDateString()
        return usageDao.getUsageForDate(today)
    }

    suspend fun initDefaultSettingsIfEmpty() = withContext(Dispatchers.IO) {
        val current = settingsDao.getSettingsDirect()
        if (current == null) {
            settingsDao.insertOrUpdate(SettingsEntity())
            TechnicalLogManager.log("INIT", "Initialisation des réglages Agnes Studio")
        }

        // Nettoyage impératif de toute donnée de démonstration antérieure
        filmDao.deleteById("film_showcase_nebula")
        filmDao.deleteById("film_showcase_cyberpunk")
        filmDao.deleteById("film_showcase_nature")

        // Récupération des films interrompus lors d'une session fermée/arrière-plan
        val allFilms = filmDao.getAllFilmsDirect()
        for (film in allFilms) {
            if (film.status == "processing") {
                val scenes = SceneItem.parseList(film.scenesJson)
                val sanitizedScenes = scenes.map { sc ->
                    if (sc.status == "processing" || sc.status == "stalled") {
                        if (sc.videoUrl.isNullOrBlank()) sc.copy(status = "pending", progressText = "En attente de reprise")
                        else sc.copy(status = "done", progressText = "Plan finalisé")
                    } else sc
                }
                filmDao.update(
                    film.copy(
                        status = "partial",
                        failureReason = "Production interrompue lors de la déconnexion",
                        scenesJson = SceneItem.serializeList(sanitizedScenes)
                    )
                )
                TechnicalLogManager.log("INIT", "Film '${film.title}' récupéré en statut Partiel pour reprise")
            }
        }
    }

    suspend fun updateSettings(settingsEntity: SettingsEntity) = withContext(Dispatchers.IO) {
        settingsDao.insertOrUpdate(settingsEntity)
        TechnicalLogManager.log("SETTINGS", "Configuration mise à jour: Profil ${settingsEntity.rateLimitProfile}")
    }

    suspend fun deleteSettingsKey() = withContext(Dispatchers.IO) {
        val current = settingsDao.getSettingsDirect() ?: SettingsEntity()
        settingsDao.insertOrUpdate(current.copy(apiKey = ""))
        TechnicalLogManager.log("SETTINGS", "Clé API supprimée du stockage local")
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
        TechnicalLogManager.log("FILM", "Film supprimé de la bibliothèque: $id")
    }

    suspend fun getFilmDirect(id: String): FilmEntity? = withContext(Dispatchers.IO) {
        filmDao.getFilmByIdDirect(id)
    }

    /**
     * Génération réelle d'images via l'API Agnes
     */
    suspend fun createAndGenerateImages(
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int,
        stopRequested: () -> Boolean = { false },
        onDone: (suspend (Boolean, String?) -> Unit)? = null
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
            progress = 30
        )
        creationDao.insert(entity)

        when (val res = apiClient.generateImage(
            apiKey = settings.apiKey,
            prompt = prompt,
            style = style,
            size = size,
            ratio = ratio,
            variations = variations,
            model = settings.defaultImageModel,
            stopRequested = stopRequested
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
                onDone?.invoke(true, null)
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
                onDone?.invoke(false, res.message)
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
                onDone?.invoke(false, res.message)
            }
        }
    }

    /**
     * Génération réelle de vidéo via l'API Agnes
     */
    suspend fun createAndGenerateVideo(
        prompt: String,
        startImageUrl: String?,
        durationSeconds: Int,
        resolution: String,
        numFrames: Int = 121,
        stopRequested: () -> Boolean = { false },
        onDone: (suspend (Boolean, String?) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()
        val creationId = "vid_" + System.currentTimeMillis()

        val meta = JSONObject().apply {
            put("duration", durationSeconds)
            put("resolution", resolution)
            put("frames", numFrames)
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

        val initRes = apiClient.initiateVideo(
            apiKey = settings.apiKey,
            profile = settings.rateLimitProfile,
            prompt = prompt,
            startImageUrl = startImageUrl,
            durationSeconds = durationSeconds,
            numFrames = numFrames,
            resolution = resolution,
            model = settings.defaultVideoModel,
            stopRequested = stopRequested,
            onCooldownWait = { remainingSec ->
                queueDao.update(queueItem.copy(status = "queued", secondsRemaining = remainingSec))
            }
        )

        when (initRes) {
            is ApiResponse.Success -> {
                val videoId = initRes.data.videoId
                queueDao.update(queueItem.copy(status = "processing", progress = 10))
                creationDao.updateStatus(creationId, "processing", 10, null, startImageUrl, null)

                val pollRes = apiClient.pollVideo(
                    apiKey = settings.apiKey,
                    videoId = videoId,
                    durationSeconds = durationSeconds,
                    stopRequested = stopRequested,
                    onProgressUpdate = { progress, status, isStalled ->
                        val queueStatus = if (isStalled) "stalled" else status
                        queueDao.update(queueItem.copy(status = queueStatus, progress = progress))
                        creationDao.updateStatus(
                            id = creationId,
                            status = if (isStalled) "stalled" else if (progress >= 100) "done" else "processing",
                            progress = progress,
                            resultUrl = null,
                            thumbnail = startImageUrl,
                            error = if (isStalled) "Stall détecté, relance en cours..." else null
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
                            thumbnail = startImageUrl ?: videoUrl,
                            error = null
                        )
                        onDone?.invoke(true, null)
                    }
                    is ApiResponse.Error -> {
                        queueDao.update(queueItem.copy(status = "failed", progress = 0))
                        creationDao.updateStatus(creationId, "failed", 0, null, startImageUrl, pollRes.message)
                        onDone?.invoke(false, pollRes.message)
                    }
                    is ApiResponse.Stalled -> {
                        queueDao.update(queueItem.copy(status = "stalled", progress = 0))
                        creationDao.updateStatus(creationId, "stalled", 0, null, startImageUrl, pollRes.message)
                        onDone?.invoke(false, pollRes.message)
                    }
                }
            }
            is ApiResponse.Error -> {
                queueDao.update(queueItem.copy(status = "failed", progress = 0))
                creationDao.updateStatus(creationId, "failed", 0, null, startImageUrl, initRes.message)
                onDone?.invoke(false, initRes.message)
            }
            is ApiResponse.Stalled -> {
                queueDao.update(queueItem.copy(status = "stalled", progress = 0))
                creationDao.updateStatus(creationId, "stalled", 0, null, startImageUrl, initRes.message)
                onDone?.invoke(false, initRes.message)
            }
        }
    }

    /**
     * Pipeline Film Complet (Phases 1 à 4) 100% réel sans données fictives
     */
    suspend fun createAndGenerateFilm(
        filmId: String,
        title: String,
        prompt: String,
        filmStyle: String,
        requestedDuration: Double,
        manualScenes: Int?,
        startImage: String,
        initialScenes: List<SceneItem>? = null,
        stopRequested: () -> Boolean = { false },
        onSceneUpdate: (suspend (scenes: List<SceneItem>, progressPct: Int, stepText: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()

        if (settings.apiKey.isBlank()) {
            throw IllegalStateException("Clé API manquante. Veuillez renseigner votre clé API dans les Réglages avant de lancer un film.")
        }

        val breakdown = calculateBreakdown(requestedDuration, manualScenes)
        val numScenes = initialScenes?.size ?: breakdown.numScenes

        TechnicalLogManager.log(
            "FILM_PIPELINE",
            "Démarrage Film: $numScenes scènes, ${breakdown.framesPerScene} frames/scène, total ${breakdown.actualTotalDuration}s"
        )

        // ═══════════════════════════════════════════════════════
        // PHASE 1 : SCRIPT (Soit déjà validé en Découpage, soit agnes-2.5-flash)
        // ═══════════════════════════════════════════════════════
        val sceneItems: MutableList<SceneItem>
        val finalTitle: String
        val finalLogline: String

        if (initialScenes != null && initialScenes.isNotEmpty()) {
            sceneItems = initialScenes.toMutableList()
            finalTitle = title.ifBlank { "Projet Film Studio" }
            finalLogline = prompt
            onSceneUpdate?.invoke(sceneItems, 18, "Découpage validé, préparation de la production...")
        } else {
            onSceneUpdate?.invoke(emptyList(), 15, "Phase 1 : Écriture du scénario ($numScenes plans)...")

            val scriptRes = apiClient.generateFilmScript(
                apiKey = settings.apiKey,
                prompt = prompt,
                style = filmStyle,
                numScenes = numScenes,
                stopRequested = stopRequested,
                onProgressUpdate = { pct, txt ->
                    onSceneUpdate?.invoke(emptyList(), pct, txt)
                }
            )

            if (scriptRes !is ApiResponse.Success) {
                val errorMsg = if (scriptRes is ApiResponse.Error) scriptRes.message else "Échec de génération du scénario"
                throw IllegalStateException(errorMsg)
            }

            val drafts = scriptRes.data.scenes
            finalTitle = title.ifBlank { scriptRes.data.title }
            finalLogline = scriptRes.data.logline

            sceneItems = drafts.map { d ->
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
        }

        val film = FilmEntity(
            id = filmId,
            title = finalTitle,
            logline = finalLogline,
            prompt = prompt,
            startImage = startImage,
            status = "processing",
            requestedDuration = requestedDuration,
            duration = breakdown.actualTotalDuration,
            numScenes = numScenes,
            framesPerScene = breakdown.framesPerScene,
            durationPerScene = breakdown.durationPerScene,
            filmStyle = filmStyle,
            scenesJson = SceneItem.serializeList(sceneItems)
        )
        filmDao.insert(film)

        // ═══════════════════════════════════════════════════════
        // PHASE 2 : KEYFRAMES (agnes-image-2.1-flash)
        // ═══════════════════════════════════════════════════════
        for (i in sceneItems.indices) {
            if (stopRequested()) {
                filmDao.update(film.copy(status = "partial", scenesJson = SceneItem.serializeList(sceneItems)))
                throw CancellationException("Annulé par l'utilisateur")
            }

            val sc = sceneItems[i]
            val pct = 20 + ((i + 1) * 20 / numScenes)
            onSceneUpdate?.invoke(sceneItems, pct, "Phase 2 : Génération Keyframe ${i + 1}/$numScenes...")

            val keyframePrompt = "${sc.image_prompt}, END frame scene ${i + 1}, 9:16 vertical cinema, 8k"
            val imgRes = apiClient.generateImage(
                apiKey = settings.apiKey,
                prompt = keyframePrompt,
                style = filmStyle,
                size = "2K",
                ratio = "9:16",
                variations = 1,
                model = "agnes-image-2.1-flash",
                stopRequested = stopRequested
            )

            if (imgRes is ApiResponse.Success) {
                val keyframeUrl = imgRes.data.urls.firstOrNull()
                sceneItems[i] = sc.copy(keyframe = keyframeUrl, progressText = "Keyframe généré")
                filmDao.update(film.copy(scenesJson = SceneItem.serializeList(sceneItems)))
            } else {
                val errorMsg = if (imgRes is ApiResponse.Error) imgRes.message else "Erreur keyframe"
                sceneItems[i] = sc.copy(status = "failed", error = errorMsg)
                filmDao.update(film.copy(status = "partial", scenesJson = SceneItem.serializeList(sceneItems)))
                throw IllegalStateException("Échec de la génération de l'image clé pour la scène ${i + 1}: $errorMsg")
            }

            rateLimiter.realWait(RateLimiter.IMAGE_CALL_DELAY_MS, stopRequested)
        }

        // ═══════════════════════════════════════════════════════
        // PHASE 3 : VIDÉOS SÉQUENTIELLES (agnes-video-v2.0)
        // ═══════════════════════════════════════════════════════
        var previousFrameUrl = startImage.ifBlank { sceneItems.firstOrNull()?.keyframe.orEmpty() }

        for (i in sceneItems.indices) {
            if (stopRequested()) {
                filmDao.update(film.copy(status = "partial", scenesJson = SceneItem.serializeList(sceneItems)))
                throw CancellationException("Annulé par l'utilisateur")
            }

            val sc = sceneItems[i]
            val currentKeyframe = sc.keyframe ?: previousFrameUrl

            val pctBase = 45 + (i * 50 / numScenes)
            sceneItems[i] = sc.copy(status = "processing", progressText = "Rendu vidéo scène ${sc.number}...")
            onSceneUpdate?.invoke(sceneItems, pctBase, "Phase 3 : Synthèse vidéo scène ${sc.number}/$numScenes...")

            val videoInit = apiClient.initiateVideo(
                apiKey = settings.apiKey,
                profile = settings.rateLimitProfile,
                prompt = "${sc.video_prompt}, continuité fluide, style $filmStyle",
                startImageUrl = previousFrameUrl,
                endImageUrl = currentKeyframe,
                durationSeconds = breakdown.durationPerScene.toInt().coerceAtLeast(3),
                numFrames = breakdown.framesPerScene,
                resolution = "720p 9:16",
                model = "agnes-video-v2.0",
                stopRequested = stopRequested
            )

            if (videoInit !is ApiResponse.Success) {
                val err = if (videoInit is ApiResponse.Error) videoInit.message else "Erreur création vidéo"
                sceneItems[i] = sc.copy(status = "failed", error = err)
                filmDao.update(film.copy(status = "partial", scenesJson = SceneItem.serializeList(sceneItems)))
                throw IllegalStateException("Échec de création vidéo pour la scène ${sc.number}: $err")
            }

            val videoId = videoInit.data.videoId

            val pollRes = apiClient.pollVideo(
                apiKey = settings.apiKey,
                videoId = videoId,
                durationSeconds = breakdown.durationPerScene.toInt().coerceAtLeast(3),
                stopRequested = stopRequested
            ) { prog, _, isStalled ->
                val txt = if (isStalled) "Stall détecté sur le serveur, attente..." else "Progression: $prog%"
                sceneItems[i] = sc.copy(
                    status = if (isStalled) "stalled" else "processing",
                    progressText = txt
                )
                onSceneUpdate?.invoke(sceneItems, pctBase + (prog / 4), "Scène ${sc.number} : $prog%")
            }

            if (pollRes !is ApiResponse.Success || pollRes.data.url.isNullOrBlank()) {
                val err = if (pollRes is ApiResponse.Error) pollRes.message else "Échec de rendu vidéo"
                sceneItems[i] = sc.copy(status = "failed", error = err)
                filmDao.update(film.copy(status = "partial", scenesJson = SceneItem.serializeList(sceneItems)))
                throw IllegalStateException("Échec du rendu vidéo pour la scène ${sc.number}: $err")
            }

            val finalVideoUrl = pollRes.data.url

            sceneItems[i] = sc.copy(
                status = "done",
                videoUrl = finalVideoUrl,
                progressText = "Plan finalisé"
            )

            filmDao.update(film.copy(scenesJson = SceneItem.serializeList(sceneItems)))
            previousFrameUrl = currentKeyframe

            val pauseMs = if (settings.rateLimitProfile == "token") {
                RateLimiter.VIDEO_PAUSE_MS_TOKEN
            } else if (settings.rateLimitProfile == "enterprise") {
                0L
            } else {
                RateLimiter.VIDEO_PAUSE_MS_FREE
            }

            if (pauseMs > 0 && i < sceneItems.size - 1) {
                TechnicalLogManager.log("RPM", "Pause RPM obligatoire de ${(pauseMs / 1000)}s avant la scène suivante", "RATE_LIMIT")
                rateLimiter.realWait(pauseMs, stopRequested) { rem, _ ->
                    onSceneUpdate?.invoke(sceneItems, pctBase + 10, "Pause RPM anti-throttling : ${rem}s...")
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // PHASE 4 : FINALISATION ET ASSEMBLAGE
        // ═══════════════════════════════════════════════════════
        val finishedFilm = film.copy(
            status = "done",
            actualDuration = breakdown.actualTotalDuration.toInt(),
            scenesJson = SceneItem.serializeList(sceneItems),
            usageImpactJson = "{\"imageRequests\":$numScenes,\"videoSeconds\":${breakdown.actualTotalDuration}}"
        )
        filmDao.update(finishedFilm)
        onSceneUpdate?.invoke(sceneItems, 100, "Film achevé avec succès !")
        TechnicalLogManager.log("FILM_PIPELINE", "Film $filmId achevé avec succès ($numScenes plans)")
    }

    suspend fun updateFilmFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        filmDao.updateFavorite(id, favorite)
    }

    suspend fun generateScriptDrafts(
        prompt: String,
        style: String,
        numScenes: Int
    ): Pair<String, List<SceneItem>> = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()
        if (settings.apiKey.isBlank()) {
            throw IllegalStateException("Clé API manquante. Veuillez renseigner votre clé API dans les Réglages.")
        }
        val scriptRes = apiClient.generateFilmScript(
            apiKey = settings.apiKey,
            prompt = prompt,
            style = style,
            numScenes = numScenes,
            stopRequested = { false }
        )
        if (scriptRes !is ApiResponse.Success) {
            val errorMsg = if (scriptRes is ApiResponse.Error) scriptRes.message else "Échec de génération du découpage"
            throw IllegalStateException(errorMsg)
        }
        val scenes = scriptRes.data.scenes.map { d ->
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
        }
        Pair(scriptRes.data.title, scenes)
    }

    suspend fun resumeFilm(
        filmId: String,
        stopRequested: () -> Boolean = { false },
        onSceneUpdate: (suspend (scenes: List<SceneItem>, progressPct: Int, stepText: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val settings = settingsDao.getSettingsDirect() ?: SettingsEntity()
        if (settings.apiKey.isBlank()) {
            throw IllegalStateException("Clé API manquante.")
        }
        val film = filmDao.getFilmByIdDirect(filmId) ?: throw IllegalStateException("Film introuvable")
        val sceneItems = SceneItem.parseList(film.scenesJson).toMutableList()
        val numScenes = film.numScenes
        val breakdown = calculateBreakdown(film.requestedDuration, numScenes)

        filmDao.update(film.copy(status = "processing", failureReason = null))

        // Phase 2 : Keyframes manquantes
        for (i in sceneItems.indices) {
            if (stopRequested()) {
                filmDao.update(film.copy(status = "partial", failureReason = "Interrompu par l'utilisateur", scenesJson = SceneItem.serializeList(sceneItems)))
                throw CancellationException("Annulé")
            }
            val sc = sceneItems[i]
            if (sc.keyframe.isNullOrBlank()) {
                val pct = 20 + ((i + 1) * 20 / numScenes)
                onSceneUpdate?.invoke(sceneItems, pct, "Keyframe ${i + 1}/$numScenes...")
                val keyframePrompt = "${sc.image_prompt}, END frame scene ${i + 1}, 9:16 vertical cinema, 8k"
                val imgRes = apiClient.generateImage(
                    apiKey = settings.apiKey,
                    prompt = keyframePrompt,
                    style = film.filmStyle,
                    size = "2K",
                    ratio = "9:16",
                    variations = 1,
                    model = "agnes-image-2.1-flash",
                    stopRequested = stopRequested
                )
                if (imgRes is ApiResponse.Success) {
                    val keyframeUrl = imgRes.data.urls.firstOrNull()
                    sceneItems[i] = sc.copy(keyframe = keyframeUrl, progressText = "Keyframe généré")
                    filmDao.update(film.copy(scenesJson = SceneItem.serializeList(sceneItems)))
                }
                rateLimiter.realWait(RateLimiter.IMAGE_CALL_DELAY_MS, stopRequested)
            }
        }

        // Phase 3 : Vidéos manquantes
        var previousFrameUrl = film.startImage.ifBlank { sceneItems.firstOrNull()?.keyframe.orEmpty() }
        for (i in sceneItems.indices) {
            if (stopRequested()) {
                filmDao.update(film.copy(status = "partial", failureReason = "Interrompu par l'utilisateur", scenesJson = SceneItem.serializeList(sceneItems)))
                throw CancellationException("Annulé")
            }
            val sc = sceneItems[i]
            val currentKeyframe = sc.keyframe ?: previousFrameUrl

            if (sc.status != "done" || sc.videoUrl.isNullOrBlank()) {
                val pctBase = 45 + (i * 50 / numScenes)
                sceneItems[i] = sc.copy(status = "processing", progressText = "Rendu vidéo scène ${sc.number}...")
                onSceneUpdate?.invoke(sceneItems, pctBase, "Scène ${sc.number}/$numScenes...")

                val videoInit = apiClient.initiateVideo(
                    apiKey = settings.apiKey,
                    profile = settings.rateLimitProfile,
                    prompt = "${sc.video_prompt}, continuité fluide, style ${film.filmStyle}",
                    startImageUrl = previousFrameUrl,
                    endImageUrl = currentKeyframe,
                    durationSeconds = breakdown.durationPerScene.toInt().coerceAtLeast(3),
                    numFrames = breakdown.framesPerScene,
                    resolution = "720p 9:16",
                    model = "agnes-video-v2.0",
                    stopRequested = stopRequested
                )
                if (videoInit !is ApiResponse.Success) {
                    val err = if (videoInit is ApiResponse.Error) videoInit.message else "Erreur création vidéo"
                    sceneItems[i] = sc.copy(status = "failed", error = err)
                    filmDao.update(film.copy(status = "partial", failureReason = err, scenesJson = SceneItem.serializeList(sceneItems)))
                    throw IllegalStateException("Échec scène ${sc.number}: $err")
                }
                val videoId = videoInit.data.videoId
                val pollRes = apiClient.pollVideo(
                    apiKey = settings.apiKey,
                    videoId = videoId,
                    durationSeconds = breakdown.durationPerScene.toInt().coerceAtLeast(3),
                    stopRequested = stopRequested
                ) { prog, _, isStalled ->
                    val txt = if (isStalled) "Stall détecté, attente..." else "Progression: $prog%"
                    sceneItems[i] = sc.copy(status = if (isStalled) "stalled" else "processing", progressText = txt)
                    onSceneUpdate?.invoke(sceneItems, pctBase + (prog / 4), "Scène ${sc.number} : $prog%")
                }
                if (pollRes !is ApiResponse.Success || pollRes.data.url.isNullOrBlank()) {
                    val err = if (pollRes is ApiResponse.Error) pollRes.message else "Échec rendu vidéo"
                    sceneItems[i] = sc.copy(status = "failed", error = err)
                    filmDao.update(film.copy(status = "partial", failureReason = err, scenesJson = SceneItem.serializeList(sceneItems)))
                    throw IllegalStateException("Échec rendu scène ${sc.number}: $err")
                }
                val finalVideoUrl = pollRes.data.url
                sceneItems[i] = sc.copy(status = "done", videoUrl = finalVideoUrl, progressText = "Plan finalisé")
                filmDao.update(film.copy(scenesJson = SceneItem.serializeList(sceneItems)))

                val pauseMs = if (settings.rateLimitProfile == "token") RateLimiter.VIDEO_PAUSE_MS_TOKEN else if (settings.rateLimitProfile == "enterprise") 0L else RateLimiter.VIDEO_PAUSE_MS_FREE
                if (pauseMs > 0 && i < sceneItems.size - 1) {
                    rateLimiter.realWait(pauseMs, stopRequested) { rem, _ ->
                        onSceneUpdate?.invoke(sceneItems, pctBase + 10, "Pause anti-throttling : ${rem}s...")
                    }
                }
            }
            previousFrameUrl = currentKeyframe
        }

        // Finalisation
        val finishedFilm = film.copy(
            status = "done",
            failureReason = null,
            actualDuration = breakdown.actualTotalDuration.toInt(),
            scenesJson = SceneItem.serializeList(sceneItems)
        )
        filmDao.update(finishedFilm)
        onSceneUpdate?.invoke(sceneItems, 100, "Film complété avec succès !")
    }
}
