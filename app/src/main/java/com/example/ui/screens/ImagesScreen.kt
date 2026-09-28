package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.CreationEntity
import com.example.ui.components.AgnesInteractiveCard
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.AgnesUploadZone
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun ImagesScreen(
    recentCreations: List<CreationEntity>,
    isGenerating: Boolean,
    onGenerate: (prompt: String, style: String, size: String, ratio: String, variations: Int) -> Unit,
    onSelectRecent: (CreationEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by remember {
        mutableStateOf("Plan large cinématique, métropole futuriste sous une pluie néon violette et cyan, éclairage volumétrique, photoréaliste 8k")
    }
    var selectedStyle by remember { mutableStateOf("Cinématique") }
    var selectedSize by remember { mutableStateOf("2K") }
    var selectedRatio by remember { mutableStateOf("9:16") }
    var variationsCount by remember { mutableIntStateOf(1) }
    var hasDroppedImage by remember { mutableStateOf(false) }

    val styles = listOf("Cinématique", "Photographique", "Anime", "3D Render", "Art Numérique")
    val sizes = listOf("1K", "2K", "4K")
    val ratios = listOf("9:16", "16:9", "1:1", "4:3")
    val variationOptions = listOf(1, 2, 3, 4)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C11))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Génération d'image",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Zone drop / preview
        AgnesUploadZone(
            hasImageSelected = hasDroppedImage,
            onClick = {
                hasDroppedImage = !hasDroppedImage
            }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Prompt
        Text(
            text = "Prompt",
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
                .height(115.dp),
            placeholder = { Text("Décrivez l'image avec précision cinématique...", color = Color(0xFF555566)) },
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

        // Style selector
        Text(
            text = "Style : $selectedStyle",
            color = Color(0xFFA1A1AA),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(styles) { style ->
                val isSelected = style == selectedStyle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFA78BFA) else Color(0xFF2E2E3E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedStyle = style }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = style,
                        color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Taille & Ratio
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Taille
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Taille", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s ->
                        val isSelected = s == selectedSize
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                                .clickable { selectedSize = s }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = s,
                                color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Ratio
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Ratio", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ratios.forEach { r ->
                        val isSelected = r == selectedRatio
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                                .clickable { selectedRatio = r }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = r,
                                color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Variations
        Text(text = "Variations : $variationsCount", color = Color(0xFFA1A1AA), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            variationOptions.forEach { v ->
                val isSelected = v == variationsCount
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF7C3AED) else Color(0xFF1A1A24))
                        .clickable { variationsCount = v },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$v",
                        color = if (isSelected) Color.White else Color(0xFFBBBBD0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bouton Générer
        AgnesPrimaryButton(
            text = if (isGenerating) "Génération en cours..." else "Générer l'image",
            onClick = {
                onGenerate(prompt, selectedStyle, selectedSize, selectedRatio, variationsCount)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating && prompt.isNotBlank(),
            isLoading = isGenerating,
            icon = AgnesIcon.GENERATE
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Historique récent
        Text(
            text = "Historique récent",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (recentCreations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14141C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucune création récente pour le moment",
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recentCreations) { creation ->
                    AgnesInteractiveCard(
                        modifier = Modifier
                            .width(110.dp)
                            .height(130.dp),
                        onClick = { onSelectRecent(creation) }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!creation.thumbnail.isNullOrBlank()) {
                                AsyncImage(
                                    model = creation.thumbnail,
                                    contentDescription = creation.prompt,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF1E1E2A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AgnesSvgIcon(
                                        icon = AgnesIcon.HOME_IMAGES,
                                        tint = Color(0xFF71717A),
                                        size = 24.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
