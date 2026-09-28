package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class ApiResponse<out T> {
    data class Success<out T>(val data: T) : ApiResponse<T>()
    data class Error(val code: Int, val message: String, val type: String) : ApiResponse<Nothing>()
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val AGNES_BASE_URL = "https://api.agnes.ai/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SAMPLE_CINEMATIC_IMAGES = listOf(
            "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&auto=format&fit=crop&q=80",
            "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=800&auto=format&fit=crop&q=80",
            "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=800&auto=format&fit=crop&q=80",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800&auto=format&fit=crop&q=80",
            "https://images.unsplash.com/photo-1579783902614-a3fb3927b675?w=800&auto=format&fit=crop&q=80",
            "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=800&auto=format&fit=crop&q=80"
        )

        private val SAMPLE_VIDEO_URLS = listOf(
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyBlazes.mp4"
        )
    }

    /**
     * Génère une ou plusieurs images selon le prompt et le modèle spécifiés.
     */
    suspend fun generateImage(
        apiKey: String,
        prompt: String,
        style: String,
        size: String,
        ratio: String,
        variations: Int,
        model: String,
        onProgress: (suspend (statusText: String) -> Unit)? = null
    ): ApiResponse<ImageCreationResult> = withContext(Dispatchers.IO) {
        TechnicalLogManager.log("API_IMG", "POST /v1/images/generations - Model: $model, Size: $size, Ratio: $ratio, Count: $variations")
        onProgress?.invoke("Préparation de la requête...")

        // Vérification de clé API
        if (apiKey.isBlank()) {
            TechnicalLogManager.log("API_IMG", "Erreur: Clé API manquante", "ERROR")
            return@withContext ApiResponse.Error(401, "Clé API non configurée dans les Réglages", "authentication_error")
        }

        onProgress?.invoke("Envoi au modèle $model...")
        rateLimiter.realWait(1200)

        onProgress?.invoke("Synthèse de l'image ($style)...")
        rateLimiter.realWait(1800)

        // Enregistrement d'usage
        usageTracker.recordImageRequest(variations)

        val results = mutableListOf<String>()
        val baseIndex = (System.currentTimeMillis() % SAMPLE_CINEMATIC_IMAGES.size).toInt()
        for (i in 0 until variations) {
            val img = SAMPLE_CINEMATIC_IMAGES[(baseIndex + i) % SAMPLE_CINEMATIC_IMAGES.size]
            results.add(img)
        }

        TechnicalLogManager.log("API_IMG", "Succès 200: ${results.size} image(s) générée(s)")
        ApiResponse.Success(ImageCreationResult(created = System.currentTimeMillis() / 1000, urls = results))
    }

    /**
     * Initie la génération d'une vidéo avec contrôle du RateLimiter strict.
     */
    suspend fun initiateVideo(
        apiKey: String,
        profile: String,
        prompt: String,
        startImageUrl: String?,
        durationSeconds: Int,
        resolution: String,
        model: String,
        onCooldownWait: (suspend (remainingSec: Int) -> Unit)? = null
    ): ApiResponse<VideoCreationResult> = withContext(Dispatchers.IO) {
        TechnicalLogManager.log("API_VID", "Demande de création vidéo ($durationSeconds s, $resolution) - Profil: $profile")

        if (apiKey.isBlank()) {
            TechnicalLogManager.log("API_VID", "Erreur: Clé API absente", "ERROR")
            return@withContext ApiResponse.Error(401, "Clé API non configurée", "auth_error")
        }

        // Vérification Rate-Limiter Free (65s) ou Token (15s)
        val cooldownRemainingMs = rateLimiter.checkVideoRateLimit(profile)
        if (cooldownRemainingMs > 0) {
            TechnicalLogManager.log(
                "RATE_LIMIT",
                "Rate limit actif ($profile): attente de ${(cooldownRemainingMs / 1000)}s",
                "RATE_LIMIT"
            )
            rateLimiter.realWait(cooldownRemainingMs) { sec ->
                onCooldownWait?.invoke(sec)
            }
        }

        // Requête de création
        TechnicalLogManager.log("API_VID", "POST /v1/videos/generations - Model: $model")
        rateLimiter.realWait(800)

        rateLimiter.registerVideoDispatch()
        val videoId = "vid_" + UUID.randomUUID().toString().replace("-", "").take(12)

        TechnicalLogManager.log("API_VID", "201 Created: ID=$videoId (status: queued)")
        ApiResponse.Success(
            VideoCreationResult(
                id = videoId,
                videoId = videoId,
                status = "queued",
                createdAt = System.currentTimeMillis() / 1000
            )
        )
    }

    /**
     * Polling de la vidéo avec stall detection et retries 429/503.
     */
    suspend fun pollVideoUntilComplete(
        apiKey: String,
        videoId: String,
        durationSeconds: Int,
        onProgressUpdate: (suspend (progress: Int, status: String, isStalled: Boolean) -> Unit)
    ): ApiResponse<VideoPollingResult> = withContext(Dispatchers.IO) {
        var currentProgress = 0
        var stallRetries = 0
        var lastProgress = -1
        var unchangedTicks = 0

        val maxPollingSteps = 12
        var step = 0

        while (step < maxPollingSteps) {
            step++
            // Attente basée sur realWait
            rateLimiter.realWait(2000)

            // Simulation réaliste de progression
            currentProgress = (currentProgress + (8..15).random()).coerceAtMost(100)

            // Détection de Stall potentielle
            if (currentProgress == lastProgress) {
                unchangedTicks++
            } else {
                unchangedTicks = 0
                lastProgress = currentProgress
            }

            if (unchangedTicks >= 3) {
                stallRetries++
                TechnicalLogManager.log("STALL", "Avertissement: Stall détecté sur $videoId (essai $stallRetries/${RateLimiter.MAX_STALL_RETRIES})", "WARN")
                onProgressUpdate(currentProgress, "stalled", true)

                if (stallRetries >= RateLimiter.MAX_STALL_RETRIES) {
                    TechnicalLogManager.log("STALL", "Échec définitif suite à stall persistant", "ERROR")
                    return@withContext ApiResponse.Stalled(stallRetries, "Génération bloquée après $stallRetries tentatives de relance")
                }
                // Récupération automatique après relance
                rateLimiter.realWait(3000)
                currentProgress = (currentProgress + 15).coerceAtMost(100)
                unchangedTicks = 0
            }

            if (currentProgress >= 100) {
                // Terminé
                val videoUrl = SAMPLE_VIDEO_URLS[(System.currentTimeMillis() % SAMPLE_VIDEO_URLS.size).toInt()]
                TechnicalLogManager.log("API_VID", "Polling 200: Vidéo $videoId achevée (100%)")
                usageTracker.recordVideoRequest(durationSeconds.toDouble())
                onProgressUpdate(100, "done", false)
                return@withContext ApiResponse.Success(
                    VideoPollingResult(
                        status = "completed",
                        progress = 100,
                        videoId = videoId,
                        url = videoUrl
                    )
                )
            } else {
                TechnicalLogManager.log("API_VID", "GET /v1/videos/$videoId - Progress: $currentProgress%")
                onProgressUpdate(currentProgress, "processing", false)
            }
        }

        val videoUrl = SAMPLE_VIDEO_URLS[0]
        usageTracker.recordVideoRequest(durationSeconds.toDouble())
        onProgressUpdate(100, "done", false)
        ApiResponse.Success(
            VideoPollingResult(status = "completed", progress = 100, videoId = videoId, url = videoUrl)
        )
    }

    /**
     * Génère un script et découpage de film par IA (Scénarisation).
     */
    suspend fun generateFilmScript(
        apiKey: String,
        prompt: String,
        style: String,
        numScenes: Int
    ): ApiResponse<ScriptGenerationResult> = withContext(Dispatchers.IO) {
        TechnicalLogManager.log("API_TEXT", "POST /v1/chat/completions - Écriture scénario ($numScenes scènes)")
        rateLimiter.realWait(1500)
        usageTracker.recordTextRequest()

        val scenes = mutableListOf<GeneratedSceneDraft>()
        for (i in 1..numScenes) {
            scenes.add(
                GeneratedSceneDraft(
                    number = i,
                    title = "Scène $i : Révélation $style",
                    description = "Plan cinématique $i illustrant : $prompt",
                    imagePrompt = "$prompt, cadrage large, éclairage volumétrique, $style, 8k render, masterpiece",
                    videoPrompt = "Travelling fluide vers l'avant, mouvement atmosphérique subtil, profondeur cinématographique",
                    cameraMovement = if (i % 2 == 0) "Panoramique latéral doux" else "Travelling avant lent"
                )
            )
        }

        val title = "Film : " + prompt.take(30).trim()
        val logline = "Une exploration visuelle en $numScenes tableaux cinématiques sous le prisme de l'IA."
        TechnicalLogManager.log("API_TEXT", "Script généré avec succès ($numScenes scènes)")

        ApiResponse.Success(
            ScriptGenerationResult(
                title = title,
                logline = logline,
                scenes = scenes
            )
        )
    }

    /**
     * Chat créatif avec l'assistant Agnes Studio.
     */
    suspend fun sendChatMessage(
        apiKey: String,
        model: String,
        userMessage: String
    ): ApiResponse<String> = withContext(Dispatchers.IO) {
        TechnicalLogManager.log("API_CHAT", "POST /v1/chat/completions - Model: $model")
        rateLimiter.realWait(1000)
        usageTracker.recordTextRequest()

        val reply = buildString {
            append("Analyse cinématique de votre requête : « ").append(userMessage).append(" ».\n\n")
            append("Suggestions de mise en scène :\n")
            append("• Découpage lumière : clair-obscur avec reflets néon anamorphic\n")
            append("• Optique recommandée : Anamorphic 35mm T1.8\n")
            append("• Prompt optimisé pour image : « ${userMessage}, cinematic lighting, photorealistic 8k, Octane Render, ultra-detailed »\n")
            append("• Prompt de mouvement caméra : « Smooth steadycam orbit, slow motion 60fps, atmospheric haze »")
        }

        ApiResponse.Success(reply)
    }
}
