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

@Composable
internal fun HideFingerprintInfoDialog(onDismiss: () -> Unit) {
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
                    "Скрывать порты и трафик",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = """
                        • Слушающий порт VPN больше не занимается по умолчанию: клиент получает свободный порт у системы, поэтому запомнить «порт MRTT» и искать по нему на телефоне нельзя.
                        • Пакеты туннеля маскируются под медиа-поток звонка VK: размер и ритм каждого пакета выглядят как кадр голоса или видео, а не как скачивание файла. Стороннее приложение, наблюдающее за трафиком устройства, видит поток, похожий на обычный звонок.
                        • Имя VPN-сессии, уведомление и его текст обезличены: в системе видно «VPN» без названия приложения и без числа потоков и объёма трафика. Уведомление скрыто с экрана блокировки.
                        • С root — почти как в NekoBox: порт VPN закрывается для выбранных приложений на уровне iptables (они не могут ходить на 127.0.0.1), процесс клиента перестаёт называться libclient.so в списке процессов, а правила снимаются сразу при отключении VPN. Без root остаётся обычный режим: порт случайный, но локально доступен.
                        • Чего не делает даже root-режим: он не вырезает сам VPN-интерфейс из чужих API. Так умеют отдельные модули (LSPosed/Zygisk/ядро) — это другая установка и другие риски.
                        • Режим честно стоит немного скорости: маскировка добавляет несколько байт в каждый пакет и снимает часть экономии на мелочи. Включайте её, если важнее незаметность.
                        • Переключатель применяется при следующем подключении: включение или выключение на ходу поднимает клиент заново.
                        • Это не защита от самого оператора связи: он по-прежнему видит адрес VK и объём трафика. Режим скрывает туннель от других приложений на телефоне и делает пакеты неотличимыми от звонка.
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
