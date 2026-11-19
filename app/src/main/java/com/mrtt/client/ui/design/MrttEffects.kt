package com.mrtt.client.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Живой эффект фона и частиц у каждого стиля: у «Аниме» — лепестки сакуры, у «Матрицы» — дождь кода и т. д. */
enum class MrttEffect { NONE, SAKURA, HEARTS, STARS, SNOW, BUBBLES, FIREFLIES, EMBERS, CODE, NEON, AURORA }

fun mrttEffectFor(styleId: String): MrttEffect = when (styleId) {
    "anime" -> MrttEffect.SAKURA
    "candy", "rosegold" -> MrttEffect.HEARTS
    "indigo", "midnight", "lavender", "gold", "dracula" -> MrttEffect.STARS
    "nord" -> MrttEffect.SNOW
    "ocean", "mint" -> MrttEffect.BUBBLES
    "forest" -> MrttEffect.FIREFLIES
    "espresso", "sunset", "ruby" -> MrttEffect.EMBERS
    "matrix", "graphite" -> MrttEffect.CODE
    "cyber", "synthwave" -> MrttEffect.NEON
    "aurora" -> MrttEffect.AURORA
    else -> MrttEffect.NONE
}

/** Стили, у которых на главном экране живёт аниме-девочка. */
fun mrttHasMascot(styleId: String): Boolean = styleId in setOf("anime", "candy", "rosegold", "lavender")

/** Детерминированный «случайный» параметр частицы: i — номер, k — какой параметр. */
internal fun mrttRnd(i: Int, k: Int): Float {
    val v = sin((i * 127.1 + k * 311.7)) * 43758.5453
    return (v - floor(v)).toFloat()
}

internal fun DrawScope.drawHeart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.6f, c.y - s * 0.1f, c.x - s * 0.9f, c.y - s * 1.2f, c.x, c.y - s * 0.4f)
        cubicTo(c.x + s * 0.9f, c.y - s * 1.2f, c.x + s * 1.6f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    drawPath(p, color)
}

internal fun DrawScope.drawSparkle(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - s)
        quadraticBezierTo(c.x, c.y, c.x + s, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y + s)
        quadraticBezierTo(c.x, c.y, c.x - s, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y - s)
        close()
    }
    drawPath(p, color)
}

internal fun DrawScope.drawPetal(c: Offset, s: Float, deg: Float, color: Color) {
    rotate(deg, c) {
        drawOval(color, Offset(c.x - s, c.y - s * 0.55f), Size(s * 2f, s * 1.1f))
        drawOval(Color.White.copy(alpha = color.alpha * 0.45f), Offset(c.x - s * 0.7f, c.y - s * 0.3f), Size(s * 0.9f, s * 0.45f))
    }
}

/** Одна частица по типу эффекта — её же берут кнопки и главный шар. */
internal fun DrawScope.drawParticle(effect: MrttEffect, c: Offset, s: Float, deg: Float, color: Color) {
    when (effect) {
        MrttEffect.SAKURA -> drawPetal(c, s, deg, color)
        MrttEffect.HEARTS -> drawHeart(c, s, color)
        MrttEffect.STARS, MrttEffect.AURORA -> drawSparkle(c, s * 1.3f, color)
        MrttEffect.BUBBLES -> {
            drawCircle(color.copy(alpha = color.alpha * 0.5f), s, c, style = Stroke(1.2.dp.toPx()))
            drawCircle(Color.White.copy(alpha = color.alpha), s * 0.22f, Offset(c.x - s * 0.4f, c.y - s * 0.4f))
        }
        MrttEffect.SNOW -> drawCircle(color, s * 0.7f, c)
        MrttEffect.NEON, MrttEffect.CODE -> {
            rotate(deg, c) { drawRect(color, Offset(c.x - s, c.y - s * 0.35f), Size(s * 2f, s * 0.7f)) }
        }
        MrttEffect.FIREFLIES -> drawCircle(
            Brush.radialGradient(listOf(color, Color.Transparent), c, s * 2.2f),
            s * 2.2f,
            c,
        )
        MrttEffect.EMBERS -> drawCircle(color, s * 0.55f, c)
        MrttEffect.NONE -> Unit
    }
}

/** Общие часы анимации: меняют только перерисовку, не пересборку. */
@Composable
internal fun rememberMrttClock(): () -> Float {
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            clock = withFrameMillis { it } - start
        }
    }
    return { clock / 1000f }
}

/** Фоновая анимация текущего стиля. Рисуется под интерфейсом, поверх цветных пятен. */
@Composable
fun MrttBackdropEffect(effect: MrttEffect, modifier: Modifier = Modifier) {
    if (effect == MrttEffect.NONE) return
    val scheme = MaterialTheme.colorScheme
    val time = rememberMrttClock()
    val primary = scheme.primary
    val secondary = scheme.secondary
    val tertiary = scheme.tertiary
    Canvas(modifier) {
        val t = time()
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        when (effect) {
            MrttEffect.SAKURA -> repeat(22) { i ->
                val speed = 0.03f + 0.04f * mrttRnd(i, 1)
                val y = ((mrttRnd(i, 2) + t * speed) % 1f) * (h + 60 * dp) - 30 * dp
                val x = mrttRnd(i, 0) * w + sin(t * (0.6f + mrttRnd(i, 3)) + i) * 28 * dp
                val color = lerp(secondary, Color(0xFFFFB7D5), 0.6f).copy(alpha = 0.55f)
                drawPetal(Offset(x, y), (4f + 5f * mrttRnd(i, 4)) * dp, t * (30f + 50f * mrttRnd(i, 5)) + i * 40f, if (i % 3 == 0) primary.copy(alpha = 0.5f) else color)
            }
            MrttEffect.HEARTS -> repeat(16) { i ->
                val speed = 0.04f + 0.05f * mrttRnd(i, 1)
                val phase = (mrttRnd(i, 2) + t * speed) % 1f
                val y = h + 20 * dp - phase * (h + 40 * dp)
                val x = mrttRnd(i, 0) * w + sin(t * 0.8f + i) * 18 * dp
                val fade = sin(phase * PI.toFloat())
                drawHeart(Offset(x, y), (3.5f + 4f * mrttRnd(i, 3)) * dp, (if (i % 2 == 0) primary else secondary).copy(alpha = 0.45f * fade))
            }
            MrttEffect.STARS -> repeat(30) { i ->
                val tw = 0.5f + 0.5f * sin(t * (0.8f + 1.6f * mrttRnd(i, 3)) + i * 2f)
                val c = Offset(mrttRnd(i, 0) * w, mrttRnd(i, 1) * h)
                val s = (1.5f + 3.5f * mrttRnd(i, 2)) * dp
                val color = (if (i % 4 == 0) tertiary else primary).copy(alpha = 0.12f + 0.55f * tw)
                if (i % 3 == 0) drawSparkle(c, s * 1.8f, color) else drawCircle(color, s * 0.45f, c)
            }
            MrttEffect.SNOW -> repeat(40) { i ->
                val speed = 0.03f + 0.05f * mrttRnd(i, 1)
                val y = ((mrttRnd(i, 2) + t * speed) % 1f) * (h + 20 * dp) - 10 * dp
                val x = mrttRnd(i, 0) * w + sin(t * 0.5f + i) * 16 * dp
                drawCircle(primary.copy(alpha = 0.18f + 0.3f * mrttRnd(i, 3)), (1.2f + 2.2f * mrttRnd(i, 4)) * dp, Offset(x, y))
            }
            MrttEffect.BUBBLES -> repeat(16) { i ->
                val speed = 0.03f + 0.04f * mrttRnd(i, 1)
                val phase = (mrttRnd(i, 2) + t * speed) % 1f
                val y = h + 20 * dp - phase * (h + 40 * dp)
                val x = mrttRnd(i, 0) * w + sin(t * 0.9f + i) * 14 * dp
                drawParticle(MrttEffect.BUBBLES, Offset(x, y), (4f + 9f * mrttRnd(i, 3)) * dp, 0f, primary.copy(alpha = 0.5f))
            }
            MrttEffect.FIREFLIES -> repeat(18) { i ->
                val x = mrttRnd(i, 0) * w + sin(t * 0.3f + i * 3f) * 40 * dp
                val y = mrttRnd(i, 1) * h + sin(t * 0.25f + i * 5f) * 50 * dp
                val pulse = 0.5f + 0.5f * sin(t * (0.7f + mrttRnd(i, 3)) + i)
                drawParticle(MrttEffect.FIREFLIES, Offset(x, y), (2f + 2.5f * mrttRnd(i, 2)) * dp, 0f, primary.copy(alpha = 0.15f + 0.5f * pulse))
            }
            MrttEffect.EMBERS -> repeat(26) { i ->
                val speed = 0.04f + 0.06f * mrttRnd(i, 1)
                val phase = (mrttRnd(i, 2) + t * speed) % 1f
                val y = h + 10 * dp - phase * (h + 20 * dp)
                val x = mrttRnd(i, 0) * w + sin(t * 1.2f + i) * 12 * dp
                val flick = 0.6f + 0.4f * sin(t * 6f + i)
                drawParticle(MrttEffect.EMBERS, Offset(x, y), (1.6f + 2.4f * mrttRnd(i, 3)) * dp, 0f, (if (i % 3 == 0) tertiary else primary).copy(alpha = 0.55f * (1f - phase) * flick))
            }
            MrttEffect.CODE -> {
                val col = 18 * dp
                val cols = (w / col).toInt() + 1
                repeat(cols) { i ->
                    val speed = 0.08f + 0.12f * mrttRnd(i, 1)
                    val head = ((mrttRnd(i, 2) + t * speed) % 1f) * (h * 1.5f) - h * 0.25f
                    repeat(9) { k ->
                        val a = 0.28f * (1f - k / 9f)
                        val seg = 7 * dp
                        drawRect(primary.copy(alpha = a), Offset(i * col + 3 * dp, head - k * (seg + 2 * dp)), Size(5 * dp, seg))
                    }
                }
            }
            MrttEffect.NEON -> {
                repeat(10) { i ->
                    val speed = 0.12f + 0.25f * mrttRnd(i, 1)
                    val head = ((mrttRnd(i, 2) + t * speed) % 1f) * (h * 1.4f) - h * 0.2f
                    val x = mrttRnd(i, 0) * w
                    val color = if (i % 2 == 0) primary else secondary
                    drawLine(
                        Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.6f)), head - 90 * dp, head),
                        Offset(x, head - 90 * dp), Offset(x, head), strokeWidth = 1.6f * dp,
                    )
                }
                val scan = ((t * 0.07f) % 1f) * h
                drawRect(secondary.copy(alpha = 0.10f), Offset(0f, scan), Size(w, 2 * dp))
            }
            MrttEffect.AURORA -> repeat(3) { b ->
                val color = when (b) { 0 -> primary; 1 -> secondary; else -> tertiary }
                val top = Path()
                val steps = 24
                for (s in 0..steps) {
                    val x = w * s / steps
                    val y = h * (0.10f + 0.10f * b) + sin(s * 0.45f + t * 0.45f + b * 2f) * h * 0.04f
                    if (s == 0) top.moveTo(x, y) else top.lineTo(x, y)
                }
                for (s in steps downTo 0) {
                    val x = w * s / steps
                    val y = h * (0.10f + 0.10f * b) + h * 0.20f + sin(s * 0.3f - t * 0.35f + b) * h * 0.05f
                    top.lineTo(x, y)
                }
                top.close()
                drawPath(top, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), h * 0.05f, h * 0.55f))
            }
            MrttEffect.NONE -> Unit
        }
    }
}
