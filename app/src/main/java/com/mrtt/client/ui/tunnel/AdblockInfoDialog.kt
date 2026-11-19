package com.mrtt.client.ui.tunnel

import com.mrtt.client.ui.design.MrttStyleShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.mrtt.client.ui.components.MrttGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mrtt.client.ui.appSectionCardBorderColor
import com.mrtt.client.ui.appSectionCardColor
import com.mrtt.client.ui.design.MrttSizes

/** Пояснение к переключателю Adblock: что именно фильтруется и чего он не делает. */
@Composable
internal fun AdblockInfoDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MrttStyleShapes.dialog,
            color = appSectionCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Adblock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = """
                        • Фильтр работает на уровне DNS внутри туннеля: запрос к рекламному или трекерному домену получает локальный пустой ответ и не уходит ни через VK/MAX/Jitsi, ни напрямую.
                        • Список — встроенные домены рекламных и трекерных сетей (около 76 000), хранится внутри APK и не требует интернета. Google, двойной клик, рекламные сети и трекеры получают отказ.
                        • Шифрованный DNS обрывается только при включённом наборе «Шифрованный DNS»: иначе приложение обошло бы список, спросив адрес рекламного домена по HTTPS. Пока набор не включён, системный «Приватный DNS» работает как обычно — это важно, потому что при нём обрыв ломает разрешение имён у всех приложений сразу. Обычный DNS внутри туннеля фильтруется всегда.
                        • Блокируются домен и все его поддомены. Реклама YouTube идёт с тех же CDN, что и видео (googlevideo.com), поэтому доменом её не отличить — для неё нужен HTTP-фильтр, он замедляет соединение. Здесь отсекается то, что приходит с отдельных рекламных доменов.
                        • Переключатель применяется при следующем подключении: включение или выключение на ходу поднимает клиент заново.
                        • Если сайт перестал открываться, проверьте, не в списке ли его рекламный домен, и отключите Adblock для проверки.
                        • Свои домены, ссылки на списки и готовые наборы MetaCubeX (мировая и российская реклама, телеметрия, трекеры) — на вкладке «Блок» в нижнем меню. Там же кнопка «Обновить списки». Изменения применяются при следующем подключении.
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                    shape = MrttStyleShapes.control,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Text(
                        "Понятно",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
