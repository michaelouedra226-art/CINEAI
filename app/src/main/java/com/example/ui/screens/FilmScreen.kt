package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.SceneItem
import com.example.data.repository.AgnesRepository
import com.example.ui.components.AgnesImagePickerModal
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.AgnesShimmerProgressBar
import com.example.ui.components.AgnesUploadZone
import com.example.ui.components.FilmProjectCard
import com.example.ui.components.MediaViewerModal
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon
import com.example.util.DownloadHelper
import com.example.util.ImagePickerHelper
import kotlinx.coroutines.launch

enum class FilmWorkflowStep {
    CONCEPT,    // Étape A : Paramétrage du projet (titre, prompt, durée, nombre de scènes)
    DECOUPAGE   // Étape B : Découpage éditable avant génération coûteuse
}

@Composable
fun FilmScreen(
    currentFilm: FilmEntity?,
    recentFilms: List<FilmEntity> = emptyList(),
    availableCreations: List<CreationEntity> = emptyList(),
    isGenerating: Boolean,
    generationProgress: Int,
    currentStepText: String,
    elapsedSeconds: Int,
    currentScenes: List<SceneItem>,
    onStartNewFilm: (
        title: String,
        prompt: String,
        style: String,
        requestedDurationSeconds: Double,
        manualScenes: Int?,
        startImage: String,
        initialScenes: List<SceneItem>?,
        dialogueLanguage: String,
        audioPresence: String
    ) -> Unit,
    onCancelGeneration: () -> Unit,
    onPrepareDrafts: ((prompt: String, style: String, numScenes: Int, dialogueLanguage: String, audioPresence: String, (String, List<SceneItem>) -> Unit) -> Unit)? = null,
    onSelectFilm: ((FilmEntity) -> Unit)? = null,
    onResumeFilm: ((String) -> Unit)? = null,
    onReshootScene: ((filmId: String, sceneNumber: Int, action: String?, dialogue: String?, camera: String?) -> Unit)? = null,
    onExportStitchedFilm: ((FilmEntity) -> Unit)? = null,
    onGenerateCastingPortrait: ((characterBible: String, filmStyle: String, (String?) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showCancelDialog by remember { mutableStateOf(false) }
    var selectedSceneForPreview by remember { mutableStateOf<SceneItem?>(null) }
    var showImagePicker by remember { mutableStateOf(false) }

    // Workflow en 3 étapes : Étape A (Concept) -> Étape B (Découpage) -> Étape C (Production)
    var currentWorkflowStep by remember { mutableStateOf(FilmWorkflowStep.CONCEPT) }

    // Formulaire de configuration Film
    var filmTitle by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf("fr") }
    var selectedAudioPresence by remember { mutableStateOf("dialogue") }
    var selectedSoundtrack by remember { mutableStateOf("orchestral") }
    var showDraftCastingModal by remember { mutableStateOf(false) }
    var showDraftScriptModal by remember { mutableStateOf(false) }
    var filmPrompt by remember { mutableStateOf("") }
    var hasStartImage by remember { mutableStateOf(false) }
    var startImageUrl by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = ImagePickerHelper.saveSelectedImage(context, uri)
                if (savedPath != null) {
                    startImageUrl = savedPath
                    hasStartImage = true
                }
            }
        }
    }

    var selectedStyle by remember { mutableStateOf("cinematic") }
    val styles = listOf(
        "cinematic" to "Cinématique",
        "documentary" to "Documentaire",
        "anime" to "Anime",
        "noir" to "Film Noir",
        "dreamlike" to "Onirique"
    )

    // Slider durée : 10s à 600s
    var requestedDurationSeconds by remember { mutableDoubleStateOf(30.0) }
    var isAutoScenesMode by remember { mutableStateOf(true) }
    var manualScenesCount by remember { mutableIntStateOf(5) }

    // Découpage éditable (Étape B)
    var draftScenes by remember { mutableStateOf<List<SceneItem>>(emptyList()) }
    var isPreparingDrafts by remember { mutableStateOf(false) }

    // Mode d'affichage des scènes en production (Grand Format Cinéma par défaut pour visibilité maximale)
    var isCinemaViewMode by remember { mutableStateOf(true) }

    // Breakdown automatique
    val breakdown = remember(requestedDurationSeconds, isAutoScenesMode, manualScenesCount) {
        AgnesRepository.calculateBreakdown(
            requestedDurationSeconds,
            if (isAutoScenesMode) null else manualScenesCount
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = {
                Text(text = "Interrompre la production ?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Voulez-vous vraiment annuler la génération du film ? L'état actuel sera sauvegardé en base de données avec le statut Partiel pour permettre une reprise ultérieure.",
                    color = Color(0xFFCCCCCC)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        triggerHapticFeedback(context)
                        showCancelDialog = false
                        onCancelGeneration()
                    }
                ) {
                    Text("Confirmer l'annulation", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Poursuivre", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (selectedSceneForPreview != null) {
        val sc = selectedSceneForPreview!!
        val isVideo = !sc.videoUrl.isNullOrBlank()
        if (currentFilm != null) {
            val sceneIdx = (sc.number - 1).coerceAtLeast(0)
            MediaViewerModal(
                creation = null,
                film = currentFilm,
                initialSceneIndex = sceneIdx,
                onDismiss = { selectedSceneForPreview = null },
                onResumeFilm = onResumeFilm,
                onReshootScene = onReshootScene,
                onExportStitchedFilm = onExportStitchedFilm,
                onGenerateCastingPortrait = onGenerateCastingPortrait
            )
        } else {
            val creationObj = CreationEntity(
                id = "scene_${sc.number}",
                type = if (isVideo) "video" else "image",
                prompt = sc.video_prompt.ifBlank { sc.image_prompt },
                model = if (isVideo) "agnes-video-v2.0" else "agnes-image-2.1-flash",
                resultUrl = if (isVideo) sc.videoUrl else sc.keyframe,
                thumbnail = sc.keyframe,
                status = sc.status
            )
            MediaViewerModal(
                creation = creationObj,
                film = null,
                onDismiss = { selectedSceneForPreview = null }
            )
        }
    }

    if (showImagePicker) {
        AgnesImagePickerModal(
            availableCreations = availableCreations,
            onImageSelected = { pathOrUrl ->
                startImageUrl = pathOrUrl
                hasStartImage = true
                showImagePicker = false
            },
            onDismiss = { showImagePicker = false }
        )
    }

    if (isGenerating) {
        // ═══════════════════════════════════════════════════════
        // ÉTAPE C — PRODUCTION (Timeline & Visualisation en direct)
        // ═══════════════════════════════════════════════════════
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        val totalScenes = currentScenes.size.coerceAtLeast(1)
        val completedScenes = currentScenes.count { it.status == "done" }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // ── 1. BANDEAU DE COMMANDE ULTRA-COMPACT (<65dp) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Statut Direct + Titre
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    AgnesSvgIcon(icon = AgnesIcon.LIVE_DOT, tint = Color(0xFFEF4444), size = 10.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DIRECT",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "•",
                        color = Color(0xFF52525B),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (filmTitle.isNotBlank()) filmTitle else "Film Studio",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Chrono & Arrêt direct
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF181822))
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.SPINNER, tint = Color(0xFFA78BFA), size = 12.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = timeString,
                            color = Color(0xFFA78BFA),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22EF4444))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                showCancelDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Arrêter",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Jauge de progression fine (3.dp)
            AgnesShimmerProgressBar(
                progress = generationProgress / 100f,
                height = 3.dp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Progression % + Action en cours + Stepper 3 étapes 1-ligne
            val scriptDone = generationProgress >= 20
            val keyframesDone = generationProgress >= 45
            val scenesDone = generationProgress >= 100

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = "$generationProgress%",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentStepText,
                        color = Color(0xFFA1A1AA),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Micro Stepper horizontal
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (scriptDone) Color(0xFF064E3B) else Color(0xFF1C1C26))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (scriptDone) "1.Script ✓" else "1.Script",
                            color = if (scriptDone) Color(0xFF34D399) else Color(0xFFA1A1AA),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (keyframesDone) Color(0xFF064E3B)
                                else if (generationProgress in 20..44) Color(0xFF4C1D95)
                                else Color(0xFF1C1C26)
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (keyframesDone) "2.Images ✓" else if (generationProgress in 20..44) "2.Images ⏳" else "2.Images",
                            color = if (keyframesDone) Color(0xFF34D399) else if (generationProgress in 20..44) Color(0xFFC084FC) else Color(0xFF71717A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (scenesDone) Color(0xFF064E3B)
                                else if (generationProgress >= 45) Color(0xFF4C1D95)
                                else Color(0xFF1C1C26)
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (scenesDone) "3.Vidéo ✓" else if (generationProgress >= 45) "3.Vidéo ⏳" else "3.Vidéo",
                            color = if (scenesDone) Color(0xFF34D399) else if (generationProgress >= 45) Color(0xFFC084FC) else Color(0xFF71717A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 2. BARRE DE CONTRÔLE DE LA LISTE DES SCÈNES ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Scènes en production",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A1B4D))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$completedScenes/$totalScenes",
                            color = Color(0xFFA78BFA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Switcher de vue Grand Format vs Compact
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF181822))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isCinemaViewMode) Color(0xFF7C3AED) else Color.Transparent)
                            .clickable { isCinemaViewMode = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Grand Format",
                            color = if (isCinemaViewMode) Color.White else Color(0xFFA1A1AA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (!isCinemaViewMode) Color(0xFF7C3AED) else Color.Transparent)
                            .clickable { isCinemaViewMode = false }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Compact",
                            color = if (!isCinemaViewMode) Color.White else Color(0xFFA1A1AA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 3. LISTE DES SCÈNES AVEC VISIBILITÉ OPTIMISÉE ──
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(currentScenes) { scene ->
                    val isDone = scene.status == "done"
                    val isProc = scene.status == "processing"
                    val isStalled = scene.status == "stalled"
                    val hasVideo = !scene.videoUrl.isNullOrBlank()
                    val hasKeyframe = !scene.keyframe.isNullOrBlank()

                    if (isCinemaViewMode) {
                        // ═════════════════════════════════════════════════
                        // VUE GRAND FORMAT CINÉMA IMMERSIF (VISUELS NETS & GRANDS)
                        // ═════════════════════════════════════════════════
                        AgnesInteractiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            isStalled = isStalled,
                            onClick = {
                                if (hasKeyframe || hasVideo) {
                                    triggerHapticFeedback(context)
                                    selectedSceneForPreview = scene
                                }
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                // Ligne 1 : Titre du plan + Statut
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Plan ${scene.number} : ${scene.title.ifBlank { "Scène ${scene.number}" }}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isDone) Color(0xFF064E3B)
                                                else if (isStalled) Color(0xFF451A03)
                                                else if (isProc) Color(0xFF4C1D95)
                                                else Color(0xFF27272A)
                                            )
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isDone) "Terminé" else if (isStalled) "Stall" else if (isProc) "En cours" else "En attente",
                                            color = if (isDone) Color(0xFF34D399) else if (isStalled) Color(0xFFF59E0B) else if (isProc) Color(0xFFC084FC) else Color(0xFFA1A1AA),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Ligne 2 : Badges Acte + Casting
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (scene.narrativePhase.isNotBlank()) {
                                        val act = scene.narrativePhase.uppercase()
                                        val (actColor, actBg) = when {
                                            act.contains("INTRO") -> Color(0xFF38BDF8) to Color(0xFF0C4A6E)
                                            act.contains("CLIMAX") -> Color(0xFFFB923C) to Color(0xFF431407)
                                            act.contains("CONCL") || act.contains("RÉSOL") -> Color(0xFF34D399) to Color(0xFF064E3B)
                                            else -> Color(0xFFA78BFA) to Color(0xFF2E1065)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(actBg)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = act, color = actColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    val charLabel = scene.charactersPresent.ifBlank { "Protagoniste" }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF20202C))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = charLabel,
                                            color = Color(0xFFD4D4D8),
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    if (hasVideo) {
                                        Text("▶ Toucher pour visionner", color = Color(0xFF34D399), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    } else if (hasKeyframe) {
                                        Text("🔍 Toucher pour agrandir", color = Color(0xFFA78BFA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // ── CADRE VISUEL GRAND FORMAT (Hauteur 195dp, Net et Immersif) ──
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(195.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF13131A))
                                        .border(
                                            1.dp,
                                            if (isDone) Color(0xFF10B981) else if (isProc) Color(0xFF7C3AED) else Color(0xFF282836),
                                            RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (hasKeyframe) {
                                        AsyncImage(
                                            model = scene.keyframe,
                                            contentDescription = scene.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )

                                        // Overlay Vidéo Prête avec grand bouton PLAY
                                        if (hasVideo) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0x44000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(50.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xEE7C3AED))
                                                        .border(2.dp, Color.White, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 24.dp)
                                                }
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xCC000000))
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "✓ VIDÉO PRÊTE",
                                                    color = Color(0xFF34D399),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else if (isProc) {
                                            // Synthèse vidéo en cours : le keyframe reste visible en dessous !
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0x55000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(Color(0xDD181824))
                                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    AgnesSvgIcon(icon = AgnesIcon.SPINNER, tint = Color(0xFFA78BFA), size = 14.dp)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = scene.progressText.ifBlank { "Animation vidéo en cours..." },
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        } else {
                                            // Keyframe généré
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xCC000000))
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "KEYFRAME PRÊT",
                                                    color = Color(0xFFA78BFA),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // Badge Loupe d'agrandissement en haut à droite
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xAA000000)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AgnesSvgIcon(icon = AgnesIcon.SEARCH, tint = Color.White, size = 15.dp)
                                        }
                                    } else {
                                        // En attente
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFF52525B), size = 32.dp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = if (isProc) scene.progressText.ifBlank { "Génération du plan en cours..." } else "En attente de rendu",
                                                color = if (isProc) Color(0xFFA78BFA) else Color(0xFF71717A),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Détails du plan sous le visuel
                                Text(
                                    text = "Caméra : ${scene.camera_movement}",
                                    color = Color(0xFFA1A1AA),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (scene.dialogue.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    val cleanDisplayDiag = scene.dialogue.trim().removePrefix("«").removeSuffix("»").trim()
                                    Text(
                                        text = "« $cleanDisplayDiag »",
                                        color = Color(0xFF93C5FD),
                                        fontSize = 11.sp,
                                        fontStyle = FontStyle.Italic,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (scene.soundDesign.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "🎧 ${scene.soundDesign}",
                                            color = Color(0xFFC4B5FD),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // ═════════════════════════════════════════════════
                        // VUE COMPACTE (MINIATURE ÉLARGIE 96x128dp)
                        // ═════════════════════════════════════════════════
                        AgnesInteractiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            isStalled = isStalled,
                            onClick = {
                                if (hasKeyframe || hasVideo) {
                                    triggerHapticFeedback(context)
                                    selectedSceneForPreview = scene
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(128.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF181822))
                                        .border(
                                            1.dp,
                                            if (isDone) Color(0xFF10B981) else if (isProc) Color(0xFF7C3AED) else Color(0xFF282836),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (hasKeyframe) {
                                        AsyncImage(
                                            model = scene.keyframe,
                                            contentDescription = scene.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )

                                        if (hasVideo) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0x33000000)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xEE7C3AED)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 16.dp)
                                                }
                                            }
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFF52525B), size = 24.dp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("En attente", color = Color(0xFF71717A), fontSize = 9.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Plan ${scene.number} : ${scene.title.ifBlank { "Scène ${scene.number}" }}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (isDone) Color(0xFF064E3B)
                                                    else if (isStalled) Color(0xFF451A03)
                                                    else if (isProc) Color(0xFF4C1D95)
                                                    else Color(0xFF27272A)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isDone) "Terminé" else if (isStalled) "Stall" else if (isProc) "En cours" else "En attente",
                                                color = if (isDone) Color(0xFF34D399) else if (isStalled) Color(0xFFF59E0B) else if (isProc) Color(0xFFC084FC) else Color(0xFFA1A1AA),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (scene.narrativePhase.isNotBlank()) {
                                            val act = scene.narrativePhase.uppercase()
                                            val (actColor, actBg) = when {
                                                act.contains("INTRO") -> Color(0xFF38BDF8) to Color(0xFF0C4A6E)
                                                act.contains("CLIMAX") -> Color(0xFFFB923C) to Color(0xFF431407)
                                                act.contains("CONCL") || act.contains("RÉSOL") -> Color(0xFF34D399) to Color(0xFF064E3B)
                                                else -> Color(0xFFA78BFA) to Color(0xFF2E1065)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(actBg)
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(text = act, color = actColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        val charLabel = scene.charactersPresent.ifBlank { "Protagoniste" }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF20202C))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = charLabel,
                                                color = Color(0xFFD4D4D8),
                                                fontSize = 8.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "Caméra : ${scene.camera_movement}",
                                        color = Color(0xFFA1A1AA),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (scene.dialogue.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        val cleanDisplayDiag = scene.dialogue.trim().removePrefix("«").removeSuffix("»").trim()
                                        Text(
                                            text = "« $cleanDisplayDiag »",
                                            color = Color(0xFF93C5FD),
                                            fontSize = 9.sp,
                                            fontStyle = FontStyle.Italic,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (hasVideo) {
                                            Text(
                                                text = "▶ Toucher pour visionner",
                                                color = Color(0xFF34D399),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else if (hasKeyframe) {
                                            Text(
                                                text = if (isProc) scene.progressText.ifBlank { "Animation vidéo..." } else "🔍 Toucher pour agrandir",
                                                color = Color(0xFFA78BFA),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (onReshootScene != null && currentFilm != null && (hasVideo || hasKeyframe || scene.status == "failed")) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFF2E1065))
                                                    .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        triggerHapticFeedback(context)
                                                        onReshootScene(
                                                            currentFilm.id,
                                                            scene.number,
                                                            scene.description,
                                                            scene.dialogue,
                                                            scene.camera_movement
                                                        )
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    AgnesSvgIcon(icon = AgnesIcon.GENERATE, tint = Color(0xFFC4B5FD), size = 10.dp)
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("Re-tourner", color = Color.White, fontSize = 9.sp)
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

            Spacer(modifier = Modifier.height(8.dp))
        }
    } else if (currentWorkflowStep == FilmWorkflowStep.DECOUPAGE) {
        // ═══════════════════════════════════════════════════════
        // ÉTAPE B — DÉCOUPAGE ÉDITABLE (Section 5 du CDC)
        // ═══════════════════════════════════════════════════════
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Étape B : Découpage Studio", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "${draftScenes.size} scènes • Structure 4 Actes & Casting varié", color = Color(0xFFA78BFA), fontSize = 11.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E28))
                        .clickable { currentWorkflowStep = FilmWorkflowStep.CONCEPT }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("← Concept", color = Color(0xFFBBBBD0), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bannière de rappel Structure & Cohérence
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF161522),
                border = BorderStroke(1.dp, Color(0xFF2C2540))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFFA78BFA), size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Rendu Studio : Les plans alternent entre décors immersifs, protagoniste, alliés et antagonistes avec une cohérence visuelle stricte.",
                        color = Color(0xFFC4B5FD),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(draftScenes) { index, sc ->
                    val act = sc.narrativePhase.uppercase()
                    val (actColor, actBg) = when {
                        act.contains("INTRO") -> Color(0xFF38BDF8) to Color(0xFF0C4A6E)
                        act.contains("CLIMAX") -> Color(0xFFFB923C) to Color(0xFF431407)
                        act.contains("CONCL") || act.contains("RÉSOL") -> Color(0xFF34D399) to Color(0xFF064E3B)
                        else -> Color(0xFFA78BFA) to Color(0xFF2E1065)
                    }

                    val charPresent = sc.charactersPresent.ifBlank { "Protagoniste" }
                    val (charColor, charBg) = when {
                        charPresent.contains("Décor", ignoreCase = true) -> Color(0xFF94A3B8) to Color(0xFF1E293B)
                        charPresent.contains("Antagoniste", ignoreCase = true) || charPresent.contains("Menace", ignoreCase = true) -> Color(0xFFF87171) to Color(0xFF450A0A)
                        charPresent.contains("Allié", ignoreCase = true) || charPresent.contains("Secondaire", ignoreCase = true) -> Color(0xFFFBBF24) to Color(0xFF451A03)
                        charPresent.contains("Duo", ignoreCase = true) || charPresent.contains("Face", ignoreCase = true) -> Color(0xFFE879F9) to Color(0xFF4A044E)
                        else -> Color(0xFF818CF8) to Color(0xFF1E1B4B)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF13131A))
                            .border(1.dp, Color(0xFF282836), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Scène ${sc.number} : ${sc.title}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(actBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(act, color = actColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF24153A))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(sc.camera_movement, color = Color(0xFFA78BFA), fontSize = 9.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Badge Personnages présents
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(charBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Personnage(s) : $charPresent",
                                    color = charColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Paroles / Dialogue (Français) :", color = Color(0xFFA78BFA), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = sc.dialogue,
                            onValueChange = { newDiag ->
                                val updated = draftScenes.toMutableList()
                                updated[index] = sc.copy(dialogue = newDiag)
                                draftScenes = updated
                            },
                            placeholder = { Text("Ex: « Ne regarde pas en arrière, nous devons sortir d'ici ! »", color = Color(0xFF555566), fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF191924),
                                unfocusedContainerColor = Color(0xFF191924),
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color(0xFF2C2C3C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "🎧 Sound Design Studio (Acoustique & Foley) :", color = Color(0xFFC4B5FD), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = sc.soundDesign,
                            onValueChange = { newSound ->
                                val updated = draftScenes.toMutableList()
                                updated[index] = sc.copy(soundDesign = newSound)
                                draftScenes = updated
                            },
                            placeholder = { Text("Ex: Grondement de basse sourd, souffle du vent nocturne, nappes de violoncelles dramatiques", color = Color(0xFF555566), fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF191924),
                                unfocusedContainerColor = Color(0xFF191924),
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color(0xFF2C2C3C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        // Ancre de cohérence personnage (Bible visuelle)
                        if (sc.characterAnchor.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Cohérence Personnage / Bible visuelle :", color = Color(0xFFA1A1AA), fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "• Identité immuable", color = Color(0xFF10B981), fontSize = 9.sp)
                            }
                            OutlinedTextField(
                                value = sc.characterAnchor,
                                onValueChange = { newAnchor ->
                                    val updated = draftScenes.toMutableList()
                                    updated[index] = sc.copy(characterAnchor = newAnchor)
                                    draftScenes = updated
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF191924),
                                    unfocusedContainerColor = Color(0xFF191924),
                                    focusedBorderColor = Color(0xFF7C3AED),
                                    unfocusedBorderColor = Color(0xFF2C2C3C),
                                    focusedTextColor = Color(0xFFD4D4D8),
                                    unfocusedTextColor = Color(0xFFD4D4D8)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Prompt Image (Keyframe) :", color = Color(0xFFA1A1AA), fontSize = 10.sp)
                        OutlinedTextField(
                            value = sc.image_prompt,
                            onValueChange = { newPrompt ->
                                val updated = draftScenes.toMutableList()
                                updated[index] = sc.copy(image_prompt = newPrompt)
                                draftScenes = updated
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF191924),
                                unfocusedContainerColor = Color(0xFF191924),
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color(0xFF2C2C3C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Prompt Vidéo (Mouvement) :", color = Color(0xFFA1A1AA), fontSize = 10.sp)
                        OutlinedTextField(
                            value = sc.video_prompt,
                            onValueChange = { newPrompt ->
                                val updated = draftScenes.toMutableList()
                                updated[index] = sc.copy(video_prompt = newPrompt)
                                draftScenes = updated
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF191924),
                                unfocusedContainerColor = Color(0xFF191924),
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color(0xFF2C2C3C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Boutons Outils Studio : Casting & Scénario Hollywood (Axe 3 & 5)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1B4B))
                        .border(1.dp, Color(0xFF6366F1), RoundedCornerShape(8.dp))
                        .clickable {
                            triggerHapticFeedback(context)
                            showDraftCastingModal = true
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgnesSvgIcon(icon = AgnesIcon.HOME_IMAGES, tint = Color(0xFFA5B4FC), size = 13.dp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Casting & Lookbook", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
                        .clickable {
                            triggerHapticFeedback(context)
                            showDraftScriptModal = true
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgnesSvgIcon(icon = AgnesIcon.LOGS, tint = Color(0xFF94A3B8), size = 13.dp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Scénario Studio", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            AgnesPrimaryButton(
                text = "Lancer la production (${draftScenes.size} plans)",
                onClick = {
                    val startImg = if (hasStartImage) startImageUrl else ""
                    onStartNewFilm(
                        filmTitle,
                        filmPrompt,
                        selectedStyle,
                        requestedDurationSeconds,
                        draftScenes.size,
                        startImg,
                        draftScenes,
                        selectedLanguage,
                        selectedAudioPresence
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                icon = AgnesIcon.GENERATE
            )
        }

        if (showDraftScriptModal && draftScenes.isNotEmpty()) {
            val tempFilm: FilmEntity = remember(draftScenes, filmTitle, filmPrompt, selectedStyle) {
                FilmEntity(
                    id = "temp_draft",
                    title = if (filmTitle.isNotBlank()) filmTitle else "Film Studio",
                    logline = filmPrompt,
                    prompt = filmPrompt,
                    filmStyle = selectedStyle,
                    duration = requestedDurationSeconds,
                    scenesJson = SceneItem.serializeList(draftScenes),
                    status = "draft"
                )
            }
            val scriptText: String = remember(tempFilm) { DownloadHelper.generateHollywoodScreenplay(tempFilm) }

            AlertDialog(
                onDismissRequest = { showDraftScriptModal = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Scénario Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Format Hollywoodien avant rendu", color = Color(0xFFA78BFA), fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282836))
                                .clickable { showDraftScriptModal = false },
                            contentAlignment = Alignment.Center
                        ) {
                            AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color.White, size = 14.dp)
                        }
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().height(380.dp)) {
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
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        DownloadHelper.shareText(context, scriptText, tempFilm.title)
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
                                        DownloadHelper.saveScriptToFile(context, tempFilm)
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

        if (showDraftCastingModal && draftScenes.isNotEmpty()) {
            val characterMap = remember(draftScenes) {
                val map = mutableMapOf<String, String>()
                draftScenes.forEach { sc ->
                    if (sc.characterAnchor.isNotBlank()) {
                        val label = sc.charactersPresent.ifBlank { "Protagoniste" }
                        if (!map.containsKey(label)) {
                            map[label] = sc.characterAnchor
                        }
                    }
                }
                if (map.isEmpty()) {
                    map["Protagoniste"] = filmPrompt
                }
                map
            }
            var portraits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var loadingRole by remember { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = { showDraftCastingModal = false },
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
                                .clickable { showDraftCastingModal = false },
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
                            .height(380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        characterMap.forEach { (role, anchor) ->
                            val portraitUrl = portraits[role]
                            val isLoading = loadingRole == role
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
                                    Text(text = role.uppercase(), color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    if (onGenerateCastingPortrait != null) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isLoading) Color(0xFF3F3F46) else Color(0xFF7C3AED))
                                            .clickable(enabled = !isLoading) {
                                                triggerHapticFeedback(context)
                                                loadingRole = role
                                                onGenerateCastingPortrait(anchor, selectedStyle) { url ->
                                                    loadingRole = null
                                                    if (!url.isNullOrBlank()) {
                                                        portraits = portraits + (role to url)
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (isLoading) "Génération..." else if (portraitUrl != null) "Re-générer" else "Portrait Lookbook",
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
                                Text(text = anchor, color = Color(0xFFCBD5E1), fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = {},
                containerColor = Color(0xFF1E1E28),
                shape = RoundedCornerShape(16.dp)
            )
        }
    } else {
        // ═══════════════════════════════════════════════════════
        // ÉTAPE A — CONCEPT (Formulaire de configuration Film)
        // ═══════════════════════════════════════════════════════
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Studio Film",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Créez des récits cinématographiques multi-plans avec continuité IA",
                color = Color(0xFFA1A1AA),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF131320),
                border = BorderStroke(1.dp, Color(0xFF282845))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFFA78BFA), size = 16.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Production Studio & Casting Équilibré",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Structure 4 Actes : Introduction (plans larges & quotidien), Développement (péripéties), Climax (confrontation), Conclusion (dénouement limpide).\n• Variété du casting : Ne focalise pas tout sur le héros ! Scènes d'ambiance, alliés et antagonistes avec bibles visuelles immuables pour une cohérence folle.",
                        color = Color(0xFFBBBBD0),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Bannière de reprise si un projet a été interrompu
            val pendingFilm = recentFilms.firstOrNull { it.status in listOf("partial", "failed", "processing") }
            if (pendingFilm != null && onResumeFilm != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E172E),
                    border = BorderStroke(1.dp, Color(0xFF7C3AED))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Projet interrompu : ${pendingFilm.title}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${pendingFilm.getCompletedScenesCount()}/${pendingFilm.numScenes} plans enregistrés. Reprise disponible.",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF7C3AED))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    onResumeFilm(pendingFilm.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Continuer",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════
            // PROJETS DE FILM RÉCENTS (Vignettes cinéma & statut)
            // ═══════════════════════════════════════════════════════
            if (recentFilms.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Projets de film",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF24153A))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${recentFilms.size}",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Vignettes & découpage",
                        color = Color(0xFF71717A),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(recentFilms) { film ->
                        FilmProjectCard(
                            film = film,
                            width = 135.dp,
                            height = 200.dp,
                            onResume = if (onResumeFilm != null) { { onResumeFilm(film.id) } } else null,
                            onClick = {
                                if (onSelectFilm != null) {
                                    onSelectFilm(film)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Titre du film
            Text(text = "Titre du projet (optionnel)", color = Color(0xFFA1A1AA), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmTitle,
                onValueChange = { filmTitle = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ex: Les Chroniques de Nébula...", color = Color(0xFF555566)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF13131A),
                    unfocusedContainerColor = Color(0xFF13131A),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Image de départ facultative avec aperçu de la vignette
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "1. Image de départ (facultatif)",
                    color = Color(0xFFA1A1AA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (hasStartImage) {
                    Text(
                        text = "Effacer",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            hasStartImage = false
                            startImageUrl = ""
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            AgnesUploadZone(
                hasImageSelected = hasStartImage,
                previewUrl = if (hasStartImage) startImageUrl else null,
                onClick = {
                    triggerHapticFeedback(context)
                    showImagePicker = true
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Durée du film (10s - 600s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "2. Durée cible", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val durationText = if (requestedDurationSeconds >= 60) {
                    val m = (requestedDurationSeconds / 60).toInt()
                    val s = (requestedDurationSeconds % 60).toInt()
                    if (s > 0) "${m}m ${s}s" else "${m} minutes"
                } else {
                    "${requestedDurationSeconds.toInt()} secondes"
                }
                Text(
                    text = durationText,
                    color = Color(0xFFA78BFA),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = requestedDurationSeconds.toFloat(),
                onValueChange = { requestedDurationSeconds = it.toDouble() },
                valueRange = 10f..3600f,
                steps = 71,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8B5CF6),
                    activeTrackColor = Color(0xFF7C3AED),
                    inactiveTrackColor = Color(0xFF282836)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Nombre de scènes : Sélection libre sans limite arbitraire
            Text(text = "3. Nombre de scènes", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Automatique
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isAutoScenesMode) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .clickable { isAutoScenesMode = true }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Automatique (${breakdown.numScenes} scènes)",
                        color = if (isAutoScenesMode) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Mode Personnalisé
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isAutoScenesMode) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .clickable { isAutoScenesMode = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Personnalisé ($manualScenesCount scènes)",
                        color = if (!isAutoScenesMode) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Commandes du mode personnalisé (Saisie directe libre de 1 à 250+ scènes)
            if (!isAutoScenesMode) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Nombre exact de plans :", color = Color(0xFFBBBBD0), fontSize = 12.sp)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282836))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    if (manualScenesCount > 1) manualScenesCount--
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("−", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedTextField(
                            value = manualScenesCount.toString(),
                            onValueChange = { str ->
                                val filtered = str.filter { it.isDigit() }
                                val parsed = filtered.toIntOrNull()
                                if (parsed != null && parsed in 1..250) {
                                    manualScenesCount = parsed
                                } else if (filtered.isBlank()) {
                                    manualScenesCount = 1
                                }
                            },
                            modifier = Modifier.width(68.dp),
                            textStyle = TextStyle(
                                color = Color(0xFFA78BFA),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF13131A),
                                unfocusedContainerColor = Color(0xFF13131A),
                                focusedBorderColor = Color(0xFF8B5CF6),
                                unfocusedBorderColor = Color(0xFF282836)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282836))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    if (manualScenesCount < 250) manualScenesCount++
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Suggestions rapides étendues (sans plafond à 20)
                Text(text = "Suggestions rapides :", color = Color(0xFF71717A), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(3, 5, 8, 12, 20, 30, 50, 100)) { n ->
                        val isSelected = manualScenesCount == n
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1C1C25))
                                .border(1.dp, if (isSelected) Color(0xFFA78BFA) else Color(0xFF2E2E3E), RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                manualScenesCount = n
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = "$n plans", color = if (isSelected) Color.White else Color(0xFFBBBBCC), fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "L'IA découpe le scénario en lots continus sans bloquer et enregistre chaque plan en base locale au fur et à mesure.",
                    color = Color(0xFF71717A),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Résumé avant lancement (Section 4.2 & 4.3 du CDC)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13131A))
                    .border(1.dp, Color(0xFF282836), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Résumé du découpage",
                    color = Color(0xFFA78BFA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${breakdown.numScenes} scènes · environ ${String.format("%.1f", breakdown.durationPerScene)}s par scène · durée finale estimée : ${String.format("%.1f", breakdown.actualTotalDuration)}s",
                    color = Color(0xFFE5E7EB),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Style visuel
            Text(text = "4. Style visuel", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(styles) { (key, label) ->
                    val isSelected = selectedStyle == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1C1C25))
                            .border(1.dp, if (isSelected) Color(0xFFA78BFA) else Color(0xFF2E2E3E), RoundedCornerShape(8.dp))
                            .clickable { selectedStyle = key }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = label, color = if (isSelected) Color.White else Color(0xFFBBBBD0), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Langue & Paroles des personnages (Garantie de cohérence linguistique et vocale)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "5. Langue & Paroles", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (selectedLanguage == "fr") "Français garanti 🇫🇷" else "English 🇬🇧",
                    color = Color(0xFFA78BFA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fr" to "Français 🇫🇷", "en" to "English 🇬🇧").forEach { (code, label) ->
                    val isSel = selectedLanguage == code
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFF7C3AED) else Color(0xFF1C1C25))
                            .border(1.dp, if (isSel) Color(0xFFA78BFA) else Color(0xFF2E2E3E), RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                selectedLanguage = code
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = label, color = if (isSel) Color.White else Color(0xFFBBBBD0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "dialogue" to "Dialogues parlés",
                    "voice_over" to "Voix off narrative",
                    "ambient" to "Sans paroles"
                ).forEach { (mode, label) ->
                    val isSel = selectedAudioPresence == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFF3B1E6D) else Color(0xFF161622))
                            .border(1.dp, if (isSel) Color(0xFFA78BFA) else Color(0xFF282836), RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                selectedAudioPresence = mode
                            }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) Color.White else Color(0xFFA1A1AA),
                            fontSize = 10.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Atmosphère sonore & Mixage studio (Axe 4)", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "orchestral" to "Symphonique",
                    "tension" to "Tension & Silence",
                    "electronic" to "Électronique",
                    "ambient_real" to "Réalisme brut"
                ).forEach { (ost, ostLabel) ->
                    val isOstSel = selectedSoundtrack == ost
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOstSel) Color(0xFF1E3A8A) else Color(0xFF161622))
                            .border(1.dp, if (isOstSel) Color(0xFF60A5FA) else Color(0xFF282836), RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHapticFeedback(context)
                                selectedSoundtrack = ost
                            }
                            .padding(vertical = 7.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ostLabel,
                            color = if (isOstSel) Color.White else Color(0xFFA1A1AA),
                            fontSize = 10.sp,
                            fontWeight = if (isOstSel) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161B26))
                    .border(1.dp, Color(0xFF25334D), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✨ Cohérence garantie : bible de personnage et style visuel réinjectés sur chaque plan. Les répliques sont générées en français avec synchronisation vocale.",
                    color = Color(0xFF93C5FD),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Prompt principal du film (Sans limite de mots)
            val promptWords = if (filmPrompt.isBlank()) 0 else filmPrompt.trim().split("\\s+".toRegex()).size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "6. Prompt principal du film (intrigue & univers)", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E1065))
                        .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(text = "∞ Illimité", color = Color(0xFFC4B5FD), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Collez librement un synopsis complet, un conte avec introduction de village, une bible ou un scénario détaillé. Aucune restriction de mots.",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmPrompt,
                onValueChange = { filmPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                placeholder = { Text("Décrivez l'intrigue, le contexte d'ouverture (ex: dans un village...), les personnages, les rencontres et les scènes d'action...", color = Color(0xFF555566), fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF13131A),
                    unfocusedContainerColor = Color(0xFF13131A),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            if (promptWords > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$promptWords mot(s) saisis • Prise en charge intégrale sans troncature",
                    color = Color(0xFFA78BFA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Actions de lancement
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Bouton Découpage intermédiaire (Étape B)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF7C3AED), RoundedCornerShape(12.dp))
                        .clickable(enabled = filmPrompt.isNotBlank() && !isPreparingDrafts) {
                            triggerHapticFeedback(context)
                            isPreparingDrafts = true
                            val num = if (isAutoScenesMode) null else manualScenesCount
                            val count = breakdown.numScenes
                            if (onPrepareDrafts != null) {
                                onPrepareDrafts(filmPrompt, selectedStyle, count, selectedLanguage, selectedAudioPresence) { finalTitle, generatedDrafts ->
                                    isPreparingDrafts = false
                                    if (filmTitle.isBlank()) filmTitle = finalTitle
                                    draftScenes = generatedDrafts
                                    currentWorkflowStep = FilmWorkflowStep.DECOUPAGE
                                }
                            } else {
                                // Fallback structuré lié fidèlement au prompt utilisateur
                                val styleSuffix = if (selectedStyle.isNotBlank()) ", $selectedStyle aesthetic" else ""
                                draftScenes = (1..count).map { idx ->
                                    val actName = when {
                                        idx <= (count * 0.25).toInt().coerceAtLeast(1) -> "Introduction"
                                        idx <= (count * 0.70).toInt().coerceAtLeast(2) -> "Développement"
                                        idx <= (count * 0.85).toInt().coerceAtLeast(3) -> "Climax"
                                        else -> "Résolution"
                                    }
                                    val actionForPlan = "Étape $idx ($actName) : $filmPrompt"
                                    SceneItem(
                                        number = idx,
                                        title = "Plan $idx : Séquence $actName",
                                        description = actionForPlan,
                                        image_prompt = "$filmPrompt, sequence progression step $idx ($actName)$styleSuffix, 9:16 vertical format",
                                        video_prompt = "Smooth cinematic camera motion, $filmPrompt, step $idx, continuous motion",
                                        camera_movement = if (idx % 2 == 0) "Contre-champ fluide" else "Travelling avant",
                                        status = "pending",
                                        dialogue = "",
                                        audioMode = selectedAudioPresence,
                                        soundDesign = "Acoustique naturelle diégétique et sound design cinématique"
                                    )
                                }
                                isPreparingDrafts = false
                                currentWorkflowStep = FilmWorkflowStep.DECOUPAGE
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPreparingDrafts) "Génération script..." else "1. Découpage →",
                        color = Color(0xFFA78BFA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Bouton Lancer directement (Étape C)
                AgnesPrimaryButton(
                    text = "Lancer directement",
                    onClick = {
                        val startImg = if (hasStartImage) startImageUrl else ""
                        onStartNewFilm(
                            filmTitle,
                            filmPrompt,
                            selectedStyle,
                            requestedDurationSeconds,
                            if (isAutoScenesMode) null else manualScenesCount,
                            startImg,
                            null,
                            selectedLanguage,
                            selectedAudioPresence
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = filmPrompt.isNotBlank() && !isPreparingDrafts,
                    icon = AgnesIcon.FILM
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
