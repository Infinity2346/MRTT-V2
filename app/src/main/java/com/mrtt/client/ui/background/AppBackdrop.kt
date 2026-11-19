package com.mrtt.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mrtt.client.ui.design.LocalMrttBackdrop
import com.mrtt.client.ui.design.LocalMrttStyle
import com.mrtt.client.ui.design.MrttBackdropEffect
import com.mrtt.client.ui.design.mrttEffectFor

@Composable
internal fun AppBackdrop(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val blobs = LocalMrttBackdrop.current
    val effect = mrttEffectFor(LocalMrttStyle.current.id)
    // Пятна света берутся из выбранного стиля, а поверх них живёт анимация стиля:
    // лепестки сакуры, дождь кода, северное сияние, светлячки и так далее.
    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().background(colors.background)) {
            blobs.forEach { blob ->
                drawRect(
                    Brush.radialGradient(
                        listOf(blob.color, Color.Transparent),
                        Offset(size.width * blob.x, size.height * blob.y),
                        size.width * blob.radius,
                    ),
                )
            }
        }
        MrttBackdropEffect(effect, Modifier.fillMaxSize())
    }
}

/** Не используется напрямую, но оставлен для предпросмотра фона в дизайне. */
@Composable
internal fun AppBackdropPreview(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
}
