package com.mrtt.client.ui

import com.mrtt.client.ui.design.MrttStyleShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.mrtt.client.ui.components.MrttGlassButton as Button
import com.mrtt.client.ui.design.MrttSizes

@Composable
internal fun AutoConnectInfoDialog(onDismiss: () -> Unit) {
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Автоподключение при запуске",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Когда функция включена, MRTT сам поднимает VPN при открытии приложения — как будто вы нажали кнопку подключения.\n\n" +
                        "Если вы выключили туннель вручную и закрыли приложение, при следующем запуске он включится снова: " +
                        "настройка означает «при входе в MRTT VPN должен работать».\n\n" +
                        "Автоподключение не срабатывает, если нет сохранённого профиля, не выдано VPN-разрешение " +
                        "или уже включена другая блокировка: в этих случаях MRTT просто откроется без подключения.\n\n" +
                        "По умолчанию функция выключена.",
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
                    Text("Понятно", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
