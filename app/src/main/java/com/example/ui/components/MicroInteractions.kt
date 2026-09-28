package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.svg.AgnesIcon
import com.example.ui.svg.AgnesSvgIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun triggerHapticFeedback(context: Context) {
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35)
            }
        }
    } catch (_: Exception) {}
}

/**
 * Bouton principal avec micro-interaction :
 * - Scale 0.97 au press
 * - Ripple violet
 * - Vibration haptique
 */
@Composable
fun AgnesPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: AgnesIcon? = null
) {
    val context = LocalContext.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "btn_scale"
    )

    val gradient = if (enabled) {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFF7C3AED), Color(0xFFDB2777))
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFF33333F), Color(0xFF262630))
        )
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(gradient)
            .pointerInput(enabled && !isLoading) {
                detectTapGestures(
                    onPress = {
                        if (enabled && !isLoading) {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        }
                    },
                    onTap = {
                        if (enabled && !isLoading) {
                            triggerHapticFeedback(context)
                            onClick()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            if (isLoading) {
                AgnesSvgIcon(
                    icon = AgnesIcon.SPINNER,
                    tint = Color.White,
                    size = 20.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
            } else if (icon != null) {
                AgnesSvgIcon(
                    icon = icon,
                    tint = if (enabled) Color.White else Color(0xFF888899),
                    size = 20.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = if (enabled) Color.White else Color(0xFF888899),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/**
 * Carte de création avec :
 * - Légère élévation + glow
 * - Détection de stall (passe en orange + bordure pulsée)
 * - Animation de secousse en cas d'erreur
 */
@Composable
fun AgnesInteractiveCard(
    modifier: Modifier = Modifier,
    isStalled: Boolean = false,
    hasError: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(hasError) {
        if (hasError) {
            // Shake horizontal léger
            for (i in 0..3) {
                shakeOffset.animateTo(6f, animationSpec = tween(50))
                shakeOffset.animateTo(-6f, animationSpec = tween(50))
            }
            shakeOffset.animateTo(0f, animationSpec = tween(50))
        }
    }

    val stallTransition = rememberInfiniteTransition(label = "stall_pulse")
    val stallAlpha by stallTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stall_glow"
    )

    val borderColor = when {
        isStalled -> Color(0xFFF59E0B).copy(alpha = stallAlpha)
        hasError -> Color(0xFFEF4444)
        else -> Color(0xFF282836)
    }

    Card(
        modifier = modifier
            .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(if (isStalled) 1.5.dp else 1.dp, borderColor), RoundedCornerShape(16.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isStalled) Color(0xFF1F1A12) else Color(0xFF14141C)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        content()
    }
}

/**
 * Barre de progression avec :
 * - Shimmer animé
 * - Transition width fluide 0.5s ease-out
 */
@Composable
fun AgnesShimmerProgressBar(
    progress: Float, // 0.0 to 1.0
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    barColor: Color = Color(0xFF8B5CF6)
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "progress_width"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "progress_shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_pos"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color(0xFF232330))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(barColor, Color(0xFFC084FC))
                    )
                )
        ) {
            // Shimmer beam
            val shimmerBrush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0f)
                ),
                start = Offset(shimmerTranslate - 200, 0f),
                end = Offset(shimmerTranslate, 0f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(shimmerBrush)
            )
        }
    }
}

/**
 * Zone de dépôt / sélection d'image avec pulsation et bordure illuminée.
 */
@Composable
fun AgnesUploadZone(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasImageSelected: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "upload_pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val borderBrush = if (hasImageSelected) {
        Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
    } else {
        Brush.linearGradient(
            listOf(
                Color(0xFF7C3AED).copy(alpha = pulseAlpha),
                Color(0xFF06B6D4).copy(alpha = pulseAlpha)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121218))
            .border(BorderStroke(1.5.dp, borderBrush), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgnesSvgIcon(
                icon = if (hasImageSelected) AgnesIcon.CHECK else AgnesIcon.UPLOAD,
                tint = if (hasImageSelected) Color(0xFF10B981) else Color(0xFFA78BFA),
                size = 28.dp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = if (hasImageSelected) "Image source sélectionnée" else "Déposer ou choisir une image de départ",
                color = if (hasImageSelected) Color(0xFF10B981) else Color(0xFFCCCCCC),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Toast glissant depuis le haut pour avertir d'une création terminée.
 */
@Composable
fun AgnesTopToast(
    message: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: AgnesIcon = AgnesIcon.CHECK
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(3500)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = spring(stiffness = 300f)),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(300)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E1E28),
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, Color(0xFF7C3AED).copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AgnesSvgIcon(
                    icon = icon,
                    tint = Color(0xFF10B981),
                    size = 20.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    AgnesSvgIcon(
                        icon = AgnesIcon.CLOSE,
                        tint = Color(0xFF888899),
                        size = 18.dp
                    )
                }
            }
        }
    }
}
