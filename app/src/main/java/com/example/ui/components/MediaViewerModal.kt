package com.example.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.SceneItem
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon
import com.example.util.AgnesVoiceManager
import com.example.util.DownloadHelper
import com.example.util.OfflineVideoManager
import kotlinx.coroutines.delay

@Composable
fun MediaViewerModal(
    creation: CreationEntity?,
    film: FilmEntity? = null,
    onDismiss: () -> Unit,
    onToggleFavorite: ((String, Boolean) -> Unit)? = null,
    onToggleFilmFavorite: ((String, Boolean) -> Unit)? = null,
    onDeleteCreation: ((String) -> Unit)? = null,
    onDeleteFilm: ((String) -> Unit)? = null,
    onResumeFilm: ((String) -> Unit)? = null,
    onRestartFilm: ((String) -> Unit)? = null,
    onReshootScene: ((filmId: String, sceneNumber: Int, action: String?, dialogue: String?, camera: String?) -> Unit)? = null,
    onExportStitchedFilm: ((FilmEntity) -> Unit)? = null,
    onGenerateCastingPortrait: ((characterBible: String, filmStyle: String, (String?) -> Unit) -> Unit)? = null
) {
    if (creation == null && film == null) return

    val context = LocalContext.current
    var isDetailsExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isFullscreenModal by remember { mutableStateOf(false) }

    val isFilm = film != null
    val isVideo = isFilm || creation?.type == "video"
    val title = when {
        isFilm -> film!!.title
        creation?.type == "video" -> "Vidéo cinématique"
        else -> "Image cinématique"
    }

    val primaryUrl = when {
        isFilm -> film!!.getEffectiveThumbnail().orEmpty()
        creation?.resultUrl != null -> creation.resultUrl
        else -> creation?.thumbnail.orEmpty()
    }

    if (showDeleteConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = if (isFilm) "Supprimer ce film ?" else "Supprimer ce média ?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Cette action est irréversible et supprimera le projet de votre galerie locale.",
                    color = Color(0xFFBBBBD0)
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        if (film != null) {
                            onDeleteFilm?.invoke(film.id)
                        } else if (creation != null) {
                            onDeleteCreation?.invoke(creation.id)
                        }
                        onDismiss()
                    }
                ) {
                    Text("Supprimer définitivement", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Annuler", color = Color(0xFFA1A1AA))
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF09090E))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── TOP BAR (masquée en mode plein écran) ─────────
                if (!isFullscreenModal) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xEE13131A))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E1E28))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color.White, size = 18.dp)
                        }

                        Column {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isFilm) "Mode Film Studio" else if (isVideo) "Rendu vidéo MP4" else "Rendu image HD",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Partager
                        if (primaryUrl.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1E28))
                                    .clickable {
                                        triggerHapticFeedback(context)
                                        DownloadHelper.shareMedia(context, primaryUrl, title)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AgnesSvgIcon(icon = AgnesIcon.SHARE, tint = Color(0xFFBBBBD0), size = 18.dp)
                            }
                        }

                        // Télécharger
                        if (primaryUrl.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF7C3AED))
                                    .clickable {
                                        triggerHapticFeedback(context)
                                        if (isVideo) {
                                            DownloadHelper.downloadVideo(context, primaryUrl, creation?.prompt)
                                        } else {
                                            DownloadHelper.downloadImage(context, primaryUrl, creation?.prompt)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AgnesSvgIcon(icon = AgnesIcon.DOWNLOAD, tint = Color.White, size = 18.dp)
                            }
                        }

                        // Favori (Film ou Création)
                        val isFav = if (film != null) film.favorite else creation?.favorite ?: false
                        if ((film != null && onToggleFilmFavorite != null) || (creation != null && onToggleFavorite != null)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1E28))
                                    .clickable {
                                        triggerHapticFeedback(context)
                                        if (film != null) {
                                            onToggleFilmFavorite?.invoke(film.id, !isFav)
                                        } else if (creation != null) {
                                            onToggleFavorite?.invoke(creation.id, !isFav)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AgnesSvgIcon(
                                    icon = if (isFav) AgnesIcon.FAVORITE_FILLED else AgnesIcon.FAVORITE,
                                    tint = if (isFav) Color(0xFFEF4444) else Color(0xFFBBBBD0),
                                    size = 18.dp
                                )
                            }
                        }

                        // Supprimer
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF28181E))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    showDeleteConfirmDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.TRASH, tint = Color(0xFFEF4444), size = 18.dp)
                        }
                    }
                }
            }

                // ─── MEDIA CONTENT VIEWPORT ───────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isFilm -> {
                            FilmPlayerView(
                                film = film!!,
                                onResumeFilm = onResumeFilm,
                                onRestartFilm = onRestartFilm,
                                onDeleteFilm = onDeleteFilm,
                                onReshootScene = onReshootScene,
                                onExportStitchedFilm = onExportStitchedFilm,
                                onGenerateCastingPortrait = onGenerateCastingPortrait,
                                onDismiss = onDismiss
                            )
                        }
                        isVideo -> {
                            if (primaryUrl.isNotBlank()) {
                                VideoPlayerComponent(
                                    videoUrl = primaryUrl,
                                    dialogue = creation?.prompt,
                                    isFullScreen = isFullscreenModal,
                                    onToggleFullScreen = { isFullscreenModal = !isFullscreenModal }
                                )
                            } else {
                                EmptyMediaState(message = "Rendu vidéo en cours ou lien indisponible")
                            }
                        }
                        else -> {
                            if (primaryUrl.isNotBlank()) {
                                ImageViewerWithZoom(imageUrl = primaryUrl)
                            } else {
                                EmptyMediaState(message = "Image indisponible")
                            }
                        }
                    }
                }

                // ─── BOTTOM DETAILS ACCORDION (masqué en mode plein écran) ─────
                if (!isFullscreenModal) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xEE13131A))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isDetailsExpanded = !isDetailsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isDetailsExpanded) "Masquer les détails ▲" else "Afficher les détails du prompt ▼",
                                color = Color(0xFFA78BFA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val promptToCopy = creation?.prompt ?: film?.prompt.orEmpty()
                            if (promptToCopy.isNotBlank()) {
                                Text(
                                    text = "Copier le prompt",
                                    color = Color(0xFF34D399),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable {
                                        triggerHapticFeedback(context)
                                        DownloadHelper.copyPrompt(context, promptToCopy)
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(visible = isDetailsExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                val promptText = creation?.prompt ?: film?.prompt.orEmpty()
                                if (promptText.isNotBlank()) {
                                    Text(
                                        text = promptText,
                                        color = Color(0xFFE5E7EB),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (creation != null) {
                                        InfoBadge(label = "Modèle", value = creation.model)
                                        InfoBadge(label = "Type", value = creation.type.uppercase())
                                        InfoBadge(label = "Statut", value = creation.status)
                                    }
                                    if (film != null) {
                                        InfoBadge(label = "Style", value = film.filmStyle)
                                        InfoBadge(label = "Plans", value = "${film.numScenes} scènes")
                                        InfoBadge(label = "Durée", value = "${film.duration.toInt()}s")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visualiseur d'image haute définition avec zoom interactif (pinch-to-zoom) et translation (pan).
 */
@Composable
fun ImageViewerWithZoom(imageUrl: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4.5f)
                    if (scale > 1f) {
                        val maxOffsetX = (size.width * (scale - 1f)) / 2f
                        val maxOffsetY = (size.height * (scale - 1f)) / 2f
                        offsetX = (offsetX + pan.x * scale).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + pan.y * scale).coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Image agrandie",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = ContentScale.Fit
        )

        if (scale > 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .clickable {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Réinitialiser le zoom (x${String.format("%.1f", scale)})",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Lecteur vidéo natif intégrant VideoView avec commandes Play/Pause, Replay et Scrubbing.
 * Rechargement dynamique garanti lors du changement d'URL de scène.
 */
@Composable
fun VideoPlayerComponent(
    videoUrl: String,
    fallbackImageUrl: String? = null,
    isLooping: Boolean = true,
    dialogue: String? = null,
    dialogueLanguage: String = "fr",
    audioMode: String = "dialogue",
    charactersPresent: String? = null,
    isFullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null,
    onNavigatePrevious: (() -> Unit)? = null,
    onNavigateNext: (() -> Unit)? = null,
    badgeText: String? = null,
    onComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isCachedLocally by remember(videoUrl) {
        mutableStateOf(OfflineVideoManager.isVideoCached(context, videoUrl))
    }
    val playableUri = remember(videoUrl, isCachedLocally) {
        OfflineVideoManager.getPlayableUri(context, videoUrl)
    }

    var isPlaying by remember(videoUrl) { mutableStateOf(false) }
    var currentPositionMs by remember(videoUrl) { mutableIntStateOf(0) }
    var durationMs by remember(videoUrl) { mutableIntStateOf(0) }
    var isBuffering by remember(videoUrl) { mutableStateOf(true) }
    var hasError by remember(videoUrl) { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var areControlsVisible by remember(videoUrl) { mutableStateOf(true) }
    var isUserScrubbing by remember { mutableStateOf(false) }

    val isSpeaking by AgnesVoiceManager.isSpeaking.collectAsState()
    val isTrueCharacterSpeech = remember(dialogue, charactersPresent) {
        SceneItem.isCharacterSpeech(dialogue, charactersPresent)
    }

    // Auto-masquage cinématique des commandes après 2.8 secondes de lecture
    LaunchedEffect(isPlaying, areControlsVisible, isUserScrubbing) {
        if (isPlaying && areControlsVisible && !isUserScrubbing) {
            delay(2800)
            areControlsVisible = false
        }
    }

    // Téléchargement en cache arrière-plan pour fluidité
    LaunchedEffect(videoUrl) {
        if (!isCachedLocally && videoUrl.startsWith("http")) {
            val cachedPath = OfflineVideoManager.cacheVideo(context, videoUrl)
            if (cachedPath != null) {
                isCachedLocally = true
            }
        }
    }

    // Synchronisation de la progression
    LaunchedEffect(isPlaying, videoUrl) {
        while (isPlaying) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying && !isUserScrubbing) {
                    currentPositionMs = vv.currentPosition
                    durationMs = vv.duration
                }
            }
            delay(200)
        }
    }

    DisposableEffect(videoUrl) {
        onDispose {
            videoViewRef?.stopPlayback()
            AgnesVoiceManager.stop()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                areControlsVisible = !areControlsVisible
            },
        contentAlignment = Alignment.Center
    ) {
        if (!hasError) {
            androidx.compose.runtime.key(videoUrl, isCachedLocally, isLooping) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(playableUri)
                            setOnPreparedListener { mp ->
                                isBuffering = false
                                durationMs = mp.duration
                                mp.isLooping = isLooping
                                start()
                                isPlaying = true
                                // Déclenchement TTS STRICTEMENT réservé aux répliques des personnages
                                if (isTrueCharacterSpeech && audioMode != "ambient") {
                                    val speech = SceneItem.extractSpokenSpeech(dialogue.orEmpty(), charactersPresent)
                                    if (speech.isNotBlank()) {
                                        AgnesVoiceManager.speak(ctx, speech, dialogueLanguage)
                                    }
                                }
                            }
                            setOnErrorListener { _, _, _ ->
                                isBuffering = false
                                isPlaying = false
                                hasError = true
                                true
                            }
                            setOnCompletionListener {
                                isPlaying = false
                                onComplete?.invoke()
                            }
                            videoViewRef = this
                        }
                    },
                    update = { vv ->
                        videoViewRef = vv
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            if (!fallbackImageUrl.isNullOrBlank()) {
                ImageViewerWithZoom(imageUrl = fallbackImageUrl)
            } else {
                EmptyMediaState(message = "Format vidéo non décodable sur ce périphérique")
            }
        }

        // Indicateur de chargement
        if (isBuffering) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFA78BFA), strokeWidth = 2.5.dp)
            }
        }

        // Bouton central Play/Pause (s'estompe quand la vidéo tourne)
        AnimatedVisibility(
            visible = areControlsVisible || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xAA0B0B14))
                    .border(1.5.dp, Color(0x66A78BFA), CircleShape)
                    .clickable {
                        triggerHapticFeedback(context)
                        videoViewRef?.let { vv ->
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                                areControlsVisible = true
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AgnesSvgIcon(
                    icon = if (isPlaying) AgnesIcon.PAUSE else AgnesIcon.PLAY,
                    tint = Color.White,
                    size = 22.dp
                )
            }
        }

        // Boutons latéraux de navigation Scène précédente / suivante (s'estompent)
        if (onNavigatePrevious != null) {
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable {
                            triggerHapticFeedback(context)
                            onNavigatePrevious()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (onNavigateNext != null) {
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable {
                            triggerHapticFeedback(context)
                            onNavigateNext()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("›", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // En-tête discret (Badges haut gauche / haut droite) qui s'estompe avec les contrôles
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCC0F172A),
                        border = BorderStroke(0.8.dp, Color(0xFF8B5CF6))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34D399))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = badgeText,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (isCachedLocally) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCC064E3B),
                        border = BorderStroke(0.8.dp, Color(0xFF10B981))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.CHECK, tint = Color(0xFF34D399), size = 11.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Hors-ligne",
                                color = Color(0xFFD1FAE5),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Sous-titres cinématiques : UNIQUEMENT pour les personnages, positionnés au-dessus des commandes
        if (isTrueCharacterSpeech && audioMode != "ambient") {
            val speakerName = SceneItem.extractCharacterSpeaker(dialogue.orEmpty())
            val speechContent = SceneItem.extractSpokenSpeech(dialogue.orEmpty(), charactersPresent).ifBlank { dialogue.orEmpty() }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (areControlsVisible) 78.dp else 22.dp,
                        start = 16.dp,
                        end = 16.dp
                    )
                    .fillMaxWidth(0.92f),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xD909090F),
                border = BorderStroke(0.8.dp, if (isSpeaking) Color(0xFFA78BFA) else Color(0x44FFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSpeaking) Color(0xFF7C3AED) else Color(0xFF262638))
                            .clickable {
                                if (isSpeaking) {
                                    AgnesVoiceManager.stop()
                                } else {
                                    val sp = SceneItem.extractSpokenSpeech(dialogue.orEmpty(), charactersPresent)
                                    if (sp.isNotBlank()) AgnesVoiceManager.speak(context, sp, dialogueLanguage)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(
                            icon = AgnesIcon.AUDIO,
                            tint = if (isSpeaking) Color.White else Color(0xFFA78BFA),
                            size = 12.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (speakerName != null) {
                            Text(
                                text = speakerName.uppercase(),
                                color = Color(0xFFA78BFA),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = speechContent,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Overlay inférieur des contrôles vidéo (Timeline + Scrubber + Actions)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE6000000))
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Barre de scrubber temporelle
                val progressRatio = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatMs(currentPositionMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Slider(
                        value = progressRatio,
                        onValueChange = { newRatio ->
                            isUserScrubbing = true
                            val targetMs = (newRatio * durationMs).toInt()
                            currentPositionMs = targetMs
                            videoViewRef?.seekTo(targetMs)
                        },
                        onValueChangeFinished = {
                            isUserScrubbing = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFA78BFA),
                            activeTrackColor = Color(0xFF7C3AED),
                            inactiveTrackColor = Color(0x44FFFFFF)
                        )
                    )

                    Text(
                        text = formatMs(durationMs),
                        color = Color(0xFFA1A1AA),
                        fontSize = 11.sp
                    )
                }

                // Actions de la barre de contrôle : Replay, Play/Pause, Fullscreen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Replay
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E28))
                            .clickable {
                                triggerHapticFeedback(context)
                                videoViewRef?.seekTo(0)
                                currentPositionMs = 0
                                videoViewRef?.start()
                                isPlaying = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.GENERATE, tint = Color(0xFFBBBBD0), size = 14.dp)
                    }

                    // Play/Pause bouton compact
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7C3AED))
                            .clickable {
                                triggerHapticFeedback(context)
                                videoViewRef?.let { vv ->
                                    if (vv.isPlaying) {
                                        vv.pause()
                                        isPlaying = false
                                    } else {
                                        vv.start()
                                        isPlaying = true
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(
                            icon = if (isPlaying) AgnesIcon.PAUSE else AgnesIcon.PLAY,
                            tint = Color.White,
                            size = 18.dp
                        )
                    }

                    // Bouton Plein écran / Mode Cinéma
                    if (onToggleFullScreen != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isFullScreen) Color(0xFF4C1D95) else Color(0xFF1E1E28))
                            .clickable {
                                triggerHapticFeedback(context)
                                onToggleFullScreen()
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(
                                icon = if (isFullScreen) AgnesIcon.FULLSCREEN_EXIT else AgnesIcon.FULLSCREEN,
                                tint = Color.White,
                                size = 15.dp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

/**
 * Visualiseur et lecteur pour les projets de films séquentiels.
 * Affiche la liste des plans/scènes et permet de visionner et télécharger chaque séquence.
 */
@Composable
fun FilmPlayerView(
    film: FilmEntity,
    onResumeFilm: ((String) -> Unit)? = null,
    onRestartFilm: ((String) -> Unit)? = null,
    onDeleteFilm: ((String) -> Unit)? = null,
    onReshootScene: ((filmId: String, sceneNumber: Int, action: String?, dialogue: String?, camera: String?) -> Unit)? = null,
    onExportStitchedFilm: ((FilmEntity) -> Unit)? = null,
    onGenerateCastingPortrait: ((characterBible: String, filmStyle: String, (String?) -> Unit) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scenes: List<SceneItem> = remember(film.scenesJson) {
        SceneItem.parseList(film.scenesJson)
    }

    var selectedSceneIndex by remember { mutableIntStateOf(0) }
    val activeScene = scenes.getOrNull(selectedSceneIndex)
    var showFullDecoupage by remember { mutableStateOf(false) }

    var isContinuousMode by remember { mutableStateOf(true) }
    var showReshootDialog by remember { mutableStateOf(false) }
    var showScriptModal by remember { mutableStateOf(false) }
    var showCastingModal by remember { mutableStateOf(false) }

    var editReshootAction by remember(activeScene) { mutableStateOf(activeScene?.description.orEmpty()) }
    var editReshootDiag by remember(activeScene) { mutableStateOf(activeScene?.dialogue.orEmpty()) }
    var editReshootCam by remember(activeScene) { mutableStateOf(activeScene?.camera_movement.orEmpty()) }

    // Dialog Scénario Studio Standard Hollywood (Axe 5)
    if (showScriptModal) {
        val scriptText = remember(film) { DownloadHelper.generateHollywoodScreenplay(film) }
        AlertDialog(
            onDismissRequest = { showScriptModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Scénario Professionnel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Format Studio & Découpage Hollywoodien", color = Color(0xFFA78BFA), fontSize = 11.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF282836))
                            .clickable { showScriptModal = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color.White, size = 14.dp)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F0F14),
                        border = BorderStroke(1.dp, Color(0xFF2A2A3A))
                    ) {
                        Text(
                            text = scriptText,
                            color = Color(0xFFE2E8F0),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF4C1D95))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    DownloadHelper.copyTextToClipboard(context, scriptText, "Scénario")
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Copier", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E28))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    DownloadHelper.shareText(context, scriptText, film.title)
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Partager", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF047857))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    DownloadHelper.saveScriptToFile(context, film)
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Sauver .txt", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog Casting & Bible de Continuité Visuelle (Axe 3)
    if (showCastingModal) {
        val characterMap = remember(scenes) {
            val map = mutableMapOf<String, String>()
            scenes.forEach { sc ->
                if (sc.characterAnchor.isNotBlank()) {
                    val label = sc.charactersPresent.ifBlank { "Protagoniste" }
                    if (!map.containsKey(label)) {
                        map[label] = sc.characterAnchor
                    }
                }
            }
            if (map.isEmpty()) {
                map["Protagoniste"] = film.prompt
            }
            map
        }
        var portraits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var loadingCharacter by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCastingModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Casting & Bible de Continuité", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Cohérence des visages et tenues", color = Color(0xFFA78BFA), fontSize = 11.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF282836))
                            .clickable { showCastingModal = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color.White, size = 14.dp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    characterMap.forEach { (role, anchor) ->
                        val portraitUrl = portraits[role]
                        val isLoadingThis = loadingCharacter == role

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF13131A))
                                .border(1.dp, Color(0xFF282836), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = role.uppercase(),
                                    color = Color(0xFFA78BFA),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (onGenerateCastingPortrait != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isLoadingThis) Color(0xFF3F3F46) else Color(0xFF7C3AED))
                                            .clickable(enabled = !isLoadingThis) {
                                                triggerHapticFeedback(context)
                                                loadingCharacter = role
                                                onGenerateCastingPortrait(anchor, film.filmStyle) { url ->
                                                    loadingCharacter = null
                                                    if (!url.isNullOrBlank()) {
                                                        portraits = portraits + (role to url)
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isLoadingThis) "Génération..." else if (portraitUrl != null) "Re-générer" else "Portrait Lookbook",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (portraitUrl != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = portraitUrl,
                                        contentDescription = "Portrait $role",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            Text(
                                text = anchor,
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showReshootDialog && activeScene != null) {
        AlertDialog(
            onDismissRequest = { showReshootDialog = false },
            title = {
                Text(
                    text = "Re-tourner le plan ${activeScene.number}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Ajustez la mise en scène, le dialogue ou la caméra pour relancer ce plan isolé :",
                        color = Color(0xFFA1A1AA),
                        fontSize = 12.sp
                    )

                    Text("Description de l'action :", color = Color(0xFFC4B5FD), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editReshootAction,
                        onValueChange = { editReshootAction = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF13131A),
                            unfocusedContainerColor = Color(0xFF13131A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Text("Dialogue parlé :", color = Color(0xFFC4B5FD), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editReshootDiag,
                        onValueChange = { editReshootDiag = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF13131A),
                            unfocusedContainerColor = Color(0xFF13131A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Text("Cadrage / Mouvement de caméra :", color = Color(0xFFC4B5FD), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editReshootCam,
                        onValueChange = { editReshootCam = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF13131A),
                            unfocusedContainerColor = Color(0xFF13131A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReshootDialog = false
                        onReshootScene?.invoke(
                            film.id,
                            activeScene.number,
                            editReshootAction,
                            editReshootDiag,
                            editReshootCam
                        )
                    }
                ) {
                    Text("Lancer le Reshoot", color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReshootDialog = false }) {
                    Text("Annuler", color = Color(0xFFA1A1AA))
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    var isFullScreenFilm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Alerte si film interrompu / partiel
        if (!isFullScreenFilm && (film.status == "partial" || film.status == "failed")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF24151C))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Production interrompue (${film.getCompletedScenesCount()}/${film.numScenes} scènes)",
                        color = Color(0xFFF59E0B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!film.failureReason.isNullOrBlank()) {
                        Text(
                            text = film.failureReason,
                            color = Color(0xFFBBBBCC),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onResumeFilm != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF7C3AED))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onResumeFilm(film.id)
                                    onDismiss?.invoke()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Reprendre", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onRestartFilm != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF282836))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onRestartFilm(film.id)
                                    onDismiss?.invoke()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Recommencer", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Zone de lecture vidéo du plan sélectionné
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val videoUrl = activeScene?.videoUrl
            val keyframeUrl = activeScene?.keyframe

            androidx.compose.runtime.key(selectedSceneIndex, videoUrl, keyframeUrl, isContinuousMode) {
                if (!videoUrl.isNullOrBlank()) {
                    val sceneDiag = activeScene?.dialogue.orEmpty()
                    val sceneAudioMode = activeScene?.audioMode ?: "dialogue"
                    val isEnglishDiag = sceneDiag.contains("the ", ignoreCase = true) || sceneDiag.contains("you ", ignoreCase = true)
                    val diagLang = if (isEnglishDiag) "en" else "fr"

                    VideoPlayerComponent(
                        videoUrl = videoUrl,
                        fallbackImageUrl = keyframeUrl,
                        isLooping = !isContinuousMode,
                        dialogue = sceneDiag,
                        dialogueLanguage = diagLang,
                        audioMode = sceneAudioMode,
                        charactersPresent = activeScene?.charactersPresent,
                        isFullScreen = isFullScreenFilm,
                        onToggleFullScreen = { isFullScreenFilm = !isFullScreenFilm },
                        onNavigatePrevious = if (selectedSceneIndex > 0) {
                            { selectedSceneIndex-- }
                        } else null,
                        onNavigateNext = if (selectedSceneIndex < scenes.size - 1) {
                            { selectedSceneIndex++ }
                        } else null,
                        badgeText = if (scenes.isNotEmpty()) "PLAN ${selectedSceneIndex + 1}/${scenes.size}" + (if (isContinuousMode) " • ENCHAÎNÉ" else "") else null,
                        onComplete = {
                            if (isContinuousMode && scenes.isNotEmpty()) {
                                if (selectedSceneIndex < scenes.size - 1) {
                                    selectedSceneIndex++
                                } else {
                                    selectedSceneIndex = 0
                                }
                            }
                        }
                    )
                } else if (!keyframeUrl.isNullOrBlank()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ImageViewerWithZoom(imageUrl = keyframeUrl)
                        val sceneDiag = activeScene?.dialogue.orEmpty()
                        val isEnglishDiag = sceneDiag.contains("the ", ignoreCase = true) || sceneDiag.contains("you ", ignoreCase = true)
                        val diagLang = if (isEnglishDiag) "en" else "fr"
                        val isCharSpeech = SceneItem.isCharacterSpeech(sceneDiag, activeScene?.charactersPresent)

                        if (isCharSpeech && activeScene?.audioMode != "ambient") {
                            val speakerName = SceneItem.extractCharacterSpeaker(sceneDiag)
                            val cleanSpoken = SceneItem.extractSpokenSpeech(sceneDiag, activeScene?.charactersPresent).ifBlank { sceneDiag }

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                                    .fillMaxWidth(0.92f),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xD909090F),
                                border = BorderStroke(0.8.dp, Color(0x44FFFFFF))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isSpeakingKeyframe by AgnesVoiceManager.isSpeaking.collectAsState()
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(if (isSpeakingKeyframe) Color(0xFF7C3AED) else Color(0xFF262638))
                                            .clickable {
                                                if (isSpeakingKeyframe) {
                                                    AgnesVoiceManager.stop()
                                                } else {
                                                    val sp = SceneItem.extractSpokenSpeech(sceneDiag, activeScene?.charactersPresent)
                                                    if (sp.isNotBlank()) AgnesVoiceManager.speak(context, sp, diagLang)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AgnesSvgIcon(
                                            icon = AgnesIcon.AUDIO,
                                            tint = if (isSpeakingKeyframe) Color.White else Color(0xFFA78BFA),
                                            size = 12.dp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (speakerName != null) {
                                            Text(
                                                text = speakerName.uppercase(),
                                                color = Color(0xFFA78BFA),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                        Text(
                                            text = cleanSpoken,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    EmptyMediaState(message = "Plan ${activeScene?.number ?: (selectedSceneIndex + 1)} en cours de production...")
                }
            }
        }

        // Sélecteur de scènes (Carousel de plans et Découpage) masqué en mode plein écran
        if (!isFullScreenFilm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF13131A))
                    .padding(12.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Toggle Mode Continu / Plan par plan (Axe 1)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isContinuousMode) Color(0xFF4C1D95) else Color(0xFF1E1E28))
                            .border(1.dp, if (isContinuousMode) Color(0xFFA78BFA) else Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                isContinuousMode = !isContinuousMode
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isContinuousMode) "▶ Film continu" else "⏸ Plan par plan",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (showFullDecoupage) "Masquer découpage" else "Voir découpage",
                        color = Color(0xFFA78BFA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { showFullDecoupage = !showFullDecoupage }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Bouton Scénario Studio (Axe 5)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF475569), RoundedCornerShape(6.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                showScriptModal = true
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgnesSvgIcon(icon = AgnesIcon.LOGS, tint = Color(0xFF94A3B8), size = 11.dp)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Script", color = Color.White, fontSize = 11.sp)
                        }
                    }

                    // Bouton Casting & Lookbook (Axe 3)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E1B4B))
                            .border(1.dp, Color(0xFF6366F1), RoundedCornerShape(6.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                showCastingModal = true
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgnesSvgIcon(icon = AgnesIcon.HOME_IMAGES, tint = Color(0xFFA5B4FC), size = 11.dp)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Casting", color = Color.White, fontSize = 11.sp)
                        }
                    }

                    // Bouton Re-tourner la scène (Axe 2)
                    if (activeScene != null && onReshootScene != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2E1065))
                                .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(6.dp))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    editReshootAction = activeScene.description
                                    editReshootDiag = activeScene.dialogue
                                    editReshootCam = activeScene.camera_movement
                                    showReshootDialog = true
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AgnesSvgIcon(icon = AgnesIcon.GENERATE, tint = Color(0xFFC4B5FD), size = 11.dp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Re-tourner", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }

                    // Bouton Exporter Film Complet Stitched (Axe 1)
                    if (onExportStitchedFilm != null && scenes.any { !it.videoUrl.isNullOrBlank() }) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF047857))
                                .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                onExportStitchedFilm(film)
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color.White, size = 11.dp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Film MP4", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (activeScene?.videoUrl != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF7C3AED))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    DownloadHelper.downloadVideo(
                                        context,
                                        activeScene.videoUrl,
                                        activeScene.title
                                    )
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.DOWNLOAD, tint = Color.White, size = 11.dp)
                            Text(text = "P${activeScene.number}", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!showFullDecoupage) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(scenes) { index, sc ->
                        val isSelected = index == selectedSceneIndex
                        Column(
                            modifier = Modifier
                                .width(110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF282836) else Color(0xFF1C1C25))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFA78BFA) else Color(0xFF2E2E3E),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    triggerHapticFeedback(context)
                                    selectedSceneIndex = index
                                }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0F0F14)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!sc.keyframe.isNullOrBlank()) {
                                    AsyncImage(
                                        model = sc.keyframe,
                                        contentDescription = sc.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFF6B7280), size = 20.dp)
                                }

                                if (!sc.videoUrl.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xCC7C3AED)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 10.dp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Scène ${sc.number}",
                                color = if (isSelected) Color(0xFFA78BFA) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = sc.camera_movement,
                                color = Color(0xFFA1A1AA),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Découpage détaillé (Section 6 du cahier des charges)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scenes.forEachIndexed { idx, sc ->
                        val isSelected = idx == selectedSceneIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF282838) else Color(0xFF1B1B25))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    selectedSceneIndex = idx
                                    showFullDecoupage = false
                                }
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Scène ${sc.number} : ${sc.title}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (sc.status == "done") Color(0xFF064E3B) else Color(0xFF3F3F46))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (sc.status == "done") "Terminé" else "En attente",
                                        color = if (sc.status == "done") Color(0xFF34D399) else Color(0xFFD1D5DB),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            Text(text = "Caméra : ${sc.camera_movement}", color = Color(0xFFA78BFA), fontSize = 10.sp)
                            Text(text = sc.description, color = Color(0xFF9CA3AF), fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun InfoBadge(label: String, value: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1F1F2C))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            Text(text = label, color = Color(0xFFA1A1AA), fontSize = 9.sp)
            Text(text = value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun EmptyMediaState(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(24.dp)
    ) {
        AgnesSvgIcon(icon = AgnesIcon.WARNING, tint = Color(0xFFF59E0B), size = 36.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = message, color = Color(0xFFBBBBD0), fontSize = 13.sp)
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
