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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.model.FilmEntity
import com.example.data.model.SceneItem
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.AgnesShimmerProgressBar
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
    onStartNewFilm: (title: String, prompt: String, style: String, numScenes: Int) -> Unit,
    onCancelGeneration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }

    var filmTitle by remember { mutableStateOf("") }
    var filmPrompt by remember {
        mutableStateOf("Un détective androïde traque un signal spectral dans les sous-sols submergés de Néo-Paris")
    }
    var selectedStyle by remember { mutableStateOf("Sci-Fi Cyberpunk") }
    var numScenes by remember { mutableIntStateOf(3) }

    val styles = listOf("Sci-Fi Cyberpunk", "Cinématique", "Film Noir", "Aventure Épique")

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = {
                Text(
                    text = "Annuler la réalisation ?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Voulez-vous vraiment interrompre la génération du film en cours ? Les scènes non finalisées seront annulées.",
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
                    Text("Continuer", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E28),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (isGenerating) {
        // Mode progression EN DIRECT conforme au Wireframe 4.4
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0C0C11))
                .padding(16.dp)
        ) {
            // Header direct
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.LIVE_DOT,
                        tint = Color(0xFFEF4444),
                        size = 14.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EN DIRECT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.SPINNER,
                        tint = Color(0xFFA78BFA),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = timeString,
                        color = Color(0xFFA78BFA),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Pourcentage et progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$generationProgress%",
                    color = Color.White,
                    fontSize = 18.sp,
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

            Spacer(modifier = Modifier.height(18.dp))

            // Jalons d'étapes :
            // ✓ Écriture du script
            // ● Génération des keyframes
            // ○ Génération des scènes
            val scriptDone = generationProgress >= 30
            val keyframesDone = generationProgress >= 50
            val scenesDone = generationProgress >= 100

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14141C))
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
                        text = "Écriture du script et découpage",
                        color = if (scriptDone) Color.White else Color(0xFFD4D4D8),
                        fontSize = 13.sp,
                        fontWeight = if (scriptDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = if (keyframesDone) AgnesIcon.CHECK else if (generationProgress in 30..49) AgnesIcon.LIVE_DOT else AgnesIcon.PLUS,
                        tint = if (keyframesDone) Color(0xFF10B981) else if (generationProgress in 30..49) Color(0xFFA78BFA) else Color(0xFF71717A),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Génération des keyframes",
                        color = if (keyframesDone) Color.White else if (generationProgress in 30..49) Color.White else Color(0xFF71717A),
                        fontSize = 13.sp,
                        fontWeight = if (keyframesDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgnesSvgIcon(
                        icon = if (scenesDone) AgnesIcon.CHECK else if (generationProgress >= 50) AgnesIcon.LIVE_DOT else AgnesIcon.PLUS,
                        tint = if (scenesDone) Color(0xFF10B981) else if (generationProgress >= 50) Color(0xFFA78BFA) else Color(0xFF71717A),
                        size = 16.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Génération des scènes vidéo",
                        color = if (scenesDone) Color.White else if (generationProgress >= 50) Color.White else Color(0xFF71717A),
                        fontSize = 13.sp,
                        fontWeight = if (scenesDone) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Scènes ──
            Text(
                text = "Scènes",
                color = Color.White,
                fontSize = 15.sp,
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

                    AgnesInteractiveCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!scene.keyframe.isNullOrBlank()) {
                                AsyncImage(
                                    model = scene.keyframe,
                                    contentDescription = scene.title,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E1E2A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(
                                        icon = AgnesIcon.FILM,
                                        tint = Color(0xFF71717A),
                                        size = 20.dp
                                    )
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
                                        color = if (isDone) Color(0xFF10B981) else Color(0xFFA78BFA),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDone) Color(0xFF064E3B) else if (isProc) Color(0xFF4C1D95) else Color(0xFF27272A))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isDone) "Terminé" else if (isProc) "En cours" else "En attente",
                                    color = if (isDone) Color(0xFF34D399) else if (isProc) Color(0xFFC084FC) else Color(0xFFA1A1AA),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bouton Annuler avec vibration
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
                    AgnesSvgIcon(
                        icon = AgnesIcon.CLOSE,
                        tint = Color(0xFFEF4444),
                        size = 18.dp
                    )
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
        // Formulaire de création d'un nouveau film studio
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0C0C11))
                .padding(16.dp)
        ) {
            Text(
                text = "Film Studio IA",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Réalisation automatique complète d'un court-métrage cinématique avec scénario, cadrages et plans vidéo synchronisés.",
                color = Color(0xFFA1A1AA),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(text = "Titre du projet (optionnel)", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmTitle,
                onValueChange = { filmTitle = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ex: Les Ombres de Néo-Paris", color = Color(0xFF555566)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF14141C),
                    unfocusedContainerColor = Color(0xFF14141C),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Synopsis / Idée de départ", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = filmPrompt,
                onValueChange = { filmPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                placeholder = { Text("Décrivez l'univers, les personnages et la dramaturgie...", color = Color(0xFF555566)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF14141C),
                    unfocusedContainerColor = Color(0xFF14141C),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Style visuel", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                styles.take(3).forEach { st ->
                    val isSelected = selectedStyle == st
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                            .clickable { selectedStyle = st }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = st,
                            color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Nombre de scènes", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 4, 5).forEach { n ->
                    val isSelected = numScenes == n
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                            .clickable { numScenes = n },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$n",
                            color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AgnesPrimaryButton(
                text = "Lancer la production du Film",
                onClick = {
                    onStartNewFilm(filmTitle, filmPrompt, selectedStyle, numScenes)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = filmPrompt.isNotBlank(),
                icon = AgnesIcon.FILM
            )
        }
    }
}
