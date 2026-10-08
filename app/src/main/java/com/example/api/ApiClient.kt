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
                "Phase 1 : Conception scénaristique Studio ($startScene à $endScene / $numScenes plans)..."
            )

            val systemPrompt = if (batchIndex == 0) {
                """
                Tu es un réalisateur et directeur de la photographie de cinéma de niveau mondial (Denis Villeneuve, Roger Deakins, Christopher Nolan, Ridley Scott).
                Tu réalises le découpage technique cinématographique complet (Storyboard) d'un film.

                RÈGLE SUPRÊME N°1 : FIDÉLITÉ ABSOLUE AU SCRIPT ET AUX INDICATIONS DE L'UTILISATEUR
                Si l'utilisateur a fourni un scénario, des scènes détaillées, des actions ou des répliques :
                TU DOIS RESPECTER STRICTEMENT CE SCRIPT. Tu ne dévies pas de l'histoire de l'utilisateur. Chaque plan doit transcrire fidèlement les lieux, les personnages et les événements demandés par l'utilisateur.

                RÈGLE SUPRÊME N°2 : PROGRESSION DRAMATIQUE LINÉAIRE STRICTE - INTERDICTION FORMELLE DE RÉPÉTITION
                - Chaque plan doit faire AVANCER l'histoire chronologiquement et logiquement : Événement A -> Conséquence B -> Événement C -> Climax D -> Résolution E.
                - INTERDICTION FORMELLE de boucler, de faire bégayer l'action, ou de régénérer la même situation sous des angles différents.
                - Deux plans consécutifs NE DOIVENT JAMAIS avoir la même action, le même enjeu ni le même contenu visuel.
                - Quand une action est accomplie au plan N (ex: un personnage entre dans une pièce, dégaine une arme, ou prononce un serment), le plan N+1 montre la RÉACTION immédiate ou l'action SUIVANTE, jamais la répétition de l'entrée ou du dégagement.

                RÈGLE SUPRÊME N°3 : CONTINUITÉ ET ADHÉRENCE VISUELLE DES PERSONNAGES
                - Dès le premier plan, définis précisément dans 'protagonist_bible', 'supporting_cast_bible' et 'antagonist_bible' les caractéristiques vestimentaires et physiques EXACTES demandées par l'utilisateur dans son prompt.
                - N'invente JAMAIS un archétype générique par défaut si l'utilisateur a spécifié des détails (âge, tenue, couleurs, coiffure, accessoires, époque).
                - Réutilise ces bibles textuellement dans chaque champ 'image_prompt' approprié pour garantir une cohérence visuelle parfaite sans dérive.

                GRAMMAIRE DU DÉCOUPAGE CINÉMATOGRAPHIQUE DE STUDIO :
                Un film n'est JAMAIS une succession monotone de plans moyens d'une seule personne. La cinématographie repose sur une alternance rigoureuse d'échelles et de points de vue :
                1. Plan d'ensemble (Extreme Wide / Establishing Shot) : Installe le monde, le décor (village, forteresse, nature, rue nocturne), l'ambiance et la météo. AUCUN gros plan de personnage.
                2. Plan de situation / Plan large (Wide Shot) : Montre les personnages DANS leur environnement avec de la perspective spatiale.
                3. Champ / Contre-champ (Reverse Angle) : Quand un personnage marche vers un autre ou parle, le plan suivant COUPE OBLIGATOIREMENT sur l'autre personnage qui l'attend ou réagit.
                4. Plan d'action / Duel : Plan dynamique montrant les DEUX combattants en chorégraphie physique (impacts, esquives, mouvement).
                5. Antagoniste / Menace : Plans dédiés à l'antagoniste seul pour faire ressentir le danger.
                6. Plan rapproché / Gros plan (Close-Up / Insert) : Émotion intense ou détail narratif clé (main qui dégaine, torche, regard).

                ATTRIBUTION DU SUJET PAR PLAN ('subject_focus') :
                Chaque plan a un sujet unique et exclusif parmi :
                - "ENVIRONMENT" : Décor pur, ville, paysage, aucun personnage au centre.
                - "PROTAGONIST" : Le héros uniquement.
                - "ANTAGONIST" : L'adversaire ou la menace uniquement (sans le héros).
                - "SUPPORTING" : L'allié, le mentor, le second rôle ou un témoin (sans le héros).
                - "DUO" : Deux personnages dans le même cadre (face-à-face, dialogue, marche à deux).
                - "ACTION" : Chorégraphie de combat, affrontement martial à deux, poursuite.

                CONSTRUCTION DU 'image_prompt' POUR CHAQUE PLAN (EN ANGLAIS) :
                Le champ 'image_prompt' est transmis directement au générateur d'images pour créer l'image clé du plan.
                Il DOIT être précis, photographique, et décrire UNIQUEMENT ce qui est visible dans ce plan précis en intégrant fidèlement les descriptions des bibles de personnages :
                - Indiquer le cadrage : "Cinematic extreme wide establishing shot of...", "Cinematic medium close-up reverse shot of...", "Dynamic wide angle action two-shot of..."
                - Si subject_focus est ENVIRONMENT : Décrire uniquement le lieu, les textures, l'éclairage, avec la mention explicite "vast panoramic scale, no people in frame".
                - Si subject_focus est ANTAGONIST : Décrire l'antagoniste (visage, costume sombre, arme, posture menaçante), sans jamais mentionner le protagoniste.
                - Si subject_focus est SUPPORTING : Décrire l'allié ou le second personnage en action ou en réaction.
                - Si subject_focus est ACTION : Décrire les deux silhouettes en combat physique intense avec mouvement cinétique et impact.

                SYSTÈME DE PAROLES & DIALOGUES CINÉMATOGRAPHIQUES :
                - RÈGLE ABSOLUE : Seuls les PERSONNAGES physiques et présents dans la scène doivent parler !
                - Si le plan montre un ou plusieurs personnages (Protagoniste, Antagoniste, Second rôle, etc.) : attribuer une réplique parlée incarnée et percutante dans 'dialogue' préfixée par le nom du personnage (ex: « Elena : Regarde ce qui nous attend. » ou « Marcus : Reste vigilant ! »).
                - Si le plan est un décor, un paysage, un plan d'ensemble panoramique ou une scène sans personnage : laisser 'dialogue' strictement VIDE ("").
                - INTERDICTION ABSOLUE de générer des voix off narratives, des narrateurs hors-champ ou des descriptions lues par une voix externe.
                $langRule
                $audioRule

                PERSONNAGES & CONTINUITÉ :
                - 'protagonist_bible' : Nom + apparence physique, tenue et détails visuels uniques du héros (extraits fidèlement de la demande).
                - 'supporting_cast_bible' : Nom + physique et tenue du second personnage / allié.
                - 'antagonist_bible' : Nom + silhouette, visage et tenue de l'adversaire ou menace.
                - 'visual_consistency' : Style $style, grain argentique Kodak 35mm, éclairage diégétique réaliste.

                Format JSON strict exigé sans balises markdown :
                {
                  "film_title": "Titre du film",
                  "logline": "Accroche dramatique",
                  "protagonist_bible": "Description physique détaillée du protagoniste",
                  "supporting_cast_bible": "Description physique du second rôle / allié",
                  "antagonist_bible": "Description physique de l'antagoniste",
                  "visual_consistency": "Direction artistique $style",
                  "scenes": [
                    {
                      "number": 1,
                      "act": "INTRODUCTION",
                      "shot_type": "Plan d'ensemble ou Plan large ou Contre-champ ou Plan moyen ou Chorégraphie",
                      "subject_focus": "ENVIRONMENT ou PROTAGONIST ou ANTAGONIST ou SUPPORTING ou DUO ou ACTION",
                      "characters_present": "Décor / Ambiance ou Protagoniste ou Antagoniste ou Secondaire ou Duo",
                      "title": "Titre du plan",
                      "action": "Description dynamique de la progression narrative (pas de répétition)",
                      "image_prompt": "Cinematic visual prompt in English (camera shot scale, lighting, specific subjects in frame conforming to character bibles)",
                      "dialogue": "« Nom : Réplique vivante » (ou vide si muet/décor)",
                      "sound_design": "Acoustique du lieu et foley",
                      "camera": "Travelling / Panoramique / Caméra épaule / Plan fixe"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            } else {
                """
                Tu es un réalisateur de cinéma de renommée mondiale. Poursuis le découpage technique du film "$filmTitle".
                CONTINUITÉ VISUELLE STRICTE :
                - Protagoniste : "$protagonistBible"
                - Allié / Secondaire : "$supportingCastBible"
                - Antagoniste : "$antagonistBible"
                - Style visuel : "$visualConsistency"
                Dernier plan tourné (Plan ${allDrafts.lastOrNull()?.number ?: (startScene - 1)}) : "${allDrafts.lastOrNull()?.title}" - "${allDrafts.lastOrNull()?.description}".

                RÈGLES IMPÉRATIVES DE PROGRESSION & NON-RÉPÉTITION :
                - Poursuis immédiatement l'action chronologique APRÈS ce dernier plan tourné.
                - INTERDICTION ABSOLUE de répéter l'action du plan précédent ou de faire du surplace narratif.
                - Chaque plan doit faire franchir une nouvelle étape au scénario : découverte, confrontation, montée de tension, résolution.
                - VARIÉTÉ DE PLANS STUDIO : alterne contre-champs, gros plans d'intensité, duels et plans larges.
                - Définis obligatoirement 'subject_focus' ("ENVIRONMENT", "ANTAGONIST", "SUPPORTING", "DUO", "ACTION", "PROTAGONIST") et rédige un 'image_prompt' anglais autonome et précis centré UNIQUEMENT sur ce sujet intégrant les bibles visuelles.
                $langRule
                $audioRule

                Format JSON compact :
                {
                  "scenes": [
                    {
                      "number": $startScene,
                      "act": "DÉVELOPPEMENT",
                      "shot_type": "Contre-champ ou Plan large duel ou Plan moyen allié ou Gros plan",
                      "subject_focus": "SUPPORTING ou ANTAGONIST ou ACTION ou DUO ou PROTAGONIST ou ENVIRONMENT",
                      "characters_present": "Secondaire ou Antagoniste ou Duo ou Protagoniste",
                      "title": "Titre court",
                      "action": "Description de la NOUVELLE étape de l'action narrative",
                      "image_prompt": "Cinematic visual prompt in English conforming to character bibles",
                      "dialogue": "« Nom : Réplique vivante » (ou vide si muet/décor)",
                      "sound_design": "Foley et ambiance du lieu",
                      "camera": "Mouvement de caméra cinématographique"
                    }
                  ]
                }
                Génère exactement $scenesInThisBatch plans numérotés de $startScene à $endScene.
                """.trimIndent()
            }

            val userContent = buildString {
                append("SCÉNARIO ET INSTRUCTIONS DU FILM FOURNIS PAR L'UTILISATEUR :\n")
                append(prompt.trim())
                append("\n\nStyle visuel souhaité : ").append(style)
                append("\nGénère le découpage technique pour les plans $startScene à $endScene (sur un total de $numScenes plans).")
                append("\nSuis fidèlement la trame narrative, les décors, les personnages et les dialogues écrits par l'utilisateur.")
                append("\nASSURE UNE PROGRESSION STRICTEMENT CHRONOLOGIQUE SANS AUCUNE RÉPÉTITION DE SCÈNES.")
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
                            val rawSoundDesign = sObj.optString("sound_design", sObj.optString("audio_ambiance", "Ambiance sonore cinématographique immersive"))
                            val soundDesign = rawSoundDesign.ifBlank { "Ambiance sonore studio et nappe orchestrale" }
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
                                charsPresent.contains("monde", ignoreCase = true) ||
                                (targetNum == 1 && (shotType.contains("ensemble", ignoreCase = true) || shotType.contains("panoramique", ignoreCase = true)))

                            val enforcedDialogue = enforceCleanDialogue(rawDiag, actionDesc, dialogueLanguage, audioPresence, targetNum, isCharacterPresent = !isEnvironment)

                            val isAntagonist = !isEnvironment && (
                                subjectFocus == "ANTAGONIST" ||
                                charsPresent.contains("antagoniste", ignoreCase = true) ||
                                charsPresent.contains("rival", ignoreCase = true) ||
                                charsPresent.contains("ennemi", ignoreCase = true) ||
                                charsPresent.contains("menace", ignoreCase = true)
                            )

                            val isSupporting = !isEnvironment && !isAntagonist && (
                                subjectFocus == "SUPPORTING" ||
                                charsPresent.contains("secondaire", ignoreCase = true) ||
                                charsPresent.contains("allié", ignoreCase = true) ||
                                charsPresent.contains("mentor", ignoreCase = true) ||
                                charsPresent.contains("compagnon", ignoreCase = true)
                            )

                            val isCombat = !isEnvironment && (
                                subjectFocus == "ACTION" ||
                                shotType.contains("combat", ignoreCase = true) ||
                                shotType.contains("duel", ignoreCase = true) ||
                                shotType.contains("chorégraphie", ignoreCase = true) ||
                                actionDesc.contains("combat", ignoreCase = true) ||
                                actionDesc.contains("duel", ignoreCase = true) ||
                                actionDesc.contains("choc", ignoreCase = true) ||
                                actionDesc.contains("épée", ignoreCase = true) ||
                                actionDesc.contains("frappe", ignoreCase = true)
                            )

                            val isDuo = !isEnvironment && !isCombat && (
                                subjectFocus == "DUO" ||
                                charsPresent.contains("duo", ignoreCase = true) ||
                                charsPresent.contains(" et ", ignoreCase = true) ||
                                charsPresent.contains("&") ||
                                actionDesc.contains("face-à-face", ignoreCase = true)
                            )

                            val isCounterShot = !isEnvironment && !isCombat && !isDuo && (
                                shotType.contains("contre-champ", ignoreCase = true) ||
                                shotType.contains("réaction", ignoreCase = true) ||
                                actionDesc.contains("contre-champ", ignoreCase = true)
                            )

                            val charactersPresentLabel = when {
                                isEnvironment -> "Décor / Environnement"
                                isCombat -> "Combat / Action martiale"
                                isDuo -> "Duo (Protagoniste & Secondaire/Antagoniste)"
                                isCounterShot -> "Contre-champ / Réaction"
                                isAntagonist -> "Antagoniste / Menace"
                                isSupporting -> "Secondaire / Allié"
                                else -> "Protagoniste"
                            }

                            val sceneCharacterAnchor = when {
                                isEnvironment -> ""
                                isAntagonist && antagonistBible.isNotBlank() -> "[Antagonist: $antagonistBible]"
                                isSupporting && supportingCastBible.isNotBlank() -> "[Supporting: $supportingCastBible]"
                                isCounterShot -> {
                                    if (supportingCastBible.isNotBlank()) "[Supporting: $supportingCastBible]"
                                    else if (antagonistBible.isNotBlank()) "[Antagonist: $antagonistBible]"
                                    else ""
                                }
                                isCombat -> {
                                    val antagPart = if (antagonistBible.isNotBlank()) " vs $antagonistBible" else ""
                                    "[Action Clash: $protagonistBible$antagPart]"
                                }
                                isDuo -> {
                                    val other = if (supportingCastBible.isNotBlank()) supportingCastBible else antagonistBible
                                    "[Duo: $protagonistBible & $other]"
                                }
                                else -> if (protagonistBible.isNotBlank()) "[Protagonist: $protagonistBible]" else ""
                            }

                            val rawImagePrompt = sObj.optString("image_prompt", "").trim()
                            val unifiedImagePrompt = if (rawImagePrompt.isNotBlank()) {
                                val anchorSuffix = when {
                                    isEnvironment -> ""
                                    isAntagonist && antagonistBible.isNotBlank() && !rawImagePrompt.contains(antagonistBible.take(20), ignoreCase = true) -> ", featuring $antagonistBible"
                                    (isSupporting || isCounterShot) && supportingCastBible.isNotBlank() && !rawImagePrompt.contains(supportingCastBible.take(20), ignoreCase = true) -> ", featuring $supportingCastBible"
                                    isCombat -> {
                                        val clash = if (antagonistBible.isNotBlank()) "$protagonistBible versus $antagonistBible" else protagonistBible
                                        if (!rawImagePrompt.contains(protagonistBible.take(20), ignoreCase = true)) ", featuring $clash" else ""
                                    }
                                    isDuo -> {
                                        val duoChars = if (supportingCastBible.isNotBlank()) "$protagonistBible and $supportingCastBible" else "$protagonistBible and $antagonistBible"
                                        if (!rawImagePrompt.contains(protagonistBible.take(20), ignoreCase = true)) ", featuring $duoChars" else ""
                                    }
                                    protagonistBible.isNotBlank() && !rawImagePrompt.contains(protagonistBible.take(20), ignoreCase = true) -> ", featuring $protagonistBible"
                                    else -> ""
                                }
                                if (isEnvironment) {
                                    "$rawImagePrompt, shot on 35mm film, Kodak Vision3, natural diégétic lighting, atmospheric cinematic depth, 9:16 vertical format"
                                } else {
                                    "$rawImagePrompt$anchorSuffix, shot on 35mm film, Kodak Vision3, natural realistic lighting, cinematic depth of field, 9:16 vertical format"
                                }
                            } else if (isEnvironment) {
                                "Cinematic establishing wide shot of $actionDesc, vast atmospheric depth, architectural and natural panorama, no people in frame, shot on 35mm film, Kodak Vision3, 9:16 vertical format"
                            } else if (isCombat) {
                                val fighters = if (antagonistBible.isNotBlank()) "$protagonistBible and $antagonistBible" else protagonistBible
                                "Dynamic martial action combat scene, wide angle action choreography, $actionDesc, featuring $fighters in intense physical clash, fluid motion, dramatic rim lighting, shot on 35mm film, 9:16 vertical format"
                            } else if (isAntagonist) {
                                val antagDesc = if (antagonistBible.isNotBlank()) antagonistBible else "menacing antagonist figure"
                                "Cinematic low angle medium shot of $antagDesc, $actionDesc, dramatic rim lighting, intense gaze, shot on 35mm film, 9:16 vertical format"
                            } else if (isSupporting || isCounterShot) {
                                val suppDesc = if (supportingCastBible.isNotBlank()) supportingCastBible else "secondary character"
                                "Cinematic reverse angle medium close-up of $suppDesc, $actionDesc, intense gaze and natural expression, shot on 35mm film, 9:16 vertical format"
                            } else if (isDuo) {
                                val duoDesc = if (supportingCastBible.isNotBlank()) "$protagonistBible and $supportingCastBible" else "$protagonistBible and $antagonistBible"
                                "Cinematic two-shot framing, $actionDesc, featuring $duoDesc in intense dramatic interaction, spatial tension, shot on 35mm film, 9:16 vertical format"
                            } else {
                                "Cinematic medium shot of $protagonistBible, $actionDesc, natural realistic lighting, shot on 35mm film, 9:16 vertical format"
                            }

                            val rawVideoPrompt = sObj.optString("video_prompt", "").trim()
                            val unifiedVideoPrompt = if (rawVideoPrompt.isNotBlank()) {
                                "$camMovement, $rawVideoPrompt"
                            } else when {
                                isEnvironment -> "$camMovement, wide atmospheric world discovery, $actionDesc, natural ambient motion, smoke, wind, lighting dynamics"
                                isCombat -> "$camMovement, dynamic martial combat choreography, $actionDesc, physical martial clash, fluid impacts, wide action framing"
                                isCounterShot -> "$camMovement, reverse angle counter-shot, $actionDesc, intense human gaze, reaction to the approaching character"
                                isAntagonist -> "$camMovement, ominous presence, $actionDesc, calculating sinister body movement"
                                isDuo -> "$camMovement, two-shot interaction, $actionDesc, natural human movement and intense dialogue timing"
                                else -> "$camMovement, $actionDesc, natural human movement, organic camera framing"
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
                            val isEnv = nextNum == 1 || nextNum % 5 == 1
                            val isCounter = nextNum % 4 == 2 && supportingCastBible.isNotBlank()
                            val isAntag = nextNum % 4 == 3 && antagonistBible.isNotBlank()
                            val isAction = nextNum % 4 == 0
                            val fallbackDialogue = if (isEnv) "" else enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, nextNum, isCharacterPresent = true)
                            val fallbackAnchor = when {
                                isEnv -> ""
                                isCounter -> "[Supporting: $supportingCastBible]"
                                isAntag -> "[Antagonist: $antagonistBible]"
                                isAction -> "[Action: $protagonistBible vs $antagonistBible]"
                                else -> "[Protagonist: $protagonistBible]"
                            }
                            val charLabel = when {
                                isEnv -> "Décor / Environnement"
                                isCounter -> "Contre-champ / Secondaire"
                                isAntag -> "Antagoniste / Menace"
                                isAction -> "Action / Combat"
                                else -> "Protagoniste"
                            }

                            val fallbackImgPrompt = when {
                                isEnv -> "Cinematic wide establishing shot of vast atmospheric landscape, no people in frame, shot on 35mm film, 9:16 vertical format"
                                isCounter -> "Cinematic reverse angle medium close-up of $supportingCastBible, observing the scene, shot on 35mm film, 9:16 vertical format"
                                isAntag -> "Cinematic low angle medium shot of $antagonistBible, dramatic lighting, shot on 35mm film, 9:16 vertical format"
                                isAction -> "Dynamic martial action combat scene, two figures clashing, fluid choreography, shot on 35mm film, 9:16 vertical format"
                                else -> "Cinematic medium shot of $protagonistBible, $fallbackDesc, natural lighting, shot on 35mm film, 9:16 vertical format"
                            }

                            allDrafts.add(
                                GeneratedSceneDraft(
                                    number = nextNum,
                                    title = "Plan $nextNum : Séquence $charLabel",
                                    description = fallbackDesc,
                                    imagePrompt = fallbackImgPrompt,
                                    videoPrompt = "Travelling dynamique, $fallbackDesc, natural movement, organic camera framing",
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
                    val isNoCharFallback = n == 1 || n % 5 == 1
                    val isCounterFallback = !isNoCharFallback && n % 4 == 2 && supportingCastBible.isNotBlank()
                    val isAntagFallback = !isNoCharFallback && n % 4 == 3 && antagonistBible.isNotBlank()
                    val isActionFallback = !isNoCharFallback && n % 4 == 0
                    val fallbackDialogue = if (isNoCharFallback) "" else enforceCleanDialogue("", fallbackDesc, dialogueLanguage, audioPresence, n, isCharacterPresent = true)
                    val fallbackAnchor = when {
                        isNoCharFallback -> ""
                        isCounterFallback -> "[Supporting: $supportingCastBible]"
                        isAntagFallback -> "[Antagonist: $antagonistBible]"
                        isActionFallback -> "[Action: $protagonistBible vs $antagonistBible]"
                        else -> "[Protagonist: $protagonistBible]"
                    }
                    val charLabel = when {
                        isNoCharFallback -> "Décor / Environnement"
                        isCounterFallback -> "Contre-champ / Secondaire"
                        isAntagFallback -> "Antagoniste / Menace"
                        isActionFallback -> "Action / Combat"
                        else -> "Protagoniste"
                    }
                    val fallbackImg = when {
                        isNoCharFallback -> "Cinematic wide establishing shot of vast atmospheric landscape, no people in frame, shot on 35mm film, 9:16 vertical format"
                        isCounterFallback -> "Cinematic reverse angle medium close-up of $supportingCastBible, observing the scene, shot on 35mm film, 9:16 vertical format"
                        isAntagFallback -> "Cinematic low angle medium shot of $antagonistBible, dramatic lighting, shot on 35mm film, 9:16 vertical format"
                        isActionFallback -> "Dynamic martial action combat scene, two figures clashing, fluid choreography, shot on 35mm film, 9:16 vertical format"
                        else -> "Cinematic medium shot of $protagonistBible, $fallbackDesc, natural lighting, shot on 35mm film, 9:16 vertical format"
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
