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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.QueueItemEntity
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.AgnesShimmerProgressBar
import com.example.ui.components.AgnesUploadZone
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun VideosScreen(
    queueItems: List<QueueItemEntity>,
    isGenerating: Boolean,
    onGenerateVideo: (prompt: String, mode: String, startImg: String?, duration: Int, resolution: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf("image") } // "text" | "image"
    var prompt by remember {
        mutableStateOf("Travelling avant cinématique continu, reflets de pluie sur l'asphalte, néons vacillants, ralenti fluide")
    }
    var durationSeconds by remember { mutableIntStateOf(5) }
    var selectedResolution by remember { mutableStateOf("720p 16:9") }
    var hasPickedStartImage by remember { mutableStateOf(false) }

    val resolutions = listOf("720p 16:9", "1080p 16:9", "720p 9:16")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C11))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Génération de vidéo",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Mode : ( ) Texte  (•) Image
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Mode : ", color = Color(0xFFA1A1AA), fontSize = 14.sp)
            Spacer(modifier = Modifier.width(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { mode = "text" }
            ) {
                RadioButton(
                    selected = mode == "text",
                    onClick = { mode = "text" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFF8B5CF6),
                        unselectedColor = Color(0xFF555566)
                    )
                )
                Text(
                    text = "Texte",
                    color = if (mode == "text") Color.White else Color(0xFF888899),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { mode = "image" }
            ) {
                RadioButton(
                    selected = mode == "image",
                    onClick = { mode = "image" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFF8B5CF6),
                        unselectedColor = Color(0xFF555566)
                    )
                )
                Text(
                    text = "Image",
                    color = if (mode == "image") Color.White else Color(0xFF888899),
                    fontSize = 13.sp
                )
            }
        }

        // Image de départ (si mode img)
        AnimatedVisibility(visible = mode == "image") {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                AgnesUploadZone(
                    hasImageSelected = hasPickedStartImage,
                    onClick = { hasPickedStartImage = !hasPickedStartImage }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Prompt de mouvement
        Text(
            text = "Prompt de mouvement",
            color = Color(0xFFA1A1AA),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(105.dp),
            placeholder = { Text("Trajectoire de caméra, vitesse, dynamique de scène...", color = Color(0xFF555566)) },
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

        Spacer(modifier = Modifier.height(16.dp))

        // Durée : [5s ≈ 121 frames] [10s ≈ 242 frames]
        Text(text = "Durée", color = Color(0xFFA1A1AA), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(5 to "5s ≈ 121 frames", 10 to "10s ≈ 242 frames").forEach { (d, label) ->
                val isSelected = durationSeconds == d
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFA78BFA) else Color(0xFF2E2E3E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { durationSeconds = d }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Résolution
        Text(text = "Résolution", color = Color(0xFFA1A1AA), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            resolutions.forEach { res ->
                val isSelected = res == selectedResolution
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .clickable { selectedResolution = res }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = res,
                        color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bouton Générer la vidéo
        AgnesPrimaryButton(
            text = if (isGenerating) "Génération vidéo en cours..." else "Générer la vidéo",
            onClick = {
                val startImg = if (mode == "image" && hasPickedStartImage) {
                    "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800"
                } else null
                onGenerateVideo(prompt, mode, startImg, durationSeconds, selectedResolution)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating && prompt.isNotBlank(),
            isLoading = isGenerating,
            icon = AgnesIcon.VIDEO
        )

        Spacer(modifier = Modifier.height(26.dp))

        // ── File d’attente ──
        Text(
            text = "File d'attente",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(10.dp))

        val activeQueue = queueItems.filter { it.status in listOf("queued", "processing", "stalled") }

        if (activeQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14141C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucune tâche en file d'attente",
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                activeQueue.forEach { item ->
                    val isStalled = item.status == "stalled"

                    AgnesInteractiveCard(
                        modifier = Modifier.fillMaxWidth(),
                        isStalled = isStalled
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (item.status == "processing") {
                                        AgnesSvgIcon(
                                            icon = AgnesIcon.LIVE_DOT,
                                            tint = Color(0xFF10B981),
                                            size = 14.dp
                                        )
                                    } else if (isStalled) {
                                        AgnesSvgIcon(
                                            icon = AgnesIcon.WARNING,
                                            tint = Color(0xFFF59E0B),
                                            size = 16.dp
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF888899))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }

                                if (item.secondsRemaining > 0) {
                                    Text(
                                        text = "Dans ${item.secondsRemaining}s",
                                        color = Color(0xFFA78BFA),
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        text = if (isStalled) "Stall détecté" else "${item.progress}%",
                                        color = if (isStalled) Color(0xFFF59E0B) else Color(0xFFA78BFA),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (item.status == "processing" || isStalled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                AgnesShimmerProgressBar(
                                    progress = item.progress / 100f,
                                    barColor = if (isStalled) Color(0xFFF59E0B) else Color(0xFF8B5CF6)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
