package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImagePickerHelper {

    /**
     * Sauvegarde une image sélectionnée depuis le Photo Picker dans le stockage privé de l'application.
     * Retourne le chemin absolu du fichier copié.
     */
    suspend fun saveSelectedImage(context: Context, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val uploadDir = File(context.filesDir, "uploads")
            if (!uploadDir.exists()) uploadDir.mkdirs()

            val fileName = "user_img_${System.currentTimeMillis()}.jpg"
            val destFile = File(uploadDir, fileName)

            val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
            if (inputStream != null) {
                FileOutputStream(destFile).use { output ->
                    inputStream.copyTo(output)
                }
                destFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convertit une image (URL http, chemin de fichier ou URI) en format exploitable par l'API Agnes :
     * - Si c'est une URL HTTP distante : renvoyée telle quelle.
     * - Si c'est un fichier local ou URI content : convertie en Data URI Base64 ("data:image/jpeg;base64,...").
     */
    suspend fun prepareImageForApi(context: Context, imageSource: String): String = withContext(Dispatchers.IO) {
        val trimmed = imageSource.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return@withContext trimmed
        }

        try {
            val bitmap = when {
                trimmed.startsWith("content://") -> {
                    val uri = Uri.parse(trimmed)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                else -> {
                    val file = File(trimmed)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                }
            }

            if (bitmap != null) {
                // Redimensionner si l'image est trop gigantesque (max 1280px pour économiser la bande passante et éviter les rejets)
                val maxDim = 1280
                val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    val newWidth = (bitmap.width * ratio).toInt()
                    val newHeight = (bitmap.height * ratio).toInt()
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else {
                    bitmap
                }

                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                "data:image/jpeg;base64,$b64"
            } else {
                trimmed
            }
        } catch (e: Exception) {
            trimmed
        }
    }
}
