package com.example.ui.screens

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.SceneItem
import com.example.data.repository.AgnesRepository
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.AgnesShimmerProgressBar
import com.example.ui.components.AgnesUploadZone
import com.example.ui.components.FilmProjectCard
import com.example.ui.components.MediaViewerModal
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

enum class FilmWorkflowStep {
    CONCEPT,    // Étape A : Paramétrage du projet (titre, prompt, durée, nombre de scènes)
    DECOUPAGE   // Étape B : Découpage éditable avant génération coûteuse
}

@Composable
fun FilmScreen(
    currentFilm: FilmEntity?,
    recentFilms: List<FilmEntity> = emptyList(),
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
        initialScenes: List<SceneItem>?
    ) -> Unit,
    onCancelGeneration: () -> Unit,
    onPrepareDrafts: ((prompt: String, style: String, numScenes: Int, (String, List<SceneItem>) -> Unit) -> Unit)? = null,
    onSelectFilm: ((FilmEntity) -> Unit)? = null,
    onResumeFilm: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }
    var selectedSceneForPreview by remember { mutableStateOf<SceneItem?>(null) }

    // Workflow en 3 étapes : Étape A (Concept) -> Étape B (Découpage) -> Étape C (Production)
    var currentWorkflowStep by remember { mutableStateOf(FilmWorkflowStep.CONCEPT) }

    // Formulaire de configuration Film
    var filmTitle by remember { mutableStateOf("") }
    var filmPrompt by remember { mutableStateOf("") }
    var hasStartImage by remember { mutableStateOf(false) }
    var startImageUrl by remember { mutableStateOf("") }

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
        val creationObj = CreationEntity(
            id = "scene_${sc.number}",
            type = if (isVideo) "video" else "image",
            prompt = sc.video_prompt.ifBlank { sc.image_prompt },
            model = if (isVideo) "agnes-video-v2.0" else "agnes-image-2.1-flash",
            resultUrl = sc.videoUrl,
            thumbnail = sc.keyframe,
            status = sc.status
        )
        MediaViewerModal(
            creation = creationObj,
            film = null,
            onDismiss = { selectedSceneForPreview = null }
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
                .padding(16.dp)
        ) {
            // Header direct
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Production Film",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "EN DIRECT",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(icon = AgnesIcon.SPINNER, tint = Color(0xFFA78BFA), size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = timeString,
                        color = Color(0xFFA78BFA),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // % et barre shimmer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$generationProgress %",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Scène $completedScenes / $totalScenes",
                    color = Color(0xFFA78BFA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentStepText,
                color = Color(0xFFA1A1AA),
                fontSize = 12.sp,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))
            AgnesShimmerProgressBar(
                progress = generationProgress / 100f,
                height = 10.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Timeline de production (Section 5 : Script -> Keyframes -> Vidéos -> Finalisation)
            val scriptDone = generationProgress >= 20
            val keyframesDone = generationProgress >= 45
            val scenesDone = generationProgress >= 100

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13131A))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = if (scriptDone) AgnesIcon.CHECK else AgnesIcon.SPINNER,
                        tint = if (scriptDone) Color(0xFF10B981) else Color(0xFFA78BFA),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "1. Script & Découpage (agnes-2.5-flash)",
                        color = if (scriptDone) Color.White else Color(0xFFD4D4D8),
                        fontSize = 13.sp,
                        fontWeight = if (scriptDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = if (keyframesDone) AgnesIcon.CHECK else if (generationProgress in 20..44) AgnesIcon.LIVE_DOT else AgnesIcon.PLUS,
                        tint = if (keyframesDone) Color(0xFF10B981) else if (generationProgress in 20..44) Color(0xFFA78BFA) else Color(0xFF71717A),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "2. Keyframes visuels (agnes-image-2.1-flash)",
                        color = if (keyframesDone) Color.White else if (generationProgress in 20..44) Color.White else Color(0xFF71717A),
                        fontSize = 13.sp,
                        fontWeight = if (keyframesDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = if (scenesDone) AgnesIcon.CHECK else if (generationProgress >= 45) AgnesIcon.LIVE_DOT else AgnesIcon.PLUS,
                        tint = if (scenesDone) Color(0xFF10B981) else if (generationProgress >= 45) Color(0xFFA78BFA) else Color(0xFF71717A),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "3. Rendu vidéo séquentiel (agnes-video-v2.0)",
                        color = if (scenesDone) Color.White else if (generationProgress >= 45) Color.White else Color(0xFF71717A),
                        fontSize = 13.sp,
                        fontWeight = if (scenesDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Scènes en production ($completedScenes/$totalScenes achevées)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(currentScenes) { scene ->
                    val isDone = scene.status == "done"
                    val isProc = scene.status == "processing"
                    val isStalled = scene.status == "stalled"

                    AgnesInteractiveCard(
                        modifier = Modifier.fillMaxWidth(),
                        isStalled = isStalled,
                        onClick = {
                            if (!scene.keyframe.isNullOrBlank() || !scene.videoUrl.isNullOrBlank()) {
                                triggerHapticFeedback(context)
                                selectedSceneForPreview = scene
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!scene.keyframe.isNullOrBlank()) {
                                AsyncImage(
                                    model = scene.keyframe,
                                    contentDescription = scene.title,
                                    modifier = Modifier
                                        .size(62.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(62.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1C1C25)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(icon = AgnesIcon.FILM, tint = Color(0xFF71717A), size = 22.dp)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Scène ${scene.number} : ${scene.title}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = scene.camera_movement,
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 11.sp
                                )
                                if (scene.progressText.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = scene.progressText,
                                        color = if (isDone) Color(0xFF10B981) else if (isStalled) Color(0xFFF59E0B) else Color(0xFFA78BFA),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isDone) Color(0xFF064E3B)
                                        else if (isStalled) Color(0xFF451A03)
                                        else if (isProc) Color(0xFF4C1D95)
                                        else Color(0xFF27272A)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isDone) "Terminé" else if (isStalled) "Stall" else if (isProc) "En cours" else "En attente",
                                    color = if (isDone) Color(0xFF34D399) else if (isStalled) Color(0xFFF59E0B) else if (isProc) Color(0xFFC084FC) else Color(0xFFA1A1AA),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bouton Annuler la génération (Section 8)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
                    .clickable {
                        triggerHapticFeedback(context)
                        showCancelDialog = true
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Interrompre la production",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
                    Text(text = "Étape B : Découpage", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "${draftScenes.size} scènes générées • modifiez les prompts avant production", color = Color(0xFFA78BFA), fontSize = 11.sp)
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

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(draftScenes) { index, sc ->
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
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF24153A))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(sc.camera_movement, color = Color(0xFFA78BFA), fontSize = 9.sp)
                            }
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
                        draftScenes
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                icon = AgnesIcon.GENERATE
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
                    hasStartImage = !hasStartImage
                    if (hasStartImage && startImageUrl.isBlank()) {
                        startImageUrl = "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=720&q=80"
                    }
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
                Text(
                    text = "${requestedDurationSeconds.toInt()} secondes",
                    color = Color(0xFFA78BFA),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = requestedDurationSeconds.toFloat(),
                onValueChange = { requestedDurationSeconds = it.toDouble() },
                valueRange = 10f..600f,
                steps = 58,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8B5CF6),
                    activeTrackColor = Color(0xFF7C3AED),
                    inactiveTrackColor = Color(0xFF282836)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Nombre de scènes : Sélecteur clair avec modes Automatique et Personnalisé (Section 4 du CDC)
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

            // Commandes du mode personnalisé (Section 4.2 : boutons - et +, valeurs suggérées 3, 5, 8, 10, 12)
            if (!isAutoScenesMode) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Ajustement précis :", color = Color(0xFFBBBBD0), fontSize = 12.sp)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                        Text(
                            text = "$manualScenesCount",
                            color = Color(0xFFA78BFA),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF282836))
                                .clickable {
                                    triggerHapticFeedback(context)
                                    if (manualScenesCount < 20) manualScenesCount++
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Suggestions rapides : 3, 5, 8, 10, 12
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 10, 12).forEach { n ->
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

            // 5. Prompt principal du film
            Text(text = "5. Prompt principal du film (intrigue & univers)", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmPrompt,
                onValueChange = { filmPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp),
                placeholder = { Text("Décrivez l'intrigue, les personnages et les ambiances...", color = Color(0xFF555566)) },
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
                                onPrepareDrafts(filmPrompt, selectedStyle, count) { finalTitle, generatedDrafts ->
                                    isPreparingDrafts = false
                                    if (filmTitle.isBlank()) filmTitle = finalTitle
                                    draftScenes = generatedDrafts
                                    currentWorkflowStep = FilmWorkflowStep.DECOUPAGE
                                }
                            } else {
                                // Fallback structuré local
                                draftScenes = (1..count).map { idx ->
                                    SceneItem(
                                        number = idx,
                                        title = "Plan $idx",
                                        description = "Plan $idx du projet $filmTitle",
                                        image_prompt = "$filmPrompt, plan $idx, style $selectedStyle",
                                        video_prompt = "$filmPrompt, caméra travelling, plan $idx",
                                        camera_movement = "Travelling avant",
                                        status = "pending"
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
                            null
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
