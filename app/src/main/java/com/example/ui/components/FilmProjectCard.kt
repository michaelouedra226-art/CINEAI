package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.FilmEntity
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon

/**
 * Carte vignette de projet de film avec affiche haute définition,
 * indicateurs d'état, durée, décompte de scènes et boutons d'action.
 */
@Composable
fun FilmProjectCard(
    film: FilmEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    height: Dp = Dp.Unspecified,
    onResume: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val thumbUrl = film.getEffectiveThumbnail()
    val completedScenes = film.getCompletedScenesCount()
    val isDone = film.status == "done"
    val isPartial = film.status == "partial"
    val isFailed = film.status == "failed"
    val isProc = film.status == "processing"

    val borderColor = when {
        isDone -> Color(0xFF10B981) // Émeraude pour projet abouti
        isPartial -> Color(0xFFF59E0B) // Ambre pour reprise possible
        isFailed -> Color(0xFFEF4444) // Rouge pour échec
        isProc -> Color(0xFF8B5CF6) // Violet animé pour en cours
        else -> Color(0xFF2E2E3E)
    }

    val boxModifier = if (width != Dp.Unspecified && height != Dp.Unspecified) {
        modifier.width(width).height(height)
    } else if (width != Dp.Unspecified) {
        modifier.width(width)
    } else if (height != Dp.Unspecified) {
        modifier.height(height)
    } else {
        modifier
    }

    Box(
        modifier = boxModifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .background(Color(0xFF14141E))
            .clickable {
                triggerHapticFeedback(context)
                onClick()
            }
    ) {
        // 1. Image vignette principale
        if (!thumbUrl.isNullOrBlank()) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = film.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dégradé cinéma vertical pour lisibilité optimale
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0x66000000),
                            0.4f to Color(0x22000000),
                            0.75f to Color(0xAA0A0A10),
                            1.0f to Color(0xF007070B)
                        )
                    )
            )
        } else {
            // Affiche cinéma stylisée procédurale si pas de clé visuelle générée
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2A1646), Color(0xFF161224), Color(0xFF0F0E17))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0x337C3AED)),
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(
                            icon = AgnesIcon.FILM,
                            tint = Color(0xFFA78BFA),
                            size = 24.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = film.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 2. En-tête : Badge FILM + Statut + Favori
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF7C3AED))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "FILM",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when {
                    isDone -> "Terminé"
                    isPartial -> "Partiel"
                    isFailed -> "Échec"
                    isProc -> "En cours"
                    else -> film.status
                }
                val statusBg = when {
                    isDone -> Color(0xDD064E3B)
                    isPartial -> Color(0xDD78350F)
                    isFailed -> Color(0xDD7F1D1D)
                    else -> Color(0xDD312E81)
                }
                val statusColor = when {
                    isDone -> Color(0xFF34D399)
                    isPartial -> Color(0xFFFBBF24)
                    isFailed -> Color(0xFFF87171)
                    else -> Color(0xFFA5B4FC)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusBg)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (film.favorite) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.FAVORITE_FILLED,
                        tint = Color(0xFFEF4444),
                        size = 13.dp
                    )
                }
            }
        }

        // 3. Pied de vignette : Titre, décompte scènes, durée et bouton de lecture
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        ) {
            Text(
                text = film.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$completedScenes/${film.numScenes} plans · ${film.duration.toInt()}s",
                    color = Color(0xFFBBBBD0),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )

                if (film.hasPlayableVideo()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7C3AED)),
                        contentAlignment = Alignment.Center
                    ) {
                        AgnesSvgIcon(icon = AgnesIcon.PLAY, tint = Color.White, size = 10.dp)
                    }
                } else if ((isPartial || isProc || isFailed) && onResume != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF59E0B))
                            .clickable {
                                triggerHapticFeedback(context)
                                onResume()
                            }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Reprendre",
                            color = Color.Black,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
