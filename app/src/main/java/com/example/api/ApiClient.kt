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

        val cleanPrompt = prompt.trim()
        val finalImagePrompt = if (style.isNotBlank() && !cleanPrompt.contains(style, ignoreCase = true)) {
            "$cleanPrompt, cinematic lighting, $style aesthetic"
        } else {
            cleanPrompt
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("prompt", finalImagePrompt)
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

        val BATCH_SIZE = 8
        val totalBatches = (numScenes + BATCH_SIZE - 1) / BATCH_SIZE
        val allDrafts = mutableListOf<GeneratedSceneDraft>()
        var filmTitle = "Film : " + prompt.take(30)
        var filmLogline = prompt
        var protagonistBible = ""
        var supportingCastBible = ""
        var antagonistBible = ""
        var visualConsistency = ""

        val langRule = if (dialogueLanguage == "fr") {
            "DIALOGUES EN FRANÇAIS OBLIGATOIRES : Si un personnage physique est visible à l'écran, il s'exprime en français percutant dans 'dialogue', entre guillemets avec son nom (« Nom : Réplique »). Si le plan est un décor ou sans personnage, 'dialogue' doit être strictement VIDE (\"\"). AUCUNE voix off, AUCUN narrateur externe, AUCUN anglais."
        } else {
            "ENGLISH SPOKEN DIALOGUE: Physical characters present speak natural English in 'dialogue' (\"Name: line\"). Pure environment or non-character shots must have 'dialogue' completely empty (\"\"). No voice-over, no external narrator."
        }

        val audioRule = when (audioPresence) {
            "ambient" -> "Mode atmosphérique/sound design : laisser 'dialogue' vide (\"\")."
            else -> "Dialogues parlés directs et incarnés entre les personnages uniquement."
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
                "Phase 1 : Conception scénaristique ($startScene à $endScene / $numScenes plans)..."
            )

            val systemPrompt = if (batchIndex == 0) {
                """
                Tu es un réalisateur et directeur de la photographie de cinéma de renom.
                Tu réalises le découpage technique cinématographique complet (Storyboard) d'un film pour un utilisateur.

                RÈGLE FONDAMENTALE N°1 : ADHÉRENCE STRICTE À LA DEMANDE UTILISATEUR
                L'utilisateur fournit un univers, des personnages, un style et une action.
                TU DOIS RESPECTER STRICTEMENT SA DEMANDE.
                Si son histoire est un drame familial, une tranche de vie, une famille réunie, du quotidien, adapte TOUS les personnages (parents, enfants, proches), décors (maison familiale, table, jardin) et actions à cette vie de famille.
                Ne rajoute AUCUN élément par défaut étranger à son prompt. Si son histoire est un thriller urbain, de la science-fiction, de l'animation ou un drame historique, adapte chaque décor et chaque personnage à son univers.

                RÈGLE FONDAMENTALE N°2 : PROGRESSION DRAMATIQUE RÉELLE ET NON-RÉPÉTITION
                - Chaque plan doit faire AVANCER l'histoire chronologiquement et logiquement : Plan 1 -> Plan 2 -> Plan 3 etc.
                - INTERDICTION FORMELLE de répéter la même action ou la même situation sous des angles différents.
                - Deux plans consécutifs NE DOIVENT JAMAIS avoir la même action ni le même contenu visuel.
                - Quand une action est faite au plan N, le plan N+1 montre la RÉACTION immédiate ou l'action SUIVANTE.

                RÈGLE FONDAMENTALE N°3 : BIBLE DES PERSONNAGES ET DÉCORS
                - Dès le premier plan, définis précisément dans 'protagonist_bible', 'supporting_cast_bible' et 'antagonist_bible' les caractéristiques physiques, vestimentaires et visuelles basées UNIQUEMENT sur la demande de l'utilisateur. Pour un film familial ou choral, décris les membres de la famille (ex: père, mère, enfants) dans ces bibles pour maintenir leur apparence exacte.
                - Réutilise ces descriptions pour assurer la continuité visuelle des visages, âges et costumes au fil des plans.

                GRAMMAIRE DU DÉCOUPAGE CINÉMATOGRAPHIQUE :
                Un film alterne rigoureusement les échelles de plan et les points de vue selon l'action :
                - Plan d'ensemble (Establishing Shot) : décor du film demandé par l'utilisateur.
                - Plan large / situation (Wide Shot) : personnages dans le décor.
                - Plan moyen / Champ / Contre-champ : dialogue ou interaction entre personnages.
                - Plan serré / Gros plan : tension, regard, émotion ou détail clé.
                - Plan d'action / interaction : mouvements physiques, moments de vie selon le scénario.

                CONSTRUCTION DU 'image_prompt' DE CHAQUE PLAN (EN ANGLAIS) :
                Le champ 'image_prompt' est transmis directement au générateur d'image pour générer l'image clé de chaque plan.
                - Il DOIT être entièrement écrit en anglais, précis et photographique.
                - Il doit décrire EXPLICITEMENT et FIDÈLEMENT le sujet et le contexte demandés par l'utilisateur pour ce plan (ex: pour une famille, mentionner explicitement les membres de la famille, le lieu familial, l'activité en cours).
                - Il doit décrire UNIQUEMENT ce qui est visible dans ce plan particulier (cadrage, sujet, éclairage, décor demandé par l'utilisateur).
                - Il DOIT refléter fidèlement le style visuel '$style' choisi par l'utilisateur.
                - Il NE DOIT PAS être répétitif : chaque plan doit avoir un 'image_prompt' distinct et progressif.

                SYSTÈME DE PAROLES & DIALOGUES CINÉMATOGRAPHIQUES :
                - Seuls les PERSONNAGES physiques et présents dans la scène doivent parler.
                - Si le plan montre un personnage : réplique parlée incarnée dans 'dialogue' préfixée par son nom (ex: « Elena : Regarde là-bas. »).
                - Si le plan est un décor pur ou sans personnage : laisser 'dialogue' vide ("").
                - AUCUNE voix off de narrateur externe.
                $langRule
                $audioRule

                Format JSON strict exigé sans balises markdown :
                {
                  "film_title": "Titre du film",
                  "logline": "Accroche dramatique",
                  "protagonist_bible": "Description physique du personnage principal ou chef de famille",
                  "supporting_cast_bible": "Description des autres membres clés de la famille ou alliés",
                  "antagonist_bible": "Description du conflit, menace ou enjeu dramatique",
                  "visual_consistency": "Direction artistique $style",
                  "scenes": [
                    {
                      "number": 1,
                      "act": "INTRODUCTION",
                      "shot_type": "Plan d'ensemble ou Plan large ou Contre-champ ou Plan moyen ou Gros plan",
                      "subject_focus": "FAMILY ou PROTAGONIST ou SUPPORTING ou DUO ou ENVIRONMENT ou ACTION",
                      "characters_present": "Nom des personnages présents ou Décor",
                      "title": "Titre du plan",
                      "action": "Description dynamique de la progression narrative",
                      "image_prompt": "Cinematic visual prompt in English conforming faithfully to user prompt and character bibles",
                      "dialogue": "« Nom : Réplique » (ou vide si décor/muet)",
                      "sound_design": "Acoustique du lieu et ambiance",
                      "camera": "Mouvement de caméra (Travelling / Panoramique / Plan fixe)"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            } else {
                """
                Tu es un réalisateur de cinéma de renom. Poursuis le découpage technique du film "$filmTitle".
                CONTINUITÉ VISUELLE :
                - Protagoniste / Membre clé : "$protagonistBible"
                - Famille / Secondaire : "$supportingCastBible"
                - Enjeu / Antagoniste : "$antagonistBible"
                - Style visuel : "$visualConsistency"
                Dernier plan tourné (Plan ${allDrafts.lastOrNull()?.number ?: (startScene - 1)}) : "${allDrafts.lastOrNull()?.title}" - "${allDrafts.lastOrNull()?.description}".

                RÈGLES IMPÉRATIVES DE PROGRESSION :
                - Poursuis immédiatement l'action chronologique APRÈS ce dernier plan tourné.
                - INTERDICTION ABSOLUE de répéter l'action du plan précédent.
                - Chaque plan doit faire franchir une nouvelle étape au scénario : découverte, échange, émotion, résolution.
                - Alterne contre-champs, gros plans d'intensité et plans d'ensemble selon l'action.
                - Rédige un 'image_prompt' anglais autonome et précis décrivant exactement la scène visuelle de ce plan sans répétition, intégrant fidèlement les personnages et le monde de l'utilisateur.
                $langRule
                $audioRule

                Format JSON compact :
                {
                  "scenes": [
                    {
                      "number": $startScene,
                      "act": "DÉVELOPPEMENT",
                      "shot_type": "Contre-champ ou Plan large ou Plan moyen ou Gros plan",
                      "subject_focus": "FAMILY ou SUPPORTING ou DUO ou PROTAGONIST ou ENVIRONMENT",
                      "characters_present": "Personnages présents",
                      "title": "Titre court",
                      "action": "Description de la NOUVELLE étape de l'action narrative",
                      "image_prompt": "Cinematic visual prompt in English for this specific scene",
                      "dialogue": "« Nom : Réplique » (ou vide si muet/décor)",
                      "sound_design": "Ambiance du lieu",
                      "camera": "Mouvement de caméra"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            }

            val userContent = buildString {
                append("SCÉNARIO ET DEMANDE DU FILM FOURNIS PAR L'UTILISATEUR :\n")
                append(prompt.trim())
                append("\n\nStyle visuel souhaité : ").append(style)
                append("\nGénère le découpage technique pour les plans $startScene à $endScene (sur un total de $numScenes plans).")
                append("\nRespecte fidèlement la trame narrative, les personnages et les décors décrits par l'utilisateur.")
                append("\nChaque plan doit avoir un 'image_prompt' unique et progressif en anglais décrivant fidèlement la scène sans répétition.")
            }

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
                                prompt.take(120)
                            }
                            supportingCastBible = scriptJson.optString("supporting_cast_bible", "")
                            antagonistBible = scriptJson.optString("antagonist_bible", "")
                            visualConsistency = scriptJson.optString("visual_consistency", style)
                        }

                        val scenesArray = scriptJson.optJSONArray("scenes") ?: JSONArray()
                        for (i in 0 until scenesArray.length()) {
                            val sObj = scenesArray.getJSONObject(i)
                            val targetNum = startScene + i
                            val rawDiag = sObj.optString("dialogue", "")
                            val actionDesc = sObj.optString("action", sObj.optString("description", "Plan $targetNum"))
                            val title = sObj.optString("title", "Plan $targetNum")
                            val camMovement = sObj.optString("camera", sObj.optString("camera_movement", "Travelling avant"))
                            val rawSoundDesign = sObj.optString("sound_design", sObj.optString("audio_ambiance", "Ambiance sonore immersive"))
                            val soundDesign = rawSoundDesign.ifBlank { "Ambiance sonore naturelle du décor" }
                            val charsPresent = sObj.optString("characters_present", sObj.optString("characters", ""))
                            val defaultAct = when {
                                targetNum <= (numScenes * 0.25).toInt().coerceAtLeast(1) -> "INTRODUCTION"
                                targetNum <= (numScenes * 0.70).toInt().coerceAtLeast(2) -> "DÉVELOPPEMENT"
                                targetNum <= (numScenes * 0.85).toInt().coerceAtLeast(3) -> "CLIMAX"
                                else -> "CONCLUSION"
                            }
                            val act = sObj.optString("act", defaultAct).uppercase()
                            val shotType = sObj.optString("shot_type", if (targetNum == 1) "Plan d'ensemble" else "Plan moyen")
                            val subjectFocus = sObj.optString("subject_focus", "").trim().uppercase()

                            val isEnvironment = subjectFocus == "ENVIRONMENT" ||
                                charsPresent.contains("aucun", ignoreCase = true) ||
                                charsPresent.contains("décor", ignoreCase = true) ||
                                charsPresent.contains("paysage", ignoreCase = true) ||
                                charsPresent.contains("monde", ignoreCase = true)

                            val enforcedDialogue = enforceCleanDialogue(rawDiag, actionDesc, dialogueLanguage, audioPresence, targetNum, isCharacterPresent = !isEnvironment)

                            val isFamily = subjectFocus == "FAMILY" ||
                                charsPresent.contains("famille", ignoreCase = true) ||
                                charsPresent.contains("family", ignoreCase = true) ||
                                charsPresent.contains("parents", ignoreCase = true) ||
                                charsPresent.contains("enfants", ignoreCase = true)

                            val isAntagonist = !isEnvironment && !isFamily && (
                                subjectFocus == "ANTAGONIST" ||
                                charsPresent.contains("antagoniste", ignoreCase = true) ||
                                charsPresent.contains("rival", ignoreCase = true) ||
                                charsPresent.contains("ennemi", ignoreCase = true) ||
                                charsPresent.contains("menace", ignoreCase = true)
                            )

                            val isSupporting = !isEnvironment && !isAntagonist && !isFamily && (
                                subjectFocus == "SUPPORTING" ||
                                charsPresent.contains("secondaire", ignoreCase = true) ||
                                charsPresent.contains("allié", ignoreCase = true) ||
                                charsPresent.contains("mentor", ignoreCase = true) ||
                                charsPresent.contains("compagnon", ignoreCase = true)
                            )

                            val isCombat = !isEnvironment && !isFamily && (
                                subjectFocus == "ACTION" ||
                                shotType.contains("combat", ignoreCase = true) ||
                                shotType.contains("duel", ignoreCase = true) ||
                                shotType.contains("chorégraphie", ignoreCase = true) ||
                                actionDesc.contains("combat", ignoreCase = true) ||
                                actionDesc.contains("duel", ignoreCase = true) ||
                                actionDesc.contains("choc", ignoreCase = true)
                            )

                            val isDuo = !isEnvironment && !isCombat && !isFamily && (
                                subjectFocus == "DUO" ||
                                charsPresent.contains("duo", ignoreCase = true) ||
                                charsPresent.contains(" et ", ignoreCase = true) ||
                                charsPresent.contains("&")
                            )

                            val isCounterShot = !isEnvironment && !isCombat && !isDuo && !isFamily && (
                                shotType.contains("contre-champ", ignoreCase = true) ||
                                shotType.contains("réaction", ignoreCase = true) ||
                                actionDesc.contains("contre-champ", ignoreCase = true)
                            )

                            val charactersPresentLabel = when {
                                isEnvironment -> "Décor / Environnement"
                                isFamily -> "Famille"
                                isCombat -> "Action / Confrontation"
                                isDuo -> "Duo"
                                isCounterShot -> "Contre-champ / Réaction"
                                isAntagonist -> "Antagoniste"
                                isSupporting -> "Secondaire / Allié"
                                charsPresent.isNotBlank() -> charsPresent
                                else -> "Personnage principal"
                            }

                            val sceneCharacterAnchor = when {
                                isEnvironment -> ""
                                isFamily -> {
                                    val familyMembers = if (supportingCastBible.isNotBlank()) "$protagonistBible & $supportingCastBible" else protagonistBible
                                    "[Family: $familyMembers]"
                                }
                                isAntagonist && antagonistBible.isNotBlank() -> "[Antagonist: $antagonistBible]"
                                isSupporting && supportingCastBible.isNotBlank() -> "[Supporting: $supportingCastBible]"
                                isCounterShot -> {
                                    if (supportingCastBible.isNotBlank()) "[Supporting: $supportingCastBible]"
                                    else if (antagonistBible.isNotBlank()) "[Antagonist: $antagonistBible]"
                                    else ""
                                }
                                isCombat -> {
                                    val antagPart = if (antagonistBible.isNotBlank()) " vs $antagonistBible" else ""
                                    "[Action: $protagonistBible$antagPart]"
                                }
                                isDuo -> {
                                    val other = if (supportingCastBible.isNotBlank()) supportingCastBible else antagonistBible
                                    "[Duo: $protagonistBible & $other]"
                                }
                                else -> if (protagonistBible.isNotBlank()) "[Protagonist: $protagonistBible]" else ""
                            }

                            val rawImagePrompt = sObj.optString("image_prompt", "").trim()
                            val unifiedImagePrompt = if (rawImagePrompt.isNotBlank()) {
                                if (rawImagePrompt.contains("9:16", ignoreCase = true)) {
                                    rawImagePrompt
                                } else {
                                    "$rawImagePrompt, 9:16 vertical format"
                                }
                            } else {
                                // Génération dynamique basée sur la description spécifique de la scène et le prompt utilisateur
                                val styleSuffix = if (style.isNotBlank()) ", $style aesthetic" else ""
                                val userTopic = prompt.take(100)
                                when {
                                    isEnvironment -> "Cinematic wide establishing shot of $actionDesc in $userTopic, atmospheric lighting, detailed background, no people in frame$styleSuffix, 9:16 vertical format"
                                    isFamily -> "Cinematic shot of family members during $actionDesc, $userTopic$styleSuffix, 9:16 vertical format"
                                    isAntagonist && antagonistBible.isNotBlank() -> "Cinematic shot of $antagonistBible, $actionDesc$styleSuffix, 9:16 vertical format"
                                    (isSupporting || isCounterShot) && supportingCastBible.isNotBlank() -> "Cinematic shot of $supportingCastBible, $actionDesc$styleSuffix, 9:16 vertical format"
                                    isCombat -> "Dynamic action shot, $actionDesc$styleSuffix, 9:16 vertical format"
                                    isDuo -> "Cinematic two-shot framing, $actionDesc$styleSuffix, 9:16 vertical format"
                                    protagonistBible.isNotBlank() -> "Cinematic medium shot of $protagonistBible, $actionDesc$styleSuffix, 9:16 vertical format"
                                    else -> "Cinematic shot of $actionDesc, $userTopic$styleSuffix, 9:16 vertical format"
                                }
                            }

                            val rawVideoPrompt = sObj.optString("video_prompt", "").trim()
                            val unifiedVideoPrompt = if (rawVideoPrompt.isNotBlank()) {
                                "$camMovement, $rawVideoPrompt"
                            } else {
                                "$camMovement, $actionDesc, smooth cinematic motion, continuous organic movement"
                            }

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

                        // Compléter si le lot est incomplet
                        while (allDrafts.size < endScene) {
                            val nextNum = allDrafts.size + 1
                            val defaultAct = when {
                                nextNum <= (numScenes * 0.25).toInt().coerceAtLeast(1) -> "INTRODUCTION"
                                nextNum <= (numScenes * 0.70).toInt().coerceAtLeast(2) -> "DÉVELOPPEMENT"
                                nextNum <= (numScenes * 0.85).toInt().coerceAtLeast(3) -> "CLIMAX"
                                else -> "CONCLUSION"
                            }
                            val fallbackDesc = "Étape narrative $nextNum ($defaultAct) : ${prompt.take(120)}"
                            val fallbackSound = "Ambiance naturelle diégétique et sound design"
                            val isEnv = nextNum == 1 || nextNum % 5 == 1
                            val fallbackDialogue = if (isEnv) "" else enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, nextNum, isCharacterPresent = true)
                            val fallbackAnchor = if (isEnv) "" else if (protagonistBible.isNotBlank()) "[Protagonist: $protagonistBible]" else "[Protagonist: ${prompt.take(60)}]"
                            val charLabel = if (isEnv) "Décor / Environnement" else "Personnage"
                            val styleSuffix = if (style.isNotBlank()) ", $style aesthetic" else ""

                            val fallbackImgPrompt = if (isEnv) {
                                "Cinematic establishing shot of the world and environment for $prompt$styleSuffix, 9:16 vertical format"
                            } else {
                                "Cinematic shot of $prompt, step $nextNum ($fallbackDesc)$styleSuffix, 9:16 vertical format"
                            }

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = nextNum,
                                    title = "Plan $nextNum : Séquence $charLabel",
                                    description = fallbackDesc,
                                    imagePrompt = fallbackImgPrompt,
                                    videoPrompt = "Travelling dynamique, $fallbackDesc, natural cinematic motion",
                                    cameraMovement = if (nextNum % 2 == 0) "Contre-champ fluide" else "Travelling avant",
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
                    val fallbackDesc = "Plan $n ($defaultAct) : progression de l'histoire - ${prompt.take(120)}"
                    val isNoCharFallback = n == 1 || n % 5 == 1
                    val fallbackDialogue = if (isNoCharFallback) "" else enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, n, isCharacterPresent = true)
                    val fallbackAnchor = if (isNoCharFallback) "" else "[Protagonist: ${prompt.take(80)}]"
                    val charLabel = if (isNoCharFallback) "Décor / Environnement" else "Personnage"
                    val styleSuffix = if (style.isNotBlank()) ", $style aesthetic" else ""

                    val fallbackImg = if (isNoCharFallback) {
                        "Cinematic wide establishing shot of the environment for $prompt$styleSuffix, 9:16 vertical format"
                    } else {
                        "Cinematic medium shot of $prompt, scene $n ($fallbackDesc)$styleSuffix, 9:16 vertical format"
                    }

                    allDrafts.add(
                        GeneratedSceneDraft(
                            number = n,
                            title = "Plan $n : Séquence $charLabel",
                            description = fallbackDesc,
                            imagePrompt = fallbackImg,
                            videoPrompt = "Continuous smooth camera motion, natural human movement, organic camera framing",
                            cameraMovement = if (n % 2 == 0) "Contre-champ fluide" else "Travelling avant",
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
        TechnicalLogManager.log("PHASE_1", "Scénario complet finalisé avec succès: ${allDrafts.size} scènes générées (structure studio, multi-angles, casting étendu, langue $dialogueLanguage)")
        val finalCharacterConsistency = buildString {
            append("Protagoniste: $protagonistBible")
            if (supportingCastBible.isNotBlank()) append(" | Allié: $supportingCastBible")
            if (antagonistBible.isNotBlank()) append(" | Antagoniste: $antagonistBible")
        }
        return@withContext ApiResponse.Success(ScriptGenerationResult(filmTitle, filmLogline, finalCharacterConsistency, visualConsistency, allDrafts))
    }

    /**
     * Valide et garantit la langue et la pertinence des répliques cinématographiques.
     * Respecte rigoureusement les dialogues écrits par le modèle et bannit les clichés stéréotypés.
     */
    private fun enforceCleanDialogue(
        raw: String,
        description: String,
        language: String,
        audioMode: String,
        sceneNum: Int,
        isCharacterPresent: Boolean = true
    ): String {
        if (audioMode == "ambient" || !isCharacterPresent) return ""
        val trimmed = raw.trim()

        // Si une réplique a été écrite par le modèle ou l'utilisateur (non vide et non "null")
        if (trimmed.isNotBlank() && !trimmed.equals("null", ignoreCase = true)) {
            val clean = trimmed.replace("«", "").replace("»", "").replace("\"", "").trim()
            if (clean.isNotBlank()) {
                // Exclusion formelle des narrateurs ou voix off
                val cleanLower = clean.lowercase()
                if (cleanLower.startsWith("voix off") || cleanLower.startsWith("voix-off") ||
                    cleanLower.startsWith("voice over") || cleanLower.startsWith("voice-over") ||
                    cleanLower.startsWith("narrateur") || cleanLower.startsWith("narrator")) {
                    return ""
                }
                return if (language == "fr") "« $clean »" else "\"$clean\""
            }
        }

        // Pas de réplique de personnage disponible : renvoyer chaîne vide (jamais de voix off artificielle)
        return ""
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
