package com.mrtt.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object MaxAuthWebViewManager {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun acquire(context: Context, darkTheme: Boolean = false): WebView = createWebView(context, darkTheme)

    fun release(view: WebView) {
        // Closing the window keeps MAX cookies and localStorage for the next login.
        ioScope.launch { runCatching { CookieManager.getInstance().flush() } }
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.webChromeClient = null
        view.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, darkTheme: Boolean): WebView = WebView(context).apply {
        setBackgroundColor(if (darkTheme) Color.rgb(23, 24, 28) else Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    }
}

internal class MaxAuthSession(
    private val requiredGroupId: String = "",
    private val darkTheme: Boolean = false,
    private val onUiReady: () -> Unit = {},
    private val onLoading: () -> Unit = {},
    private val onTokenDetected: () -> Unit = {},
    private val onCredentials: (MaxAuthCredentials) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var finished = false
    private var paused = false
    private var tokenDetected = false
    private var targetOpened = false
    private var uiReadyReported = false
    private var startedAt = 0L
    private var pageStartedAt = 0L
    private var pausedAt = 0L
    private var deviceDiagnosticStarted = false
    private var deviceKeyListReported = false
    private var deviceSourceReported = false

    fun attach(view: WebView) {
        webView = view
        startedAt = SystemClock.elapsedRealtime()
        pageStartedAt = startedAt
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (finished) return
                pageStartedAt = SystemClock.elapsedRealtime()
                uiReadyReported = false
                onLoading()
            }

            override fun onPageFinished(v: WebView, url: String) {
                if (finished) return
                if (MaxAuthPage.isMaxUrl(url)) v.evaluateJavascript(MaxWebViewScripts.theme(darkTheme), null)
                readPage()
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                !isAllowedNavigation(request.url)

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || request.url.path.orEmpty().endsWith(".js")) {
                    finishWithError("Не удалось загрузить MAX. Проверьте подключение к интернету.")
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || request.url.path.orEmpty().endsWith(".js")) {
                    finishWithError("MAX не смог загрузить страницу входа. Попробуйте ещё раз.")
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        if (view.url.isNullOrBlank() || view.url == "about:blank") {
            view.loadUrl(BASE_URL)
        } else {
            readPage()
        }
        scheduleMonitor()
    }

    fun retry() {
        finished = false
        tokenDetected = false
        targetOpened = false
        uiReadyReported = false
        startedAt = SystemClock.elapsedRealtime()
        pageStartedAt = startedAt
        webView?.onResume()
        webView?.loadUrl(BASE_URL)
        scheduleMonitor()
    }

    fun stop() {
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        webView = null
    }

    fun pause() {
        if (finished || paused) return
        paused = true
        pausedAt = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(monitorTick)
        webView?.onPause()
    }

    fun resume() {
        if (finished || !paused) return
        val pauseDuration = (SystemClock.elapsedRealtime() - pausedAt).coerceAtLeast(0L)
        startedAt += pauseDuration
        pageStartedAt += pauseDuration
        paused = false
        webView?.onResume()
        scheduleMonitor()
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            if (finished || paused) return
            if (!uiReadyReported && SystemClock.elapsedRealtime() - pageStartedAt > PAGE_LOAD_TIMEOUT_MS) {
                finishWithError("Форма входа MAX не загрузилась. Попробуйте ещё раз.")
                return
            }
            if (!tokenDetected && SystemClock.elapsedRealtime() - startedAt > TOKEN_TIMEOUT_MS) {
                finishWithError("Не удалось дождаться входа в MAX. Попробуйте ещё раз.")
                return
            }
            readPage()
            scheduleMonitor()
        }
    }

    private fun scheduleMonitor() {
        mainHandler.removeCallbacks(monitorTick)
        if (!finished && !paused) mainHandler.postDelayed(monitorTick, MONITOR_INTERVAL_MS)
    }

    private fun readPage() {
        if (finished || paused) return
        val view = webView ?: return
        if (!MaxAuthPage.isMaxUrl(view.url.orEmpty())) return
        view.evaluateJavascript(READ_PAGE_JS) { result ->
            if (finished || paused) return@evaluateJavascript
            val snapshot = runCatching { JSONObject(result.orEmpty()) }.getOrNull() ?: return@evaluateJavascript
            if (!snapshot.optBoolean("ready")) return@evaluateJavascript
            if (!uiReadyReported) {
                uiReadyReported = true
                onUiReady()
            }
            val page = MaxAuthPage.parse(result.orEmpty()) ?: return@evaluateJavascript
            if (!tokenDetected) {
                tokenDetected = true
                onTokenDetected()
            }
            if (page.deviceId.isBlank() && !deviceDiagnosticStarted) {
                deviceDiagnosticStarted = true
                onDiagnostic("MAX: deviceId не найден на странице $BASE_URL · ищем по ключам localStorage")
                listLocalStorageKeys()
            }
            if (page.deviceId.isNotBlank() && !deviceSourceReported) {
                deviceSourceReported = true
                onDiagnostic("MAX: deviceId найден (${page.deviceSource.ifBlank { "?" }}) · ${page.deviceId.take(8)}…")
            }
            if (requiredGroupId.isNotEmpty() && page.groupId != requiredGroupId && !targetOpened) {
                targetOpened = true
                view.loadUrl("$BASE_URL/$requiredGroupId")
                return@evaluateJavascript
            }
            val credentials = page.credentials(requiredGroupId) ?: return@evaluateJavascript
            finished = true
            mainHandler.removeCallbacksAndMessages(null)
            view.onPause()
            onCredentials(credentials)
        }
    }

    private fun listLocalStorageKeys() {
        val view = webView ?: return
        view.evaluateJavascript(LIST_KEYS_JS) { result ->
            if (finished) return@evaluateJavascript
            if (deviceKeyListReported) return@evaluateJavascript
            deviceKeyListReported = true
            val keys = runCatching { JSONObject("{\"k\":$result}").optString("k").trim('"') }
                .getOrDefault("")
                .ifBlank { "(пусто)" }
            onDiagnostic("MAX: ключи localStorage страницы: $keys")
        }
    }

    private fun finishWithError(message: String) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        onError(message)
    }

    private fun isAllowedNavigation(uri: android.net.Uri): Boolean {
        if (uri.toString() == "about:blank") return true
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return MaxAuthPage.isMaxUrl(uri.toString()) ||
            host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" || host.endsWith(".vk.ru")
    }

    private companion object {
        private const val BASE_URL = "https://web.max.ru"
        private const val MONITOR_INTERVAL_MS = 1500L
        private const val PAGE_LOAD_TIMEOUT_MS = 45_000L
        private const val TOKEN_TIMEOUT_MS = 12L * 60L * 1000L

        private const val READ_PAGE_JS = """
            (function() {
                try {
                    var token = '';
                    try {
                        var raw = localStorage.getItem('__oneme_auth');
                        token = raw ? JSON.parse(raw).token : '';
                    } catch (e) {}
                    // Сервер MAX привязывает токен к устройству: с чужим
                    // deviceId LOGIN отвечает login.cred / FAIL_WRONG_PASSWORD.
                    // Реальное имя ключа не документировано, поэтому смотрим
                    // несколько вероятных мест и перебираем ключи по маске.
                    function clean(value) {
                        if (typeof value === 'number') value = String(value);
                        if (typeof value !== 'string') return '';
                        var v = value.trim().replace(/^"|"$/g, '');
                        if (v.length < 4 || v.length > 128) return '';
                        if (/\s/.test(v)) return '';
                        return v;
                    }
                    function fromObject(raw) {
                        if (!raw) return '';
                        try {
                            var parsed = JSON.parse(raw);
                            if (!parsed || typeof parsed !== 'object') return '';
                            var keys = ['deviceId', 'device_id', 'deviceID', 'device', 'did'];
                            for (var k = 0; k < keys.length; k++) {
                                var v = clean(parsed[keys[k]]);
                                if (v) return v;
                            }
                        } catch (e) {}
                        return '';
                    }
                    var deviceId = '';
                    var deviceSource = '';
                    var direct = ['__oneme_device_id', '__oneme_deviceId', 'oneme_device_id',
                                  'deviceId', 'device_id', 'deviceID'];
                    for (var i = 0; i < direct.length && !deviceId; i++) {
                        try {
                            var v = clean(localStorage.getItem(direct[i]));
                            if (v) { deviceId = v; deviceSource = direct[i]; }
                        } catch (e) {}
                    }
                    if (!deviceId) {
                        try { deviceId = fromObject(localStorage.getItem('__oneme_auth')); } catch (e) {}
                        if (deviceId) deviceSource = '__oneme_auth.deviceId';
                    }
                    if (!deviceId) {
                        try {
                            var keys = [];
                            for (var j = 0; j < localStorage.length; j++) keys.push(localStorage.key(j));
                            for (var n = 0; n < keys.length && !deviceId; n++) {
                                var name = keys[n] || '';
                                var value = '';
                                try { value = localStorage.getItem(name) || ''; } catch (e) { value = ''; }
                                if (/device.?id/i.test(name)) {
                                    var c = clean(value);
                                    if (c) { deviceId = c; deviceSource = name; break; }
                                    var nested = fromObject(value);
                                    if (nested) { deviceId = nested; deviceSource = name + '.deviceId'; break; }
                                }
                                // `__oneme_*`-объект может содержать deviceId полем
                                // под любым регистром.
                                if (/oneme/i.test(name) && value.indexOf('device') !== -1) {
                                    var inner = fromObject(value);
                                    if (inner) { deviceId = inner; deviceSource = name + '.deviceId'; break; }
                                }
                            }
                        } catch (e) {}
                    }
                    var app = document.getElementById('app') || document.body;
                    var ready = !!(app && app.querySelector('button, input, [role="button"]') && app.innerText.trim().length > 20);
                    return {url: location.href, token: typeof token === 'string' ? token : '',
                            deviceId: deviceId, deviceSource: deviceSource, ready: ready};
                } catch (e) { return null; }
            })();
        """

        private const val LIST_KEYS_JS = """
            (function() {
                try {
                    var keys = [];
                    for (var i = 0; i < localStorage.length; i++) {
                        var name = localStorage.key(i);
                        if (name) keys.push(name);
                    }
                    return keys.join(', ');
                } catch (e) { return ''; }
            })();
        """
    }
}
