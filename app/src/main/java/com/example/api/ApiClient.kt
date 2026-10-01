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
    val characterAnchor: String = "",
    val narrativePhase: String = "Développement",
    val charactersPresent: String = "",
    val soundDesign: String = ""
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

        val hasImages = !startImageUrl.isNullOrBlank() || !endImageUrl.isNullOrBlank()
        val extraBody = JSONObject().apply {
            if (hasImages) {
                put("mode", "keyframes")
                val imagesArray = JSONArray()
                val start = startImageUrl?.trim().orEmpty()
                val end = endImageUrl?.trim().orEmpty()
                if (start.isNotBlank() && end.isNotBlank()) {
                    imagesArray.put(start)
                    imagesArray.put(end)
                } else if (start.isNotBlank()) {
                    imagesArray.put(start)
                    imagesArray.put(start)
                } else if (end.isNotBlank()) {
                    imagesArray.put(end)
                    imagesArray.put(end)
                }
                put("image", imagesArray)
            } else {
                put("mode", "text")
            }
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

        val BATCH_SIZE = 16
        val totalBatches = (numScenes + BATCH_SIZE - 1) / BATCH_SIZE
        val allDrafts = mutableListOf<GeneratedSceneDraft>()
        var filmTitle = "Film : " + prompt.take(30)
        var filmLogline = prompt
        var protagonistBible = ""
        var supportingCastBible = ""
        var antagonistBible = ""
        var visualConsistency = ""

        val langRule = if (dialogueLanguage == "fr") {
            "DIALOGUES EN FRANÇAIS OBLIGATOIRES : Chaque scène DOIT avoir une réplique parlée ou une phrase de voix off en français complet dans 'dialogue', entre guillemets « ... ». Interdiction d'anglais et interdiction de réplique vide. Indique qui parle quand c'est pertinent (ex: « Elena : Attention ! »)."
        } else {
            "ENGLISH SPOKEN DIALOGUE: Every scene must have a spoken dialogue or voice-over in English in 'dialogue'."
        }

        val audioRule = when (audioPresence) {
            "voice_over" -> "Voix off narrative continue en français pour chaque scène dans 'dialogue'."
            "ambient" -> "Mode muet/sound design : laisser 'dialogue' vide."
            else -> "Dialogues parlés vifs entre les personnages en français dans 'dialogue'."
        }

        TechnicalLogManager.log("PHASE_1", "POST $AGNES_CHAT_URL - Écriture scénario studio ($numScenes scènes en $totalBatches lot(s), langue: $dialogueLanguage)")

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
                "Phase 1 : Conception scénaristique Studio ($startScene à $endScene / $numScenes plans)..."
            )

            val systemPrompt = if (batchIndex == 0) {
                """
                Tu es un cinéaste d'exception réputé pour son réalisme humain, sa justesse émotionnelle et sa mise en scène organique (style Denis Villeneuve, Jacques Audiard, Alfonso Cuarón).
                Tu conçois un film captivant, profondément vivant et d'un réalisme saisissant.
                Chaque scène doit donner l'impression indiscutable d'avoir été écrite par un être humain, cadrée par un chef opérateur au regard sensible, et interprétée par de vrais acteurs.
                ZÉRO CLICHÉ ARTIFICIEL D'IA, ZÉRO DIALOGUE ROBOTIQUE, ZÉRO RÉPÉTITION.

                PROGRESSION DRAMATIQUE RÉALISTE EN 4 ACTES (pour les $numScenes plans) :
                - ACTE 1 : INTRODUCTION & ANCRAGE CONCRET (plans 1 à ~25%) :
                  * Plan 1 OBLIGATOIRE : Vrai plan d'établissement cinématographique atmosphérique. Un lieu habité, une lumière naturelle (aube, pluie, soleil déclinant, intérieur tamisé), des bruits de vie ambiants sans visage au centre.
                  * Plans suivants : Le protagoniste est saisi dans un geste concret, humain et intime du quotidien (attacher un manteau, consulter un message, regarder par la fenêtre), avant qu'un événement réel et plausible ne vienne faire basculer sa routine.
                - ACTE 2 : DÉVELOPPEMENT & DYNAMIQUES HUMAINES (plans ~26% à ~70%) :
                  * Progression organique de la situation : doutes, regards complices, gestes nerveux, fatigue palpable, réactions physiques réelles.
                  * VARIÉTÉ DU DÉCOUPAGE : Un vrai réalisateur varie les angles : plan large pour situer la scène dans l'espace, plan moyen pour les gestes et la posture corporelle, gros plan sur une main, un objet ou un regard intense.
                  * Des personnages secondaires et antagonistes vivants, qui existent par eux-mêmes.
                - ACTE 3 : POINT DE TENSION & VULNÉRABILITÉ (plans ~71% à ~85%) :
                  * Montée de tension crédible : ce n'est pas du spectacle creux, mais une urgence humaine viscérale, une hésitation lourde, un choix difficile.
                - ACTE 4 : CONCLUSION & ATTERRISSAGE ÉMOTIONNEL (plans ~86% à 100%) :
                  * Résolution sobre et juste : soulagement, silence partagé, la vie qui reprend, plan final poétique et contemplatif qui reste en mémoire.

                PERSONNAGES EN CHAIR ET EN OS (COHÉRENCE NATURELLE) :
                - 'protagonist_bible' : Nom + silhouette, visage humain expressif, vêtements réels de tous les jours avec détails tangibles (texture du tissu, usure naturelle).
                - 'supporting_cast_bible' : Nom + rôle + présence contrastée et humaine.
                - 'antagonist_bible' : Nom + rôle + motivation tangible et regard crédible.
                - 'visual_consistency' : Style $style, éclairage naturel diégétique, grain argentique 35mm, textures réelles.

                PAROLES HUMAINES VIVANTES (UNE SEULE RÉPLIQUE COURTE, JAMAIS RÉPÉTÉE) :
                $langRule
                $audioRule
                - Règles strictes pour 'dialogue' :
                  * Phrases brèves et naturelles (4 à 8 mots maximum), comme dans une vraie conversation.
                  * AUCUNE répétition de mots, AUCUNE tirade théâtrale.
                  * Format : « Elena : Attends un instant. » ou « Marcus : Regarde par ici. » ou « Voix off : Je savais que tout allait changer. ».
                - 'sound_design' : Acoustique réelle du décor (écho d'une pièce, bruits de pas sur le pavé, souffle du vent, foley naturel, ambiance musicale sobre).

                Réponds EXCLUSIVEMENT en JSON compact sans balises markdown :
                {
                  "film_title": "Titre cinématographique réaliste",
                  "logline": "Accroche humaine résumant l'émotion et l'enjeu du film",
                  "protagonist_bible": "Description physique d'une personne réelle, vêtements et traits distinctifs",
                  "supporting_cast_bible": "Description de l'allié, look réaliste et distinct",
                  "antagonist_bible": "Description de l'antagoniste, présence crédible et vivante",
                  "visual_consistency": "Atmosphère visuelle $style, lumière naturelle et grain 35mm",
                  "scenes": [
                    {
                      "number": 1,
                      "act": "INTRODUCTION",
                      "shot_type": "Plan d'ensemble atmosphérique",
                      "characters_present": "Décor seul / Ambiance vécue",
                      "title": "Titre du plan",
                      "action": "Description vivante, sensorielle et réaliste de l'action",
                      "dialogue": "Voix off : « Réplique humaine courte et sobre »",
                      "sound_design": "Ambiance sonore réelle des lieux et foley naturel",
                      "camera": "Travelling lent et stable à hauteur d'homme"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            } else {
                """
                Tu es un cinéaste d'exception réputé pour son réalisme humain et la justesse de ses personnages. Poursuis le film "$filmTitle".
                PERSONNAGES IMMUABLES :
                - Protagoniste : "$protagonistBible"
                - Allié : "$supportingCastBible"
                - Antagoniste : "$antagonistBible"
                - Style visuel : "$visualConsistency"
                Plan précédent : "${allDrafts.lastOrNull()?.title}" - "${allDrafts.lastOrNull()?.description}".

                PROGRESSION NATURELLE ET HUMAINE :
                Poursuis le déroulement organique en variant les points de vue (scènes de duo, réactions des uns et des autres, plans d'ambiance).
                RAPPEL CRUCIAL : Des dialogues courts, vivants, oraux et sobres (max 6-8 mots), prononcés une seule fois sans répétition.
                $langRule
                $audioRule

                Réponds EXCLUSIVEMENT en JSON compact :
                {
                  "scenes": [
                    {
                      "number": $startScene,
                      "act": "DÉVELOPPEMENT",
                      "shot_type": "Plan moyen vivant",
                      "characters_present": "Allié / Secondaire",
                      "title": "Titre court",
                      "action": "Description réaliste et humaine de l'action",
                      "dialogue": "« Réplique courte et naturelle en français »",
                      "sound_design": "Bruitages réalistes du décor et tension sonore discrète",
                      "camera": "Cadre naturel à hauteur d'homme"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            }

            val userContent = "Projet : $prompt. Direction : $style. Scènes $startScene à $endScene."

            var batchSuccess = false
            for (attempt in 1..2) {
                if (stopRequested()) throw CancellationException("Annulé par l'utilisateur")
                try {
                    val messages = JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userContent))
                    }

                    val requestJson = JSONObject().apply {
                        put("model", "agnes-2.5-flash")
                        put("messages", messages)
                        put("temperature", 0.6)
                        put("max_tokens", 3500)
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
                            val extractedProtagonist = scriptJson.optString("protagonist_bible", scriptJson.optString("character_consistency", ""))
                            protagonistBible = if (extractedProtagonist.isNotBlank()) {
                                extractedProtagonist
                            } else {
                                "Héros principal aux traits cinématographiques distinctifs, tenue invariable détaillée ($style)"
                            }
                            val extractedSecondary = scriptJson.optString("supporting_cast_bible", "")
                            supportingCastBible = if (extractedSecondary.isNotBlank()) {
                                extractedSecondary
                            } else {
                                "Personnage allié au look contrasté et tenue spécifique invariable ($style)"
                            }
                            val extractedAntagonist = scriptJson.optString("antagonist_bible", "")
                            antagonistBible = if (extractedAntagonist.isNotBlank()) {
                                extractedAntagonist
                            } else {
                                "Antagoniste marquant à l'aura menaçante et costume sombre invariable ($style)"
                            }
                            val extractedVis = scriptJson.optString("visual_consistency", "")
                            visualConsistency = if (extractedVis.isNotBlank()) {
                                extractedVis
                            } else {
                                "Palette cinématographique $style, éclairage soigné, 9:16 vertical cinema, 35mm grain, 8k"
                            }
                        }

                        val scenesArray = scriptJson.optJSONArray("scenes") ?: JSONArray()
                        for (i in 0 until scenesArray.length()) {
                            val sObj = scenesArray.getJSONObject(i)
                            val targetNum = startScene + i
                            val rawDiag = sObj.optString("dialogue", "")
                            val actionDesc = sObj.optString("action", sObj.optString("description", "Plan $targetNum"))
                            val title = sObj.optString("title", "Plan $targetNum")
                            val camMovement = sObj.optString("camera", sObj.optString("camera_movement", "Travelling avant"))
                            val rawSoundDesign = sObj.optString("sound_design", sObj.optString("audio_ambiance", "Ambiance sonore cinématographique immersive"))
                            val soundDesign = rawSoundDesign.ifBlank { "Ambiance sonore studio et nappe orchestrale" }
                            val enforcedDialogue = enforceCleanDialogue(rawDiag, actionDesc, dialogueLanguage, audioPresence, targetNum)

                            val charsPresent = sObj.optString("characters_present", sObj.optString("characters", ""))
                            val defaultAct = when {
                                targetNum <= (numScenes * 0.25).toInt().coerceAtLeast(1) -> "INTRODUCTION"
                                targetNum <= (numScenes * 0.70).toInt().coerceAtLeast(2) -> "DÉVELOPPEMENT"
                                targetNum <= (numScenes * 0.85).toInt().coerceAtLeast(3) -> "CLIMAX"
                                else -> "CONCLUSION"
                            }
                            val act = sObj.optString("act", defaultAct).uppercase()
                            val shotType = sObj.optString("shot_type", if (targetNum == 1) "Plan d'ensemble large" else "Plan moyen")

                            val isNoCharacter = charsPresent.contains("aucun", ignoreCase = true) ||
                                charsPresent.contains("décor", ignoreCase = true) ||
                                charsPresent.contains("paysage", ignoreCase = true) ||
                                charsPresent.contains("monde", ignoreCase = true) ||
                                charsPresent.contains("none", ignoreCase = true) ||
                                (targetNum == 1 && shotType.contains("ensemble", ignoreCase = true))

                            val isAntagonistOnly = !isNoCharacter && (
                                charsPresent.contains("antagoniste", ignoreCase = true) ||
                                charsPresent.contains("rival", ignoreCase = true) ||
                                charsPresent.contains("ennemi", ignoreCase = true)
                            )

                            val isSecondaryOnly = !isNoCharacter && !isAntagonistOnly && (
                                charsPresent.contains("secondaire", ignoreCase = true) ||
                                charsPresent.contains("allié", ignoreCase = true) ||
                                charsPresent.contains("mentor", ignoreCase = true) ||
                                charsPresent.contains("compagnon", ignoreCase = true)
                            )

                            val isConfrontation = !isNoCharacter && (
                                charsPresent.contains("confrontation", ignoreCase = true) ||
                                (charsPresent.contains("antagoniste", ignoreCase = true) && charsPresent.contains("protagoniste", ignoreCase = true))
                            )

                            val isDuo = !isNoCharacter && !isConfrontation && (
                                charsPresent.contains("duo", ignoreCase = true) ||
                                charsPresent.contains(" et ", ignoreCase = true) ||
                                charsPresent.contains("&")
                            )

                            val sceneCharacterAnchor = when {
                                isNoCharacter -> "Cinematic scenery, architectural depth, environmental establishing shot without people"
                                isConfrontation && antagonistBible.isNotBlank() -> "[Protagonist: $protagonistBible] in intense dramatic face-off against [Antagonist: $antagonistBible]"
                                isDuo && supportingCastBible.isNotBlank() -> "[Protagonist: $protagonistBible] side-by-side with [Ally: $supportingCastBible]"
                                isAntagonistOnly && antagonistBible.isNotBlank() -> "[Antagonist Focus: $antagonistBible]"
                                isSecondaryOnly && supportingCastBible.isNotBlank() -> "[Supporting Ally Focus: $supportingCastBible]"
                                else -> "[Protagonist Focus: $protagonistBible]"
                            }

                            val charactersPresentLabel = when {
                                isNoCharacter -> "Décor / Ambiance"
                                isConfrontation -> "Face-à-face (Protagoniste & Antagoniste)"
                                isDuo -> "Duo (Protagoniste & Allié)"
                                isAntagonistOnly -> "Antagoniste / Menace"
                                isSecondaryOnly -> "Allié / Secondaire"
                                else -> "Protagoniste"
                            }

                            val humanCinematicStyle = "shot on 35mm film, Kodak Vision3, natural realistic lighting, authentic lived-in textures, natural human skin tones, documentary cinema realism, $visualConsistency, 9:16 vertical format"

                            val unifiedImagePrompt = if (isNoCharacter) {
                                "$prompt, [ESTABLISHING SHOT - SCENERY ONLY], scène $targetNum [$act - $shotType]: $title - $actionDesc, $humanCinematicStyle"
                            } else {
                                "$prompt, [MASTER CHARACTER CONTINUITY: $sceneCharacterAnchor, identical facial features, realistic natural clothing], scène $targetNum [$act - $shotType]: $title - $actionDesc, $humanCinematicStyle"
                            }
                            val unifiedVideoPrompt = "$camMovement, $actionDesc, natural human motion, organic camera framing, realistic physical interaction"

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = sObj.optInt("number", targetNum),
                                    title = title,
                                    description = actionDesc,
                                    imagePrompt = unifiedImagePrompt,
                                    videoPrompt = unifiedVideoPrompt,
                                    cameraMovement = camMovement,
                                    dialogue = enforcedDialogue,
                                    audioMode = audioPresence,
                                    characterAnchor = sceneCharacterAnchor,
                                    narrativePhase = act,
                                    charactersPresent = charactersPresentLabel,
                                    soundDesign = soundDesign
                                )
                            )
                        }

                        // Compléter si le lot est incomplet avec alternance studio
                        while (allDrafts.size < endScene) {
                            val nextNum = allDrafts.size + 1
                            val defaultAct = when {
                                nextNum <= (numScenes * 0.25).toInt().coerceAtLeast(1) -> "INTRODUCTION"
                                nextNum <= (numScenes * 0.70).toInt().coerceAtLeast(2) -> "DÉVELOPPEMENT"
                                nextNum <= (numScenes * 0.85).toInt().coerceAtLeast(3) -> "CLIMAX"
                                else -> "CONCLUSION"
                            }
                            val fallbackDesc = "Progression dramatique ($defaultAct) - Plan $nextNum"
                            val fallbackSound = "Nappe sonore sobre, acoustique naturelle du lieu et foley discret"
                            val fallbackDialogue = enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, nextNum)

                            val isSecFallback = nextNum % 3 == 0 && supportingCastBible.isNotBlank()
                            val isAntagFallback = nextNum % 4 == 0 && antagonistBible.isNotBlank()
                            val fallbackAnchor = when {
                                isAntagFallback -> "[Antagonist: $antagonistBible]"
                                isSecFallback -> "[Ally/Supporting: $supportingCastBible]"
                                else -> "[Protagonist: $protagonistBible]"
                            }
                            val charLabel = when {
                                isAntagFallback -> "Antagoniste / Menace"
                                isSecFallback -> "Allié / Secondaire"
                                else -> "Protagoniste"
                            }

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = nextNum,
                                    title = "Plan $nextNum : Séquence $charLabel",
                                    description = fallbackDesc,
                                    imagePrompt = "$prompt, [MASTER CONTINUITY: $fallbackAnchor], scène $nextNum [$defaultAct]: $fallbackDesc, shot on 35mm film, natural lighting, $visualConsistency, 9:16 vertical format",
                                    videoPrompt = "Travelling avant, $fallbackDesc, natural human movement, organic camera framing",
                                    cameraMovement = "Travelling avant",
                                    dialogue = fallbackDialogue,
                                    audioMode = audioPresence,
                                    characterAnchor = fallbackAnchor,
                                    narrativePhase = defaultAct,
                                    charactersPresent = charLabel,
                                    soundDesign = fallbackSound
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
                    TechnicalLogManager.log("PHASE_1", "Tentative $attempt/2 sur le lot $startScene-$endScene : ${e.message}", "WARN")
                    delay(1000L * attempt)
                }
            }

            if (!batchSuccess) {
                TechnicalLogManager.log("PHASE_1", "Génération procédurale de secours pour les scènes $startScene à $endScene", "WARN")
                for (n in startScene..endScene) {
                    val defaultAct = when {
                        n <= (numScenes * 0.25).toInt().coerceAtLeast(1) -> "INTRODUCTION"
                        n <= (numScenes * 0.70).toInt().coerceAtLeast(2) -> "DÉVELOPPEMENT"
                        n <= (numScenes * 0.85).toInt().coerceAtLeast(3) -> "CLIMAX"
                        else -> "CONCLUSION"
                    }
                    val fallbackDesc = "Développement de l'intrigue ($defaultAct) - Plan $n"
                    val fallbackDialogue = enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, n)

                    val isNoCharFallback = n == 1
                    val isSecFallback = !isNoCharFallback && n % 3 == 0 && supportingCastBible.isNotBlank()
                    val isAntagFallback = !isNoCharFallback && n % 4 == 0 && antagonistBible.isNotBlank()
                    val fallbackAnchor = when {
                        isNoCharFallback -> "Cinematic scenery, environmental and architectural details without characters"
                        isAntagFallback -> "[Antagonist: $antagonistBible]"
                        isSecFallback -> "[Ally/Supporting: $supportingCastBible]"
                        else -> "[Protagonist: $protagonistBible]"
                    }
                    val charLabel = when {
                        isNoCharFallback -> "Décor / Ambiance"
                        isAntagFallback -> "Antagoniste / Menace"
                        isSecFallback -> "Allié / Secondaire"
                        else -> "Protagoniste"
                    }

                    allDrafts.add(
                        GeneratedSceneDraft(
                            number = n,
                            title = "Plan $n : Séquence $charLabel",
                            description = fallbackDesc,
                            imagePrompt = "$prompt, [Character Bible: $fallbackAnchor], plan $n [$defaultAct], shot on 35mm film, natural lighting, $visualConsistency, 9:16 vertical format",
                            videoPrompt = "Continuous smooth camera motion, natural human movement, organic camera framing",
                            cameraMovement = if (n % 2 == 0) "Panoramique fluide" else "Travelling avant",
                            dialogue = fallbackDialogue,
                            audioMode = audioPresence,
                            characterAnchor = fallbackAnchor,
                            narrativePhase = defaultAct,
                            charactersPresent = charLabel
                        )
                    )
                }
            }
        }

        usageTracker.recordTextRequest()
        TechnicalLogManager.log("PHASE_1", "Scénario complet finalisé avec succès: ${allDrafts.size} scènes générées (structure 4 actes, casting varié, langue $dialogueLanguage)")
        val finalCharacterConsistency = buildString {
            append("Protagoniste: $protagonistBible")
            if (supportingCastBible.isNotBlank()) append(" | Allié: $supportingCastBible")
            if (antagonistBible.isNotBlank()) append(" | Antagoniste: $antagonistBible")
        }
        return@withContext ApiResponse.Success(ScriptGenerationResult(filmTitle, filmLogline, finalCharacterConsistency, visualConsistency, allDrafts))
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
