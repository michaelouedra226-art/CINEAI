package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
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
                    // Vérification périodique du seuil de cache LRU
                    pruneCache(context)
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

    /**
     * Calcule la taille totale en octets du cache vidéo local.
     */
    fun getCacheSizeBytes(context: Context): Long {
        val dir = getOfflineDir(context)
        val files = dir.listFiles() ?: return 0L
        return files.filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Nettoyage automatique selon la politique LRU (Least Recently Used).
     * Maintient le cache sous un seuil maximum (par défaut 500 Mo).
     * Supprime les fichiers les plus anciens d'abord.
     * Retourne le nombre d'octets libérés.
     */
    fun pruneCache(context: Context, maxSizeBytes: Long = 500 * 1024 * 1024L): Long {
        val dir = getOfflineDir(context)
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("vid_") } ?: return 0L
        val totalSize = files.sumOf { it.length() }
        if (totalSize <= maxSizeBytes) return 0L

        var bytesToFree = totalSize - maxSizeBytes
        var freedBytes = 0L

        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            val len = file.length()
            if (file.delete()) {
                freedBytes += len
                bytesToFree -= len
                if (bytesToFree <= 0) break
            }
        }
        return freedBytes
    }

    /**
     * Vide intégralement le cache des vidéos locales.
     */
    fun clearCache(context: Context): Long {
        val dir = getOfflineDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0L
        var freedBytes = 0L
        for (file in files) {
            val len = file.length()
            if (file.delete()) {
                freedBytes += len
            }
        }
        return freedBytes
    }

    /**
     * Concaténation séquentielle et assemblage MP4 complet (Stitching) :
     * Assemble tous les plans du film en un seul fichier MP4 unifié.
     */
    suspend fun stitchFilmScenes(
        context: Context,
        filmTitle: String,
        sceneVideoUrls: List<String>,
        onProgress: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val validUrls = sceneVideoUrls.filter { it.isNotBlank() }
        if (validUrls.isEmpty()) return@withContext null

        onProgress?.invoke(5)

        val localFiles = mutableListOf<File>()
        for ((idx, url) in validUrls.withIndex()) {
            val cachedPath = cacheVideo(context, url)
            if (cachedPath != null) {
                localFiles.add(File(cachedPath))
            } else if (url.startsWith("/") && File(url).exists()) {
                localFiles.add(File(url))
            }
            val downloadPct = 5 + ((idx + 1) * 35 / validUrls.size)
            onProgress?.invoke(downloadPct)
        }

        if (localFiles.isEmpty()) return@withContext null

        if (localFiles.size == 1) {
            onProgress?.invoke(100)
            return@withContext localFiles.first()
        }

        val cleanTitle = filmTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "StudioFilms")
        if (!exportDir.exists()) exportDir.mkdirs()
        val outputFile = File(exportDir, "${cleanTitle}_Film_Complet.mp4")
        if (outputFile.exists()) outputFile.delete()

        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            var videoPtsOffsetUs = 0L
            var audioPtsOffsetUs = 0L

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            for ((fileIndex, file) in localFiles.withIndex()) {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)

                var sourceVideoTrack = -1
                var sourceAudioTrack = -1
                var maxVideoPtsInFile = 0L
                var maxAudioPtsInFile = 0L

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && sourceVideoTrack == -1) {
                        sourceVideoTrack = i
                        if (!muxerStarted && videoTrackIndex == -1) {
                            videoTrackIndex = muxer.addTrack(format)
                        }
                    } else if (mime.startsWith("audio/") && sourceAudioTrack == -1) {
                        sourceAudioTrack = i
                        if (!muxerStarted && audioTrackIndex == -1) {
                            audioTrackIndex = muxer.addTrack(format)
                        }
                    }
                }

                if (!muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                }

                if (sourceVideoTrack != -1 && videoTrackIndex != -1) {
                    extractor.selectTrack(sourceVideoTrack)
                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break

                        bufferInfo.presentationTimeUs = extractor.sampleTime + videoPtsOffsetUs
                        bufferInfo.flags = extractor.sampleFlags

                        if (extractor.sampleTime > maxVideoPtsInFile) {
                            maxVideoPtsInFile = extractor.sampleTime
                        }

                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(sourceVideoTrack)
                }

                if (sourceAudioTrack != -1 && audioTrackIndex != -1) {
                    extractor.selectTrack(sourceAudioTrack)
                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break

                        bufferInfo.presentationTimeUs = extractor.sampleTime + audioPtsOffsetUs
                        bufferInfo.flags = extractor.sampleFlags

                        if (extractor.sampleTime > maxAudioPtsInFile) {
                            maxAudioPtsInFile = extractor.sampleTime
                        }

                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(sourceAudioTrack)
                }

                extractor.release()

                val fileDurationUs = maxOf(maxVideoPtsInFile, maxAudioPtsInFile)
                videoPtsOffsetUs += if (fileDurationUs > 0) fileDurationUs + 33_333L else 4_000_000L
                audioPtsOffsetUs += if (fileDurationUs > 0) fileDurationUs + 33_333L else 4_000_000L

                val muxPct = 40 + ((fileIndex + 1) * 55 / localFiles.size)
                onProgress?.invoke(muxPct)
            }

            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }

            onProgress?.invoke(100)
            if (outputFile.exists() && outputFile.length() > 1024L) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            outputFile.delete()
            null
        }
    }
}
