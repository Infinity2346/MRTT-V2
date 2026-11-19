package com.mrtt.client.ui

import com.mrtt.client.ui.design.MrttStyleShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrtt.client.ui.design.MrttStyle
import com.mrtt.client.ui.design.MrttStyles

/**
 * Выбор стиля приложения.
 *
 * Стиль — это не только цвет кнопки: он меняет палитру целиком, поэтому в
 * списке сразу видно, как выглядит каждый вариант. Круглые чипы с превью
 * читаются быстрее, чем выпадающий список с названиями.
 */
@Composable
internal fun ThemeStylePicker(
    currentStyle: String,
    isDark: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val styles = MrttStyles.all
    val selected = MrttStyles.byId(currentStyle)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Стиль",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp),
        ) {
            items(styles, key = { it.id }) { style ->
                StyleChip(
                    style = style,
                    isDark = isDark,
                    selected = style.id == selected.id,
                    onClick = { onSelected(style.id) },
                )
            }
        }
    }
}

@Composable
private fun StyleChip(
    style: MrttStyle,
    isDark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = if (isDark) style.dark else style.light
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .clip(MrttStyleShapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(MrttStyleShapes.control)
                .background(
                    Brush.linearGradient(
                        listOf(scheme.primary, scheme.tertiary, scheme.secondary),
                    ),
                )
                .border(border, MrttStyleShapes.control),
            contentAlignment = Alignment.Center,
        ) {
            // Галочка на круге цвета фона: читается на любом градиенте превью.
            Box(
                modifier = Modifier
                    .size(if (selected) 24.dp else 18.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (selected) scheme.background else scheme.surface.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = style.label.substringBefore(" ·"),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Небольшой образец палитры для других экранов (превью в настройках). */
@Composable
internal fun StyleSwatch(style: MrttStyle, isDark: Boolean, modifier: Modifier = Modifier) {
    val scheme = if (isDark) style.dark else style.light
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.surfaceVariant).forEach { color ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(MrttStyleShapes.control)
                    .background(color),
            )
        }
    }
}
