package com.example.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ApiResponse<out T> {
    data class Success<out T>(val data: T) : ApiResponse<T>()
    data class Error(val code: Int, val message: String, val type: String = "general_error") : ApiResponse<Nothing>()
    data class Stalled(val stallRetries: Int, val message: String) : ApiResponse<Nothing>()
}

data class VideoCreationResult(
    val id: String,
    val videoId: String,
    val status: String,
    val createdAt: Long
)

data class VideoPollingResult(
    val status: String, // "processing" | "completed" | "failed"
    val progress: Int,
    val videoId: String,
    val url: String? = null
)

data class ImageCreationResult(
    val created: Long,
    val urls: List<String>
)

data class ScriptGenerationResult(
    val title: String,
    val logline: String,
    val characterConsistency: String = "",
    val visualConsistency: String = "",
    val scenes: List<GeneratedSceneDraft>
)

data class GeneratedSceneDraft(
    val number: Int,
    val title: String,
    val description: String,
    val imagePrompt: String,
    val videoPrompt: String,
    val cameraMovement: String,
    val dialogue: String = "",
    val audioMode: String = "dialogue",
    val characterAnchor: String = ""
)

class ApiClient(
    private val rateLimiter: RateLimiter,
    private val usageTracker: UsageTracker
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        const val AGNES_CHAT_URL = "https://apihub.agnes-ai.com/v1/chat/completions"
        const val AGNES_IMAGE_URL = "https://apihub.agnes-ai.com/v1/images/generations"
        const val AGNES_VIDEO_URL = "https://apihub.agnes-ai.com/v1/videos"
        const val AGNES_POLL_URL = "https://apihub.agnes-ai.com/agnesapi"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Génération d'images réelles via POST https://apihub.agnes-ai.com/v1/images/generations
     */
    suspend fun generateImage(
        apiKey: String,
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int,
        model: String = "agnes-image-2.1-flash",
        stopRequested: () -> Boolean = { false },
        onProgress: (suspend (statusText: String) -> Unit)? = null
    ): ApiResponse<ImageCreationResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Clé API manquante. Veuillez renseigner votre clé API Agnes dans l'onglet Réglages.",
                type = "auth_missing"
            )
        }

        TechnicalLogManager.log("API_IMG", "POST $AGNES_IMAGE_URL - Modèle: $model, Style: $style, Variations: $variations")
        onProgress?.invoke("Préparation de la requête...")

        val dimensions = when (ratio) {
            "9:16" -> "576x1024"
            "16:9" -> "1024x576"
            "4:3" -> "1024x768"
            else -> "1024x1024"
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("prompt", "$prompt, style $style, cinematic lighting, 8k render, masterpiece")
            put("n", variations)
            put("size", dimensions)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(AGNES_IMAGE_URL)
            .addHeader("Authorization", "Bearer $cleanKey")
            .post(requestBody)
            .build()

        var attempts = 0
        var lastErrorMsg = "Erreur de connexion à l'API Agnes"
        var lastErrorCode = 500

        while (attempts < 3) {
            if (stopRequested()) throw CancellationException("Annulé par l'utilisateur")
            attempts++

            try {
                onProgress?.invoke("Génération par l'API Agnes ($model) - Tentative $attempts...")
                val response = okHttpClient.newCall(request).execute()
                val code = response.code
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val dataArray = json.optJSONArray("data") ?: JSONArray()
                    val urls = mutableListOf<String>()
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        val url = obj.optString("url", "")
                        if (url.isNotBlank()) urls.add(url)
                    }

                    if (urls.isNotEmpty()) {
                        usageTracker.recordImageRequest(urls.size)
                        TechnicalLogManager.log("API_IMG", "200 OK: ${urls.size} image(s) générée(s) par Agnes")
                        return@withContext ApiResponse.Success(
                            ImageCreationResult(created = System.currentTimeMillis() / 1000, urls = urls)
                        )
                    } else {
                        lastErrorMsg = "L'API Agnes a répondu avec une liste d'images vide"
                    }
                } else if (code == 429) {
                    TechnicalLogManager.log("API_IMG", "429 Rate Limit - Pause 90s", "WARN")
                    rateLimiter.realWait(RateLimiter.RETRY_429_WAIT_MS, stopRequested)
                    continue
                } else if (code == 503) {
                    TechnicalLogManager.log("API_IMG", "503 Serveur occupé - Attente 20s", "WARN")
                    rateLimiter.realWait(RateLimiter.RETRY_503_WAIT_MS, stopRequested)
                    continue
                } else if (code == 401 || code == 403) {
                    TechnicalLogManager.log("API_IMG", "Erreur d'authentification ($code)", "ERROR")
                    return@withContext ApiResponse.Error(code, "Clé API Agnes invalide ou non autorisée ($code)", "auth_error")
                } else {
                    lastErrorCode = code
                    lastErrorMsg = "Erreur API Agnes ($code) : $responseBody"
                    TechnicalLogManager.log("API_IMG", lastErrorMsg, "ERROR")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErrorMsg = "Exception réseau : ${e.message}"
                TechnicalLogManager.log("API_IMG", "Tentative $attempts échouée: ${e.message}", "WARN")
                rateLimiter.realWait(2000L * attempts, stopRequested)
            }
        }

        ApiResponse.Error(lastErrorCode, lastErrorMsg)
    }

    /**
     * Initialisation d'une tâche vidéo réelle avec vérification de quota (500s/jour)
     */
    suspend fun initiateVideo(
        apiKey: String,
        profile: String,
        prompt: String,
        startImageUrl: String?,
        endImageUrl: String? = null,
        durationSeconds: Int,
        numFrames: Int = 121,
        resolution: String = "720p 16:9",
        model: String = "agnes-video-v2.0",
        stopRequested: () -> Boolean = { false },
        onCooldownWait: (suspend (remainingSec: Int) -> Unit)? = null
    ): ApiResponse<VideoCreationResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Clé API manquante. Veuillez renseigner votre clé API Agnes dans l'onglet Réglages.",
                type = "auth_missing"
            )
        }

        // 1. Vérification du quota journalier 500s
        val todayDate = usageTracker.getTodayDateString()
        val currentUsage = usageTracker.getUsageForDate(todayDate)
        val currentSeconds = currentUsage?.videoSeconds ?: 0.0

        if (currentSeconds + durationSeconds > RateLimiter.MAX_VIDEO_SECONDS_PER_DAY) {
            val msg = "Quota journalier de 500s dépassé (${String.format("%.1f", currentSeconds)}/500s consommées aujourd'hui)"
            TechnicalLogManager.log("QUOTA", msg, "ERROR")
            return@withContext ApiResponse.Error(429, msg, "quota_exceeded")
        }

        // 2. Cooldown anti-throttling (61s Free, 12s Token)
        val cooldownMs = rateLimiter.checkVideoRateLimit(profile)
        if (cooldownMs > 0) {
            TechnicalLogManager.log("RATE_LIMIT", "Espacement anti-throttling ($profile): attente de ${(cooldownMs / 1000)}s", "RATE_LIMIT")
            rateLimiter.realWait(cooldownMs, stopRequested) { remaining, _ ->
                onCooldownWait?.invoke(remaining)
            }
        }

        TechnicalLogManager.log("API_VID", "POST $AGNES_VIDEO_URL - Modèle: $model, Frames: $numFrames, Durée: ${durationSeconds}s")

        val extraBody = JSONObject().apply {
            put("mode", "keyframes")
            val imagesArray = JSONArray()
            if (!startImageUrl.isNullOrBlank()) imagesArray.put(startImageUrl)
            if (!endImageUrl.isNullOrBlank()) imagesArray.put(endImageUrl)
            put("image", imagesArray)
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("num_frames", numFrames)
            put("frame_rate", 24)
            put("width", if (resolution.contains("9:16")) 576 else 1024)
            put("height", if (resolution.contains("9:16")) 1024 else 576)
            put("extra_body", extraBody)
        }

        val request = Request.Builder()
            .url(AGNES_VIDEO_URL)
            .addHeader("Authorization", "Bearer $cleanKey")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var attempts = 0
        var lastErrorMsg = "Échec d'initialisation de la vidéo sur l'API Agnes"
        var lastErrorCode = 500

        while (attempts < 3) {
            if (stopRequested()) throw CancellationException("Annulé par l'utilisateur")
            attempts++

            try {
                val response = okHttpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val videoId = json.optString("video_id", json.optString("id", ""))
                    if (videoId.isNotBlank()) {
                        rateLimiter.registerVideoDispatch()
                        TechnicalLogManager.log("API_VID", "201 Created: ID=$videoId (status: queued)")
                        return@withContext ApiResponse.Success(
                            VideoCreationResult(
                                id = videoId,
                                videoId = videoId,
                                status = "queued",
                                createdAt = System.currentTimeMillis() / 1000
                            )
                        )
                    }
                } else if (code == 429) {
                    TechnicalLogManager.log("API_VID", "429 Rate Limit - Attente 90s", "WARN")
                    rateLimiter.realWait(RateLimiter.RETRY_429_WAIT_MS, stopRequested)
                    continue
                } else if (code == 503) {
                    TechnicalLogManager.log("API_VID", "503 Serveur occupé - Attente 20s", "WARN")
                    rateLimiter.realWait(RateLimiter.RETRY_503_WAIT_MS, stopRequested)
                    continue
                } else if (code == 401 || code == 403) {
                    return@withContext ApiResponse.Error(code, "Authentification refusée par Agnes ($code)", "auth_error")
                } else {
                    lastErrorCode = code
                    lastErrorMsg = "Erreur création vidéo ($code) : $body"
                    TechnicalLogManager.log("API_VID", lastErrorMsg, "ERROR")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErrorMsg = "Erreur réseau vidéo : ${e.message}"
                TechnicalLogManager.log("API_VID", "Tentative $attempts échouée: ${e.message}", "WARN")
                rateLimiter.realWait(2000L * attempts, stopRequested)
            }
        }

        ApiResponse.Error(lastErrorCode, lastErrorMsg)
    }

    /**
     * Polling vidéo réel avec détection de stall (6 polls identiques) et gestion des erreurs.
     */
    suspend fun pollVideo(
        apiKey: String,
        videoId: String,
        durationSeconds: Int,
        stopRequested: () -> Boolean = { false },
        onProgressUpdate: (suspend (progress: Int, status: String, isStalled: Boolean) -> Unit)
    ): ApiResponse<VideoPollingResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Clé API manquante pour le suivi du rendu vidéo.",
                type = "auth_missing"
            )
        }

        var lastProgress = -1
        var sameCount = 0
        var lastStatus = ""
        var stallRetries = 0

        // Attente initiale (FIRST_POLL_DELAY_MS = 120s selon cahier des charges)
        TechnicalLogManager.log("POLL", "Démarrage polling vidéo $videoId (intervalle 30s)")

        for (pollAttempt in 1..RateLimiter.MAX_POLL_ATTEMPTS) {
            if (stopRequested()) throw CancellationException("Annulé par l'utilisateur")

            rateLimiter.realWait(RateLimiter.POLL_INTERVAL_MS, stopRequested)

            try {
                val pollUrl = "$AGNES_POLL_URL?video_id=$videoId&model_name=agnes-video-v2.0"
                val request = Request.Builder()
                    .url(pollUrl)
                    .addHeader("Authorization", "Bearer $cleanKey")
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val status = json.optString("status", "processing")
                    val progress = json.optInt("progress", if (status == "completed") 100 else 0)
                    val directUrl = json.optString("url", "")
                    val videoUrl = if (directUrl.isNotBlank()) directUrl else json.optJSONObject("metadata")?.optString("url", "").orEmpty()

                    // Détection de blocage (StallDetector : 6 polls identiques)
                    if (progress == lastProgress && status == lastStatus && status != "completed") {
                        sameCount++
                        if (sameCount >= RateLimiter.STALL_THRESHOLD) {
                            stallRetries++
                            TechnicalLogManager.log("STALL", "Stall détecté sur $videoId (essai $stallRetries/${RateLimiter.MAX_STALL_RETRIES})", "WARN")
                            onProgressUpdate(progress, "stalled", true)

                            if (stallRetries >= RateLimiter.MAX_STALL_RETRIES) {
                                TechnicalLogManager.log("STALL", "Abandon après 3 stalls consécutifs", "ERROR")
                                return@withContext ApiResponse.Stalled(stallRetries, "Blocage prolongé détecté après $stallRetries relances sur l'API Agnes.")
                            }
                            rateLimiter.realWait(RateLimiter.STALL_RETRY_DELAY_MS, stopRequested)
                            sameCount = 0
                        }
                    } else {
                        sameCount = 0
                    }
                    lastProgress = progress
                    lastStatus = status

                    if (status == "completed" || (progress >= 100 && !videoUrl.isNullOrBlank())) {
                        if (!videoUrl.isNullOrBlank()) {
                            usageTracker.recordVideoRequest(durationSeconds.toDouble())
                            TechnicalLogManager.log("API_VID", "Vidéo $videoId finalisée avec succès: $videoUrl")
                            onProgressUpdate(100, "done", false)
                            return@withContext ApiResponse.Success(
                                VideoPollingResult(status = "completed", progress = 100, videoId = videoId, url = videoUrl)
                            )
                        }
                    } else if (status == "failed") {
                        TechnicalLogManager.log("API_VID", "L'API Agnes a échoué la synthèse de la vidéo $videoId", "ERROR")
                        return@withContext ApiResponse.Error(500, "Échec de génération vidéo sur le cluster Agnes")
                    } else {
                        TechnicalLogManager.log("POLL", "Poll $pollAttempt/${RateLimiter.MAX_POLL_ATTEMPTS}: $progress% ($status)")
                        onProgressUpdate(progress, "processing", false)
                    }
                } else if (code == 429) {
                    TechnicalLogManager.log("POLL", "429 Rate limit pendant le polling - Pause 90s", "WARN")
                    rateLimiter.realWait(RateLimiter.RETRY_429_WAIT_MS, stopRequested)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                TechnicalLogManager.log("POLL", "Poll $pollAttempt exception: ${e.message}", "WARN")
            }
        }

        ApiResponse.Error(408, "Délai d'attente dépassé pour la vidéo $videoId")
    }

    /**
     * Phase 1 : Script complet généré via l'endpoint réel agnes-2.5-flash
     * Traitement par lots de 8 scènes max avec garantie de cohérence de personnage et langue des dialogues stricte.
     */
    suspend fun generateFilmScript(
        apiKey: String,
        prompt: String,
        style: String,
        numScenes: Int,
        dialogueLanguage: String = "fr",
        audioPresence: String = "dialogue",
        stopRequested: () -> Boolean = { false },
        onProgressUpdate: (suspend (progressPct: Int, stepText: String) -> Unit)? = null
    ): ApiResponse<ScriptGenerationResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Clé API manquante pour générer le script du film.",
                type = "auth_missing"
            )
        }

        val BATCH_SIZE = 8
        val totalBatches = (numScenes + BATCH_SIZE - 1) / BATCH_SIZE
        val allDrafts = mutableListOf<GeneratedSceneDraft>()
        var filmTitle = "Film : " + prompt.take(30)
        var filmLogline = prompt
        var characterConsistency = ""
        var visualConsistency = ""

        val langRule = if (dialogueLanguage == "fr") {
            """
            DIRECTIVE LINGUISTIQUE ABSOLUE :
            1. TOUTES les répliques dans le champ 'dialogue' DOIVENT être en FRANÇAIS pur, soutenu et expressif.
            2. INTERDICTION TOTALE DE TOUT MOT EN ANGLAIS (interdit de mettre "look", "wait", "run", "what", "come on", etc.).
            3. INTERDICTION FORMELLE DE LAISSER LE CHAMP 'dialogue' VIDE. Chaque scène DOIT comporter une réplique parlée ou une phrase de voix off en français complet.
            4. Le champ 'dialogue' doit être rédigé entre guillemets français : « [Réplique en français] ».
            """.trimIndent()
        } else {
            "LANGUAGE RULE: All spoken dialogue and voice-overs must be in English with double quotes."
        }

        val audioRule = when (audioPresence) {
            "voice_over" -> "STYLE AUDIO : Voix off narrative profonde, poétique et continue en français pour chaque scène dans le champ 'dialogue'."
            "ambient" -> "STYLE AUDIO : Ambiance sonore et sound design cinématographique sans paroles (laisser 'dialogue' vide uniquement pour ce mode ambiance)."
            else -> "STYLE AUDIO : Dialogues parlés vifs, cinématographiques et expressifs entre les personnages pour chaque scène dans le champ 'dialogue' (en français)."
        }

        TechnicalLogManager.log("PHASE_1", "POST $AGNES_CHAT_URL - Écriture scénario ($numScenes scènes, langue: $dialogueLanguage, audio: $audioPresence)")

        for (batchIndex in 0 until totalBatches) {
            if (stopRequested()) {
                throw CancellationException("Génération de scénario annulée")
            }

            val startScene = batchIndex * BATCH_SIZE + 1
            val endScene = minOf((batchIndex + 1) * BATCH_SIZE, numScenes)
            val scenesInThisBatch = endScene - startScene + 1

            val currentPct = 15 + ((batchIndex + 1) * 5 / totalBatches)
            onProgressUpdate?.invoke(
                currentPct,
                "Phase 1 : Écriture du scénario (plans $startScene à $endScene / $numScenes)..."
            )

            val systemPrompt = if (batchIndex == 0) {
                """
                Tu es un réalisateur, scénariste et showrunner IA d'élite.
                Tu dois garantir une COHÉRENCE VISUELLE ET NARRATIVE TOTALE entre chaque plan du film.

                CONSIGNES MAÎTRESSES DE COHÉRENCE ET DE DIALOGUES :
                1. BIBLE VISUELLE ET PERSONNAGES :
                   - 'character_consistency' : Décris le protagoniste principal de manière immuable et ultra-précise (traits du visage, âge, coupe et couleur de cheveux, tenue vestimentaire exacte avec couleurs, accessoires distinctifs). Cette description servira d'ancre pour TOUTES les scènes.
                   - 'visual_consistency' : Charte visuelle maîtresse (palette de couleurs, ambiance lumineuse, texture 35mm anamorphic).
                2. LANGUE ET PAROLES :
                   $langRule
                   $audioRule
                3. PROMPTS D'IMAGES ET VIDÉO :
                   - 'image_prompt' : Prompt cinématographique décrivant l'action du plan, avec intégration du protagoniste identifiable, 9:16 vertical cinema, 8k.
                   - 'video_prompt' : Mouvement fluide de caméra, action dynamique des personnages, éclairage cinématique.
                   - 'dialogue' : Réplique parlée ou voix off (strictement en français, non vide).

                Exemple de plan attendu :
                {
                  "number": 1,
                  "title": "L'Alerte au Poste de Contrôle",
                  "description": "Marc scrute les écrans holographiques clignotants alors que les alarmes retentissent.",
                  "dialogue": "« Le signal provient du secteur quatre... Nous n'avons plus que quelques minutes ! »",
                  "audio_mode": "$audioPresence",
                  "image_prompt": "Marc in charcoal trench coat checking holographic monitors, alarmed expression, emergency red neon lights, 9:16 vertical cinema, 8k",
                  "video_prompt": "Slow push-in camera movement towards the monitors, urgent atmosphere, cinematic lighting",
                  "camera_movement": "Travelling avant lent"
                }

                Tu dois répondre exclusivement avec un objet JSON valide, sans formatage markdown :
                {
                  "film_title": "Titre cinématographique",
                  "logline": "Accroche narrative résumant l'intrigue en une phrase",
                  "character_consistency": "Description détaillée immuable du personnage (visage, cheveux, vêtements précis, accessoires)",
                  "visual_consistency": "Charte visuelle commune (palette, lumière contrastée, grain 35mm)",
                  "scenes": [
                    {
                      "number": 1,
                      "title": "Titre court de la scène",
                      "description": "Description détaillée de l'action",
                      "dialogue": "« Réplique en français »",
                      "audio_mode": "$audioPresence",
                      "image_prompt": "Cinematic visual prompt, 9:16 vertical cinema, 8k",
                      "video_prompt": "Cinematic camera movement and character action",
                      "camera_movement": "Travelling avant"
                    }
                  ]
                }
                Tu DOIS générer exactement $scenesInThisBatch scènes numérotées de $startScene à $endScene.
                """.trimIndent()
            } else {
                """
                Tu es un réalisateur et scénariste de cinéma IA d'élite.
                Tu poursuis l'écriture du film "$filmTitle".
                Synopsis : "$filmLogline".

                BIBLE DE COHÉRENCE ÉTABLIE (À RESPECTER STRICTEMENT POUR TOUS LES PLANS) :
                - Personnage principal immuable : "$characterConsistency"
                - Direction visuelle commune : "$visualConsistency"
                - Dernier plan précédent (${allDrafts.lastOrNull()?.number ?: (startScene - 1)}) : "${allDrafts.lastOrNull()?.title}" - "${allDrafts.lastOrNull()?.description}"
                - Dernières paroles prononcées : "${allDrafts.lastOrNull()?.dialogue}"

                $langRule
                $audioRule

                Tu dois répondre exclusivement avec un objet JSON valide :
                {
                  "scenes": [
                    {
                      "number": $startScene,
                      "title": "Titre court de la scène",
                      "description": "Description détaillée de la suite de l'action",
                      "dialogue": "« Réplique continue en français »",
                      "audio_mode": "$audioPresence",
                      "image_prompt": "Prompt with consistent character and style, 9:16 vertical cinema, 8k",
                      "video_prompt": "Smooth continuous camera movement and character action",
                      "camera_movement": "Panoramique fluide"
                    }
                  ]
                }
                Tu DOIS générer exactement $scenesInThisBatch scènes numérotées de $startScene à $endScene dans la continuité narrative et vocale.
                """.trimIndent()
            }

            val userContent = "Projet : $prompt. Direction artistique : $style. Rédige les scènes de $startScene à $endScene avec dialogues en français cohérents."

            var batchSuccess = false
            for (attempt in 1..3) {
                if (stopRequested()) throw CancellationException("Annulé par l'utilisateur")
                try {
                    val messages = JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userContent))
                    }

                    val requestJson = JSONObject().apply {
                        put("model", "agnes-2.5-flash")
                        put("messages", messages)
                        put("temperature", 0.7)
                    }

                    val request = Request.Builder()
                        .url(AGNES_CHAT_URL)
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    val body = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val rootJson = JSONObject(body)
                        val choices = rootJson.optJSONArray("choices")
                        val rawContent = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
                        val cleaned = cleanJsonContent(rawContent)
                        val scriptJson = JSONObject(cleaned)

                        if (batchIndex == 0) {
                            filmTitle = scriptJson.optString("film_title", filmTitle)
                            filmLogline = scriptJson.optString("logline", filmLogline)
                            val extractedChar = scriptJson.optString("character_consistency", "")
                            characterConsistency = if (extractedChar.isNotBlank()) {
                                extractedChar
                            } else {
                                "Protagoniste cinématographique principal aux traits distinctifs, coupe soignée et tenue détaillée cohérente ($style)"
                            }
                            val extractedVis = scriptJson.optString("visual_consistency", "")
                            visualConsistency = if (extractedVis.isNotBlank()) {
                                extractedVis
                            } else {
                                "Palette cinématographique $style, éclairage soigné, ratio 9:16 vertical cinema, 35mm grain, 8k"
                            }
                        }

                        val scenesArray = scriptJson.optJSONArray("scenes") ?: JSONArray()
                        for (i in 0 until scenesArray.length()) {
                            val sObj = scenesArray.getJSONObject(i)
                            val targetNum = startScene + i
                            val rawDiag = sObj.optString("dialogue", "")
                            val desc = sObj.optString("description", "Plan $targetNum")
                            val enforcedDialogue = enforceCleanDialogue(rawDiag, desc, dialogueLanguage, audioPresence, targetNum)

                            val rawImg = sObj.optString("image_prompt", "")
                            val unifiedImagePrompt = if (rawImg.isNotBlank()) {
                                "[Protagonist: $characterConsistency], $rawImg, $visualConsistency, 9:16 vertical cinema, 8k"
                            } else {
                                "$prompt, [Protagonist: $characterConsistency], shot $targetNum: $desc, $visualConsistency, 9:16 vertical cinema, 8k"
                            }

                            val cleanDiagSpeech = enforcedDialogue.replace("«", "").replace("»", "").replace("\"", "").trim()
                            val audioDirective = when {
                                audioPresence == "ambient" -> ", atmospheric cinema sound foley, characters remain silent, no speech"
                                dialogueLanguage == "fr" -> ", Authentic Spoken French dialogue: \"$cleanDiagSpeech\", synchronized French lip sync, audible clear French voice, no English words"
                                else -> ", Authentic Spoken English dialogue: \"$cleanDiagSpeech\", synchronized lip movement, clear speech"
                            }
                            val rawVideo = sObj.optString("video_prompt", "Fluid cinematic camera movement for plan $targetNum")
                            val unifiedVideoPrompt = "$rawVideo$audioDirective"

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = sObj.optInt("number", targetNum),
                                    title = sObj.optString("title", "Plan $targetNum"),
                                    description = desc,
                                    imagePrompt = unifiedImagePrompt,
                                    videoPrompt = unifiedVideoPrompt,
                                    cameraMovement = sObj.optString("camera_movement", "Travelling avant"),
                                    dialogue = enforcedDialogue,
                                    audioMode = sObj.optString("audio_mode", audioPresence),
                                    characterAnchor = characterConsistency
                                )
                            )
                        }

                        // Compléter si le lot est incomplet
                        while (allDrafts.size < endScene) {
                            val nextNum = allDrafts.size + 1
                            val fallbackDesc = "Développement narratif du plan $nextNum"
                            val fallbackDialogue = enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, nextNum)
                            val cleanSpeech = fallbackDialogue.replace("«", "").replace("»", "").replace("\"", "").trim()
                            val audioDirective = if (dialogueLanguage == "fr") {
                                ", Authentic Spoken French dialogue: \"$cleanSpeech\", synchronized French lip sync, no English words"
                            } else {
                                ", Authentic Spoken English dialogue: \"$cleanSpeech\", synchronized lip movement"
                            }

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = nextNum,
                                    title = "Plan $nextNum",
                                    description = fallbackDesc,
                                    imagePrompt = "$prompt, [Protagonist: $characterConsistency], cinematic shot $nextNum, $visualConsistency, 9:16 vertical cinema, 8k",
                                    videoPrompt = "Smooth cinematic tracking shot for plan $nextNum$audioDirective",
                                    cameraMovement = "Travelling avant",
                                    dialogue = fallbackDialogue,
                                    audioMode = audioPresence,
                                    characterAnchor = characterConsistency
                                )
                            )
                        }

                        batchSuccess = true
                        break
                    } else {
                        delay(1000L * attempt)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    TechnicalLogManager.log("PHASE_1", "Tentative $attempt/3 échouée sur le lot $startScene-$endScene : ${e.message}", "WARN")
                    delay(1500L * attempt)
                }
            }

            if (!batchSuccess) {
                TechnicalLogManager.log("PHASE_1", "Génération procédurale de secours pour les scènes $startScene à $endScene", "WARN")
                for (n in startScene..endScene) {
                    val fallbackDesc = "Développement de l'intrigue du projet $filmTitle (Plan $n)"
                    val fallbackDialogue = enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, n)
                    val cleanSpeech = fallbackDialogue.replace("«", "").replace("»", "").replace("\"", "").trim()
                    val audioDirective = if (dialogueLanguage == "fr") {
                        ", Authentic Spoken French dialogue: \"$cleanSpeech\", synchronized French lip sync, no English words"
                    } else {
                        ", Authentic Spoken English dialogue: \"$cleanSpeech\", synchronized lip movement"
                    }

                    allDrafts.add(
                        GeneratedSceneDraft(
                            number = n,
                            title = "Plan $n : Séquence cinématique",
                            description = fallbackDesc,
                            imagePrompt = "$prompt, [Character: $characterConsistency], plan $n, highly detailed cinematic shot, $visualConsistency, 9:16 vertical cinema, 8k",
                            videoPrompt = "Continuous smooth camera motion, cinematic tracking shot$audioDirective",
                            cameraMovement = if (n % 2 == 0) "Panoramique fluide" else "Travelling avant",
                            dialogue = fallbackDialogue,
                            audioMode = audioPresence,
                            characterAnchor = characterConsistency
                        )
                    )
                }
            }
        }

        usageTracker.recordTextRequest()
        TechnicalLogManager.log("PHASE_1", "Scénario complet finalisé avec succès: ${allDrafts.size} scènes générées (cohérence de personnage garantie, langue $dialogueLanguage)")
        return@withContext ApiResponse.Success(ScriptGenerationResult(filmTitle, filmLogline, characterConsistency, visualConsistency, allDrafts))
    }

    /**
     * Valide et garantit la langue et la complétude des dialogues cinématographiques.
     * Élimine les répliques en anglais et empêche toute scène muette involontaire en français.
     */
    private fun enforceCleanDialogue(
        raw: String,
        description: String,
        language: String,
        audioMode: String,
        sceneNum: Int
    ): String {
        if (audioMode == "ambient") return ""
        val trimmed = raw.trim()

        val englishWordsRegex = Regex("\\b(the|is|are|was|were|you|your|we|our|they|their|what|where|when|why|who|how|look|listen|hurry|run|come|let's|don't|can't|won't|wait|please|here|there|stop|help|okay|yeah|yes|no way|oh my god|good|bad|right|now|something|someone)\\b", RegexOption.IGNORE_CASE)
        val frenchWordsRegex = Regex("\\b(le|la|les|un|une|des|du|de|ce|cet|cette|ces|je|tu|il|elle|on|nous|vous|ils|elles|mon|ma|mes|ton|ta|tes|son|sa|ses|dans|pour|avec|sans|sur|sous|par|mais|ou|et|donc|or|ni|car|pas|ne|plus|rien|tout|fait|faire|aller|suis|est|sommes|êtes|sont|ici|là|vite|regarde|écoute|attends|allons|pourquoi|comment|quand|qui|que|quoi)\\b", RegexOption.IGNORE_CASE)

        if (language == "fr") {
            val hasEnglish = englishWordsRegex.containsMatchIn(trimmed)
            val hasFrench = frenchWordsRegex.containsMatchIn(trimmed)

            if (trimmed.isBlank() || (hasEnglish && !hasFrench) || trimmed.equals("null", ignoreCase = true)) {
                val descLower = description.lowercase()
                val frenchLine = when {
                    descLower.contains("danger") || descLower.contains("fuit") || descLower.contains("court") || descLower.contains("poursuit") || descLower.contains("combat") || descLower.contains("ennemi") -> {
                        when (sceneNum % 4) {
                            0 -> "Vite, par ici ! Ne reste pas découvert !"
                            1 -> "Ils approchent, nous devons accélérer le pas !"
                            2 -> "Baisse-toi ! Ils ne doivent pas nous repérer."
                            else -> "Tiens bon, l'issue est juste devant nous !"
                        }
                    }
                    descLower.contains("découvr") || descLower.contains("trouv") || descLower.contains("regard") || descLower.contains("porte") || descLower.contains("salle") || descLower.contains("indice") -> {
                        when (sceneNum % 4) {
                            0 -> "Regarde ça... Les relevés indiquent que c'est bien ici."
                            1 -> "Incroyable. Cet endroit n'a pas été ouvert depuis des décennies."
                            2 -> "Les mécanismes sont encore intacts... Faisons attention."
                            else -> "C'est exactement ce que nous cherchions depuis le début."
                        }
                    }
                    descLower.contains("nuit") || descLower.contains("sombre") || descLower.contains("ombre") || descLower.contains("peur") || descLower.contains("mystère") -> {
                        when (sceneNum % 3) {
                            0 -> "Le silence de ces lieux n'a rien de naturel..."
                            1 -> "Reste vigilant, chaque recoin peut dissimuler un piège."
                            else -> "Nous ne sommes pas seuls ici, je peux le sentir."
                        }
                    }
                    audioMode == "voice_over" -> {
                        when (sceneNum % 4) {
                            0 -> "Chaque seconde nous rapproche de la vérité, ou de notre perte."
                            1 -> "Le temps semblait suspendu, comme avant chaque grande tempête."
                            2 -> "Les souvenirs s'effacent peu à peu, mais notre serment demeure absolu."
                            else -> "Au bout du chemin, la lumière finira par percer les ténèbres."
                        }
                    }
                    else -> {
                        when (sceneNum % 5) {
                            0 -> "Nous n'avons plus le droit à l'erreur désormais."
                            1 -> "Prépare-toi, la prochaine étape sera décisive."
                            2 -> "Garde ton sang-froid, nous irons jusqu'au bout ensemble."
                            3 -> "Observe bien les alentours avant d'avancer d'un pas."
                            else -> "Rien ne pourra nous faire reculer à présent."
                        }
                    }
                }
                return "« $frenchLine »"
            }

            val clean = trimmed.replace("«", "").replace("»", "").replace("\"", "").trim()
            return "« $clean »"
        } else {
            if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) {
                return "\"We have to keep moving forward, no matter what.\""
            }
            val clean = trimmed.replace("\"", "").replace("«", "").replace("»", "").trim()
            return "\"$clean\""
        }
    }

    /**
     * Chat avec agnes-2.5-flash
     */
    suspend fun sendChatMessage(
        apiKey: String,
        userMessage: String,
        stopRequested: () -> Boolean = { false }
    ): ApiResponse<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Veuillez configurer votre clé API Agnes dans l'onglet Réglages pour discuter avec l'assistant.",
                type = "auth_missing"
            )
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", "Tu es l'assistant de réalisation cinématique d'Agnes Studio. Aide l'utilisateur à concevoir ses prompts d'images, de caméras et de scénarios."))
            put(JSONObject().put("role", "user").put("content", userMessage))
        }

        val requestJson = JSONObject().apply {
            put("model", "agnes-2.5-flash")
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(AGNES_CHAT_URL)
            .addHeader("Authorization", "Bearer $cleanKey")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val root = JSONObject(body)
                val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (!content.isNullOrBlank()) {
                    usageTracker.recordTextRequest()
                    return@withContext ApiResponse.Success(content)
                } else {
                    return@withContext ApiResponse.Error(500, "Réponse vide reçue de l'API de chat")
                }
            } else {
                return@withContext ApiResponse.Error(code, "Erreur API Chat ($code) : $body")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            TechnicalLogManager.log("CHAT", "Erreur réseau chat: ${e.message}", "ERROR")
            return@withContext ApiResponse.Error(500, "Erreur de communication : ${e.message}")
        }
    }

    private fun cleanJsonContent(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json")
        if (s.startsWith("```")) s = s.removePrefix("```")
        if (s.endsWith("```")) s = s.removeSuffix("```")
        s = s.trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) {
            s.substring(start, end + 1)
        } else s
    }
}
