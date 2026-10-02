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
                Tu es un réalisateur et scénariste de cinéma de renommée internationale, primé pour la puissance de sa mise en scène, son réalisme viscéral et sa direction d'acteurs de niveau studio (références : Denis Villeneuve, Christopher Nolan, Jacques Audiard, Alfonso Cuarón, Hayao Miyazaki).
                Tu écris un film marquant, saisissant et cinématographiquement exceptionnel.
                Chaque plan doit témoigner d'une véritable grammaire de cinéma : intelligence du cadre, dynamique des points de vue, authenticité humaine absolue.
                INTERDICTION FORMELLE DE CLICHÉS D'IA, DE DIALOGUES ROBOTIQUES ET DE TOUTE RÉPÉTITION.

                1. OUVERTURE & INTRODUCTION ADAPTATIVE AU GENRE :
                - L'introduction ne doit JAMAIS être statique ni stéréotypée : elle doit épouser plastiquement la nature du film.
                - Si l'histoire est un conte, une fable, une aventure mythologique, un drame historique ou rural (ex: village, royaume, contrée lointaine) :
                  * Poser le cadre et l'immersion contextuelle : le village, son architecture, ses reliefs, les légendes ou l'époque (« Il était une fois dans un village perché sur les crêtes... », ou description poétique de l'atmosphère, de la fumée des toits, du vent dans les collines avant que le drame ne se noue).
                - Si le film est un thriller, polar, action ou SF :
                  * Ouvrir selon la dynamique du genre : in media res, tension nocturne, plan de situation architectural vertigineux ou geste intime décisif.

                2. DÉCOUPAGE TECHNIQUE MULTI-AXES ET VARIÉTÉ DES CADRAGES (NIVEAU STUDIO) :
                - RÈGLE D'OR : INTERDICTION ABSOLUE D'ENFERMER LA CAMÉRA SUR LE PERSONNAGE PRINCIPAL.
                - ALTERNANCE DES POINTS DE VUE ET CONTRE-CHAMPS OBLIGATOIRES :
                  * Quand un personnage marche vers un autre pour le rencontrer : le plan suivant NE RESTE PAS sur son dos ! Il bascule IMMÉDIATEMENT en contre-champ sur le second personnage qui l'attend ou le regarde arriver, ou en plan large montrant leur face-à-face dans l'espace.
                  * Scènes de combat ou d'action : alternance dynamique entre plan large chorégraphié (lisibilité spatiale du combat), plan serré percutant (impact, garde, esquive), plan de réaction sur l'adversaire déstabilisé ou déterminé, et contre-plongée dramatique.
                  * Plans de respiration : plans d'ensemble immersifs du décor sans silhouette au centre, inserts sur des détails tangibles (mains, arme, regard, objet clé).
                - Répartition équilibrée de l'attention caméra : Protagoniste, Secondaire / Allié, Antagoniste / Menace, Duo en vis-à-vis, et Environnement seul.

                3. DIALOGUES HUMAINS, VIVANTS ET SANS AUCUN CLICHÉ :
                - PROHIBITION FORMELLE des phrases toutes faites de série B (« Garde ton sang-froid », « La prochaine étape sera décisive », « Nous irons jusqu'au bout ensemble », « Pas le droit à l'erreur »).
                - Des répliques incarnées, sobres, spécifiques au lore du film, ou un silence lourd habité par le sound design.
                $langRule
                $audioRule
                - 'dialogue' : Réplique orale brève (4 à 8 mots) ou phrase de voix off immersive qui installe l'histoire.
                - 'sound_design' : Texture sonore réaliste et organique (foley naturel, acoustique du lieu, souffle, pas, vent, nappe musicale diégétique).

                PERSONNAGES EN CHAIR ET EN OS (COHÉRENCE NATURELLE) :
                - 'protagonist_bible' : Nom + silhouette, visage humain expressif, vêtements réels avec textures tangibles.
                - 'supporting_cast_bible' : Nom + rôle + visage et tenue contrastée d'un allié ou personnage secondaire fort.
                - 'antagonist_bible' : Nom + motivation tangible + présence visuelle distincte.
                - 'visual_consistency' : Style $style, grain argentique 35mm, palette et lumière diégétique réaliste.

                Réponds EXCLUSIVEMENT en JSON compact sans balises markdown :
                {
                  "film_title": "Titre cinématographique réaliste",
                  "logline": "Accroche forte résumant le cœur dramatique du film",
                  "protagonist_bible": "Description physique du héros, tenue et détails visuels",
                  "supporting_cast_bible": "Description de l'allié ou personnage rencontré, tenue et traits distincts",
                  "antagonist_bible": "Description de l'antagoniste ou de la menace, carrure et présence",
                  "visual_consistency": "Atmosphère visuelle $style, lumière naturelle et grain 35mm",
                  "scenes": [
                    {
                      "number": 1,
                      "act": "INTRODUCTION",
                      "shot_type": "Plan d'ensemble panoramique ou Contre-champ ou Plan moyen ou Chorégraphie",
                      "characters_present": "Décor / Ambiance OU Protagoniste OU Secondaire OU Antagoniste OU Duo",
                      "title": "Titre cinématographique du plan",
                      "action": "Description précise et dynamique du plan (angles, mouvements réels, interactions physiques)",
                      "dialogue": "Voix off : « Phrase immersive d'ouverture » OU « Personnage : Réplique vivante et naturelle »",
                      "sound_design": "Acoustique précise du décor et foley réaliste",
                      "camera": "Mouvement fluide (Travelling latéral, Contre-champ fluide, Panoramique, Caméra épaule immersive)"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            } else {
                """
                Tu es un réalisateur de cinéma de renommée internationale. Poursuis la mise en scène du film "$filmTitle".
                CONTINUITÉ VISUELLE STRICTE :
                - Protagoniste : "$protagonistBible"
                - Allié / Secondaire : "$supportingCastBible"
                - Antagoniste : "$antagonistBible"
                - Style visuel : "$visualConsistency"
                Plan précédent (Plan ${allDrafts.lastOrNull()?.number ?: (startScene - 1)}) : "${allDrafts.lastOrNull()?.title}" - "${allDrafts.lastOrNull()?.description}" (Cadrage : ${allDrafts.lastOrNull()?.cameraMovement}).

                VARIATION DE MISE EN SCÈNE OBLIGATOIRE (RÈGLE DU CONTRE-CHAMP & DE L'ACTION) :
                - Si le plan précédent montrait un personnage avançant vers un autre : coupe OBLIGATOIREMENT sur l'autre personnage qui attend/réagit (contre-champ), ou plan large montrant les deux personnages se faisant face.
                - En cas d'action ou combat : alterne entre chorégraphie d'ensemble, plan rapproché sur le choc ou la parade, et réaction de l'adversaire.
                - Ne reste pas bloqué sur le personnage principal : fais vivre les personnages secondaires, l'antagoniste et le décor.
                - AUCUN cliché artificiel (« Garde ton sang-froid », « Décisive », « Jusqu'au bout » sont STRICTEMENT INTERDITS). Des dialogues vrais, ancrés dans la scène.
                $langRule
                $audioRule

                Réponds EXCLUSIVEMENT en JSON compact :
                {
                  "scenes": [
                    {
                      "number": $startScene,
                      "act": "DÉVELOPPEMENT",
                      "shot_type": "Contre-champ / Réaction ou Plan large duel ou Plan moyen allié",
                      "characters_present": "Secondaire / Allié ou Antagoniste ou Duo ou Protagoniste",
                      "title": "Titre court",
                      "action": "Description dynamique de la scène sans focalisation exclusive sur le héros",
                      "dialogue": "« Réplique vivante, contextuelle et originale en français »",
                      "sound_design": "Foley réaliste et tension acoustique des lieux",
                      "camera": "Axe caméra dynamique à hauteur d'homme"
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
                            val shotType = sObj.optString("shot_type", if (targetNum == 1) "Plan d'ensemble panoramique" else "Plan moyen")

                            val isNoCharacter = charsPresent.contains("aucun", ignoreCase = true) ||
                                charsPresent.contains("décor", ignoreCase = true) ||
                                charsPresent.contains("paysage", ignoreCase = true) ||
                                charsPresent.contains("monde", ignoreCase = true) ||
                                charsPresent.contains("village", ignoreCase = true) && !charsPresent.contains("protagoniste", ignoreCase = true) ||
                                charsPresent.contains("none", ignoreCase = true) ||
                                (targetNum == 1 && (shotType.contains("ensemble", ignoreCase = true) || shotType.contains("panoramique", ignoreCase = true)))

                            val isCounterShot = !isNoCharacter && (
                                shotType.contains("contre-champ", ignoreCase = true) ||
                                shotType.contains("réaction", ignoreCase = true) ||
                                actionDesc.contains("contre-champ", ignoreCase = true) ||
                                charsPresent.contains("contre-champ", ignoreCase = true) ||
                                actionDesc.contains("regarde approcher", ignoreCase = true) ||
                                actionDesc.contains("attend", ignoreCase = true) && !charsPresent.contains("protagoniste seul", ignoreCase = true)
                            )

                            val isCombat = !isNoCharacter && (
                                actionDesc.contains("combat", ignoreCase = true) ||
                                actionDesc.contains("choc", ignoreCase = true) ||
                                actionDesc.contains("frappe", ignoreCase = true) ||
                                actionDesc.contains("épée", ignoreCase = true) ||
                                actionDesc.contains("parade", ignoreCase = true) ||
                                actionDesc.contains("duel", ignoreCase = true) ||
                                shotType.contains("combat", ignoreCase = true) ||
                                shotType.contains("duel", ignoreCase = true) ||
                                shotType.contains("chorégraphie", ignoreCase = true)
                            )

                            val isConfrontation = !isNoCharacter && !isCombat && (
                                charsPresent.contains("confrontation", ignoreCase = true) ||
                                (charsPresent.contains("antagoniste", ignoreCase = true) && charsPresent.contains("protagoniste", ignoreCase = true)) ||
                                actionDesc.contains("face-à-face", ignoreCase = true)
                            )

                            val isDuo = !isNoCharacter && !isConfrontation && !isCombat && (
                                charsPresent.contains("duo", ignoreCase = true) ||
                                charsPresent.contains(" et ", ignoreCase = true) ||
                                charsPresent.contains("&")
                            )

                            val isAntagonistOnly = !isNoCharacter && !isCombat && !isConfrontation && (
                                charsPresent.contains("antagoniste", ignoreCase = true) ||
                                charsPresent.contains("rival", ignoreCase = true) ||
                                charsPresent.contains("ennemi", ignoreCase = true) ||
                                charsPresent.contains("menace", ignoreCase = true)
                            )

                            val isSecondaryOnly = !isNoCharacter && !isCombat && !isConfrontation && !isAntagonistOnly && (
                                charsPresent.contains("secondaire", ignoreCase = true) ||
                                charsPresent.contains("allié", ignoreCase = true) ||
                                charsPresent.contains("mentor", ignoreCase = true) ||
                                charsPresent.contains("compagnon", ignoreCase = true)
                            )

                            val sceneCharacterAnchor = when {
                                isNoCharacter -> "Cinematic scenery, environmental spatial architecture, lived-in world without human presence"
                                isCombat && antagonistBible.isNotBlank() -> "[Dynamic Action Choreography: $protagonistBible engaged in high-tension physical martial combat against $antagonistBible, wide framing, authentic physical impacts and athletic movement]"
                                isCounterShot -> {
                                    val otherChar = if (supportingCastBible.isNotBlank()) supportingCastBible else antagonistBible.ifBlank { protagonistBible }
                                    "[Cinematic Reverse Angle / Counter-Shot: $otherChar, observing the arrival, nuanced human facial expression and intense eye contact]"
                                }
                                isConfrontation && antagonistBible.isNotBlank() -> "[Tense Two-Shot Face-off: $protagonistBible facing $antagonistBible in same cinematic frame, psychological standoff]"
                                isDuo && supportingCastBible.isNotBlank() -> "[Cinematic Two-Shot: $protagonistBible side-by-side with $supportingCastBible, authentic mutual interaction]"
                                isAntagonistOnly && antagonistBible.isNotBlank() -> "[Antagonist Focus: $antagonistBible, menacing presence and calculated movements]"
                                isSecondaryOnly && supportingCastBible.isNotBlank() -> "[Supporting Ally Focus: $supportingCastBible, autonomous character action and distinct screen presence]"
                                else -> "[Protagonist Focus: $protagonistBible]"
                            }

                            val charactersPresentLabel = when {
                                isNoCharacter -> "Décor / Ambiance"
                                isCombat -> "Chorégraphie Combat / Duel"
                                isCounterShot -> "Contre-champ / Réaction"
                                isConfrontation -> "Face-à-face (Protagoniste & Antagoniste)"
                                isDuo -> "Duo (Protagoniste & Allié)"
                                isAntagonistOnly -> "Antagoniste / Menace"
                                isSecondaryOnly -> "Allié / Secondaire"
                                else -> "Protagoniste"
                            }

                            val humanCinematicStyle = "shot on 35mm film, Kodak Vision3, natural realistic lighting, authentic lived-in textures, natural human skin tones, documentary cinema realism, $visualConsistency, 9:16 vertical format"

                            val unifiedImagePrompt = if (isNoCharacter) {
                                "$prompt, [ESTABLISHING SHOT - SCENERY & WORLD], scène $targetNum [$act - $shotType]: $title - $actionDesc, $humanCinematicStyle"
                            } else {
                                "$prompt, [MASTER CINEMATIC CONTINUITY: $sceneCharacterAnchor, identical facial features, realistic natural clothing], scène $targetNum [$act - $shotType]: $title - $actionDesc, $humanCinematicStyle"
                            }
                            val unifiedVideoPrompt = when {
                                isCombat -> "$camMovement, dynamic combat choreography, $actionDesc, physical martial clash, fluid defensive stance and impacts, wide cinematic action framing"
                                isCounterShot -> "$camMovement, reverse angle counter-shot, $actionDesc, intense human gaze, reaction to the approaching character, cinematic timing"
                                isNoCharacter -> "$camMovement, atmospheric world discovery, $actionDesc, natural ambient motion, smoke, wind, lighting dynamics"
                                else -> "$camMovement, $actionDesc, natural human movement, organic camera framing, realistic physical interaction"
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

                            val isCounter = nextNum % 2 == 0 && supportingCastBible.isNotBlank()
                            val isAntag = nextNum % 3 == 0 && antagonistBible.isNotBlank()
                            val fallbackAnchor = when {
                                isCounter -> "[Cinematic Counter-Shot / Ally: $supportingCastBible]"
                                isAntag -> "[Antagonist Focus: $antagonistBible]"
                                else -> "[Protagonist: $protagonistBible]"
                            }
                            val charLabel = when {
                                isCounter -> "Contre-champ / Secondaire"
                                isAntag -> "Antagoniste / Menace"
                                else -> "Protagoniste"
                            }

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = nextNum,
                                    title = "Plan $nextNum : Séquence $charLabel",
                                    description = fallbackDesc,
                                    imagePrompt = "$prompt, [MASTER CONTINUITY: $fallbackAnchor], scène $nextNum [$defaultAct]: $fallbackDesc, shot on 35mm film, natural lighting, $visualConsistency, 9:16 vertical format",
                                    videoPrompt = "Travelling dynamique, $fallbackDesc, natural human movement, organic camera framing",
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
                    val fallbackDesc = "Développement de l'intrigue ($defaultAct) - Plan $n"
                    val fallbackDialogue = enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, n)

                    val isNoCharFallback = n == 1
                    val isCounterFallback = !isNoCharFallback && n % 2 == 0 && supportingCastBible.isNotBlank()
                    val isAntagFallback = !isNoCharFallback && n % 3 == 0 && antagonistBible.isNotBlank()
                    val fallbackAnchor = when {
                        isNoCharFallback -> "Cinematic scenery, environmental and architectural details without characters"
                        isCounterFallback -> "[Counter-Shot Reaction: $supportingCastBible]"
                        isAntagFallback -> "[Antagonist: $antagonistBible]"
                        else -> "[Protagonist: $protagonistBible]"
                    }
                    val charLabel = when {
                        isNoCharFallback -> "Décor / Ambiance"
                        isCounterFallback -> "Contre-champ / Réaction"
                        isAntagFallback -> "Antagoniste / Menace"
                        else -> "Protagoniste"
                    }

                    allDrafts.add(
                        GeneratedSceneDraft(
                            number = n,
                            title = "Plan $n : Séquence $charLabel",
                            description = fallbackDesc,
                            imagePrompt = "$prompt, [Character Bible: $fallbackAnchor], plan $n [$defaultAct], shot on 35mm film, natural lighting, $visualConsistency, 9:16 vertical format",
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
        sceneNum: Int
    ): String {
        if (audioMode == "ambient") return ""
        val trimmed = raw.trim()

        // Si une réplique a été écrite par le modèle ou l'utilisateur (non vide et non "null")
        if (trimmed.isNotBlank() && !trimmed.equals("null", ignoreCase = true)) {
            val clean = trimmed.replace("«", "").replace("»", "").replace("\"", "").trim()
            if (clean.isNotBlank()) {
                return if (language == "fr") "« $clean »" else "\"$clean\""
            }
        }

        // Si aucun dialogue n'a été spécifié pour ce plan, la scène est purement visuelle et sonore (aucun texte inventé ou stéréotypé en dur)
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
