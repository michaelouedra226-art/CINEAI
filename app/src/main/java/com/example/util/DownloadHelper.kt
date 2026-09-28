package com.example.util

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.io.File

object DownloadHelper {

    fun downloadImage(
        context: Context,
        url: String,
        prompt: String? = null
    ): Boolean {
        return try {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
                Toast.makeText(context, "URL d'image invalide", Toast.LENGTH_SHORT).show()
                return false
            }

            val filename = "Agnes_IMG_${System.currentTimeMillis()}.jpg"
            val title = if (!prompt.isNullOrBlank()) "Agnes: ${prompt.take(25)}..." else "Image Agnes Studio"

            val request = DownloadManager.Request(Uri.parse(cleanUrl)).apply {
                setTitle(title)
                setDescription("Téléchargement de l'image haute définition")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "AgnesStudio/$filename")
                setMimeType("image/jpeg")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Téléchargement de l'image lancé...", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Échec du téléchargement: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun downloadVideo(
        context: Context,
        url: String,
        prompt: String? = null
    ): Boolean {
        return try {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
                Toast.makeText(context, "URL de vidéo invalide", Toast.LENGTH_SHORT).show()
                return false
            }

            val filename = "Agnes_VID_${System.currentTimeMillis()}.mp4"
            val title = if (!prompt.isNullOrBlank()) "Agnes: ${prompt.take(25)}..." else "Vidéo Agnes Studio"

            val request = DownloadManager.Request(Uri.parse(cleanUrl)).apply {
                setTitle(title)
                setDescription("Téléchargement de la séquence vidéo MP4")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "AgnesStudio/$filename")
                setMimeType("video/mp4")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Téléchargement de la vidéo lancé...", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Échec du téléchargement: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun shareMedia(
        context: Context,
        url: String,
        title: String
    ) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, "$title\n\nLien du média : $url\n\nCréé avec Agnes Studio.")
            }
            context.startActivity(Intent.createChooser(shareIntent, "Partager ce média"))
        } catch (e: Exception) {
            Toast.makeText(context, "Impossible d'ouvrir le menu de partage", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyPrompt(context: Context, prompt: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Agnes Prompt", prompt)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Prompt copié dans le presse-papier", Toast.LENGTH_SHORT).show()
    }
}
