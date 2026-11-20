package com.mrtt.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.mrtt.client.ui.design.LocalMrttStyle
import com.mrtt.client.ui.design.drawHeart
import com.mrtt.client.ui.design.drawSparkle
import com.mrtt.client.ui.design.rememberMrttClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun MrttMascot(
    connected: Boolean,
    connecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val styleId = LocalMrttStyle.current.id
    val time = rememberMrttClock()
    // Свой оттенок волос для каждого стиля: розовая, голубая, золотистая, лавандовая.
    val hair = remember(styleId, scheme.primary) {
        when (styleId) {
            "candy" -> Color(0xFF5CA8E8)
            "rosegold" -> Color(0xFFE0A56B)
            "lavender" -> Color(0xFF8E6FE0)
            else -> Color(0xFFF06BAA)
        }
    }
    val hairDark = lerp(hair, Color.Black, 0.22f)
    val hairLight = lerp(hair, Color.White, 0.35f)
    val eye = when (styleId) {
        "candy" -> Color(0xFFE05AA0)
        "rosegold" -> Color(0xFF9C6B8E)
        "lavender" -> Color(0xFFC26FB3)
        else -> Color(0xFF5B8DEF)
    }
    val skin = Color(0xFFFFE6D8)
    val ink = Color(0xFF3A2430)
    val bow = scheme.secondary
    val heartColor = scheme.primary

    Canvas(modifier) {
        val t = time()
        val s = minOf(size.width, size.height)
        val bob = sin(t * 2.1f) * s * 0.018f
        val ox = (size.width - s) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * s, y * s + bob)

        // Хвостики качаются.
        val sway = sin(t * 2.1f + 1f) * 0.02f
        drawCircle(hairDark, s * 0.16f, p(0.12f + sway, 0.66f))
        drawCircle(hairDark, s * 0.16f, p(0.88f - sway, 0.66f))
        drawCircle(hair, s * 0.15f, p(0.12f + sway, 0.64f))
        drawCircle(hair, s * 0.15f, p(0.88f - sway, 0.64f))
        // Задние волосы.
        drawOval(hairDark, p(0.11f, 0.12f), Size(s * 0.78f, s * 0.74f))
        // Лицо.
        drawOval(skin, p(0.19f, 0.27f), Size(s * 0.62f, s * 0.58f))

        // Чёлка: волосы сверху и три зубца вниз.
        drawArc(hair, 180f, 180f, true, p(0.15f, 0.12f), Size(s * 0.70f, s * 0.60f))
        val fringe = Path().apply {
            moveTo(p(0.17f, 0.40f).x, p(0.17f, 0.40f).y)
            lineTo(p(0.30f, 0.52f).x, p(0.30f, 0.52f).y)
            lineTo(p(0.40f, 0.38f).x, p(0.40f, 0.38f).y)
            lineTo(p(0.50f, 0.50f).x, p(0.50f, 0.50f).y)
            lineTo(p(0.60f, 0.38f).x, p(0.60f, 0.38f).y)
            lineTo(p(0.70f, 0.52f).x, p(0.70f, 0.52f).y)
            lineTo(p(0.83f, 0.40f).x, p(0.83f, 0.40f).y)
            lineTo(p(0.83f, 0.30f).x, p(0.83f, 0.30f).y)
            lineTo(p(0.17f, 0.30f).x, p(0.17f, 0.30f).y)
            close()
        }
        drawPath(fringe, hair)
        // Блик на волосах.
        drawArc(hairLight, 200f, 45f, false, p(0.25f, 0.17f), Size(s * 0.5f, s * 0.4f), style = Stroke(s * 0.025f, cap = StrokeCap.Round))

        // Моргание раз в ~3.6 секунды.
        val cycle = t % 3.6f
        val blink = if (cycle < 0.14f) abs(cycle - 0.07f) / 0.07f else 1f
        val look = if (connecting) sin(t * 2.4f) * 0.012f * s else 0f
        val ey = 0.60f
        for (side in intArrayOf(-1, 1)) {
            val c = p(0.5f + side * 0.135f, ey)
            if (connected) {
                // Счастливые «^ ^».
                drawArc(ink, 200f, 140f, false, Offset(c.x - s * 0.05f, c.y - s * 0.03f), Size(s * 0.10f, s * 0.08f), style = Stroke(s * 0.022f, cap = StrokeCap.Round))
            } else {
                val ew = s * 0.058f
                val eh = s * 0.082f * blink.coerceAtLeast(0.08f)
                drawOval(ink, Offset(c.x - ew, c.y - eh), Size(ew * 2f, eh * 2f))
                drawOval(eye, Offset(c.x - ew * 0.82f + look, c.y - eh * 0.9f + eh * 0.15f), Size(ew * 1.64f, eh * 1.7f))
                drawOval(ink, Offset(c.x - ew * 0.38f + look, c.y - eh * 0.45f), Size(ew * 0.76f, eh * 0.9f))
                if (blink > 0.5f) {
                    drawCircle(Color.White, s * 0.017f, Offset(c.x - ew * 0.35f + look, c.y - eh * 0.5f))
                    drawCircle(Color.White.copy(alpha = 0.8f), s * 0.009f, Offset(c.x + ew * 0.35f + look, c.y + eh * 0.35f))
                }
            }
        }
        // Румянец.
        val blush = Color(0xFFFF8FA8).copy(alpha = if (connected) 0.7f else 0.45f)
        drawOval(blush, p(0.25f, 0.665f), Size(s * 0.10f, s * 0.045f))
        drawOval(blush, p(0.65f, 0.665f), Size(s * 0.10f, s * 0.045f))
        // Рот: улыбка при подключении, маленькая «w» в покое.
        val m = p(0.5f, 0.725f)
        if (connected) {
            drawArc(Color(0xFFE0506A), 0f, 180f, true, Offset(m.x - s * 0.04f, m.y - s * 0.02f), Size(s * 0.08f, s * 0.06f))
        } else {
            drawArc(ink, 20f, 140f, false, Offset(m.x - s * 0.035f, m.y - s * 0.02f), Size(s * 0.035f, s * 0.03f), style = Stroke(s * 0.012f, cap = StrokeCap.Round))
            drawArc(ink, 20f, 140f, false, Offset(m.x, m.y - s * 0.02f), Size(s * 0.035f, s * 0.03f), style = Stroke(s * 0.012f, cap = StrokeCap.Round))
        }

        // Бантик на макушке.
        val b = p(0.24f, 0.17f)
        rotate(-18f, b) {
            val wing = Path().apply {
                moveTo(b.x, b.y)
                lineTo(b.x - s * 0.10f, b.y - s * 0.055f)
                lineTo(b.x - s * 0.10f, b.y + s * 0.055f)
                close()
                moveTo(b.x, b.y)
                lineTo(b.x + s * 0.10f, b.y - s * 0.055f)
                lineTo(b.x + s * 0.10f, b.y + s * 0.055f)
                close()
            }
            drawPath(wing, bow)
            drawCircle(lerp(bow, Color.Black, 0.2f), s * 0.026f, b)
        }

        // Сердечки при подключении, искорки при подключении-в-процессе.
        if (connected) {
            repeat(3) { i ->
                val ph = (t * 0.45f + i / 3f) % 1f
                drawHeart(Offset(ox + s * (0.78f + 0.08f * sin(t + i)), s * (0.30f - 0.30f * ph) + bob), s * (0.03f + 0.012f * i), heartColor.copy(alpha = 1f - ph))
            }
        } else if (connecting) {
            repeat(3) { i ->
                val a = 0.5f + 0.5f * sin(t * 4f + i * 2f)
                drawSparkle(Offset(ox + s * (0.14f + 0.36f * i), s * (0.10f + 0.05f * i) + bob), s * 0.035f, heartColor.copy(alpha = a))
            }
        }
    }
}
