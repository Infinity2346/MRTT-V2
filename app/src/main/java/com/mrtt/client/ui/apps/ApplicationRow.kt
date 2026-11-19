package com.mrtt.client.ui

import com.mrtt.client.ui.design.MrttStyleShapes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrtt.client.ui.apps.AppRoute
import com.mrtt.client.ui.design.MrttSpacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.luminance

@Immutable
internal data class AppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
)

/**
 * Строка приложения. Краткое нажатие только ставит или снимает галочку — то
 * есть выбирает приложение в белый/чёрный список. Режим не меняется сам по
 * себе: он настраивается отдельно, долгим нажатием по уже отмеченному
 * приложению. Так список остаётся таким же, как раньше, и ничего не
 * «включается» само.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(
    app: AppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    route: AppRoute = AppRoute.Tunnel,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    // Подсветка — только у реально отмеченного приложения. Галочка никогда не
    // появляется сама из-за выбранного режима.
    val selected = isSelected

    val surfaceColor = if (selected) {
        colors.primaryContainer.copy(alpha = if (isDark) 0.65f else 0.85f)
    } else {
        colors.surface.copy(alpha = if (isDark) 0.50f else 0.65f)
    }

    val borderColor = if (selected) {
        colors.primary.copy(alpha = 0.50f)
    } else {
        colors.outlineVariant.copy(alpha = if (isDark) 0.28f else 0.35f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .combinedClickable(
                role = Role.Checkbox,
                onClick = { onClick() },
                onLongClick = onLongClick,
            ),
        shape = MrttStyleShapes.card,
        color = surfaceColor,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MrttSpacing.Md, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(MrttSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(MrttStyleShapes.small),
                )
            } else {
                Box(modifier = Modifier.size(42.dp).background(colors.surfaceVariant, MrttStyleShapes.small))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                // Плашку режима показываем только у отмеченного приложения:
                // пока галочки нет, приложение просто не выбрано.
                if (isSelected) RouteChip(route)
            }

            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** Маленькая плашка с текущим режимом приложения. */
@Composable
private fun RouteChip(route: AppRoute) {
    val colors = MaterialTheme.colorScheme
    val color = when (route) {
        AppRoute.Tunnel -> colors.onSurfaceVariant
        AppRoute.DirectFiltered -> colors.primary
        AppRoute.Direct -> colors.secondary
    }
    Surface(
        shape = MrttStyleShapes.control,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            text = route.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
