package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import com.example.ui.components.MediaViewerModal
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun FilmScreen(
    currentFilm: FilmEntity?,
    isGenerating: Boolean,
    generationProgress: Int,
    currentStepText: String,
    elapsedSeconds: Int,
    currentScenes: List<SceneItem>,
    onStartNewFilm: (title: String, prompt: String, style: String, requestedDurationSeconds: Double, manualScenes: Int?, startImage: String) -> Unit,
    onCancelGeneration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }
    var selectedSceneForPreview by remember { mutableStateOf<SceneItem?>(null) }

    // Formulaire de configuration Film
    var filmTitle by remember { mutableStateOf("") }
    var filmPrompt by remember {
        mutableStateOf("")
    }
    var hasStartImage by remember { mutableStateOf(false) }
    var startImageUrl by remember {
        mutableStateOf("")
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
                    text = "Voulez-vous vraiment annuler la génération du film ? L'état actuel sera sauvegardé en base de données pour permettre une reprise ultérieure.",
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
        // Wireframe 3.3 : Écran Film (état progression)
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

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
                        text = "Film",
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
                    text = currentStepText,
                    color = Color(0xFFA1A1AA),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            AgnesShimmerProgressBar(
                progress = generationProgress / 100f,
                height = 10.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Jalons (Script / Keyframes / Scènes)
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
                        text = "Écriture du script (agnes-2.5-flash)",
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
                        text = "Génération des keyframes (agnes-image-2.1-flash)",
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
                        text = "Génération séquentielle des scènes (agnes-video-v2.0)",
                        color = if (scenesDone) Color.White else if (generationProgress >= 45) Color.White else Color(0xFF71717A),
                        fontSize = 13.sp,
                        fontWeight = if (scenesDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Scènes", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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

            // Bouton Annuler la génération
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF271C1C))
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable {
                        triggerHapticFeedback(context)
                        showCancelDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(icon = AgnesIcon.CLOSE, tint = Color(0xFFEF4444), size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Annuler la génération",
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    } else {
        // Mode Configuration Film Studio (Sections 11 & 15 du cahier des charges)
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Film Studio IA",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pipeline complet : Image clé de départ → Script → Keyframes → Vidéos synchronisées.",
                color = Color(0xFFA1A1AA),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Image de départ
            Text(text = "1. Image de départ", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            AgnesUploadZone(
                hasImageSelected = hasStartImage,
                onClick = { hasStartImage = !hasStartImage }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Slider durée : 10 s -> 600 s
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "2. Durée du film", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Mode scènes : auto ou manuel
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "3. Mode scènes : ", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isAutoScenesMode = true }
                ) {
                    RadioButton(
                        selected = isAutoScenesMode,
                        onClick = { isAutoScenesMode = true },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8B5CF6), unselectedColor = Color(0xFF555566))
                    )
                    Text(text = "Auto", color = if (isAutoScenesMode) Color.White else Color(0xFF888899), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isAutoScenesMode = false }
                ) {
                    RadioButton(
                        selected = !isAutoScenesMode,
                        onClick = { isAutoScenesMode = false },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8B5CF6), unselectedColor = Color(0xFF555566))
                    )
                    Text(text = "Manuel (2–50)", color = if (!isAutoScenesMode) Color.White else Color(0xFF888899), fontSize = 13.sp)
                }
            }

            if (!isAutoScenesMode) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 12).forEach { n ->
                        val isSelected = manualScenesCount == n
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1C1C25))
                                .clickable { manualScenesCount = n }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(text = "$n scènes", color = if (isSelected) Color.White else Color(0xFFBBBBCC), fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Carte Breakdown Automatique
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF13131A))
                    .border(1.dp, Color(0xFF282836), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(text = "Breakdown Automatique (Règle 8n + 1)", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Nombre de scènes :", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "${breakdown.numScenes} scènes", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Frames par scène :", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "${breakdown.framesPerScene} frames (≈ ${String.format("%.2f", breakdown.durationPerScene)}s)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Durée réelle totale :", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Text(text = "${String.format("%.1f", breakdown.actualTotalDuration)} secondes", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Style visuel (au moins 5 options)
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

            // 5. Prompt principal
            Text(text = "5. Prompt principal du film", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmPrompt,
                onValueChange = { filmPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp),
                placeholder = { Text("Décrivez l'intrigue, la continuité et les éléments clés...", color = Color(0xFF555566)) },
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

            Spacer(modifier = Modifier.height(22.dp))

            // Bouton Lancer la production
            AgnesPrimaryButton(
                text = "Lancer la production du Film",
                onClick = {
                    val startImg = if (hasStartImage) startImageUrl else ""
                    onStartNewFilm(
                        filmTitle,
                        filmPrompt,
                        selectedStyle,
                        requestedDurationSeconds,
                        if (isAutoScenesMode) null else manualScenesCount,
                        startImg
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = filmPrompt.isNotBlank(),
                icon = AgnesIcon.FILM
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
