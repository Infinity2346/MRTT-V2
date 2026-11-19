package com.mrtt.client

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

internal data class VkAuthPayload(
    val token: String,
    val userId: String,
    val expiresIn: Long,
    /** Куки сессии VK: это и есть привязка, токен из них получается заново при каждом подключении. */
    val cookies: String = "",
    /**
     * Вход по протоколу VK ID (через приложение ВК): привязкой становится пара
     * токенов, а не куки — их приложение ВК нам не отдаёт.
     */
    val refreshToken: String = "",
)

internal enum class VkAuthPhase { LOGIN, TOKEN }

internal object VkAuthWebViewManager {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun acquire(context: Context): WebView = createWebView(context)

    fun clearVkSessionCookies() {
        mainHandler.post { clearVkSessionCookiesNow() }
    }

    /** То же, но сразу: вызывать с главного потока до открытия окна входа. */
    fun clearVkSessionCookiesNow() {
        runCatching {
            val manager = CookieManager.getInstance()
            val deletions = VK_AUTH_COOKIE_ORIGINS.flatMap { origin ->
                vkCookieDeletionHeaders(origin, manager.getCookie(origin)).map { origin to it }
            }
            if (deletions.isEmpty()) {
                manager.flush()
            } else {
                val remaining = AtomicInteger(deletions.size)
                deletions.forEach { (origin, header) ->
                    manager.setCookie(origin, header) {
                        if (remaining.decrementAndGet() == 0) manager.flush()
                    }
                }
            }
        }
    }

    /**
     * Приложение вернулось на экран: если это был возврат из приложения ВК,
     * активное окно входа сейчас же проверит куки сессии.
     */
    fun onAppReturned() {
        mainHandler.post {
            val session = VkAuthSession.active ?: return@post
            val note = session.resumeFromExternalLogin() ?: return@post
            (session.onNote ?: session.onAppLoginNote)(note)
        }
    }

    fun release(view: WebView) {
        mainHandler.post {
            runCatching { CookieManager.getInstance().flush() }
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            // Страница входа открывает окно «Войти через приложение» через window.open:
            // без этой настройки окно не создаётся и кнопка ВК молчит.
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        if (TvDevicePolicy.isTelevision(context)) {
            configureTelevisionInput(view)
        }
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }
        return view
    }

    /**
     * Мост для официальной кнопки ВК «Войти через приложение».
     *
     * Кнопка открывает приложение по ссылке `vk://…` из JavaScript (`window.open`
     * или `location.href`). WebView такие ссылки не отдаёт системе сам: он пытается
     * загрузить их как страницу, получает ошибку и молча останавливается. Мост
     * принимает только внешние схемы и передаёт их открывателю — см. [handleExternalTarget].
     */
    internal class VkExternalLinkBridge(
        private val onExternalLink: (Uri) -> Unit,
        private val onReturn: (String) -> Unit = {},
        /** Обычная страница входа (вход прошёл в этом же окне) — тоже событие. */
        private val onPage: (String) -> Unit = {},
    ) {
        @JavascriptInterface
        fun open(url: String) {
            if (url.isBlank()) return
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
            if (!VkAuthSecurity.isExternalScheme(uri.scheme.orEmpty())) {
                // Страницы входа (https) сходим сами: так вход, случившийся после
                // перехода, замечается даже если WebView не сообщил о загрузке.
                onPage(url)
                return
            }
            onExternalLink(uri)
        }

        /** ВК вернул человека из приложения ссылкой на свою схему. */
        @JavascriptInterface
        fun returned(url: String) {
            if (url.isBlank()) return
            onReturn(url)
        }
    }

    private fun configureTelevisionInput(view: WebView) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.post { view.evaluateJavascript(TV_FOCUS_FIRST_ELEMENT_JS, null) }
            }
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val command = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> "previous"
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> "next"
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> "activate"
                else -> null
            } ?: return@setOnKeyListener false

            view.evaluateJavascript("window.__mrttTvInput && window.__mrttTvInput('$command');", null)
            true
        }
        view.post { view.requestFocus() }
    }

    private const val TV_FOCUS_FIRST_ELEMENT_JS = """
        (function() {
          if (window.__mrttTvInput) {
            window.__mrttTvInput('ensure-focus');
            return;
          }
          var selector = 'a[href],button,input:not([type=hidden]),select,textarea,[role=button],[tabindex]:not([tabindex="-1"])';
          function elements() {
            return Array.prototype.filter.call(document.querySelectorAll(selector), function(node) {
              var style = window.getComputedStyle(node);
              return !node.disabled && style.display !== 'none' && style.visibility !== 'hidden' && node.getClientRects().length > 0;
            });
          }
          function focus(node) {
            if (!node) return false;
            node.focus({preventScroll: true});
            node.scrollIntoView({block: 'center', inline: 'nearest'});
            return true;
          }
          window.__mrttTvInput = function(command) {
            var items = elements();
            var active = document.activeElement;
            var index = items.indexOf(active);
            if (command === 'ensure-focus') {
              return index >= 0 || focus(items[0]);
            }
            if (command === 'next' || command === 'previous') {
              if (!items.length) return false;
              var direction = command === 'next' ? 1 : -1;
              var next = index < 0 ? (direction > 0 ? 0 : items.length - 1) : (index + direction + items.length) % items.length;
              return focus(items[next]);
            }
            if (command === 'activate') {
              if (index < 0 && !focus(items[0])) return false;
              active = document.activeElement;
              if (!active) return false;
              if (active.matches('button,a[href],input[type=button],input[type=submit],input[type=checkbox],input[type=radio],[role=button]')) {
                active.click();
                return true;
              }
              active.click();
              return true;
            }
            return false;
          };
          window.__mrttTvInput('ensure-focus');
        })();
    """
}

@SuppressLint("SetJavaScriptEnabled")
internal class VkAuthSession(
    private val onUiReady: () -> Unit,
    private val onToken: (VkAuthPayload) -> Unit,
    private val onError: (String) -> Unit,
    private val onPhaseChange: (VkAuthPhase) -> Unit = {},
    /** Результат попытки «войти через приложение»: человеку нужно объяснить, что произошло. */
    internal val onAppLoginNote: (String) -> Unit = {},
) {
    /** Строка о возврате из приложения ВК для уже открытого окна. */
    internal var onNote: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var finished = false
    private var silentRetried = false
    private var lastUrl: String? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var uiReadyReported = false
    private var phase = VkAuthPhase.LOGIN
    private var paused = false
    private var pendingAuthorizationRetry = false
    /** Человек нажал «Войти через приложение»: ждём возврата с готовой сессией. */
    private var externalLoginRequested = false
    /** Сколько раз уже перепроверяли куки после возврата из приложения ВК. */
    private var externalLoginPolls = 0
    /** Сколько раз пробовали обменять куки на токен: вторая попытка часто удаётся. */
    private var tokenAttempts = 0
    /** Параметры текущего входа по протоколу VK ID (PKCE). */
    private var pkce = VkIdOAuth.newPkce()
    /** Идёт обмен кода VK ID на токен: второй обмен того же кода ВК отклонит. */
    private var exchangingCode = false
    /** Страница VK ID уже отказала: дальше работаем на обычной странице входа ВК. */
    private var vkIdPageFellBack = false
    /** Сейчас открыта страница VK ID (а не обычная страница входа ВК). */
    private var vkIdAttempt = false

    fun attach(view: WebView) {
        active = this
        webView = view
        startedAt = System.currentTimeMillis()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                installTelevisionNavigation(v)
                installExternalLinkShim(v)
                if (!uiReadyReported) {
                    uiReadyReported = true
                    onUiReady()
                }
                detectVkIdPageRejected(v, url)
            }

            override fun onPageCommitVisible(v: WebView, url: String) {
                installTelevisionNavigation(v)
                installExternalLinkShim(v)
                if (!uiReadyReported) {
                    uiReadyReported = true
                    onUiReady()
                }
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // Возврат из приложения ВК по протоколу VK ID:
                // `vk7793118://vk.ru/blank.html?code=…&device_id=…&state=…`.
                // WebView такую схему не грузит, поэтому разбираем сами.
                if (VkIdOAuth.parseCallback(url.toString()) != null) {
                    handleAppReturnByUrl(url.toString())
                    return true
                }
                if (VkAuthSecurity.isVkAppReturn(url.toString())) {
                    handleAppReturn(v)
                    return true
                }
                if (handleExternalTarget(url)) return true
                val path = url.path ?: ""
                // «Человек вошёл» определяем только по сессионной куке: раньше
                // сюда попадала и обычная страница vk.ru с её служебными куками,
                // и окно уходило за токеном, которого VK не выдавал.
                if (phase == VkAuthPhase.LOGIN && hasSessionCookie()) {
                    switchToTokenPhase(v)
                    return true
                }
                if (phase == VkAuthPhase.LOGIN && path.startsWith("/feed")) {
                    switchToTokenPhase(v)
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.lastPathSegment != "blank.html") return null
                // Сюда приходит и возврат VK ID с кодом подтверждения
                // (`https://oauth.vk.ru/blank.html?code=…&state=…`): страницу не
                // грузим, а сразу обмениваем код на токен.
                val full = uri.toString()
                val callback = VkIdOAuth.parseCallback(full)
                if (callback != null) {
                    mainHandler.post { handleAppReturnByUrl(full) }
                } else {
                    // Ни кода, ни ошибки: ВК не принял запрос. Показываем запрос
                    // целиком — без этого белый экран ничего не объясняет.
                    Log.w(MrttConstants.General.APP_NAME, "[VK] blank.html без кода: $full")
                }
                return WebResourceResponse(
                    "text/html",
                    "utf-8",
                    200,
                    "OK",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(TOKEN_WAITING_HTML.toByteArray(Charsets.UTF_8)),
                )
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (finished) return
                // Переход в приложение не должен выглядеть как «нет интернета»:
                // WebView сообщает ошибку загрузки для схем, которые он не умеет.
                if (isExternalScheme(request.url.scheme.orEmpty())) return
                finishWithError("Не удалось открыть страницу авторизации VK — проверьте интернет")
            }
        }
        // Официальная кнопка ВК «Войти через приложение» работает двумя путями:
        // переходом по `vk://…` и всплывающим окном (`window.open`). WebView по
        // умолчанию не умеет ни то, ни другое — поэтому и казалось, что кнопка мертва.
        installExternalLinkHandlers(view)
        view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }

            /**
             * Окно, которое открывает страница ВК. Внутри него бывает и приложение
             * (`vk://`), и обычная страница проверки: первое отдаём системе, второе
             * грузим в этом же WebView, чтобы вход не прерывался.
             */
            override fun onCreateWindow(
                v: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(v.context)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    // Окно живёт только до первого адреса: его содержимое (страница
                    // проверки или ссылка на приложение) уходит в основной WebView.
                    override fun shouldOverrideUrlLoading(p: WebView, request: WebResourceRequest): Boolean {
                        openInMainWindow(request.url)
                        destroyPopup(p)
                        return true
                    }

                    override fun onPageStarted(p: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (url.isNullOrBlank() || url == "about:blank") return
                        openInMainWindow(Uri.parse(url))
                        destroyPopup(p)
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        if (view.progress == 100 && !uiReadyReported) {
            uiReadyReported = true
            onUiReady()
        }

        if (hasSessionCookie()) {
            // Сессия уже есть (человек вошёл в этом же WebView раньше):
            // незачем просить вход заново — сразу берём токен из кук.
            externalLoginPolls = 0
            switchToTokenPhase(view)
        } else {
            phase = VkAuthPhase.LOGIN
            onPhaseChange(phase)
            if (view.url.isNullOrBlank() || view.url == "about:blank") {
                loadLoginPage(view)
            }
        }
        scheduleMonitor()
    }

    fun stop() {
        finished = true
        if (active === this) active = null
        mainHandler.removeCallbacksAndMessages(null)
        webView = null
    }

    fun pause() {
        if (finished || paused) return
        paused = true
        pausedAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(monitorTick)
        webView?.onPause()
    }

    fun resume() {
        if (finished || !paused) return
        val resumedAt = System.currentTimeMillis()
        if (pausedAt != 0L) {
            startedAt += (resumedAt - pausedAt).coerceAtLeast(0L)
            pausedAt = 0L
        }
        paused = false
        webView?.onResume()
        if (pendingAuthorizationRetry) {
            pendingAuthorizationRetry = false
            repeatAuthorization()
        }
        scheduleMonitor()
    }

    /** Закрывает всплывающее окно: дальше его адрес живёт в основном WebView. */
    private fun destroyPopup(view: WebView) {
        // Уничтожаем после текущего вызова: onPageStarted приходит из самого WebView.
        mainHandler.post {
            runCatching {
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
            }
        }
    }

    /**
     * Открывает адрес из всплывающего окна: внешнюю схему — приложению,
     * обычную страницу — в основном WebView.
     */
    private fun openInMainWindow(uri: Uri): Boolean {
        if (handleExternalTarget(uri)) return true
        val url = uri.toString()
        if (url.isBlank() || url == "about:blank") return false
        val main = webView ?: return false
        if (main.url == url) return true
        main.loadUrl(url)
        return true
    }

    /**
     * Учит страницу отдавать внешние ссылки приложению: перехватывает `window.open`
     * и клики по ссылкам с чужой схемой. Нужно для официальной кнопки ВК
     * «Войти через приложение»: она открывает приложение из JavaScript, а WebView
     * такие переходы молча игнорирует.
     */
    private fun installExternalLinkShim(view: WebView) {
        runCatching { view.evaluateJavascript(EXTERNAL_LINK_SHIM_JS, null) }
    }

    /**
     * Приложения, которые открываются по ссылке: VK-клиент, браузер и любая
     * сторонняя программа. Внутри WebView такие ссылки не работают.
     */
    private fun isExternalScheme(scheme: String): Boolean = VkAuthSecurity.isExternalScheme(scheme)

    /** Имя JS-моста, через который страница отдаёт внешние ссылки. */
    private val vkBridgeName = "MRTTVk"

    /** Служебная схема для нажатий, которые нельзя отследить переходом. */
    private val mrttScheme = "mrtt"

    /**
     * Вешает на WebView JS-мост: без него официальная кнопка ВК «Войти через
     * приложение» жмётся, но ничего не открывает.
     */
    private fun installExternalLinkHandlers(view: WebView) {
        runCatching {
            view.addJavascriptInterface(
                VkAuthWebViewManager.VkExternalLinkBridge(
                    onExternalLink = { uri -> handleExternalTarget(uri) },
                    onReturn = { url -> handleAppReturnByUrl(url) },
                    onPage = { url -> handlePageFromBridge(url) },
                ),
                vkBridgeName,
            )
        }
    }

    /**
     * Пытается выполнить внешний переход: `intent://…#Intent;…`, свой протокол
     * приложения (`vk://`, `vkauth://`) или обычную ссылку на другое приложение.
     *
     * Возвращает `true`, если ссылку забрали себе: тогда WebView её не грузит.
     * Любой исход объясняется строкой в окне — молча ничего не делать нельзя,
     * иначе кнопка «войти через приложение» выглядит сломанной.
     */
    private fun handleExternalTarget(uri: Uri): Boolean {
        // Из JS-моста вызов приходит не в главном потоке: и startActivity,
        // и работы с WebView должны идти в main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleExternalTarget(uri) }
            return true
        }
        val scheme = uri.scheme.orEmpty().lowercase()
        if (!isExternalScheme(scheme)) return false
        val context = webView?.context ?: return true
        // Нажатие на официальную кнопку ВК: открываем приложение сами,
        // потому что из вложенного фрейма ссылка `vk://` до WebView не доходит.
        if (scheme == mrttScheme) {
            onAppLoginNote(openVkAppAndWait(context))
            return true
        }
        // ВК умеет слать ссылку `intent://…;S.browser_fallback_url=https://…;end`:
        // если приложения нет, страницу нужно открыть прямо здесь, а не терять переход.
        if (scheme == "intent") {
            val fallback = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }
                .getOrNull()
                ?.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank() && vkAppInstalled(context).not()) {
                webView?.loadUrl(fallback)
                onAppLoginNote("Приложение ВК не установлено: продолжаем вход здесь")
                return true
            }
        }
        // `file://` из чужой страницы — это не вход, а попытка прочитать локальный файл.
        if (scheme == "file") {
            onAppLoginNote("Ссылка ведёт на локальный файл: открывать её нельзя")
            return true
        }

        val intent = when (scheme) {
            "intent" -> runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
            else -> Intent(Intent.ACTION_VIEW, uri)
        }
        if (intent == null) {
            onAppLoginNote("ВК предложил открыть приложение, но ссылку не удалось разобрать")
            return true
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val canOpen = runCatching { context.packageManager.resolveActivity(intent, 0) != null }
            .getOrDefault(false)
        if (!canOpen) {
            onAppLoginNote("Приложение ВК не установлено: входите здесь, логином и паролем")
            return true
        }
        return try {
            context.startActivity(intent)
            // Возврат из приложения VK = повод проверить куки сессии.
            externalLoginRequested = true
            externalLoginPolls = 0
            onAppLoginNote("Открыли приложение ВК: войдите в нём и вернитесь в MRTT")
            true
        } catch (error: ActivityNotFoundException) {
            onAppLoginNote("Приложение ВК не установлено: входите здесь, логином и паролем")
            true
        } catch (error: Exception) {
            Log.w(MrttConstants.General.APP_NAME, "[VK] Не удалось открыть внешнюю ссылку", error)
            onAppLoginNote("Не удалось открыть приложение: входите здесь, логином и паролем")
            true
        }
    }

    /**
     * Возврат из приложения ВК: `vk7793118://vk.ru/blank.html?code=…&device_id=…&state=…`.
     *
     * Это официальный путь VK ID: приложение ВК не отдаёт нам свои куки, зато
     * отдаёт код подтверждения. Код обмениваем на токены сами (PKCE), и вход
     * доводится до конца — человеку больше не нужно вводить пароль в MRTT.
     */
    private fun handleAppReturnByUrl(url: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleAppReturnByUrl(url) }
            return
        }
        val view = webView ?: return
        val callback = VkIdOAuth.parseCallback(url)
        if (callback != null) {
            handleVkIdCallback(view, callback)
            return
        }
        // Возврат пришёл по схеме ВК, но без кода: остаётся только cookie-путь.
        Log.i(
            MrttConstants.General.APP_NAME,
            "[VK] Возврат из приложения: ${VkAuthSecurity.vkAppReturnHost(url) ?: "схема без ВК-хоста"}",
        )
        handleAppReturn(view)
    }

    /**
     * Проверяет, что страница входа VK ID действительно открылась.
     *
     * `id.vk.ru/authorize` умеет отвечать `{"error":"not found"}` — например,
     * если приложение не знает такой `redirect_uri`. Тогда окно остаётся с
     * голым JSON, и нажать в нём нечего. В этом случае один раз переходим на
     * обычную страницу входа ВК: вход оттуда тоже доводится до токена.
     */
    private fun detectVkIdPageRejected(view: WebView, url: String) {
        if (finished || vkIdPageFellBack || !vkIdAttempt) return
        if (!url.contains("id.vk.", ignoreCase = true)) return
        view.evaluateJavascript(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return t.replace(/\\s+/g,' ').trim().slice(0,120);})();",
        ) { raw ->
            if (finished || vkIdPageFellBack) return@evaluateJavascript
            val text = raw.orEmpty().replace("\\", "")
            val looksRejected = text.contains("\"error\"") ||
                text.contains("not found", ignoreCase = true) ||
                text.contains("/blank.html") ||
                text.contains("invalid", ignoreCase = true)
            if (!looksRejected) return@evaluateJavascript
            vkIdPageFellBack = true
            Log.w(MrttConstants.General.APP_NAME, "[VK] Страница VK ID не приняла запрос: $text")
            // Обычная страница входа: там вход точно работает, а токен возьмём из кук.
            onAppLoginNote(
                "Вход через приложение ВК недоступен — войдите здесь логином и паролем",
            )
            vkIdAttempt = false
            view.loadUrl(MrttConstants.VkAutoHash.VK_MOBILE_LOGIN_URL)
        }
    }

    /**
     * JS-мост сообщил об обычном переходе (в том числе `location.href` внутри
     * iframe, который WebView иногда не показывает как загрузку страницы).
     * Ведём WebView туда же — так вход не может «зависнуть» на полпути.
     */
    private fun handlePageFromBridge(url: String) {
        mainHandler.post {
            if (finished) return@post
            val view = webView ?: return@post
            if (VkIdOAuth.parseCallback(url) != null) {
                handleAppReturnByUrl(url)
                return@post
            }
            if (VkAuthSecurity.isVkAppReturn(url)) {
                handleAppReturn(view)
                return@post
            }
            val target = runCatching { url.toUri() }.getOrNull() ?: return@post
            if (!VkAuthSecurity.isExternalScheme(target.scheme.orEmpty())) {
                if (view.url != url) view.loadUrl(url)
            }
        }
    }

    /** Обмен кода VK ID на токены: единственный путь, который реально работает из приложения ВК. */
    private fun handleVkIdCallback(view: WebView, callback: VkIdOAuth.Callback) {
        if (finished || exchangingCode) return
        if (callback.error.isNotBlank()) {
            // ВК отвечает причиной в `error`/`error_description`: показываем её
            // как есть, иначе «не работает» — единственное, что остаётся человеку.
            val description = callback.errorDescription.ifBlank { callback.error }
            onAppLoginNote("ВК отклонил вход: $description")
            phase = VkAuthPhase.LOGIN
            onPhaseChange(phase)
            loadLoginPage(view)
            scheduleMonitor()
            return
        }
        if (!VkIdOAuth.stateMatches(pkce.state, callback.state)) {
            // Чужой/устаревший возврат: код не наш, обменивать нельзя.
            onAppLoginNote("Возврат из ВК не совпал с этим входом: попробуйте ещё раз")
            pkce = VkIdOAuth.newPkce()
            phase = VkAuthPhase.LOGIN
            onPhaseChange(phase)
            loadLoginPage(view)
            scheduleMonitor()
            return
        }
        exchangingCode = true
        externalLoginRequested = false
        externalLoginPolls = 0
        phase = VkAuthPhase.TOKEN
        onPhaseChange(phase)
        lastUrl = null
        onAppLoginNote("Вход в приложении ВК получен: забираем токен…")
        view.stopLoading()
        view.loadDataWithBaseURL(null, TOKEN_WAITING_HTML, "text/html", "UTF-8", null)
        val current = pkce
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                VkTokenScraper.exchangeVkIdCode(
                    code = callback.code,
                    deviceId = callback.deviceId,
                    verifier = current.verifier,
                    state = callback.state,
                )
            }
            mainHandler.post {
                exchangingCode = false
                if (finished) return@post
                result.fold(
                    onSuccess = { tokens ->
                        finishWithVkIdToken(tokens, current)
                    },
                    onFailure = { error ->
                        val reason = error.message ?: error.javaClass.simpleName
                        // Код живёт 10 минут и обменивается один раз: повторять его
                        // бессмысленно, поэтому просим новый вход этой же кнопкой.
                        pkce = VkIdOAuth.newPkce()
                        phase = VkAuthPhase.LOGIN
                        onPhaseChange(phase)
                        onAppLoginNote("Не удалось получить токен: $reason")
                        loadLoginPage(view)
                        scheduleMonitor()
                    },
                )
            }
        }
    }

    private fun finishWithVkIdToken(tokens: VkTokenScraper.VkIdTokens, used: VkIdOAuth.Pkce) {
        // Токен VK ID привязан к устройству и к IP: cookies тут не нужны.
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        if (used !== pkce) pkce = VkIdOAuth.newPkce()
        onToken(
            VkAuthPayload(
                token = tokens.accessToken,
                userId = tokens.userId,
                expiresIn = 0L,
                cookies = "",
                refreshToken = tokens.refreshToken,
            ),
        )
    }

    private fun handleAppReturn(view: WebView) {
        if (finished) return
        externalLoginRequested = true
        externalLoginPolls = 0
        // Сессия могла появиться прямо в этот момент: не ждём следующего тика.
        if (hasSessionCookie()) {
            onAppLoginNote("Сессия ВК найдена: получаем токен…")
            switchToTokenPhase(view)
            return
        }
        onAppLoginNote("Вернулись из приложения ВК: проверяем сессию…")
        // Приложение ВК не отдаёт свой вход чужому WebView. Если сессия не появилась
        // сразу, показываем форму входа в самом окне, а не уходим в тупик.
        phase = VkAuthPhase.LOGIN
        lastUrl = null
        loadLoginPage(view)
        scheduleMonitor()
    }

    /**
     * Открывает приложение ВК и запоминает, что надо ждать возврата с сессией.
     *
     * Вызывается, когда человек нажал официальную кнопку ВК «Войти через
     * приложение»: страница открывает приложение из вложенного фрейма, и
     * переход по `vk://` до WebView не доходит — открываем сами.
     */
    private fun openVkAppAndWait(context: Context): String {
        externalLoginRequested = true
        externalLoginPolls = 0
        return openVkApp(context)
    }

    private fun openVkApp(context: Context): String {
        externalLoginRequested = true
        externalLoginPolls = 0
        // Открываем приложение ВК сразу на нашем запросе входа VK ID: иначе оно
        // просто показывает свою ленту, ничего не подтверждает и возврат
        // приходит пустым — это и выглядело как «нажал и ничего не произошло».
        val authorize = Intent(Intent.ACTION_VIEW, VkIdOAuth.authorizeUrl(pkce).toUri()).apply {
            setPackage(VK_APP_PACKAGE)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Приложение ВК может и не уметь открывать https-ссылку authorize
        // (`ActivityNotFound` либо открытие «в никуда»). Тогда пробуем нашу
        // схему `vk{client_id}://` — её ВК понимает как вход в приложение,
        // и запускаем приложение обычным способом.
        val schemeIntent = Intent(
            Intent.ACTION_VIEW,
            "vk${VkIdOAuth.CLIENT_ID}://vk.ru/login".toUri(),
        ).apply {
            setPackage(VK_APP_PACKAGE)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (intent in listOf(authorize, schemeIntent)) {
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                externalLoginRequested = true
                externalLoginPolls = 0
                return "Подтвердите вход в приложении ВК — вернёмся сюда автоматически"
            }
        }
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(VK_APP_PACKAGE) }
            .getOrNull()
        if (launch != null) {
            // Только этот пакет: любой `com.vk.*` в чужих руках — подделка.
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(launch) }.isSuccess
            if (opened) {
                // Приложение открылось без нашего запроса: вход им не подтверждён,
                // поэтому честно предупреждаем и оставляем форму входа на экране.
                return "Открыли приложение ВК, но вход не подтверждён. Вернитесь и войдите здесь"
            }
        }
        // Приложения нет — открываем страницу входа в обычном браузере: там кнопка
        // «Войти через приложение» работает штатно, а не внутри WebView.
        // Браузер выбираем явно: ACTION_VIEW без пакета может уехать в WebView-обёртку.
        val uri = MrttConstants.VkAutoHash.VK_LOGIN_URL.toUri()
        val opened = BROWSER_PACKAGES.any { packageName ->
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.isSuccess
        }
        return if (opened) {
            "Приложение ВК не установлено. Открыли вход в браузере: после входа вернитесь " +
                "в MRTT и нажмите «Войти через приложение» ещё раз"
        } else {
            "Не удалось открыть ни приложение ВК, ни браузер: входите здесь, логином и паролем"
        }
    }

    /** Установлено ли настоящее приложение ВК. */
    private fun vkAppInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(VK_APP_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun scheduleMonitor() {
        mainHandler.removeCallbacks(monitorTick)
        if (!finished && !paused) {
            mainHandler.postDelayed(monitorTick, MrttConstants.VkAutoHash.AUTH_MONITOR_INTERVAL_MS)
        }
    }

    private fun installTelevisionNavigation(view: WebView) {
        if (TvDevicePolicy.isTelevision(view.context)) {
            view.evaluateJavascript(TV_FOCUS_FIRST_ELEMENT_JS, null)
            view.post { view.requestFocus() }
        }
    }

    /**
     * Вход мог случиться в приложении VK: WebView ничего не грузил, но куки
     * сессии в CookieManager уже есть. Проверяем это при возврате в приложение.
     */
    fun resumeFromExternalLogin(): String? {
        if (finished) return null
        if (phase != VkAuthPhase.LOGIN) return null
        val returnedWithSession = hasSessionCookie()
        // Флаг больше не сбрасывается на первой неудаче: вход в приложении мог
        // закончиться позже, чем MRTT вернулся на экран, и раньше такой возврат
        // терялся навсегда — человек видел окно входа и «ничего не произошло».
        if (!returnedWithSession) {
            if (externalLoginRequested && externalLoginPolls < EXTERNAL_LOGIN_MAX_POLLS) {
                externalLoginPolls += 1
                mainHandler.postDelayed(
                    { resumeFromExternalLogin()?.let(onAppLoginNote) },
                    EXTERNAL_LOGIN_POLL_MS,
                )
                return null
            }
            // Приложение ВК хранит свой вход отдельно и не отдаёт сессию чужому
            // WebView. Раньше мы всё равно шли за токеном и получали
            // «web_token: VK не выдал access_token» — ошибка на пустом месте.
            // Честно говорим, что вход надо сделать здесь.
            if (externalLoginRequested) {
                externalLoginRequested = false
                externalLoginPolls = 0
                return APP_LOGIN_NO_SESSION_NOTE
            }
            return null
        }
        externalLoginRequested = false
        externalLoginPolls = 0
        val view = webView ?: return null
        switchToTokenPhase(view)
        return "Сессия VK найдена: получаем токен…"
    }

    private fun switchToTokenPhase(view: WebView) {
        if (finished) return
        val cookieString = collectVkCookieHeader()
        // Идём за токеном только с настоящей сессией: иначе VK отвечает отказом,
        // и человек получает «web_token: VK не выдал access_token» на каждой
        // попытке, хотя вход просто ещё не случился.
        if (!VkAuthSecurity.hasVkSessionCookie(cookieString)) return
        phase = VkAuthPhase.TOKEN
        onPhaseChange(phase)
        lastUrl = null

        view.stopLoading()
        view.loadDataWithBaseURL(null, TOKEN_WAITING_HTML, "text/html", "UTF-8", null)
        val userAgent = view.settings.userAgentString ?: MrttConstants.VkAutoHash.WEB_USER_AGENT

        tokenAttempts += 1
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { VkTokenScraper.fetchAccessToken(cookieString, userAgent) }
            mainHandler.post {
                if (finished) return@post
                result.fold(
                    onSuccess = { token ->
                        tokenAttempts = 0
                        finishWithToken(token, cookieString)
                    },
                    onFailure = { error ->
                        val reason = error.message ?: error.javaClass.simpleName
                        // Куки могли появиться на мгновение позже: один повтор решает
                        // «токен не выдан» без того, чтобы гонять человека по кругу.
                        if (tokenAttempts < TOKEN_ATTEMPTS_MAX && hasSessionCookie()) {
                            mainHandler.postDelayed(
                                { if (!finished) switchToTokenPhase(view) },
                                TOKEN_RETRY_DELAY_MS,
                            )
                            return@post
                        }
                        // Сессии нет или VK её не принимает: возвращаемся к входу
                        // с понятной строкой, а протухшие куки убираем — иначе
                        // следующая попытка снова упадёт на том же месте.
                        returnToLoginAfterTokenFailure(view, reason)
                    },
                )
            }
        }
    }

    /**
     * Вход случился, но токен не выдан. Возвращаемся в окно входа и объясняем
     * причину по-человечески: приложение ВК не делится своим входом, поэтому
     * войти нужно прямо здесь, логином и паролем (или кодом из SMS).
     */
    private fun returnToLoginAfterTokenFailure(view: WebView, reason: String) {
        tokenAttempts = 0
        phase = VkAuthPhase.LOGIN
        onPhaseChange(phase)
        lastUrl = null
        val sessionRejected = reason.contains("web_token") ||
            reason.contains("API 4") || reason.contains("API 5") ||
            reason.contains("API 28") || reason.contains("авторизац", ignoreCase = true)
        onAppLoginNote(
            if (sessionRejected) {
                "ВК не принял вход из приложения: войдите в этом окне логином и паролем"
            } else {
                "Не удалось получить токен ($reason): попробуйте войти ещё раз"
            },
        )
        if (sessionRejected) {
            // Протухшую сессию убираем: иначе страница входа сразу «узнаёт» человека
            // и снова отдаёт те же непригодные куки.
            clearSessionCookiesNow()
        }
        view.stopLoading()
        loadLoginPage(view)
        scheduleMonitor()
    }

    /**
     * Повтор входа из окна: человек нажал «Войти заново» после ошибки.
     * Возвращает окно в рабочее состояние — без этого единственным выходом
     * было закрыть и открыть окно, и ошибка повторялась.
     */
    fun retryLogin(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { retryLogin() }
            return true
        }
        val view = webView ?: return false
        finished = false
        silentRetried = false
        pendingAuthorizationRetry = false
        externalLoginRequested = false
        externalLoginPolls = 0
        tokenAttempts = 0
        phase = VkAuthPhase.LOGIN
        onPhaseChange(phase)
        lastUrl = null
        startedAt = System.currentTimeMillis()
        // Новая пара PKCE: старый код/состояние к этому входу уже не относятся.
        pkce = VkIdOAuth.newPkce()
        clearSessionCookiesNow()
        onAppLoginNote("Начинаем вход заново: войдите в этом окне логином и паролем")
        view.stopLoading()
        loadLoginPage(view)
        scheduleMonitor()
        return true
    }

    /** Гасит именно сессионные куки ВК: служебные трогать незачем. */
    private fun clearSessionCookiesNow() {
        val manager = CookieManager.getInstance()
        runCatching {
            VK_AUTH_COOKIE_ORIGINS.forEach { origin ->
                val current = manager.getCookie(origin).orEmpty()
                if (current.isBlank()) return@forEach
                val headers = current.split(';')
                    .map { it.substringBefore('=').trim() }
                    .filter { VkAuthSecurity.isVkSessionCookieName(it) }
                    .map { name ->
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure"
                    }
                headers.forEach { manager.setCookie(origin, it) }
            }
            manager.flush()
        }
    }

    private fun finishWithToken(token: String, cookies: String) {
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { CookieManager.getInstance().flush() }
        onToken(
            VkAuthPayload(
                token = token,
                userId = "",
                expiresIn = 0L,
                cookies = VkTokenScraper.trimCookieHeader(cookies),
            ),
        )
    }

    /**
     * Страница входа. Мобильная версия открывается сразу формой логина и не
     * показывает витрину с кнопкой «Войти через приложение», которая всё равно
     * не может передать нам сессию приложения ВК.
     */
    private fun loadLoginPage(view: WebView) {
        // Обычная страница ВК: она открывается всегда и даёт вход логином,
        // а токен из неё получается по кукам. Страницу VK ID открываем только
        // по отдельной кнопке: наше приложение в VK ID не зарегистрировано, и
        // `id.vk.ru/authorize` отвечает `{"error":"not found"}` — пустое окно
        // вместо формы входа никому не помогает.
        vkIdAttempt = false
        view.loadUrl(MrttConstants.VkAutoHash.VK_MOBILE_LOGIN_URL)
    }

    /**
     * Вход через приложение ВК: пробуем протокол VK ID (PKCE) и сразу открываем
     * приложение ВК на нашем запросе подтверждения.
     *
     * Возвращает строку для окна: человек должен понимать, что произошло.
     */
    fun startVkIdLogin(context: Context): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startVkIdLogin(context) }
            return "Открываем вход через приложение ВК…"
        }
        val view = webView ?: return "Окно входа закрыто"
        finished = false
        phase = VkAuthPhase.LOGIN
        onPhaseChange(phase)
        pkce = VkIdOAuth.newPkce()
        vkIdAttempt = true
        vkIdPageFellBack = false
        lastUrl = null
        view.stopLoading()
        view.loadUrl(VkIdOAuth.authorizeUrl(pkce))
        scheduleMonitor()
        return openVkApp(context)
    }

    private fun isVkLoggedIn(): Boolean = hasSessionCookie()

    /**
     * Куки сессии после входа в приложении VK.
     *
     * Вход «через приложение» проходит в другом процессе, и его куки лежат в
     * общем CookieManager: если WebView уже пустил на свою страницу ВК, сессия
     * видна и здесь. На этом и держится подхват входа после возврата в MRTT.
     */
    fun hasSessionCookie(): Boolean {
        // Только настоящие сессионные куки (`remixsid` и родственные). Раньше
        // здесь хватало любой куки длиннее 24 символов, и после возврата из
        // приложения ВК окно уходило за токеном с пустышкой: web_token отвечал
        // отказом, человек видел ошибку и не мог войти вообще.
        return VkAuthSecurity.hasVkSessionCookie(collectVkCookieHeader())
    }

    /** Все куки сессии ВК одной строкой: их обмениваем на токен. */
    fun collectVkCookieHeader(): String {
        val manager = CookieManager.getInstance()
        val builder = StringBuilder()
        for (origin in VK_AUTH_COOKIE_ORIGINS) {
            val value = runCatching { manager.getCookie(origin) }.getOrNull().orEmpty()
            if (value.isBlank()) continue
            for (cookie in value.split("; ")) {
                if (cookie.isBlank()) continue
                if (builder.isNotEmpty()) builder.append("; ")
                builder.append(cookie)
            }
        }
        return VkTokenScraper.trimCookieHeader(builder.toString())
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            if (finished || paused) return
            val view = webView
            if (view == null) {
                stop()
                return
            }
            if (System.currentTimeMillis() - startedAt > MrttConstants.VkAutoHash.AUTH_TIMEOUT_MS) {
                finishWithError("Тайм-аут ожидания access_token")
                return
            }

            when (phase) {
                VkAuthPhase.LOGIN -> {
                    if (isVkLoggedIn()) {
                        switchToTokenPhase(view)
                    } else {
                        val currentUrl = view.url.orEmpty()
                        val path = runCatching { currentUrl.toUri().path }.getOrNull() ?: ""
                        if (path.startsWith("/feed")) {
                            switchToTokenPhase(view)
                        } else {
                            view.evaluateJavascript(
                                "(function() { return document.body && (document.body.innerText.includes('Мессенджер') || document.body.innerText.includes('Лента')); })();"
                            ) { result ->
                                if (result == "true" && phase == VkAuthPhase.LOGIN) {
                                    switchToTokenPhase(view)
                                }
                            }
                        }
                    }
                }
                VkAuthPhase.TOKEN -> {
                    val url = view.url.orEmpty()
                    if (url != lastUrl && url.isNotBlank()) {
                        lastUrl = url
                        handleUrl(url)
                    }
                }
            }

            scheduleMonitor()
        }
    }

    private fun handleUrl(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        // Токен VK приходит во фрагменте, но часть редиректов кладёт его в query:
        // раньше при отсутствии фрагмента мы просто выходили и вход «зависал» до тайм-аута.
        val fragment = uri.fragment.orEmpty()
        val query = uri.query.orEmpty()
        val candidates = listOf(fragment, query).filter { it.isNotBlank() }
        if (candidates.any { it.contains("access_token=") }) {
            handleAccessToken(candidates.first { it.contains("access_token=") })
            return
        }
        if (candidates.any { it.contains("error=") }) {
            handleOAuthError(candidates.first { it.contains("error=") })
            return
        }
        val payloadSource = candidates.firstOrNull { it.startsWith("payload=") }
        if (payloadSource != null) {
            val host = uri.host.orEmpty()
            if (host == "oauth.vk.com" || host == "oauth.vk.ru" || host == "vkhost.github.io") {
                handleSilentPayload(payloadSource)
            }
        }
    }

    private fun handleAccessToken(fragment: String) {
        val params = parseFragmentParams(fragment)
        val token = params["access_token"]
        if (token.isNullOrBlank()) {
            finishWithError("Не удалось извлечь access_token из URL")
            return
        }
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L
        // Токен с ограниченным сроком не отбрасываем: привязка держится на куках сессии,
        // а свежий токен из них запрашивается заново перед каждым подключением.
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { CookieManager.getInstance().flush() }
        onToken(VkAuthPayload(token = token, userId = params["user_id"].orEmpty(), expiresIn = expiresIn))
    }

    private fun handleSilentPayload(fragment: String) {
        if (silentRetried) return
        val payload = runCatching {
            JSONObject(URLDecoder.decode(fragment.removePrefix("payload="), "UTF-8"))
        }.getOrNull() ?: return
        if (payload.optString("type") != "silent_token") return
        if (payload.optString("token").isBlank()) return

        silentRetried = true
        mainHandler.postDelayed({
            if (!finished) repeatAuthorization()
        }, MrttConstants.VkAutoHash.AUTH_SILENT_RETRY_DELAY_MS)
    }

    private fun handleOAuthError(fragment: String) {
        val params = parseFragmentParams(fragment)
        val message = params["error_description"] ?: params["error"] ?: "Ошибка VK"
        finishWithError(message)
    }

    private fun repeatAuthorization() {
        if (paused) {
            pendingAuthorizationRetry = true
            return
        }
        val view = webView ?: return
        lastUrl = null
        view.loadUrl(MrttConstants.VkAutoHash.VK_OAUTH_AUTH_URL)
    }

    private fun finishWithError(message: String) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        onError(message)
    }

    private fun parseFragmentParams(fragment: String): Map<String, String> {
        val result = HashMap<String, String>()
        fragment.split('&').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq)
            val value = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault("")
            result[key] = value
        }
        return result
    }

    companion object {
        /** Перехват `window.open` и кликов по ссылкам с чужой схемой. */
        private val EXTERNAL_LINK_SHIM_JS = """
            (function() {
              if (window.__mrttExternalShim) return;
              window.__mrttExternalShim = true;
              var external = function(url) {
                return !!url && /^(vk|vkontakte|vkapp|vkme|intent|market|mailto|tg|mrtt):/i.test(url);
              };
              var send = function(url) {
                try { window.MRTTVk && window.MRTTVk.open(url); return true; } catch (e) { return false; }
              };
              // Официальная кнопка ВК «Войти через приложение» внутри окна входа
              // открывает приложение из вложенного фрейма, и переход по `vk://`
              // до WebView не доходит. Ловим само нажатие: эффект тот же — открываем
              // приложение ВК, а сессию забираем при возврате в MRTT.
              var officialAppLogin = function(node) {
                if (!node) return false;
                var label = (node.innerText || node.textContent || node.value || '').replace(/\s+/g, ' ').trim();
                if (!label || label.length > 64) return false;
                return /войти через приложение|войти через приложение вк/i.test(label) && !/не установлено/i.test(label);
              };
              var originalOpen = window.open;
              window.open = function(url, name, features) {
                if (external(url)) { send(url); return null; }
                return originalOpen ? originalOpen.call(window, url, name, features) : null;
              };
              // Официальная кнопка ВК в новой разметке: у неё нет текста «Войти
              // через приложение» в самом элементе, зато есть data-атрибуты.
              var appLoginByMarkup = function(node) {
                var probe = node;
                while (probe && probe !== document.body) {
                  if (probe.getAttribute) {
                    var href = probe.getAttribute('href') || '';
                    var action = probe.getAttribute('data-action') || probe.getAttribute('data-testid') || '';
                    if (/^vk:|^intent:/i.test(href) || /app[_\- ]?login|one[_\- ]?tap|login[_\- ]?app/i.test(action)) {
                      return true;
                    }
                  }
                  probe = probe.parentNode;
                }
                return false;
              };
              document.addEventListener('click', function(event) {
                var target = event.target;
                if (officialAppLogin(target) || appLoginByMarkup(target)) {
                  event.preventDefault();
                  event.stopPropagation();
                  send('mrtt://app-login');
                  return;
                }
                var node = target;
                while (node && node.tagName !== 'A') node = node.parentNode;
                if (!node) return;
                var href = node.getAttribute('href') || '';
                if (!external(href)) return;
                event.preventDefault();
                event.stopPropagation();
                send(href);
              }, true);
            })();
        """.trimIndent()

        /** Открытое сейчас окно входа: возврат из приложения ВК находит его сам. */
        internal var active: VkAuthSession? = null

        /** Как часто и как долго ждём куки сессии после возврата из приложения ВК. */
        private const val EXTERNAL_LOGIN_POLL_MS = 700L
        private const val EXTERNAL_LOGIN_MAX_POLLS = 20

        /** Обмен кук на токен: одна повторная попытка и пауза между ними. */
        private const val TOKEN_ATTEMPTS_MAX = 2
        private const val TOKEN_RETRY_DELAY_MS = 900L

        /**
         * Честная строка для случая «нажали “Войти через приложение”, вернулись,
         * а сессии в MRTT нет»: приложение ВК хранит вход у себя.
         */
        private const val APP_LOGIN_NO_SESSION_NOTE =
            "Приложение ВК не передало вход в MRTT — так бывает всегда: " +
                "войдите в этом окне логином и паролем" 

        /** Настоящее приложение ВК. Других «VK-клиентов» не открываем: это подделки. */
        private const val VK_APP_PACKAGE = "com.vkontakte.android"

        /** Браузеры, которыми открываем страницу входа, если приложения ВК нет. */
        private val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.yandex.browser",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
        )

        private const val TV_FOCUS_FIRST_ELEMENT_JS = """
            (function() {
              if (window.__mrttTvInput) {
                window.__mrttTvInput('ensure-focus');
                return;
              }
              var selector = 'a[href],button,input:not([type=hidden]),select,textarea,[role=button],[tabindex]:not([tabindex="-1"])';
              function elements() {
                return Array.prototype.filter.call(document.querySelectorAll(selector), function(node) {
                  var style = window.getComputedStyle(node);
                  return !node.disabled && style.display !== 'none' && style.visibility !== 'hidden' && node.getClientRects().length > 0;
                });
              }
              function focus(node) {
                if (!node) return false;
                node.focus({preventScroll: true});
                node.scrollIntoView({block: 'center', inline: 'nearest'});
                return true;
              }
              window.__mrttTvInput = function(command) {
                var items = elements();
                var active = document.activeElement;
                var index = items.indexOf(active);
                if (command === 'ensure-focus') {
                  return index >= 0 || focus(items[0]);
                }
                if (command === 'next' || command === 'previous') {
                  if (!items.length) return false;
                  var direction = command === 'next' ? 1 : -1;
                  var next = index < 0 ? (direction > 0 ? 0 : items.length - 1) : (index + direction + items.length) % items.length;
                  return focus(items[next]);
                }
                if (command === 'activate') {
                  if (index < 0 && !focus(items[0])) return false;
                  active = document.activeElement;
                  if (!active) return false;
                  active.click();
                  return true;
                }
                return false;
              };
              window.__mrttTvInput('ensure-focus');
            })();
        """

        private val TOKEN_WAITING_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                    html, body {
                        width: 100%;
                        height: 100%;
                        margin: 0;
                        background: #f2f3f5;
                        font-family: Arial, sans-serif;
                    }
                    body {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        gap: 18px;
                    }
                    .title {
                        color: #2787f5;
                        font-size: 20px;
                        font-weight: 600;
                    }
                    .dots span {
                        display: inline-block;
                        width: 10px;
                        height: 10px;
                        margin: 0 4px;
                        border-radius: 50%;
                        background: #2787f5;
                        animation: mrtt-blink 1.2s infinite ease-in-out;
                    }
                    .dots span:nth-child(2) { animation-delay: .2s; }
                    .dots span:nth-child(3) { animation-delay: .4s; }
                    @keyframes mrtt-blink {
                        0%, 80%, 100% { opacity: .25; transform: scale(.85); }
                        40% { opacity: 1; transform: scale(1); }
                    }
                </style>
            </head>
            <body>
                <div class="title">Секунду…</div>
                <div class="dots"><span></span><span></span><span></span></div>
            </body>
            </html>
        """.trimIndent()
    }
}
