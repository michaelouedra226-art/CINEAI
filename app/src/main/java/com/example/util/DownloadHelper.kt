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
            if (cleanUrl.isBlank()) {
                Toast.makeText(context, "URL d'image invalide", Toast.LENGTH_SHORT).show()
                return false
            }

            val filename = "Agnes_IMG_${System.currentTimeMillis()}.jpg"

            // Si c'est un fichier local
            val localFile = when {
                cleanUrl.startsWith("file://") -> File(Uri.parse(cleanUrl).path.orEmpty())
                cleanUrl.startsWith("/") -> File(cleanUrl)
                else -> null
            }

            if (localFile != null && localFile.exists()) {
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AgnesStudio")
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, filename)
                localFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(context, "Image enregistrée dans Galerie/Photos/AgnesStudio", Toast.LENGTH_SHORT).show()
                return true
            }

            if (!cleanUrl.startsWith("http")) {
                Toast.makeText(context, "URL d'image non supportée", Toast.LENGTH_SHORT).show()
                return false
            }

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
            Toast.makeText(context, "Échec de l'enregistrement: ${e.message}", Toast.LENGTH_LONG).show()
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
            if (cleanUrl.isBlank()) {
                Toast.makeText(context, "URL de vidéo invalide", Toast.LENGTH_SHORT).show()
                return false
            }

            val filename = "Agnes_VID_${System.currentTimeMillis()}.mp4"

            // Si c'est déjà un fichier local ou mis en cache
            val localFile = when {
                cleanUrl.startsWith("file://") -> File(Uri.parse(cleanUrl).path.orEmpty())
                cleanUrl.startsWith("/") -> File(cleanUrl)
                OfflineVideoManager.isVideoCached(context, cleanUrl) -> OfflineVideoManager.getCachedFile(context, cleanUrl)
                else -> null
            }

            if (localFile != null && localFile.exists()) {
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AgnesStudio")
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, filename)
                localFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(context, "Vidéo enregistrée dans Galerie/Vidéos/AgnesStudio", Toast.LENGTH_SHORT).show()
                return true
            }

            if (!cleanUrl.startsWith("http")) {
                Toast.makeText(context, "URL de vidéo non supportée", Toast.LENGTH_SHORT).show()
                return false
            }

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
            Toast.makeText(context, "Échec de l'enregistrement: ${e.message}", Toast.LENGTH_LONG).show()
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

    fun shareFile(
        context: Context,
        file: File,
        title: String,
        mimeType: String = "video/mp4"
    ) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "Fichier introuvable pour le partage", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Partager le film studio"))
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur de partage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, text: String, title: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Partager le scénario studio"))
        } catch (e: Exception) {
            Toast.makeText(context, "Impossible de partager le texte", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyTextToClipboard(context: Context, text: String, label: String = "Scénario") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copié dans le presse-papier", Toast.LENGTH_SHORT).show()
    }

    /**
     * Génère un scénario complet au format standard Hollywood / Studio professionnel (Axe 5)
     */
    fun generateHollywoodScreenplay(film: com.example.data.model.FilmEntity): String {
        val scenes = com.example.data.model.SceneItem.parseList(film.scenesJson)
        val sb = StringBuilder()

        sb.append("=================================================================\n")
        sb.append("                   AGNES STUDIO PRODUCTION SCRIPT\n")
        sb.append("=================================================================\n\n")
        sb.append("TITRE : ${film.title.uppercase()}\n")
        if (film.logline.isNotBlank()) {
            sb.append("LOGLINE : ${film.logline}\n")
        }
        sb.append("STYLE CINÉMATOGRAPHIQUE : ${film.filmStyle}\n")
        sb.append("DURÉE ESTIMÉE : ${film.duration.toInt()} secondes (${scenes.size} plans)\n")
        sb.append("FORMAT DU PROJET : 9:16 Vertical Cinéma (24 fps)\n\n")

        // Section Casting & Continuité
        val characters = mutableMapOf<String, String>()
        scenes.forEach { sc ->
            if (sc.characterAnchor.isNotBlank()) {
                val label = sc.charactersPresent.ifBlank { "Personnage principal" }
                if (!characters.containsKey(label)) {
                    characters[label] = sc.characterAnchor
                }
            }
        }

        if (characters.isNotEmpty()) {
            sb.append("-----------------------------------------------------------------\n")
            sb.append("                   CASTING & BIBLE DE CONTINUITÉ\n")
            sb.append("-----------------------------------------------------------------\n")
            characters.forEach { (role, anchor) ->
                sb.append("• [${role.uppercase()}] :\n  $anchor\n\n")
            }
        }

        sb.append("=================================================================\n")
        sb.append("                   DÉCOUPAGE TECHNIQUE & SÉQUENCIER\n")
        sb.append("=================================================================\n\n")

        scenes.forEach { sc ->
            val act = sc.narrativePhase.ifBlank { "DÉROULEMENT" }.uppercase()
            val camera = sc.camera_movement.ifBlank { "PLAN FIXE" }.uppercase()
            val title = sc.title.ifBlank { "PLAN ${sc.number}" }.uppercase()

            sb.append("PLAN ${sc.number} - $title [$act]\n")
            sb.append("CADRAGE & CAMÉRA : $camera\n\n")
            sb.append("ACTION :\n${sc.description}\n\n")

            if (sc.dialogue.isNotBlank()) {
                val cleanDiag = sc.dialogue.trim().removePrefix("«").removeSuffix("»").trim()
                val speaker = if (sc.charactersPresent.isNotBlank() && !sc.charactersPresent.contains("Décor", ignoreCase = true)) {
                    sc.charactersPresent.uppercase()
                } else {
                    "PERSONNAGE"
                }
                sb.append("                   $speaker\n")
                sb.append("          \"$cleanDiag\"\n\n")
            }

            if (sc.soundDesign.isNotBlank()) {
                sb.append("SOUND DESIGN & AMBIANCE :\n[${sc.soundDesign}]\n\n")
            }

            sb.append("-----------------------------------------------------------------\n\n")
        }

        sb.append("                               FIN\n")
        sb.append("=================================================================\n")
        return sb.toString()
    }

    /**
     * Sauvegarde le script en fichier .txt dans le dossier documents de l'application
     */
    fun saveScriptToFile(context: Context, film: com.example.data.model.FilmEntity): File? {
        return try {
            val text = generateHollywoodScreenplay(film)
            val cleanTitle = film.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
            val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "StudioScripts")
            if (!exportDir.exists()) exportDir.mkdirs()
            val scriptFile = File(exportDir, "${cleanTitle}_Scenario_Studio.txt")
            scriptFile.writeText(text)
            Toast.makeText(context, "Scénario enregistré : ${scriptFile.name}", Toast.LENGTH_SHORT).show()
            scriptFile
        } catch (e: Exception) {
            Toast.makeText(context, "Erreur de sauvegarde: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }
}
