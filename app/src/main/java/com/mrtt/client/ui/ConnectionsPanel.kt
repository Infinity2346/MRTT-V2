package com.mrtt.client.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrtt.client.BatterySaver
import com.mrtt.client.LogLevel
import com.mrtt.client.MrttConstants
import com.mrtt.client.SettingsStore
import com.mrtt.client.TrafficMonitor
import com.mrtt.client.TunnelManager
import com.mrtt.client.ui.components.MrttSpeedChart
import com.mrtt.client.ui.components.MrttSpeedReadout
import com.mrtt.client.ui.design.MrttSpacing
import kotlin.math.ceil

@Composable
internal fun ConnectionsPanel(settingsStore: SettingsStore, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val totals by TrafficMonitor.totals.collectAsStateWithLifecycle()
    val ready by TrafficMonitor.readyWorkers.collectAsStateWithLifecycle()
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val targetWorkers by TunnelManager.targetWorkers.collectAsStateWithLifecycle()
    val uptime by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    val batterySaving by BatterySaver.active.collectAsStateWithLifecycle()
    val mtu by settingsStore.tunnelMtu.collectAsStateWithLifecycle(initialValue = MrttConstants.Vpn.DEFAULT_MTU)
    val context = LocalContext.current
    var network by remember { mutableStateOf(readNetwork(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            network = readNetwork(context)
            kotlinx.coroutines.delay(3_000)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MrttSpacing.Md),
    ) {
        AppSectionCard {
            SectionTitle("Скорость")
            MrttSpeedReadout()
            MrttSpeedChart(height = 140.dp)
            Text(
                "Последние 60 секунд · ↓ от сервера к вам, ↑ от вас",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }

        AppSectionCard {
            SectionTitle("Статистика сессии")
            StatRow("Скачано", TrafficMonitor.formatBytes(totals.downBytes))
            StatRow("Отправлено", TrafficMonitor.formatBytes(totals.upBytes))
            StatRow("Пик ↓ / ↑", TrafficMonitor.formatSpeed(totals.peakDownBps) + " / " + TrafficMonitor.formatSpeed(totals.peakUpBps))
            StatRow("Средняя ↓ / ↑", TrafficMonitor.formatSpeed(totals.avgDownBps) + " / " + TrafficMonitor.formatSpeed(totals.avgUpBps))
            StatRow("Активных соединений", totals.activeConnections.toString())
            StatRow("Время работы", formatDuration(uptime))
        }

        AppSectionCard {
            SectionTitle(if (running) "Потоки: $activeWorkers из $targetWorkers" else "Потоки")
            if (targetWorkers > 0) {
                // Пока туннель выключен, ни один поток не поднят: все точки тусклые.
                WorkerDots(
                    target = targetWorkers,
                    ready = ready,
                    activeCount = activeWorkers,
                    running = running,
                )
                Text(
                    if (running) {
                        "Каждая точка — один поток через звонок. Яркая — поток поднят, тусклая — ещё нет. " +
                            "Один поток даёт порядка 2 Мбит/с, поэтому чем больше ярких точек, тем выше потолок скорости."
                    } else {
                        "Туннель не запущен — потоки погашены."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Text("Туннель не запущен", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }

        AppSectionCard {
            SectionTitle("Сеть и настройки")
            StatRow("Сеть телефона", network.label)
            StatRow("Оценка канала Android", network.bandwidth)
            StatRow("MTU туннеля", mtu.toString())
            StatRow("Экономия батареи", if (batterySaving) "работает (туннель может засыпать)" else "не мешает")
        }

        val advice = diagnose(
            running = running,
            activeWorkers = activeWorkers,
            readyCount = maxOf(ready.size, activeWorkers),
            targetWorkers = targetWorkers,
            peakDownBps = totals.peakDownBps,
            lastDownBps = totals.lastDownBps,
            batterySaving = batterySaving,
            mtu = mtu,
            network = network,
        )
        AppSectionCard {
            SectionTitle("Почему может быть медленно")
            advice.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
            }
        }

        val errors = TunnelManager.logs.filter { it.isError || it.level == LogLevel.ERR }.takeLast(5)
        if (errors.isNotEmpty()) {
            AppSectionCard {
                SectionTitle("Последние ошибки")
                errors.forEach {
                    Text(
                        text = it.message.take(160),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WorkerDots(target: Int, ready: Set<Int>, activeCount: Int, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cell = 14.dp
        val columns = (maxWidth / cell).toInt().coerceAtLeast(1)
        val rows = ceil(target / columns.toFloat()).toInt().coerceAtLeast(1)
        Canvas(modifier = Modifier.fillMaxWidth().height(cell * rows)) {
            val step = cell.toPx()
            // Если события Ready не приходили по номерам, закрашиваем первые activeCount точек.
            val byNumber = ready.isNotEmpty() && ready.size >= activeCount
            for (i in 0 until target) {
                // Туннель выключен — светиться нечему, даже если номера потоков
                // остались в памяти от прошлой сессии.
                val on = running && if (byNumber) (i + 1) in ready else i < activeCount
                val cx = (i % columns) * step + step / 2f
                val cy = (i / columns) * step + step / 2f
                drawRoundRect(
                    color = if (on) scheme.primary else scheme.onSurface.copy(alpha = 0.14f),
                    topLeft = Offset(cx - step * 0.34f, cy - step * 0.34f),
                    size = Size(step * 0.68f, step * 0.68f),
                    cornerRadius = CornerRadius(step * 0.2f),
                )
            }
        }
    }
}

private data class NetworkInfo(val label: String, val bandwidth: String, val mobile: Boolean, val slowKbps: Boolean)

private fun readNetwork(context: Context): NetworkInfo = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Underlying-сеть: у VPN транспорт один, поэтому ищем физическую.
    val physical = manager.allNetworks.mapNotNull { network ->
        manager.getNetworkCapabilities(network)?.takeIf {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.firstOrNull()
    if (physical == null) {
        NetworkInfo("нет сети", "неизвестно", mobile = false, slowKbps = false)
    } else {
        val label = when {
            physical.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            physical.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            physical.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "другая"
        }
        val down = physical.linkDownstreamBandwidthKbps
        val text = when {
            down <= 0 -> "неизвестно"
            down >= 1000 -> "до ${down / 1000} Мбит/с"
            else -> "до $down Кбит/с"
        }
        NetworkInfo(label, text, physical.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), down in 1..5000)
    }
}.getOrDefault(NetworkInfo("неизвестно", "неизвестно", mobile = false, slowKbps = false))

private fun diagnose(
    running: Boolean,
    activeWorkers: Int,
    readyCount: Int,
    targetWorkers: Int,
    peakDownBps: Float,
    lastDownBps: Float,
    batterySaving: Boolean,
    mtu: Int,
    network: NetworkInfo,
): List<String> {
    if (!running) return listOf("Туннель выключен: включите его на главном экране, тогда здесь появятся подсказки.")
    val out = mutableListOf<String>()
    if (batterySaving) {
        out += "Включена «Экономия батареи»: туннель может засыпать, загрузки при этом встают. Отключите её в настройках."
    }
    if (activeWorkers == 0) {
        out += "Нет живых потоков: сервер или звонок недоступны. Смотрите журнал выше и проверьте сервер."
    } else if (targetWorkers > 0 && readyCount < targetWorkers * 0.6f) {
        out += "Поднято $readyCount из $targetWorkers потоков: потолок скорости ниже ожидаемого. Подождите минуту или переподключитесь."
    }
    if (mtu > MrttConstants.Vpn.DEFAULT_MTU) {
        out += "MTU $mtu больше 1280: пакеты делятся на два кадра, потери растут. Вернитесь на 1280."
    }
    if (network.slowKbps) {
        out += "Сама сеть телефона медленная (${network.bandwidth}): VPN быстрее её не сделает. Проверьте сигнал или Wi-Fi."
    }
    val mbit = peakDownBps * 8f / 1_000_000f
    if (activeWorkers >= 9 && mbit in 0.2f..3f && lastDownBps * 8f < 3_000_000f) {
        out += "Скорость низкая для стольких потоков: один канал отдаёт около 2 Мбит/с, и одна загрузка " +
            "раскладывается по каналам не мгновенно. Если так и остаётся, переподключитесь — клиент поднимет потоки заново."
    }
    if (out.isEmpty()) out += "Явных проблем не видно. Если медленно именно на одном сайте, проверьте его без VPN: возможно, он сам ограничивает скорость."
    return out
}

private fun formatDuration(seconds: Long?): String {
    val s = (seconds ?: 0L).coerceAtLeast(0L)
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}
