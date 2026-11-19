package com.mrtt.client.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.mrttGlass(shape: Shape, tint: Color? = null): Modifier {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > 0.5f
    val accent = scheme.primary

    val high: Color
    val low: Color
    if (tint != null) {
        // Цветное стекло (кнопки, выбранные элементы): заметная подкраска.
        high = tint.copy(alpha = if (light) 0.22f else 0.30f)
        low = tint.copy(alpha = if (light) 0.12f else 0.16f)
    } else if (light) {
        // Светлое стекло берёт цвет карточки стиля, а не белый.
        high = lerp(scheme.surface, accent, 0.06f).copy(alpha = 0.92f)
        low = lerp(scheme.surfaceVariant, accent, 0.05f).copy(alpha = 0.78f)
    } else {
        high = lerp(scheme.surfaceVariant, accent, 0.10f).copy(alpha = 0.62f)
        low = lerp(scheme.surface, accent, 0.06f).copy(alpha = 0.50f)
    }

    val line = tint ?: if (light) scheme.onSurface else scheme.onSurface
    val rimTop = lerp(line, accent, 0.25f).copy(alpha = if (tint != null) 0.55f else if (light) 0.20f else 0.26f)
    val rimBottom = line.copy(alpha = if (tint != null) 0.28f else if (light) 0.10f else 0.12f)

    return this
        .background(Brush.verticalGradient(listOf(high, low)), shape)
        .border(0.9.dp, Brush.verticalGradient(listOf(rimTop, rimBottom)), shape)
}
