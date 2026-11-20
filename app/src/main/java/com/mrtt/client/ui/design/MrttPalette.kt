package com.mrtt.client.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

private val InkOnLight = Color(0xFF101014)

private fun contrast(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05f) / (lo + 0.05f)
}

fun mrttOn(background: Color): Color =
    if (contrast(Color.White, background) >= contrast(InkOnLight, background)) Color.White else InkOnLight

fun mrttScheme(
    dark: Boolean,
    primary: Long,
    secondary: Long,
    tertiary: Long,
    background: Long,
    surface: Long,
    surfaceVariant: Long,
    text: Long,
    textMuted: Long,
    outline: Long,
    outlineVariant: Long,
): ColorScheme {
    val p = Color(primary)
    val s = Color(secondary)
    val t = Color(tertiary)
    val bg = Color(background)
    val sf = Color(surface)
    val sv = Color(surfaceVariant)
    val mix = if (dark) 0.34f else 0.20f
    fun container(c: Color) = lerp(if (dark) bg else Color.White, c, mix + if (dark) 0.0f else 0.12f)
    val pc = container(p)
    val sc = container(s)
    val tc = container(t)
    return if (dark) {
        darkColorScheme(
            primary = p, onPrimary = mrttOn(p),
            primaryContainer = pc, onPrimaryContainer = mrttOn(pc),
            secondary = s, onSecondary = mrttOn(s),
            secondaryContainer = sc, onSecondaryContainer = mrttOn(sc),
            tertiary = t, onTertiary = mrttOn(t),
            tertiaryContainer = tc, onTertiaryContainer = mrttOn(tc),
            background = bg, onBackground = Color(text),
            surface = sf, onSurface = Color(text),
            surfaceVariant = sv, onSurfaceVariant = Color(textMuted),
            outline = Color(outline), outlineVariant = Color(outlineVariant),
            inverseSurface = Color(text), inverseOnSurface = bg,
            surfaceTint = p,
        )
    } else {
        lightColorScheme(
            primary = p, onPrimary = mrttOn(p),
            primaryContainer = pc, onPrimaryContainer = mrttOn(pc),
            secondary = s, onSecondary = mrttOn(s),
            secondaryContainer = sc, onSecondaryContainer = mrttOn(sc),
            tertiary = t, onTertiary = mrttOn(t),
            tertiaryContainer = tc, onTertiaryContainer = mrttOn(tc),
            background = bg, onBackground = Color(text),
            surface = sf, onSurface = Color(text),
            surfaceVariant = sv, onSurfaceVariant = Color(textMuted),
            outline = Color(outline), outlineVariant = Color(outlineVariant),
            inverseSurface = Color(text), inverseOnSurface = bg,
            surfaceTint = p,
        )
    }
}

fun mrttBlob(x: Float, y: Float, radius: Float, argb: Long) =
    MrttBackdropBlob(x = x, y = y, radius = radius, color = Color(argb))
