package com.mrtt.client.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Стиль оформления целиком: цвета, фон под стеклом и настроение.
 *
 * Один стиль — это пара схем (светлая и тёмная) плюс свои пятна света на фоне.
 * Приложение не «перекрашивается наполовину»: всё берётся из [ColorScheme],
 * поэтому новый стиль достаточно объявить здесь, а разметку менять не нужно.
 */
@Immutable
data class MrttStyle(
    val id: String,
    val label: String,
    /** Короткое описание, чтобы в настройках было понятно, что выбираешь. */
    val hint: String,
    val light: ColorScheme,
    val dark: ColorScheme,
    val lightBackdrop: List<MrttBackdropBlob>,
    val darkBackdrop: List<MrttBackdropBlob>,
    /** Геометрия: углы карточек и кнопок. */
    val geometry: MrttGeometry = MrttDefaultGeometry,
)

/** Цветное пятно на фоне: координаты и радиус — доли от размера экрана. */
@Immutable
data class MrttBackdropBlob(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
)

internal val LocalMrttStyle = staticCompositionLocalOf { MrttStyles.all.first() }

/** Пятна текущего стиля для текущей темы. */
internal val LocalMrttBackdrop = staticCompositionLocalOf<List<MrttBackdropBlob>> { emptyList() }

object MrttStyles {
    const val DEFAULT_ID = "classic"

    // --- Classic: монохром как в Rawline, чистый белый или чёрный OLED ---
    private val classicLight = lightColorScheme(
        primary = Color(0xFF0A0A0B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6E6EA),
        onPrimaryContainer = Color(0xFF0A0A0B),
        secondary = Color(0xFF0A0A0B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE6E6EA),
        onSecondaryContainer = Color(0xFF0A0A0B),
        tertiary = Color(0xFFD27D00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE4B7),
        onTertiaryContainer = Color(0xFF241400),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF0A0A0B),
        surface = Color(0xFFF3F3F6),
        onSurface = Color(0xFF0A0A0B),
        surfaceVariant = Color(0xFFEDEDF0),
        onSurfaceVariant = Color(0xFF696970),
        outline = Color(0xFF8E8E96),
        outlineVariant = Color(0xFFDDDDE2),
        inverseSurface = Color(0xFF151518),
        inverseOnSurface = Color(0xFFF5F5F7),
        surfaceTint = Color.Transparent,
    )

    private val classicDark = darkColorScheme(
        primary = Color(0xFFF5F5F7),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2A2A2E),
        onPrimaryContainer = Color(0xFFF5F5F7),
        secondary = Color(0xFFF5F5F7),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF2A2A2E),
        onSecondaryContainer = Color(0xFFF5F5F7),
        tertiary = Color(0xFFFFB74D),
        onTertiary = Color(0xFF271500),
        tertiaryContainer = Color(0xFF5A3900),
        onTertiaryContainer = Color(0xFFFFE5BD),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF5F5F7),
        surface = Color(0xFF151518),
        onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF1C1C20),
        onSurfaceVariant = Color(0xFF8E8E96),
        outline = Color(0xFF69696F),
        outlineVariant = Color(0xFF2C2C31),
        inverseSurface = Color(0xFFF5F5F7),
        inverseOnSurface = Color(0xFF151518),
        surfaceTint = Color.Transparent,
    )

    // --- Espresso: тёплые коричневые тона ---
    private val espressoLight = lightColorScheme(
        primary = Color(0xFF6D4C41),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD7CCC8),
        onPrimaryContainer = Color(0xFF3E2723),
        secondary = Color(0xFF8D6E63),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEFEBE9),
        onSecondaryContainer = Color(0xFF4E342E),
        tertiary = Color(0xFF795548),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBCAAA4),
        onTertiaryContainer = Color(0xFF3E2723),
        background = Color(0xFFF2F0EC),
        onBackground = Color(0xFF1C1B1A),
        surface = Color(0xFFFAF8F4),
        onSurface = Color(0xFF1C1B1A),
        surfaceVariant = Color(0xFFEFEBE9),
        onSurfaceVariant = Color(0xFF5D4037),
        outline = Color(0xFFBCAAA4),
        outlineVariant = Color(0xFFD7CCC8),
        surfaceTint = Color(0xFF6D4C41),
    )

    private val espressoDark = darkColorScheme(
        primary = Color(0xFFD7CCC8),
        onPrimary = Color(0xFF3E2723),
        primaryContainer = Color(0xFF5D4037),
        onPrimaryContainer = Color(0xFFEFEBE9),
        secondary = Color(0xFFBCAAA4),
        onSecondary = Color(0xFF3E2723),
        secondaryContainer = Color(0xFF4E342E),
        onSecondaryContainer = Color(0xFFEFEBE9),
        tertiary = Color(0xFFA1887F),
        onTertiary = Color(0xFF3E2723),
        tertiaryContainer = Color(0xFF5D4037),
        onTertiaryContainer = Color(0xFFEFEBE9),
        background = Color(0xFF1A1614),
        onBackground = Color(0xFFEDE0D4),
        surface = Color(0xFF211D1B),
        onSurface = Color(0xFFEDE0D4),
        surfaceVariant = Color(0xFF2C2624),
        onSurfaceVariant = Color(0xFFD7CCC8),
        outline = Color(0xFF8D6E63),
        outlineVariant = Color(0xFF4E342E),
        surfaceTint = Color(0xFFD7CCC8),
    )

    // --- Indigo: спокойный сине-фиолетовый ---
    private val indigoLight = lightColorScheme(
        primary = Color(0xFF4B47A8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2DFFF),
        onPrimaryContainer = Color(0xFF1A1744),
        secondary = Color(0xFF5B588D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE2DFFF),
        onSecondaryContainer = Color(0xFF1A1744),
        tertiary = Color(0xFF8E4585),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD7F2),
        onTertiaryContainer = Color(0xFF33002C),
        background = Color(0xFFFBF8FF),
        onBackground = Color(0xFF1B1B1F),
        surface = Color(0xFFF6F3FA),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = Color(0xFFE4E1EC),
        onSurfaceVariant = Color(0xFF47464F),
        outline = Color(0xFF787680),
        outlineVariant = Color(0xFFC8C5D0),
        surfaceTint = Color(0xFF4B47A8),
    )

    private val indigoDark = darkColorScheme(
        primary = Color(0xFFC4C0FF),
        onPrimary = Color(0xFF2D2A5B),
        primaryContainer = Color(0xFF434073),
        onPrimaryContainer = Color(0xFFE2DFFF),
        secondary = Color(0xFFC4C0FF),
        onSecondary = Color(0xFF2D2A5B),
        secondaryContainer = Color(0xFF434073),
        onSecondaryContainer = Color(0xFFE2DFFF),
        tertiary = Color(0xFFF0ACDF),
        onTertiary = Color(0xFF4D1F45),
        tertiaryContainer = Color(0xFF67355D),
        onTertiaryContainer = Color(0xFFFFD7F2),
        background = Color(0xFF101016),
        onBackground = Color(0xFFE4E1E6),
        surface = Color(0xFF18181F),
        onSurface = Color(0xFFE4E1E6),
        surfaceVariant = Color(0xFF2A2933),
        onSurfaceVariant = Color(0xFFC8C5D0),
        outline = Color(0xFF918F9A),
        outlineVariant = Color(0xFF3B3A44),
        surfaceTint = Color(0xFFC4C0FF),
    )

    // --- Forest: зелёный, глухой и спокойный ---
    private val forestLight = lightColorScheme(
        primary = Color(0xFF2F6B45),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC6EDD3),
        onPrimaryContainer = Color(0xFF062814),
        secondary = Color(0xFF4C6355),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD6E7DA),
        onSecondaryContainer = Color(0xFF0A1F12),
        tertiary = Color(0xFF3E6374),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC2E8FC),
        onTertiaryContainer = Color(0xFF001F2A),
        background = Color(0xFFF7FBF4),
        onBackground = Color(0xFF191C19),
        surface = Color(0xFFF0F5EE),
        onSurface = Color(0xFF191C19),
        surfaceVariant = Color(0xFFDCE5DB),
        onSurfaceVariant = Color(0xFF414942),
        outline = Color(0xFF717970),
        outlineVariant = Color(0xFFC1C9C0),
        surfaceTint = Color(0xFF2F6B45),
    )

    private val forestDark = darkColorScheme(
        primary = Color(0xFF8BD5A3),
        onPrimary = Color(0xFF00391B),
        primaryContainer = Color(0xFF14522F),
        onPrimaryContainer = Color(0xFFC6EDD3),
        secondary = Color(0xFFB4CCBB),
        onSecondary = Color(0xFF203527),
        secondaryContainer = Color(0xFF364B3D),
        onSecondaryContainer = Color(0xFFD6E7DA),
        tertiary = Color(0xFFA6CCE0),
        onTertiary = Color(0xFF073543),
        tertiaryContainer = Color(0xFF254C5B),
        onTertiaryContainer = Color(0xFFC2E8FC),
        background = Color(0xFF0E1511),
        onBackground = Color(0xFFDFE4DD),
        surface = Color(0xFF151C17),
        onSurface = Color(0xFFDFE4DD),
        surfaceVariant = Color(0xFF28312A),
        onSurfaceVariant = Color(0xFFBFC9C0),
        outline = Color(0xFF89938A),
        outlineVariant = Color(0xFF3C463E),
        surfaceTint = Color(0xFF8BD5A3),
    )

    // --- Ocean: прохладный бирюзово-синий ---
    private val oceanLight = lightColorScheme(
        primary = Color(0xFF00677A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFAFECF9),
        onPrimaryContainer = Color(0xFF001F26),
        secondary = Color(0xFF4A6267),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDE7EC),
        onSecondaryContainer = Color(0xFF051F24),
        tertiary = Color(0xFF565D7E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDDE1FF),
        onTertiaryContainer = Color(0xFF131A37),
        background = Color(0xFFF5FAFB),
        onBackground = Color(0xFF171D1E),
        surface = Color(0xFFEEF4F6),
        onSurface = Color(0xFF171D1E),
        surfaceVariant = Color(0xFFD6E6EA),
        onSurfaceVariant = Color(0xFF3F494B),
        outline = Color(0xFF6F797B),
        outlineVariant = Color(0xFFBEC8CA),
        surfaceTint = Color(0xFF00677A),
    )

    private val oceanDark = darkColorScheme(
        primary = Color(0xFF55D6EF),
        onPrimary = Color(0xFF003641),
        primaryContainer = Color(0xFF004E5C),
        onPrimaryContainer = Color(0xFFAFECF9),
        secondary = Color(0xFFB1CBD0),
        onSecondary = Color(0xFF1C3438),
        secondaryContainer = Color(0xFF334B4F),
        onSecondaryContainer = Color(0xFFCDE7EC),
        tertiary = Color(0xFFBEC5EA),
        onTertiary = Color(0xFF282F4D),
        tertiaryContainer = Color(0xFF3F4565),
        onTertiaryContainer = Color(0xFFDDE1FF),
        background = Color(0xFF061417),
        onBackground = Color(0xFFDDE3E5),
        surface = Color(0xFF0E1A1D),
        onSurface = Color(0xFFDDE3E5),
        surfaceVariant = Color(0xFF233033),
        onSurfaceVariant = Color(0xFFBEC8CA),
        outline = Color(0xFF889295),
        outlineVariant = Color(0xFF394547),
        surfaceTint = Color(0xFF55D6EF),
    )

    // --- Anime / sakura: пастель, розовый и лаванда, мягкий градиентный фон ---
    private val animeLight = lightColorScheme(
        primary = Color(0xFFD5378A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9EA),
        onPrimaryContainer = Color(0xFF3C0025),
        secondary = Color(0xFF8A5CD6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEADDFF),
        onSecondaryContainer = Color(0xFF23005C),
        tertiary = Color(0xFF2E8FD0),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD3E9FF),
        onTertiaryContainer = Color(0xFF001D33),
        background = Color(0xFFFFF6FB),
        onBackground = Color(0xFF231920),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF231920),
        surfaceVariant = Color(0xFFFFE4F1),
        onSurfaceVariant = Color(0xFF6B4A5C),
        outline = Color(0xFFB18CA0),
        outlineVariant = Color(0xFFF3D3E3),
        surfaceTint = Color(0xFFD5378A),
    )

    private val animeDark = darkColorScheme(
        primary = Color(0xFFFF9BD0),
        onPrimary = Color(0xFF4C0030),
        primaryContainer = Color(0xFF6E1A4A),
        onPrimaryContainer = Color(0xFFFFD9EA),
        secondary = Color(0xFFD5BBFF),
        onSecondary = Color(0xFF382070),
        secondaryContainer = Color(0xFF4E3488),
        onSecondaryContainer = Color(0xFFEADDFF),
        tertiary = Color(0xFF9ED2FF),
        onTertiary = Color(0xFF00304E),
        tertiaryContainer = Color(0xFF10456B),
        onTertiaryContainer = Color(0xFFD3E9FF),
        background = Color(0xFF17101A),
        onBackground = Color(0xFFFFEAF4),
        surface = Color(0xFF1F1623),
        onSurface = Color(0xFFFFEAF4),
        surfaceVariant = Color(0xFF36243A),
        onSurfaceVariant = Color(0xFFE6C3D5),
        outline = Color(0xFFA98BA0),
        outlineVariant = Color(0xFF4E3A4E),
        surfaceTint = Color(0xFFFF9BD0),
    )

    // --- Cyberpunk: неон на почти чёрном ---
    private val cyberLight = lightColorScheme(
        primary = Color(0xFFB0128C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD8F3),
        onPrimaryContainer = Color(0xFF3B002F),
        secondary = Color(0xFF00697B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFABEDFF),
        onSecondaryContainer = Color(0xFF001F26),
        tertiary = Color(0xFF8A6A00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE48F),
        onTertiaryContainer = Color(0xFF2A1D00),
        background = Color(0xFFFDF7FE),
        onBackground = Color(0xFF1F1A24),
        surface = Color(0xFFF8F1FA),
        onSurface = Color(0xFF1F1A24),
        surfaceVariant = Color(0xFFEEDFF3),
        onSurfaceVariant = Color(0xFF4E4256),
        outline = Color(0xFF7F7288),
        outlineVariant = Color(0xFFD3C5D9),
        surfaceTint = Color(0xFFB0128C),
    )

    private val cyberDark = darkColorScheme(
        primary = Color(0xFFFF4FD8),
        onPrimary = Color(0xFF3B002F),
        primaryContainer = Color(0xFF7A0060),
        onPrimaryContainer = Color(0xFFFFD8F3),
        secondary = Color(0xFF3FE0FF),
        onSecondary = Color(0xFF00363F),
        secondaryContainer = Color(0xFF00505E),
        onSecondaryContainer = Color(0xFFABEDFF),
        tertiary = Color(0xFFFFD84D),
        onTertiary = Color(0xFF3A2E00),
        tertiaryContainer = Color(0xFF544400),
        onTertiaryContainer = Color(0xFFFFE48F),
        background = Color(0xFF07040C),
        onBackground = Color(0xFFF2E4FA),
        surface = Color(0xFF100A18),
        onSurface = Color(0xFFF2E4FA),
        surfaceVariant = Color(0xFF221733),
        onSurfaceVariant = Color(0xFFD8C4E8),
        outline = Color(0xFF8F7AA6),
        outlineVariant = Color(0xFF3A2B4E),
        surfaceTint = Color(0xFFFF4FD8),
    )

    // --- Matrix: зелёный терминал ---
    private val matrixLight = lightColorScheme(
        primary = Color(0xFF1B6B3A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBCF0C8),
        onPrimaryContainer = Color(0xFF00210D),
        secondary = Color(0xFF4E6352),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD0E8D2),
        onSecondaryContainer = Color(0xFF0C1F12),
        tertiary = Color(0xFF3A6470),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBEE9F6),
        onTertiaryContainer = Color(0xFF001F27),
        background = Color(0xFFF3FAF2),
        onBackground = Color(0xFF111A12),
        surface = Color(0xFFEAF4E9),
        onSurface = Color(0xFF111A12),
        surfaceVariant = Color(0xFFD5E7D4),
        onSurfaceVariant = Color(0xFF3D4B3E),
        outline = Color(0xFF6D7B6E),
        outlineVariant = Color(0xFFBDCBBD),
        surfaceTint = Color(0xFF1B6B3A),
    )

    private val matrixDark = darkColorScheme(
        primary = Color(0xFF57F287),
        onPrimary = Color(0xFF00391B),
        primaryContainer = Color(0xFF0C5227),
        onPrimaryContainer = Color(0xFFBCF0C8),
        secondary = Color(0xFFB4CCB7),
        onSecondary = Color(0xFF1F3524),
        secondaryContainer = Color(0xFF344C38),
        onSecondaryContainer = Color(0xFFD0E8D2),
        tertiary = Color(0xFFA2CDDB),
        onTertiary = Color(0xFF03313D),
        tertiaryContainer = Color(0xFF204C57),
        onTertiaryContainer = Color(0xFFBEE9F6),
        background = Color(0xFF020A05),
        onBackground = Color(0xFFD6E5D7),
        surface = Color(0xFF071109),
        onSurface = Color(0xFFD6E5D7),
        surfaceVariant = Color(0xFF15241A),
        onSurfaceVariant = Color(0xFFB5C4B6),
        outline = Color(0xFF7E8E80),
        outlineVariant = Color(0xFF2E3F32),
        surfaceTint = Color(0xFF57F287),
    )

    // --- Sunset: оранжевый и розовый закат ---
    private val sunsetLight = lightColorScheme(
        primary = Color(0xFFB34700),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBC8),
        onPrimaryContainer = Color(0xFF3A1000),
        secondary = Color(0xFF9C4147),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDADA),
        onSecondaryContainer = Color(0xFF40000B),
        tertiary = Color(0xFF7B4E9E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEFD9FF),
        onTertiaryContainer = Color(0xFF2B0B44),
        background = Color(0xFFFFF8F4),
        onBackground = Color(0xFF231917),
        surface = Color(0xFFFEF1E9),
        onSurface = Color(0xFF231917),
        surfaceVariant = Color(0xFFF5DED2),
        onSurfaceVariant = Color(0xFF53433C),
        outline = Color(0xFF85736B),
        outlineVariant = Color(0xFFD8C2B9),
        surfaceTint = Color(0xFFB34700),
    )

    private val sunsetDark = darkColorScheme(
        primary = Color(0xFFFFB68C),
        onPrimary = Color(0xFF5C1B00),
        primaryContainer = Color(0xFF8A3400),
        onPrimaryContainer = Color(0xFFFFDBC8),
        secondary = Color(0xFFFFB3B4),
        onSecondary = Color(0xFF5D1A21),
        secondaryContainer = Color(0xFF7A3036),
        onSecondaryContainer = Color(0xFFFFDADA),
        tertiary = Color(0xFFD6BAFF),
        onTertiary = Color(0xFF42215F),
        tertiaryContainer = Color(0xFF593779),
        onTertiaryContainer = Color(0xFFEFD9FF),
        background = Color(0xFF17100D),
        onBackground = Color(0xFFF0E0D9),
        surface = Color(0xFF1F1714),
        onSurface = Color(0xFFF0E0D9),
        surfaceVariant = Color(0xFF3A2C26),
        onSurfaceVariant = Color(0xFFD8C2B9),
        outline = Color(0xFF9E8B82),
        outlineVariant = Color(0xFF4C3E37),
        surfaceTint = Color(0xFFFFB68C),
    )

    // Фоновые пятна: доля ширины/высоты, доля экрана и цвет.
    private fun blob(x: Float, y: Float, radius: Float, argb: Long) = MrttBackdropBlob(
        x = x,
        y = y,
        radius = radius,
        color = Color(
            red = ((argb shr 16) and 0xFF).toInt(),
            green = ((argb shr 8) and 0xFF).toInt(),
            blue = (argb and 0xFF).toInt(),
            alpha = ((argb shr 24) and 0xFF).toInt(),
        ),
    )

    val all: List<MrttStyle> = listOf(
        MrttStyle(
            id = "classic",
            label = "Rawline · моно",
            hint = "Чистый чёрный или белый без акцентов",
            light = classicLight,
            dark = classicDark,
            lightBackdrop = listOf(
                blob(0.92f, 0.10f, 0.85f, 0x1A46465A),
                blob(0.02f, 0.48f, 0.80f, 0x1A46465A),
            ),
            darkBackdrop = emptyList(),
        ),
        MrttStyle(
            id = "espresso",
            label = "Эспрессо",
            hint = "Тёплые кофейные тона",
            light = espressoLight,
            dark = espressoDark,
            lightBackdrop = listOf(
                blob(0.90f, 0.08f, 0.85f, 0x33D7CCC8),
                blob(0.05f, 0.55f, 0.80f, 0x33795548),
            ),
            darkBackdrop = listOf(
                blob(0.90f, 0.08f, 0.85f, 0x33000000),
                blob(0.05f, 0.55f, 0.80f, 0x2B4E342E),
            ),
        ),
        MrttStyle(
            id = "indigo",
            label = "Индиго",
            hint = "Сине-фиолетовый, ночной",
            light = indigoLight,
            dark = indigoDark,
            lightBackdrop = listOf(
                blob(0.88f, 0.10f, 0.90f, 0x2E7C74E8),
                blob(0.05f, 0.50f, 0.80f, 0x2E8E4585),
            ),
            darkBackdrop = listOf(
                blob(0.88f, 0.10f, 0.95f, 0x33201C52),
                blob(0.05f, 0.55f, 0.80f, 0x2E3B1F45),
            ),
        ),
        MrttStyle(
            id = "forest",
            label = "Лес",
            hint = "Зелёный, спокойный",
            light = forestLight,
            dark = forestDark,
            lightBackdrop = listOf(
                blob(0.90f, 0.08f, 0.85f, 0x302F6B45),
                blob(0.06f, 0.55f, 0.80f, 0x263E6374),
            ),
            darkBackdrop = listOf(
                blob(0.90f, 0.08f, 0.90f, 0x33145320),
                blob(0.06f, 0.55f, 0.80f, 0x2B10302A),
            ),
        ),
        MrttStyle(
            id = "ocean",
            label = "Океан",
            hint = "Бирюза и глубокий синий",
            light = oceanLight,
            dark = oceanDark,
            lightBackdrop = listOf(
                blob(0.90f, 0.06f, 0.90f, 0x2E00A0BE),
                blob(0.04f, 0.52f, 0.80f, 0x26565D7E),
            ),
            darkBackdrop = listOf(
                blob(0.90f, 0.06f, 0.95f, 0x33004E5C),
                blob(0.04f, 0.52f, 0.80f, 0x2B103A54),
            ),
        ),
        MrttStyle(
            id = "anime",
            label = "Аниме",
            hint = "Сакура, лаванда и мягкое свечение",
            light = animeLight,
            dark = animeDark,
            geometry = MrttSoftGeometry,
            lightBackdrop = listOf(
                blob(0.92f, 0.04f, 0.95f, 0x40FF9BD0),
                blob(0.02f, 0.42f, 0.90f, 0x3BD5BBFF),
                blob(0.72f, 0.92f, 0.85f, 0x339ED2FF),
            ),
            darkBackdrop = listOf(
                blob(0.92f, 0.04f, 1.00f, 0x4D6E1A4A),
                blob(0.02f, 0.42f, 0.95f, 0x4D4E3488),
                blob(0.72f, 0.92f, 0.85f, 0x3310456B),
            ),
        ),
        MrttStyle(
            id = "cyber",
            label = "Киберпанк",
            hint = "Неоновый розовый и циан",
            light = cyberLight,
            dark = cyberDark,
            geometry = MrttSharpGeometry,
            lightBackdrop = listOf(
                blob(0.95f, 0.02f, 0.90f, 0x33FF4FD8),
                blob(0.00f, 0.46f, 0.85f, 0x333FE0FF),
            ),
            darkBackdrop = listOf(
                blob(0.95f, 0.02f, 1.05f, 0x4D7A0060),
                blob(0.00f, 0.46f, 0.95f, 0x4000505E),
                blob(0.60f, 0.95f, 0.80f, 0x332B0A45),
            ),
        ),
        MrttStyle(
            id = "matrix",
            label = "Матрица",
            hint = "Зелёный терминал на чёрном",
            light = matrixLight,
            dark = matrixDark,
            geometry = MrttSharpGeometry,
            lightBackdrop = listOf(
                blob(0.90f, 0.05f, 0.90f, 0x2E1B6B3A),
                blob(0.04f, 0.50f, 0.80f, 0x263A6470),
            ),
            darkBackdrop = listOf(
                blob(0.90f, 0.05f, 1.00f, 0x400C5227),
                blob(0.04f, 0.50f, 0.85f, 0x33204C57),
            ),
        ),
        MrttStyle(
            id = "sunset",
            label = "Закат",
            hint = "Тёплый оранжевый с розовым",
            light = sunsetLight,
            dark = sunsetDark,
            lightBackdrop = listOf(
                blob(0.92f, 0.02f, 0.95f, 0x3BFFB68C),
                blob(0.02f, 0.46f, 0.85f, 0x33FFB3B4),
                blob(0.70f, 0.94f, 0.85f, 0x33D6BAFF),
            ),
            darkBackdrop = listOf(
                blob(0.92f, 0.02f, 1.00f, 0x4D8A3400),
                blob(0.02f, 0.46f, 0.90f, 0x4D7A3036),
                blob(0.70f, 0.94f, 0.85f, 0x33593779),
            ),
        ),
    ) + MrttExtraStyles

    /** Старые ключи из сохранённых настроек тоже должны открываться. */
    fun byId(id: String?): MrttStyle = when (id) {
        null, "", "brand", "pure_black" -> byId(DEFAULT_ID)
        else -> all.firstOrNull { it.id == id } ?: all.first()
    }
}

/** Для выпадающего списка: ключ → название. */
val MrttStyleOptions: List<Pair<String, String>> =
    MrttStyles.all.map { it.id to it.label }
