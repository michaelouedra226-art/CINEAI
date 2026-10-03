package com.example.media

import android.content.Context
import android.net.Uri
import com.example.BuildConfig
import com.example.api.TechnicalLogManager
import com.example.util.ImagePickerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads local image references to the already-provisioned private Supabase bucket.
 * Only the public anon key is used; object access is scoped by the authenticated user's RLS policies.
 */
object SupabaseImageStorage {
    private const val BUCKET = "cineai-video-inputs"
    private const val PREFERENCES = "cineai_supabase_session"
    private const val SIGNED_URL_TTL_SECONDS = 86_400
    private const val SESSION_REFRESH_MARGIN_SECONDS = 60

    private val authMutex = Mutex()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val imageType = "image/jpeg".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class ResolvedImage(val url: String, val objectPath: String? = null)

    fun isHttpUrl(source: String): Boolean =
        source.trim().startsWith("https://", ignoreCase = true) ||
            source.trim().startsWith("http://", ignoreCase = true)

    /** Leaves an existing HTTP(S) image untouched; uploads local files and data URIs privately. */
    suspend fun resolveImageSource(context: Context, imageSource: String): ResolvedImage =
        withContext(Dispatchers.IO) {
            val source = imageSource.trim()
            if (source.isBlank()) throw IllegalArgumentException("Image de référence vide.")
            if (isHttpUrl(source)) return@withContext ResolvedImage(source)

            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val publicKey = BuildConfig.SUPABASE_ANON_KEY
            if (baseUrl.isBlank() || publicKey.isBlank()) {
                throw IllegalStateException("Configuration Supabase absente de l’application.")
            }

            val imageBytes = ImagePickerHelper.prepareImageBytes(context, source)
                ?: throw IllegalArgumentException("Impossible de lire l’image sélectionnée.")
            val session = getOrCreateSession(context, baseUrl, publicKey)
            val objectPath = "${session.userId}/${UUID.randomUUID()}.jpg"
            val encodedPath = Uri.encode(objectPath, "/")
            val uploadRequest = Request.Builder()
                .url("$baseUrl/storage/v1/object/$BUCKET/$encodedPath")
                .header("apikey", publicKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Content-Type", "image/jpeg")
                .header("x-upsert", "false")
                .post(imageBytes.toRequestBody(imageType))
                .build()

            httpClient.newCall(uploadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    TechnicalLogManager.log("SUPABASE", "Échec upload image temporaire (HTTP ${response.code})", "ERROR")
                    throw IllegalStateException("Échec de l’upload privé de l’image (HTTP ${response.code}).")
                }
            }

            val signRequest = Request.Builder()
                .url("$baseUrl/storage/v1/object/sign/$BUCKET/$encodedPath")
                .header("apikey", publicKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .post(JSONObject().put("expiresIn", SIGNED_URL_TTL_SECONDS).toString().toRequestBody(jsonType))
                .build()

            val signedPath = httpClient.newCall(signRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    TechnicalLogManager.log("SUPABASE", "Échec de création de l’URL signée (HTTP ${response.code})", "ERROR")
                    throw IllegalStateException("Impossible de préparer l’image pour Agnes (HTTP ${response.code}).")
                }
                val json = JSONObject(body)
                json.optString("signedURL").ifBlank { json.optString("signedUrl") }
            }
            if (signedPath.isBlank()) {
                throw IllegalStateException("Supabase n’a pas renvoyé d’URL signée pour l’image.")
            }

            val signedUrl = when {
                signedPath.startsWith("https://", ignoreCase = true) || signedPath.startsWith("http://", ignoreCase = true) -> signedPath
                signedPath.startsWith("/storage/v1/") -> "$baseUrl$signedPath"
                signedPath.startsWith("/") -> "$baseUrl/storage/v1$signedPath"
                else -> "$baseUrl/storage/v1/$signedPath"
            }
            TechnicalLogManager.log("SUPABASE", "Image envoyée au bucket privé; URL signée créée (expiration 24 h)")
            ResolvedImage(url = signedUrl, objectPath = objectPath)
        }

    /** Removes an uploaded temporary object. Failures are logged but do not mask generation results. */
    suspend fun deleteTemporaryImage(context: Context, image: ResolvedImage?) = withContext(Dispatchers.IO) {
        val objectPath = image?.objectPath ?: return@withContext
        try {
            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val publicKey = BuildConfig.SUPABASE_ANON_KEY
            val session = getOrCreateSession(context, baseUrl, publicKey)
            val deleteRequest = Request.Builder()
                .url("$baseUrl/storage/v1/object/$BUCKET")
                .header("apikey", publicKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .delete(JSONObject().put("prefixes", JSONArray().put(objectPath)).toString().toRequestBody(jsonType))
                .build()
            httpClient.newCall(deleteRequest).execute().use { response ->
                if (response.isSuccessful) {
                    TechnicalLogManager.log("SUPABASE", "Image temporaire supprimée du bucket privé")
                } else {
                    TechnicalLogManager.log("SUPABASE", "Nettoyage image temporaire échoué (HTTP ${response.code})", "WARN")
                }
            }
        } catch (e: Exception) {
            TechnicalLogManager.log("SUPABASE", "Nettoyage image temporaire impossible: ${e.message}", "WARN")
        }
    }

    private suspend fun getOrCreateSession(context: Context, baseUrl: String, publicKey: String): AuthSession =
        authMutex.withLock {
            val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val cachedAccess = prefs.getString("access_token", null)
            val cachedRefresh = prefs.getString("refresh_token", null)
            val cachedUserId = prefs.getString("user_id", null)
            val expiresAt = prefs.getLong("expires_at", 0L)

            if (!cachedAccess.isNullOrBlank() && !cachedUserId.isNullOrBlank() &&
                expiresAt > nowSeconds + SESSION_REFRESH_MARGIN_SECONDS
            ) {
                return@withLock AuthSession(cachedAccess, cachedUserId)
            }

            if (!cachedRefresh.isNullOrBlank() && !cachedUserId.isNullOrBlank()) {
                try {
                    val refreshed = postAuthJson(
                        "$baseUrl/auth/v1/token?grant_type=refresh_token",
                        publicKey,
                        JSONObject().put("refresh_token", cachedRefresh)
                    )
                    return@withLock saveSession(prefs, refreshed, cachedUserId)
                } catch (_: Exception) {
                    prefs.edit().clear().apply()
                }
            }

            val anonymousSession = postAuthJson(
                "$baseUrl/auth/v1/signup",
                publicKey,
                JSONObject().put("data", JSONObject().put("app", "cineai-android"))
            )
            saveSession(prefs, anonymousSession, null)
        }

    private fun postAuthJson(url: String, publicKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("apikey", publicKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                TechnicalLogManager.log("SUPABASE", "Connexion anonyme/renouvellement refusé (HTTP ${response.code})", "ERROR")
                throw IllegalStateException(
                    if (response.code == 400 || response.code == 403 || response.code == 422) {
                        "Supabase refuse la session temporaire. Vérifiez que les connexions anonymes sont activées pour le projet."
                    } else {
                        "Impossible d’obtenir une session Supabase (HTTP ${response.code})."
                    }
                )
            }
            JSONObject(responseBody)
        }
    }

    private fun saveSession(
        prefs: android.content.SharedPreferences,
        json: JSONObject,
        fallbackUserId: String?
    ): AuthSession {
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val userId = json.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: fallbackUserId
        if (accessToken.isBlank() || refreshToken.isBlank() || userId.isNullOrBlank()) {
            throw IllegalStateException("Réponse de session Supabase incomplète.")
        }
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val tokenExpiry = json.optLong("expires_at", nowSeconds + json.optLong("expires_in", 3_600L))
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putString("user_id", userId)
            .putLong("expires_at", tokenExpiry)
            .apply()
        return AuthSession(accessToken, userId)
    }

    private data class AuthSession(val accessToken: String, val userId: String)
}
