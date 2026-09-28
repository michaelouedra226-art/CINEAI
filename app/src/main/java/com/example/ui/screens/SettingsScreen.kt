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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity
import com.example.ui.components.AgnesPrimaryButton
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun SettingsScreen(
    settings: SettingsEntity,
    todayUsage: UsageEntity?,
    onSaveSettings: (SettingsEntity) -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var rateLimitProfile by remember(settings.rateLimitProfile) { mutableStateOf(settings.rateLimitProfile) }
    var defaultImageModel by remember(settings.defaultImageModel) { mutableStateOf(settings.defaultImageModel) }
    var defaultVideoModel by remember(settings.defaultVideoModel) { mutableStateOf(settings.defaultVideoModel) }
    var defaultTextModel by remember(settings.defaultTextModel) { mutableStateOf(settings.defaultTextModel) }

    val imageModels = listOf("agnes-image-2.1-flash", "agnes-image-2.0-pro")
    val videoModels = listOf("agnes-video-v2.0", "agnes-video-v1.5-fast")
    val textModels = listOf("agnes-2.0-flash", "agnes-2.0-pro")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C11))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Réglages",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Clé API
        Text(text = "Clé API", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                placeholder = { Text("Entrez votre clé API Agnes...", color = Color(0xFF555566)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF14141C),
                    unfocusedContainerColor = Color(0xFF14141C),
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF7C3AED))
                    .clickable {
                        triggerHapticFeedback(context)
                        onSaveSettings(
                            settings.copy(
                                apiKey = apiKey,
                                rateLimitProfile = rateLimitProfile,
                                defaultImageModel = defaultImageModel,
                                defaultVideoModel = defaultVideoModel,
                                defaultTextModel = defaultTextModel
                            )
                        )
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Enregistrer", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Profil de rate-limit : Free / Token Plan / Enterprise
        Text(text = "Profil de rate-limit", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))

        listOf(
            "free" to "Free (Cooldown vidéo 65s)",
            "token" to "Token Plan (Cooldown vidéo 15s)",
            "enterprise" to "Enterprise (Illimité, 0s)"
        ).forEach { (profile, label) ->
            val isSelected = rateLimitProfile == profile
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        rateLimitProfile = profile
                        onSaveSettings(settings.copy(rateLimitProfile = profile))
                    }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = {
                        rateLimitProfile = profile
                        onSaveSettings(settings.copy(rateLimitProfile = profile))
                    },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFF8B5CF6),
                        unselectedColor = Color(0xFF555566)
                    )
                )
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Modèles par défaut
        Text(text = "Modèles par défaut", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14141C))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Texte", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF242434))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(text = defaultTextModel, color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Image", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF242434))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(text = defaultImageModel, color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Vidéo", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF242434))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(text = defaultVideoModel, color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Usage du jour
        Text(text = "Usage du jour", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))

        val usage = todayUsage ?: UsageEntity(date = "Aujourd'hui")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14141C))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Images générées :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(text = "${usage.imageRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Vidéos générées :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(
                    text = "${usage.videoRequests} (${String.format("%.1f", usage.videoSeconds)} s)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Requêtes texte / IA :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(text = "${usage.textRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Journal technique
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A24))
                .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenLogs),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgnesSvgIcon(icon = AgnesIcon.LOGS, tint = Color(0xFFA78BFA), size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Afficher les Logs Techniques en Direct", color = Color(0xFFA78BFA), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
