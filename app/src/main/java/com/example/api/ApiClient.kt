package com.example.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val scenes: List<GeneratedSceneDraft>
)

data class GeneratedSceneDraft(
    val number: Int,
    val title: String,
    val description: String,
    val imagePrompt: String,
    val videoPrompt: String,
    val cameraMovement: String
)

class ApiClient(
    private val rateLimiter: RateLimiter,
    private val usageTracker: UsageTracker
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
     */
    suspend fun generateFilmScript(
        apiKey: String,
        prompt: String,
        style: String,
        numScenes: Int,
        stopRequested: () -> Boolean = { false }
    ): ApiResponse<ScriptGenerationResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ApiResponse.Error(
                code = 401,
                message = "Clé API manquante pour générer le script du film.",
                type = "auth_missing"
            )
        }

        TechnicalLogManager.log("PHASE_1", "POST $AGNES_CHAT_URL - Écriture scénario ($numScenes scènes, style $style)")

        val systemPrompt = """
            Tu es un réalisateur et scénariste de cinéma IA d'élite.
            Tu dois répondre exclusivement avec un objet JSON valide, sans formatage markdown additionnel :
            {
              "film_title": "Titre cinématographique",
              "logline": "Accroche narrative résumant l'intrigue en une phrase",
              "scenes": [
                {
                  "number": 1,
                  "title": "Titre court de la scène",
                  "description": "Description détaillée de l'action",
                  "image_prompt": "Prompt anglais cinématique ultra-précis pour l'image clé",
                  "video_prompt": "Prompt anglais cinématique décrivant le mouvement continu de la caméra",
                  "camera_movement": "Nom technique du mouvement de caméra"
                }
              ]
            }
            Tu DOIS générer exactement $numScenes scènes numérotées de 1 à $numScenes.
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", "Projet de film : $prompt. Direction artistique : $style."))
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

        try {
            val response = okHttpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val rootJson = JSONObject(body)
                val choices = rootJson.optJSONArray("choices")
                val rawContent = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()

                val cleaned = cleanJsonContent(rawContent)
                val scriptJson = JSONObject(cleaned)
                val title = scriptJson.optString("film_title", "Film : " + prompt.take(30))
                val logline = scriptJson.optString("logline", prompt)
                val scenesArray = scriptJson.optJSONArray("scenes") ?: JSONArray()

                val drafts = mutableListOf<GeneratedSceneDraft>()
                for (i in 0 until scenesArray.length()) {
                    val sObj = scenesArray.getJSONObject(i)
                    drafts.add(
                        GeneratedSceneDraft(
                            number = sObj.optInt("number", i + 1),
                            title = sObj.optString("title", "Scène ${i + 1}"),
                            description = sObj.optString("description", ""),
                            imagePrompt = sObj.optString("image_prompt", prompt),
                            videoPrompt = sObj.optString("video_prompt", "Slow cinematic tracking shot"),
                            cameraMovement = sObj.optString("camera_movement", "Travelling avant")
                        )
                    )
                }

                if (drafts.isNotEmpty()) {
                    usageTracker.recordTextRequest()
                    TechnicalLogManager.log("PHASE_1", "Script validé avec succès: ${drafts.size} scènes générées")
                    return@withContext ApiResponse.Success(ScriptGenerationResult(title, logline, drafts))
                } else {
                    return@withContext ApiResponse.Error(500, "Le script retourné par l'IA ne contient aucune scène exploitable")
                }
            } else {
                return@withContext ApiResponse.Error(code, "Erreur lors de la scénarisation ($code) : $body")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            TechnicalLogManager.log("PHASE_1", "Erreur réseau script: ${e.message}", "ERROR")
            return@withContext ApiResponse.Error(500, "Erreur réseau lors de la communication avec agnes-2.5-flash : ${e.message}")
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
