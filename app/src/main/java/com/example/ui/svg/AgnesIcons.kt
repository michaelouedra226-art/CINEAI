package com.example.ui.svg

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Système d'icônes 100% SVG inline.
 * Respecte strictement le cahier des charges :
 * - Tracé vectoriel pur (Canvas)
 * - Stroke currentColor
 * - Stroke-width 1.8-2.0
 * - Stroke-linecap Round, Stroke-linejoin Round
 * - Zéro emoji, zéro police d'icônes
 */
enum class AgnesIcon {
    HOME_IMAGES,
    VIDEO,
    FILM,
    GALLERY,
    CHAT,
    SETTINGS,
    BACK,
    GENERATE,
    UPLOAD,
    DOWNLOAD,
    TRASH,
    FAVORITE,
    FAVORITE_FILLED,
    CLOSE,
    CHECK,
    WARNING,
    SPINNER,
    LIVE_DOT,
    PLUS,
    SEARCH,
    FILTER,
    LOGS,
    PLAY,
    PAUSE,
    SHARE
}

@Composable
fun AgnesSvgIcon(
    icon: AgnesIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 22.dp,
    contentDescription: String? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "svg_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing)
        ),
        label = "spin_angle"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing)
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidthPx = 1.9f * density
            val stroke = Stroke(
                width = strokeWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            when (icon) {
                AgnesIcon.HOME_IMAGES -> drawHomeImages(tint, stroke)
                AgnesIcon.VIDEO -> drawVideoCamera(tint, stroke)
                AgnesIcon.FILM -> drawFilmClap(tint, stroke)
                AgnesIcon.GALLERY -> drawGallery(tint, stroke)
                AgnesIcon.CHAT -> drawChatBubble(tint, stroke)
                AgnesIcon.SETTINGS -> drawSettings(tint, stroke)
                AgnesIcon.BACK -> drawChevronLeft(tint, stroke)
                AgnesIcon.GENERATE -> drawGeneratePlay(tint, stroke)
                AgnesIcon.UPLOAD -> drawUpload(tint, stroke)
                AgnesIcon.DOWNLOAD -> drawDownload(tint, stroke)
                AgnesIcon.TRASH -> drawTrash(tint, stroke)
                AgnesIcon.FAVORITE -> drawFavorite(tint, stroke, filled = false)
                AgnesIcon.FAVORITE_FILLED -> drawFavorite(tint, stroke, filled = true)
                AgnesIcon.CLOSE -> drawClose(tint, stroke)
                AgnesIcon.CHECK -> drawCheck(tint, stroke)
                AgnesIcon.WARNING -> drawWarning(tint, stroke)
                AgnesIcon.SPINNER -> drawSpinner(tint, stroke, rotation)
                AgnesIcon.LIVE_DOT -> drawLiveDot(tint, pulseAlpha)
                AgnesIcon.PLUS -> drawPlus(tint, stroke)
                AgnesIcon.SEARCH -> drawSearch(tint, stroke)
                AgnesIcon.FILTER -> drawFilter(tint, stroke)
                AgnesIcon.LOGS -> drawLogs(tint, stroke)
                AgnesIcon.PLAY -> drawPlay(tint, stroke)
                AgnesIcon.PAUSE -> drawPause(tint, stroke)
                AgnesIcon.SHARE -> drawShare(tint, stroke)
            }
        }
    }
}

private fun DrawScope.drawHomeImages(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Rect cadre image
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.12f, h * 0.12f),
        size = Size(w * 0.76f, h * 0.76f),
        cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
        style = stroke
    )
    // Cercle soleil
    drawCircle(
        color = color,
        radius = w * 0.08f,
        center = Offset(w * 0.35f, h * 0.35f),
        style = stroke
    )
    // Montagnes intérieures
    val path = Path().apply {
        moveTo(w * 0.15f, h * 0.72f)
        lineTo(w * 0.42f, h * 0.48f)
        lineTo(w * 0.60f, h * 0.62f)
        lineTo(w * 0.72f, h * 0.52f)
        lineTo(w * 0.85f, h * 0.72f)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawVideoCamera(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Boîtier principal
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.12f, h * 0.22f),
        size = Size(w * 0.52f, h * 0.56f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = stroke
    )
    // Objectif trapézoïdal
    val lens = Path().apply {
        moveTo(w * 0.64f, h * 0.38f)
        lineTo(w * 0.88f, h * 0.24f)
        lineTo(w * 0.88f, h * 0.76f)
        lineTo(w * 0.64f, h * 0.62f)
        close()
    }
    drawPath(lens, color, style = stroke)
}

private fun DrawScope.drawFilmClap(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Clapboard base
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.14f, h * 0.38f),
        size = Size(w * 0.72f, h * 0.48f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = stroke
    )
    // Haut incliné du clap
    val clapTop = Path().apply {
        moveTo(w * 0.14f, h * 0.38f)
        lineTo(w * 0.84f, h * 0.22f)
        lineTo(w * 0.86f, h * 0.34f)
        lineTo(w * 0.14f, h * 0.38f)
    }
    drawPath(clapTop, color, style = stroke)
    // Bandes diagonales du clap
    drawLine(color, Offset(w * 0.32f, h * 0.34f), Offset(w * 0.38f, h * 0.24f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.52f, h * 0.30f), Offset(w * 0.58f, h * 0.20f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.70f, h * 0.26f), Offset(w * 0.76f, h * 0.16f), strokeWidth = stroke.width)
}

private fun DrawScope.drawGallery(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Carte arrière
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.26f, h * 0.14f),
        size = Size(w * 0.60f, h * 0.60f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = stroke
    )
    // Carte avant
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.14f, h * 0.26f),
        size = Size(w * 0.60f, h * 0.60f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = stroke
    )
    // Image intérieure
    val path = Path().apply {
        moveTo(w * 0.20f, h * 0.70f)
        lineTo(w * 0.38f, h * 0.50f)
        lineTo(w * 0.52f, h * 0.62f)
        lineTo(w * 0.68f, h * 0.46f)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawChatBubble(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.22f, h * 0.20f)
        lineTo(w * 0.78f, h * 0.20f)
        quadraticTo(w * 0.88f, h * 0.20f, w * 0.88f, h * 0.30f)
        lineTo(w * 0.88f, h * 0.62f)
        quadraticTo(w * 0.88f, h * 0.72f, w * 0.78f, h * 0.72f)
        lineTo(w * 0.44f, h * 0.72f)
        lineTo(w * 0.26f, h * 0.86f)
        lineTo(w * 0.28f, h * 0.72f)
        lineTo(w * 0.22f, h * 0.72f)
        quadraticTo(w * 0.12f, h * 0.72f, w * 0.12f, h * 0.62f)
        lineTo(w * 0.12f, h * 0.30f)
        quadraticTo(w * 0.12f, h * 0.20f, w * 0.22f, h * 0.20f)
        close()
    }
    drawPath(path, color, style = stroke)
    // Points à l'intérieur
    drawCircle(color, w * 0.035f, Offset(w * 0.35f, h * 0.46f))
    drawCircle(color, w * 0.035f, Offset(w * 0.50f, h * 0.46f))
    drawCircle(color, w * 0.035f, Offset(w * 0.65f, h * 0.46f))
}

private fun DrawScope.drawSettings(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.5f)
    drawCircle(color, w * 0.18f, center, style = stroke)

    for (i in 0 until 6) {
        val angle = i * 60f
        rotate(angle, center) {
            drawLine(
                color = color,
                start = Offset(center.x, h * 0.14f),
                end = Offset(center.x, h * 0.28f),
                strokeWidth = stroke.width
            )
        }
    }
    drawCircle(color, w * 0.36f, center, style = stroke)
}

private fun DrawScope.drawChevronLeft(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.62f, h * 0.22f)
        lineTo(w * 0.34f, h * 0.50f)
        lineTo(w * 0.62f, h * 0.78f)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawGeneratePlay(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.30f, h * 0.22f)
        lineTo(w * 0.76f, h * 0.50f)
        lineTo(w * 0.30f, h * 0.78f)
        close()
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawUpload(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Flèche haut
    drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.5f, h * 0.20f), strokeWidth = stroke.width)
    val head = Path().apply {
        moveTo(w * 0.32f, h * 0.36f)
        lineTo(w * 0.50f, h * 0.18f)
        lineTo(w * 0.68f, h * 0.36f)
    }
    drawPath(head, color, style = stroke)
    // Plateau bas
    val base = Path().apply {
        moveTo(w * 0.20f, h * 0.62f)
        lineTo(w * 0.20f, h * 0.80f)
        lineTo(w * 0.80f, h * 0.80f)
        lineTo(w * 0.80f, h * 0.62f)
    }
    drawPath(base, color, style = stroke)
}

private fun DrawScope.drawDownload(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Flèche bas
    drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.64f), strokeWidth = stroke.width)
    val head = Path().apply {
        moveTo(w * 0.32f, h * 0.48f)
        lineTo(w * 0.50f, h * 0.66f)
        lineTo(w * 0.68f, h * 0.48f)
    }
    drawPath(head, color, style = stroke)
    // Plateau bas
    val base = Path().apply {
        moveTo(w * 0.20f, h * 0.62f)
        lineTo(w * 0.20f, h * 0.80f)
        lineTo(w * 0.80f, h * 0.80f)
        lineTo(w * 0.80f, h * 0.62f)
    }
    drawPath(base, color, style = stroke)
}

private fun DrawScope.drawTrash(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Poubelle cuve
    val body = Path().apply {
        moveTo(w * 0.25f, h * 0.32f)
        lineTo(w * 0.30f, h * 0.82f)
        lineTo(w * 0.70f, h * 0.82f)
        lineTo(w * 0.75f, h * 0.32f)
    }
    drawPath(body, color, style = stroke)
    // Couvercle
    drawLine(color, Offset(w * 0.18f, h * 0.32f), Offset(w * 0.82f, h * 0.32f), strokeWidth = stroke.width)
    // Poignée
    val handle = Path().apply {
        moveTo(w * 0.38f, h * 0.32f)
        lineTo(w * 0.38f, h * 0.20f)
        lineTo(w * 0.62f, h * 0.20f)
        lineTo(w * 0.62f, h * 0.32f)
    }
    drawPath(handle, color, style = stroke)
    // Lignes verticales
    drawLine(color, Offset(w * 0.42f, h * 0.42f), Offset(w * 0.42f, h * 0.72f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.58f, h * 0.42f), Offset(w * 0.58f, h * 0.72f), strokeWidth = stroke.width)
}

private fun DrawScope.drawFavorite(color: Color, stroke: Stroke, filled: Boolean) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.78f)
        cubicTo(w * 0.15f, h * 0.55f, w * 0.12f, h * 0.32f, w * 0.28f, h * 0.22f)
        cubicTo(w * 0.40f, h * 0.14f, w * 0.48f, h * 0.24f, w * 0.5f, h * 0.32f)
        cubicTo(w * 0.52f, h * 0.24f, w * 0.60f, h * 0.14f, w * 0.72f, h * 0.22f)
        cubicTo(w * 0.88f, h * 0.32f, w * 0.85f, h * 0.55f, w * 0.5f, h * 0.78f)
        close()
    }
    if (filled) {
        drawPath(path, color, style = Fill)
    } else {
        drawPath(path, color, style = stroke)
    }
}

private fun DrawScope.drawClose(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = stroke.width)
}

private fun DrawScope.drawCheck(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.20f, h * 0.52f)
        lineTo(w * 0.42f, h * 0.74f)
        lineTo(w * 0.82f, h * 0.28f)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawWarning(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val triangle = Path().apply {
        moveTo(w * 0.50f, h * 0.16f)
        lineTo(w * 0.88f, h * 0.82f)
        lineTo(w * 0.12f, h * 0.82f)
        close()
    }
    drawPath(triangle, color, style = stroke)
    drawLine(color, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.58f), strokeWidth = stroke.width)
    drawCircle(color, radius = w * 0.035f, center = Offset(w * 0.50f, h * 0.70f))
}

private fun DrawScope.drawSpinner(color: Color, stroke: Stroke, rotationAngle: Float) {
    val w = size.width
    val h = size.height
    rotate(rotationAngle, Offset(w * 0.5f, h * 0.5f)) {
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = Size(w * 0.68f, h * 0.68f),
            style = stroke
        )
    }
}

private fun DrawScope.drawLiveDot(color: Color, alpha: Float) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.5f, h * 0.5f)
    drawCircle(color.copy(alpha = alpha * 0.35f), radius = w * 0.42f, center = center)
    drawCircle(color.copy(alpha = alpha), radius = w * 0.22f, center = center)
}

private fun DrawScope.drawPlus(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), strokeWidth = stroke.width)
}

private fun DrawScope.drawSearch(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.44f, h * 0.44f), style = stroke)
    drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.84f, h * 0.84f), strokeWidth = stroke.width)
}

private fun DrawScope.drawFilter(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val funnel = Path().apply {
        moveTo(w * 0.18f, h * 0.22f)
        lineTo(w * 0.82f, h * 0.22f)
        lineTo(w * 0.56f, h * 0.54f)
        lineTo(w * 0.56f, h * 0.78f)
        lineTo(w * 0.44f, h * 0.78f)
        lineTo(w * 0.44f, h * 0.54f)
        close()
    }
    drawPath(funnel, color, style = stroke)
}

private fun DrawScope.drawLogs(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.18f, h * 0.18f),
        size = Size(w * 0.64f, h * 0.64f),
        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        style = stroke
    )
    val prompt = Path().apply {
        moveTo(w * 0.30f, h * 0.38f)
        lineTo(w * 0.44f, h * 0.50f)
        lineTo(w * 0.30f, h * 0.62f)
    }
    drawPath(prompt, color, style = stroke)
    drawLine(color, Offset(w * 0.52f, h * 0.62f), Offset(w * 0.70f, h * 0.62f), strokeWidth = stroke.width)
}

private fun DrawScope.drawPlay(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val triangle = Path().apply {
        moveTo(w * 0.35f, h * 0.25f)
        lineTo(w * 0.75f, h * 0.50f)
        lineTo(w * 0.35f, h * 0.75f)
        close()
    }
    drawPath(triangle, color, style = stroke)
}

private fun DrawScope.drawPause(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawLine(color, Offset(w * 0.36f, h * 0.26f), Offset(w * 0.36f, h * 0.74f), strokeWidth = stroke.width)
    drawLine(color, Offset(w * 0.64f, h * 0.26f), Offset(w * 0.64f, h * 0.74f), strokeWidth = stroke.width)
}

private fun DrawScope.drawShare(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    // Node 1 (right top), Node 2 (left center), Node 3 (right bottom)
    val p1 = Offset(w * 0.75f, h * 0.28f)
    val p2 = Offset(w * 0.28f, h * 0.50f)
    val p3 = Offset(w * 0.75f, h * 0.72f)
    val r = w * 0.11f

    drawLine(color, p2, p1, strokeWidth = stroke.width)
    drawLine(color, p2, p3, strokeWidth = stroke.width)

    drawCircle(color, radius = r, center = p1, style = stroke)
    drawCircle(color, radius = r, center = p2, style = stroke)
    drawCircle(color, radius = r, center = p3, style = stroke)
}

