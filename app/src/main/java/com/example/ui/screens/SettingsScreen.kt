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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.api.RateLimiter
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity
import com.example.ui.components.AgnesShimmerProgressBar
import com.example.ui.components.triggerHapticFeedback
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

@Composable
fun SettingsScreen(
    settings: SettingsEntity,
    todayUsage: UsageEntity?,
    onSaveSettings: (SettingsEntity) -> Unit,
    onDeleteApiKey: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var rateLimitProfile by remember(settings.rateLimitProfile) { mutableStateOf(settings.rateLimitProfile) }
    var autoDownload by remember(settings.autoDownload) { mutableStateOf(settings.autoDownload) }
    var showTechLog by remember(settings.showTechnicalLog) { mutableStateOf(settings.showTechnicalLog) }

    val usage = todayUsage ?: UsageEntity(date = "Aujourd'hui")
    val videoQuotaMax = RateLimiter.MAX_VIDEO_SECONDS_PER_DAY
    val videoQuotaFraction = (usage.videoSeconds / videoQuotaMax).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
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

        // Clé API (saisie, sauvegarde, suppression)
        Text(text = "Clé API Agnes", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                placeholder = { Text("Entrez votre clé API...", color = Color(0xFF555566), fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF13131A),
                    unfocusedContainerColor = Color(0xFF13131A),
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
                        onSaveSettings(settings.copy(apiKey = apiKey.trim()))
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Enregistrer", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            if (settings.apiKey.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2B1616))
                        .clickable {
                            triggerHapticFeedback(context)
                            apiKey = ""
                            onDeleteApiKey()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AgnesSvgIcon(icon = AgnesIcon.TRASH, tint = Color(0xFFEF4444), size = 18.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Profil de rate-limit
        Text(text = "Profil de rate-limit", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))

        listOf(
            "free" to "Free (Pause 61s vidéo obligatoire, 1 RPM)",
            "token" to "Token Plan (Pause 12s vidéo, 5 RPM)",
            "enterprise" to "Enterprise (Illimité, 0s pause)"
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
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8B5CF6), unselectedColor = Color(0xFF555566))
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

        // Modèles par défaut vérifiés
        Text(text = "Modèles par défaut (Endpoints vérifiés)", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF13131A))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Texte & Script :", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C25)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(text = "agnes-2.5-flash", color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Image & Keyframes :", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C25)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(text = "agnes-image-2.1-flash", color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Vidéo Synthèse :", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C25)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(text = "agnes-video-v2.0", color = Color(0xFFA78BFA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Usage du jour & Quota Vidéo (500s max)
        Text(text = "Usage du jour & Quota", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF13131A))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Quota Vidéo Journalier (500s max) :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                    Text(
                        text = "${String.format("%.1f", usage.videoSeconds)} / 500 s",
                        color = if (usage.videoSeconds > 450) Color(0xFFEF4444) else Color(0xFF34D399),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                AgnesShimmerProgressBar(
                    progress = videoQuotaFraction,
                    height = 8.dp,
                    barColor = if (usage.videoSeconds > 450) Color(0xFFEF4444) else Color(0xFF8B5CF6)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Vidéos créées :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(text = "${usage.videoRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Images générées :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(text = "${usage.imageRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Requêtes script / texte :", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                Text(text = "${usage.textRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Options
        Text(text = "Options avancées", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF13131A))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Téléchargement automatique des rendus", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = autoDownload,
                    onCheckedChange = {
                        autoDownload = it
                        onSaveSettings(settings.copy(autoDownload = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF8B5CF6))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Afficher les logs techniques", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = showTechLog,
                    onCheckedChange = {
                        showTechLog = it
                        onSaveSettings(settings.copy(showTechnicalLog = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF8B5CF6))
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Bouton consultation des logs
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C25))
                .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenLogs),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgnesSvgIcon(icon = AgnesIcon.LOGS, tint = Color(0xFFA78BFA), size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Consulter le journal technique en direct", color = Color(0xFFA78BFA), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
