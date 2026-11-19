package com.mrtt.client.ui.tunnel

import com.mrtt.client.ui.design.MrttStyleShapes
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrtt.client.ForegroundApp
import com.mrtt.client.GameModePolicy
import com.mrtt.client.SettingsStore
import com.mrtt.client.TunnelManager
import com.mrtt.client.ui.AppItem
import com.mrtt.client.ui.AppSectionCard
import com.mrtt.client.ui.components.MrttScreen
import com.mrtt.client.ui.components.MrttSettingRow
import com.mrtt.client.ui.design.MrttSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вкладка «Игры»: игровой режим, список игр и автовключение.
 *
 * Логика режима живёт в Rust-клиенте (закрепление потока за одним воркером),
 * здесь только переключатели и список игр.
 */
@Composable
fun GameModeTab(settingsStore: SettingsStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val manualEnabled by settingsStore.gameModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val autoEnabled by settingsStore.gameModeAuto.collectAsStateWithLifecycle(initialValue = true)
    val packagesRaw by settingsStore.gamePackages.collectAsStateWithLifecycle(initialValue = "")
    val extraPorts by settingsStore.gameExtraPorts.collectAsStateWithLifecycle(initialValue = "")
    val active by TunnelManager.gameModeActive.collectAsStateWithLifecycle()
    val foreground by TunnelManager.foregroundPackage.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    val games = remember(packagesRaw) { GameModePolicy.parsePackages(packagesRaw) }
    val appDirectRaw by settingsStore.appDirectRaw.collectAsStateWithLifecycle(initialValue = "")
    val appDirectFiltered by settingsStore.appDirectFiltered.collectAsStateWithLifecycle(initialValue = "")

    // Как игра ходит сейчас: список «напрямую» и есть ответ, отдельного флага нет.
    val throughTunnel: (String) -> Boolean = remember(appDirectRaw, appDirectFiltered) {
        val raw = GameModePolicy.parsePackages(appDirectRaw)
        val filtered = GameModePolicy.parsePackages(appDirectFiltered)
        // Явный тип: без него строка ниже читается как ещё один аргумент-лямбда.
        val route: (String) -> Boolean = { pkg -> pkg !in raw && pkg !in filtered }
        route
    }

    var showGamesDialog by remember { mutableStateOf(false) }
    var portsDraft by remember(extraPorts) { mutableStateOf(extraPorts) }
    var hasUsageAccess by remember { mutableStateOf(ForegroundApp.hasUsageAccess(context)) }

    LaunchedEffect(Unit) {
        hasUsageAccess = ForegroundApp.hasUsageAccess(context)
    }

    val foregroundLabel = remember(foreground, games) {
        foreground?.let { pkg ->
            val isGame = pkg in games
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            if (isGame) "$label (игра)" else label
        }
    }

    MrttScreen {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Игровой режим",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = if (active) {
                        if (tunnelRunning) "Включён · потоков не переключает, пинг ровный" else "Включён · заработает при подключении"
                    } else {
                        "Обычный режим: скорость выше, пинг может скакать"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                MrttSettingRow(
                    title = "Игровой режим",
                    checked = manualEnabled,
                    description = "Каждый поток остаётся на одном канале: без переупорядочивания пакетов",
                    onCheckedChange = { enabled -> TunnelManager.setGameModeEnabled(context, enabled) },
                )
                MrttSettingRow(
                    title = "Автовключение в играх",
                    checked = autoEnabled,
                    description = "Режим сам включается, когда на экране игра из списка ниже",
                    onCheckedChange = { enabled -> TunnelManager.setGameModeAuto(context, enabled) },
                )

                if (autoEnabled && !hasUsageAccess) {
                    Text(
                        text = "Чтобы приложение видело запущенную игру, разрешите «Доступ к данным об использовании»: " +
                            "в открывшемся списке найдите MRTT и включите переключатель. " +
                            "Если списка нет — откроется карточка приложения, там «Использование данных».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            ForegroundApp.openUsageAccessSettings(context)
                        },
                        modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text("Открыть доступ к данным", fontWeight = FontWeight.SemiBold)
                    }
                } else if (autoEnabled) {
                    Text(
                        text = foregroundLabel?.let { "Сейчас на экране: $it" } ?: "Сейчас на экране: нет данных",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Мои игры",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (games.isEmpty()) {
                            "Список пуст: выберите игры, они включат режим сами"
                        } else {
                            "Выбрано игр: ${games.size}. Долгое нажатие по игре в списке — " +
                                "«через туннель» или «напрямую»"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { showGamesDialog = true },
                    modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                    shape = MrttStyleShapes.control,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать игры", fontWeight = FontWeight.SemiBold)
                }
            }

            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Порты игр",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Пакеты на эти порты идут игровым путём, даже если режим выключен. Встроенный набор (${GameModePolicy.DEFAULT_GAME_PORTS.size} портов) включён всегда; свои добавьте через запятую.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = portsDraft,
                    onValueChange = { portsDraft = it },
                    label = { Text("Свои порты") },
                    placeholder = { Text("например 27015, 3478") },
                    singleLine = true,
                    shape = MrttStyleShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        TunnelManager.setGameExtraPorts(context, GameModePolicy.parsePorts(portsDraft).joinToString(","))
                    },
                    modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                    shape = MrttStyleShapes.control,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    Text("Применить", fontWeight = FontWeight.SemiBold)
                }
            }

            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Как это работает",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Обычный режим раскладывает новые соединения по всем каналам сразу — так выше скорость, но пакеты одной игры приходят с разной задержкой и она «дёргается». Игровой режим оставляет каждый поток на одном канале: скорость ниже, зато пинг ровный и без переподключений посреди матча.\n\nРежим переключается на ходу, переподключать VPN не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showGamesDialog) {
        GamesPickerDialog(
            initial = games,
            throughTunnel = throughTunnel,
            onRouteChange = { packageName, viaTunnel ->
                TunnelManager.setGameRoute(context, packageName, viaTunnel)
            },
            onDismiss = { showGamesDialog = false },
            onSave = { selected ->
                showGamesDialog = false
                scope.launch { TunnelManager.setGamePackages(context, selected) }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GamesPickerDialog(
    initial: Set<String>,
    throughTunnel: (String) -> Boolean,
    onRouteChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial) }
    // Долгое нажатие по выбранной игре: решаем, идёт она в туннель или мимо.
    var routeMenuApp by remember { mutableStateOf<AppItem?>(null) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Только приложения, которые поставил сам пользователь: в списке игр
    // системные службы не нужны и только мешают искать.
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != self }
                .filter { app ->
                    val flags = app.flags
                    (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                        pm.getLaunchIntentForPackage(app.packageName) != null
                }
                .map { app ->
                    AppItem(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        // Иконка нужна, чтобы список узнавался глазами, а не по именам пакетов.
                        icon = runCatching { app.loadIcon(pm).toBitmap().asImageBitmap() }.getOrNull(),
                        isSystem = false,
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
        apps = loaded
        loading = false
    }

    // Выбранные игры всегда сверху: их видно даже без поиска.
    val filtered = remember(apps, query, selected) {
        val base = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        base.sortedWith(
            compareByDescending<AppItem> { it.packageName in selected }
                .thenBy { it.name.lowercase() },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MrttStyleShapes.dialog,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Игры (${selected.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                }
                Text(
                    text = "Долгое нажатие по выбранной игре: через туннель или напрямую. " +
                        "Короткое нажатие только ставит галочку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск") },
                    singleLine = true,
                    shape = MrttStyleShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Text("Загрузка…", style = MaterialTheme.typography.bodySmall)
                } else if (filtered.isEmpty()) {
                    // Пустой список — это не ошибка: значит поиск ничего не нашёл.
                    Text(
                        text = if (query.isBlank()) {
                            "Нет установленных игр и приложений"
                        } else {
                            "Ничего не найдено по запросу «$query»"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                        items(items = filtered, key = { it.packageName }) { app ->
                            val isSelected = app.packageName in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            selected = if (isSelected) {
                                                selected - app.packageName
                                            } else {
                                                selected + app.packageName
                                            }
                                        },
                                        // Режим меняем только у выбранной игры — как на вкладке приложений.
                                        onLongClick = { if (isSelected) routeMenuApp = app },
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(MrttStyleShapes.small),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, MrttStyleShapes.small),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (isSelected) {
                                            val route = if (throughTunnel(app.packageName)) {
                                                "через туннель"
                                            } else {
                                                "напрямую, мимо туннеля"
                                            }
                                            "${app.packageName} · $route"
                                        } else {
                                            app.packageName
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected && throughTunnel(app.packageName)) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onSave(selected) },
                    modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                    shape = MrttStyleShapes.control,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Долгое нажатие по выбранной игре: куда её пускать.
    routeMenuApp?.let { app ->
        val viaTunnel = throughTunnel(app.packageName)
        Dialog(onDismissRequest = { routeMenuApp = null }) {
            Surface(
                shape = MrttStyleShapes.dialog,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Куда пускать трафик игры",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MrttSettingRow(
                        title = "Через туннель",
                        checked = viaTunnel,
                        description = "Трафик идёт через VPN: доступно то, что открывается только с VPN, " +
                            "но пинг может быть выше",
                        onCheckedChange = { on ->
                            if (on) onRouteChange(app.packageName, true)
                        },
                    )
                    MrttSettingRow(
                        title = "Напрямую, мимо туннеля",
                        checked = !viaTunnel,
                        description = "Свой интернет: пинг ровнее, но сайты и сервисы, закрытые без VPN, " +
                            "не откроются",
                        onCheckedChange = { on ->
                            if (on) onRouteChange(app.packageName, false)
                        },
                    )
                    OutlinedButton(
                        onClick = { routeMenuApp = null },
                        modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text("Готово", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
