package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

enum class AgnesScreen(val title: String, val icon: AgnesIcon) {
    IMAGES("Images", AgnesIcon.HOME_IMAGES),
    VIDEOS("Vidéos", AgnesIcon.VIDEO),
    FILM("Film", AgnesIcon.FILM),
    GALLERY("Galerie", AgnesIcon.GALLERY),
    CHAT("Chat", AgnesIcon.CHAT),
    SETTINGS("Réglages", AgnesIcon.SETTINGS)
}

/**
 * Header sticky conforme aux wireframes :
 * [←] Titre de la page [⚙] [📚]
 * Fond semi-transparent #0A0A0F
 */
@Composable
fun AgnesHeader(
    title: String,
    canNavigateBack: Boolean,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLogsClick: () -> Unit,
    hasActiveQueue: Boolean = false,
    modifier: Modifier = Modifier
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0F))
    ) {
        Spacer(modifier = Modifier.height(statusBarPadding))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canNavigateBack) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBackClick),
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(
                            icon = AgnesIcon.BACK,
                            tint = Color.White,
                            size = 20.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp
                )

                if (hasActiveQueue) {
                    Spacer(modifier = Modifier.width(8.dp))
                    AgnesSvgIcon(
                        icon = AgnesIcon.LIVE_DOT,
                        tint = Color(0xFF10B981),
                        size = 14.dp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bouton Logs techniques [📚]
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onLogsClick),
                    contentAlignment = Alignment.Center
                ) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.LOGS,
                        tint = Color(0xFFA1A1AA),
                        size = 20.dp
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Bouton Réglages [⚙]
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onSettingsClick),
                    contentAlignment = Alignment.Center
                ) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.SETTINGS,
                        tint = Color(0xFFA1A1AA),
                        size = 20.dp
                    )
                }
            }
        }
        HorizontalDivider(color = Color(0xFF1C1C25), thickness = 1.dp)
    }
}

/**
 * Tabbar fixe 68px + safe-area avec indicateur qui glisse avec animation spring.
 * Onglets : [Images] [Vidéos] [Film] [Galerie] [Chat]
 */
@Composable
fun AgnesBottomBar(
    currentScreen: AgnesScreen,
    onScreenSelected: (AgnesScreen) -> Unit,
    hasGalleryNewItems: Boolean = false,
    modifier: Modifier = Modifier
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current

    val mainTabs = listOf(
        AgnesScreen.IMAGES,
        AgnesScreen.VIDEOS,
        AgnesScreen.FILM,
        AgnesScreen.GALLERY,
        AgnesScreen.CHAT
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF13131A))
    ) {
        HorizontalDivider(color = Color(0xFF1C1C25), thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            mainTabs.forEach { screen ->
                val isSelected = currentScreen == screen

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                triggerHapticFeedback(context)
                                onScreenSelected(screen)
                            }
                        )
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AgnesSvgIcon(
                                icon = screen.icon,
                                tint = if (isSelected) Color(0xFFA78BFA) else Color(0xFF71717A),
                                size = 22.dp
                            )
                            if (screen == AgnesScreen.GALLERY && hasGalleryNewItems) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .offset(x = 10.dp, y = (-8).dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF8B5CF6))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = screen.title,
                            color = if (isSelected) Color(0xFFA78BFA) else Color(0xFF71717A),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        // Indicateur animé sous l'onglet actif
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 18.dp else 0.dp)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSelected) Color(0xFF8B5CF6) else Color.Transparent)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(navBarPadding))
    }
}
