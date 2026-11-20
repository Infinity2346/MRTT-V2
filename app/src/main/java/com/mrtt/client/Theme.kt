package com.mrtt.client

import android.os.Build
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mrtt.client.MrttConstants
import com.mrtt.client.ui.design.MrttDarkExtendedColors
import com.mrtt.client.ui.design.MrttLightExtendedColors
import com.mrtt.client.ui.design.MrttStyleShapes
import com.mrtt.client.ui.design.materialShapesFor
import com.mrtt.client.ui.design.MrttStyles
import com.mrtt.client.ui.design.LocalMrttStyle
import com.mrtt.client.ui.design.LocalMrttBackdrop
import com.mrtt.client.ui.design.LocalMrttGeometry
import com.mrtt.client.ui.design.LocalMrttExtendedColors
import com.mrtt.client.ui.design.tinted

private val Geologica = FontFamily(
    Font(R.font.geologica_regular, FontWeight.Normal),
    Font(R.font.geologica_medium, FontWeight.Medium),
    Font(R.font.geologica_bold, FontWeight.Bold),
)

val MrttTypography = Typography(
    displayLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

object MrttColors {
    val connected = Color(0xFF4CAF50)
    val brand = Color(0xFF00E5C0)
    val brandDeep = Color(0xFF00B8A3)

    val warning = Color(0xFFFFA726)

    val terminalBg = Color(0xFF1A1A2E)
    val terminalBgDark = Color(0xFF0D0D1A)
    val terminalText = Color(0xFFE0E0E0)
    val terminalGreen = Color(0xFF4CAF50)
    val terminalBlue = Color(0xFF42A5F5)
    val terminalCyan = Color(0xFF00BCD4)
    val terminalRed = Color(0xFFEF5350)
    val terminalCounter = Color(0xFF1E88E5)

    val donate = Color(0xFF8B3FFD)
    val telegram = Color(0xFF24A1DE)
}

@Composable
fun MrttAppTheme(
    themeMode: String = "system",
    styleId: String = MrttStyles.DEFAULT_ID,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        MrttConstants.Theme.MODE_DARK -> true
        MrttConstants.Theme.MODE_LIGHT -> false
        else -> isSystemInDarkTheme()
    }

    // Стиль меняет всё оформление сразу: цвета, фон, настроение.
    val style = remember(styleId) { MrttStyles.byId(styleId) }
    val colorScheme = remember(style, darkTheme) {
        if (darkTheme) style.dark else style.light
    }
    // Для светлых стилей чуть приглушаем пятна: иначе они спорят со стеклом.
    val backdrop = remember(style, darkTheme) {
        val base = if (darkTheme) style.darkBackdrop else style.lightBackdrop
        if (darkTheme) base else base.map { it.copy(color = it.color.copy(alpha = it.color.alpha * 0.8f)) }
    }
    // Стиль меняет и форму элементов, а не только цвета.
    val shapes = remember(style) { materialShapesFor(style.geometry) }
    SideEffect { MrttStyleShapes.update(style.geometry) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        @Suppress("DEPRECATION")
        SideEffect {
            val window = (view.context as Activity).window
            val navigationBarColor = Color.Transparent
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = navigationBarColor.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val currentDensity = LocalDensity.current
    val fixedDensity = remember(currentDensity.density) {
        Density(density = currentDensity.density, fontScale = 1f)
    }

    CompositionLocalProvider(
        LocalMrttExtendedColors provides remember(colorScheme, darkTheme) {
            (if (darkTheme) MrttDarkExtendedColors else MrttLightExtendedColors)
                .tinted(colorScheme.primary)
        },
        LocalDensity provides fixedDensity,
        LocalMrttStyle provides style,
        LocalMrttBackdrop provides backdrop,
        LocalMrttGeometry provides style.geometry,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MrttTypography,
            shapes = shapes,
            content = content,
        )
    }
}
