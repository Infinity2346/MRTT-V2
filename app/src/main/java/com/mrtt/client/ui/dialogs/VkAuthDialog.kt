package com.mrtt.client.ui.dialogs

import com.mrtt.client.ui.design.MrttStyleShapes
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import com.mrtt.client.ui.appSectionCardBorderColor
import com.mrtt.client.ui.appSectionCardColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import com.mrtt.client.ui.components.MrttGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrtt.client.VkAuthPayload
import com.mrtt.client.VkAuthPhase
import com.mrtt.client.VkAuthSession
import com.mrtt.client.VkAuthWebViewManager

@Composable
internal fun VkAuthDialog(
    onToken: (VkAuthPayload) -> Unit,
    onDismiss: () -> Unit,
    // Вход в отдельный аккаунт (для сервера): сессия VK клиента не подхватывается и не перезаписывается.
    freshSession: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var uiReady by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(VkAuthPhase.LOGIN) }
    // Что произошло при попытке войти через приложение VK.
    var appLoginNote by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        // Вход в отдельный аккаунт (для сервера) начинается с чистой сессии;
        // свой вход подхватывает уже сохранённые куки — по ним токен и берётся.
        if (freshSession) VkAuthWebViewManager.clearVkSessionCookiesNow()
        VkAuthWebViewManager.acquire(context)
    }
    val session = remember {
        VkAuthSession(
            onUiReady = { uiReady = true },
            onToken = { payload ->
                if (freshSession) VkAuthWebViewManager.clearVkSessionCookies()
                onToken(payload)
            },
            onError = { errorMessage = it },
            onPhaseChange = { phase = it },
            onAppLoginNote = { appLoginNote = it },
        )
    }

    DisposableEffect(session, webView, lifecycleOwner) {
        // Возврат из приложения ВК замечает не только Lifecycle окна: MainActivity
        // тоже сообщает о возврате, и строка об этом должна появиться здесь.
        session.onNote = { note -> appLoginNote = note }
        session.attach(webView)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.pause()
                Lifecycle.Event.ON_START -> {
                    session.resume()
                    // Вернулись из приложения VK: если сессия уже есть — сразу берём токен.
                    session.resumeFromExternalLogin()?.let { note -> appLoginNote = note }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.stop()
            VkAuthWebViewManager.release(webView)
        }
    }

    val webAlpha by animateFloatAsState(
        targetValue = if (uiReady && errorMessage == null) 1f else 0f,
        label = "vk_auth_web_alpha",
    )

    val title = when (phase) {
        VkAuthPhase.LOGIN -> "Войдите в аккаунт ВК"
        VkAuthPhase.TOKEN -> "Получение токена"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val previousMode = window?.attributes?.softInputMode
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose {
                if (previousMode != null) window.setSoftInputMode(previousMode)
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MrttStyleShapes.dialog,
                color = appSectionCardColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, appSectionCardBorderColor()),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.94f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Закрыть",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (phase == VkAuthPhase.LOGIN) {
                        // Отдельная кнопка: вход через приложение ВК по протоколу
                        // VK ID. Если страница VK ID недоступна, окно само
                        // возвращается к обычной форме входа и говорит об этом.
                        TextButton(
                            onClick = {
                                appLoginNote = session.startVkIdLogin(context)
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Войти через приложение ВК", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    appLoginNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }



                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                                    val radius = 32f * ctx.resources.displayMetrics.density
                                    outlineProvider = object : ViewOutlineProvider() {
                                        override fun getOutline(view: View, outline: Outline) {
                                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                                        }
                                    }
                                    clipToOutline = true
                                    addView(
                                        webView,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                    post { webView.requestFocus() }
                                }
                            },
                            modifier = Modifier.fillMaxSize().alpha(webAlpha),
                        )

                        if (!uiReady && errorMessage == null) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }

                        errorMessage?.let { message ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // «Войти заново» вместо тупика: раньше единственным
                                // выходом было закрыть окно и открыть его снова,
                                // а ошибка повторялась на той же протухшей сессии.
                                Button(
                                    onClick = {
                                        errorMessage = null
                                        uiReady = false
                                        session.retryLogin()
                                    },
                                    shape = MrttStyleShapes.control,
                                    colors = ButtonDefaults.buttonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                ) {
                                    Text("Войти заново", fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = onDismiss) {
                                    Text("Закрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
