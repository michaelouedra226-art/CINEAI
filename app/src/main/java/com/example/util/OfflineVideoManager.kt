package com.example.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OfflineVideoManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // Verrou pour éviter les téléchargements simultanés du même fichier
    private val downloadingUrls = ConcurrentHashMap.newKeySet<String>()

    /**
     * Calcule une clé de fichier locale déterministe à partir de l'URL
     */
    private fun getFileHash(url: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(url.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    /**
     * Retourne le répertoire de stockage local des vidéos hors-ligne
     */
    fun getOfflineDir(context: Context): File {
        val extDir = context.getExternalFilesDir("offline_videos")
        if (extDir != null && (extDir.exists() || extDir.mkdirs())) {
            return extDir
        }
        val dir = File(context.filesDir, "offline_videos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Vérifie si la vidéo est déjà stockée localement sur l'appareil
     */
    fun isVideoCached(context: Context, videoUrl: String): Boolean {
        if (videoUrl.isBlank()) return false
        if (videoUrl.startsWith("file://")) {
            val file = File(Uri.parse(videoUrl).path.orEmpty())
            return file.exists() && file.length() > 1024L
        }
        if (videoUrl.startsWith("/") && File(videoUrl).exists()) {
            return File(videoUrl).length() > 1024L
        }
        val file = getCachedFile(context, videoUrl)
        return file.exists() && file.length() > 1024L
    }

    /**
     * Retourne le fichier local correspondant à l'URL (qu'il existe ou non)
     */
    fun getCachedFile(context: Context, videoUrl: String): File {
        val hash = getFileHash(videoUrl)
        return File(getOfflineDir(context), "vid_$hash.mp4")
    }

    /**
     * Retourne l'URI directement lisible par VideoView :
     * - Si le fichier existe en local, renvoie un Content URI FileProvider sécurisé (lisible 100% hors-ligne)
     * - Sinon, renvoie Uri.parse(videoUrl)
     */
    fun getPlayableUri(context: Context, videoUrl: String): Uri {
        val trimmed = videoUrl.trim()
        if (trimmed.isBlank()) return Uri.EMPTY

        if (trimmed.startsWith("content://")) {
            return Uri.parse(trimmed)
        }

        if (trimmed.startsWith("file://")) {
            val file = File(Uri.parse(trimmed).path.orEmpty())
            if (file.exists() && file.length() > 1024L) {
                return try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }
            }
        }

        if (trimmed.startsWith("/") && File(trimmed).exists()) {
            val file = File(trimmed)
            if (file.length() > 1024L) {
                return try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }
            }
        }

        val localFile = getCachedFile(context, trimmed)
        return if (localFile.exists() && localFile.length() > 1024L) {
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
            } catch (e: Exception) {
                Uri.fromFile(localFile)
            }
        } else {
            Uri.parse(trimmed)
        }
    }

    /**
     * Télécharge et enregistre une vidéo pour la rendre disponible hors-ligne.
     * Retourne le chemin absolu du fichier local ou null en cas d'échec.
     */
    suspend fun cacheVideo(context: Context, videoUrl: String): String? = withContext(Dispatchers.IO) {
        val trimmed = videoUrl.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("http")) return@withContext null

        val destFile = getCachedFile(context, trimmed)
        if (destFile.exists() && destFile.length() > 1024L) {
            return@withContext destFile.absolutePath
        }

        if (!downloadingUrls.add(trimmed)) {
            // Déjà en cours de téléchargement par une autre tâche
            return@withContext destFile.absolutePath
        }

        val tempFile = File(getOfflineDir(context), "temp_" + destFile.name)

        try {
            val request = Request.Builder().url(trimmed).get().build()
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body ?: return@withContext null
                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { input ->
                        input.copyTo(fos)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 1024L) {
                    if (destFile.exists()) destFile.delete()
                    tempFile.renameTo(destFile)
                    destFile.absolutePath
                } else {
                    tempFile.delete()
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            tempFile.delete()
            null
        } finally {
            downloadingUrls.remove(trimmed)
        }
    }
}
