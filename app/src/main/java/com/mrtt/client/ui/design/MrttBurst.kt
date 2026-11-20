package com.mrtt.client.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Modifier.mrttBurst(pressed: Boolean): Modifier {
    // Анимация запускается только на нажатие и заканчивается сама; после
    // окончания рисуется ровно фон, без остаточных частиц.
    val effect = mrttEffectFor(LocalMrttStyle.current.id)
    val progress = remember { Animatable(1f) }
    val first = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.secondary
    LaunchedEffect(pressed) {
        if (pressed) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        } else {
            // Отпустили кнопку — гасим всплеск, чтобы частицы не висели.
            progress.snapTo(1f)
        }
    }
    if (effect == MrttEffect.NONE) return this
    return this.drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val unit = 1.dp.toPx()
            repeat(12) { i ->
                val ang = (i * 2.0 * PI / 12) + mrttRnd(i, 7) * 0.5
                val reach = (size.width * 0.35f + 26 * unit * mrttRnd(i, 8)) * (0.25f + p)
                val rise = -18 * unit * p // частицы слегка всплывают
                val pos = Offset(center.x + reach * cos(ang).toFloat(), center.y + reach * 0.55f * sin(ang).toFloat() + rise)
                val alpha = (1f - p) * (1f - p) * 0.95f
                drawParticle(
                    effect,
                    pos,
                    (3.2f + 3.2f * mrttRnd(i, 9)) * unit * (1f - 0.3f * p),
                    p * 220f * (if (i % 2 == 0) 1f else -1f) + i * 30f,
                    (if (i % 2 == 0) first else second).copy(alpha = alpha),
                )
            }
        }
    }
}
