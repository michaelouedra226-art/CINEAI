package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

const val AGNES_VIDEO_MODEL = "agnes-video-2.5"

@Entity(tableName = "creations")
data class CreationEntity(
    @PrimaryKey val id: String,
    val type: String, // "image" | "video" | "film"
    val status: String, // "pending" | "queued" | "processing" | "done" | "failed" | "stalled" | "cancelled"
    val prompt: String,
    val negativePrompt: String? = null,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val resultUrl: String? = null,
    val thumbnail: String? = null,
    val favorite: Boolean = false,
    val tagsJson: String = "[]",
    val metadataJson: String = "{}",
    val error: String? = null,
    val stallRetries: Int = 0,
    val parentFilmId: String? = null,
    val progress: Int = 0
)

@Entity(tableName = "films")
data class FilmEntity(
    @PrimaryKey val id: String,
    val title: String,
    val logline: String,
    val prompt: String,
    val startImage: String = "",
    val status: String, // "processing" | "partial" | "done" | "failed"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val requestedDuration: Double = 30.0,
    val duration: Double = 30.0,
    val numScenes: Int = 5,
    val framesPerScene: Int = 120,
    val durationPerScene: Double = 5.0,
    val actualDuration: Int = 0,
    val filmStyle: String = "Cinématique",
    val scenesJson: String = "[]",
    val usageImpactJson: String = "{\"imageRequests\":0,\"videoSeconds\":0}",
    val favorite: Boolean = false,
    val failureReason: String? = null
) {
    /**
     * Priorité de la vignette selon le cahier des charges (Section 6) :
     * 1. image de départ du film ;
     * 2. première keyframe générée ;
     * 3. première image vidéo disponible ;
     * 4. null (fallback graphique stylé Agnes Studio)
     */
    fun getEffectiveThumbnail(): String? {
        if (startImage.isNotBlank()) return startImage
        val scenes = SceneItem.parseList(scenesJson)
        val firstKeyframe = scenes.firstOrNull { !it.keyframe.isNullOrBlank() }?.keyframe
        if (!firstKeyframe.isNullOrBlank()) return firstKeyframe
        val firstVideo = scenes.firstOrNull { !it.videoUrl.isNullOrBlank() }?.videoUrl
        if (!firstVideo.isNullOrBlank()) return firstVideo
        return null
    }

    fun getCompletedScenesCount(): Int {
        val scenes = SceneItem.parseList(scenesJson)
        return scenes.count { it.status == "done" }
    }

    fun hasPlayableVideo(): Boolean {
        val scenes = SceneItem.parseList(scenesJson)
        return scenes.any { !it.videoUrl.isNullOrBlank() }
    }

    fun getFirstIncompleteSceneIndex(): Int {
        val scenes = SceneItem.parseList(scenesJson)
        val idx = scenes.indexOfFirst { it.status != "done" }
        return if (idx >= 0) idx else 0
    }
}

@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey val date: String, // "2026-09-28"
    val textRequests: Int = 0,
    val imageRequests: Int = 0,
    val videoRequests: Int = 0,
    val videoSeconds: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: String = "main",
    val apiKey: String = "",
    val rateLimitProfile: String = "free", // "free" | "token" | "enterprise"
    val defaultImageModel: String = "agnes-image-2.1-flash",
    val defaultVideoModel: String = AGNES_VIDEO_MODEL,
    val defaultTextModel: String = "agnes-2.5-flash",
    val defaultImageSize: String = "2K",
    val defaultImageRatio: String = "9:16",
    val autoDownload: Boolean = false,
    val showTechnicalLog: Boolean = true,
    val theme: String = "dark"
)

@Entity(tableName = "queue")
data class QueueItemEntity(
    @PrimaryKey val id: String,
    val type: String, // "image" | "video" | "scene"
    val targetCreationId: String,
    val title: String,
    val status: String, // "queued" | "processing" | "stalled" | "done" | "failed"
    val progress: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val scheduledTime: Long = System.currentTimeMillis(),
    val stallRetries: Int = 0,
    val secondsRemaining: Int = 0
)

data class SceneItem(
    val number: Int,
    val title: String,
    val description: String,
    val image_prompt: String,
    val video_prompt: String,
    val camera_movement: String,
    val status: String, // "pending" | "processing" | "done" | "failed" | "stalled"
    val keyframe: String? = null,
    val videoUrl: String? = null,
    val error: String? = null,
    val stallRetries: Int = 0,
    val progressText: String = "",
    val dialogue: String = "",
    val audioMode: String = "dialogue",
    val characterAnchor: String = "",
    val narrativePhase: String = "Développement",
    val charactersPresent: String = "",
    val soundDesign: String = ""
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("number", number)
            put("title", title)
            put("description", description)
            put("image_prompt", image_prompt)
            put("video_prompt", video_prompt)
            put("camera_movement", camera_movement)
            put("status", status)
            put("keyframe", keyframe ?: JSONObject.NULL)
            put("videoUrl", videoUrl ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
            put("stallRetries", stallRetries)
            put("progressText", progressText)
            put("dialogue", dialogue)
            put("audioMode", audioMode)
            put("characterAnchor", characterAnchor)
            put("narrativePhase", narrativePhase)
            put("charactersPresent", charactersPresent)
            put("soundDesign", soundDesign)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): SceneItem {
            return SceneItem(
                number = obj.optInt("number", 1),
                title = obj.optString("title", ""),
                description = obj.optString("description", ""),
                image_prompt = obj.optString("image_prompt", ""),
                video_prompt = obj.optString("video_prompt", ""),
                camera_movement = obj.optString("camera_movement", "Travelling avant lent"),
                status = obj.optString("status", "pending"),
                keyframe = if (obj.isNull("keyframe")) null else obj.optString("keyframe"),
                videoUrl = if (obj.isNull("videoUrl")) null else obj.optString("videoUrl"),
                error = if (obj.isNull("error")) null else obj.optString("error"),
                stallRetries = obj.optInt("stallRetries", 0),
                progressText = obj.optString("progressText", ""),
                dialogue = obj.optString("dialogue", ""),
                audioMode = obj.optString("audioMode", "dialogue"),
                characterAnchor = obj.optString("characterAnchor", ""),
                narrativePhase = obj.optString("narrativePhase", "Développement"),
                charactersPresent = obj.optString("charactersPresent", ""),
                soundDesign = obj.optString("soundDesign", "")
            )
        }

        fun parseList(jsonStr: String): List<SceneItem> {
            val list = mutableListOf<SceneItem>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(fromJsonObject(array.getJSONObject(i)))
                }
            } catch (_: Exception) {}
            return list
        }

        fun serializeList(list: List<SceneItem>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJsonObject()) }
            return array.toString()
        }

        fun extractSpokenSpeech(rawDialogue: String): String {
            var cleaned = rawDialogue.replace("«", "").replace("»", "").replace("\"", "").trim()
            val colonIndex = cleaned.indexOf(':')
            if (colonIndex in 1..30) {
                val speaker = cleaned.substring(0, colonIndex).trim()
                if (!speaker.contains(" ") || speaker.equals("Voix off", ignoreCase = true) || speaker.equals("Voice over", ignoreCase = true)) {
                    cleaned = cleaned.substring(colonIndex + 1).trim()
                }
            }
            return cleaned
        }
    }
}
