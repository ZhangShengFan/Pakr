package com.webviewapp

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ClipData
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: TopProgressBar
    private lateinit var overlay: View

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = false
    private var isFirstLoad = true
    private var pageVisibleCommitted = false

    private val timeoutRunnable = Runnable { hideOverlay() }
    private val renderTimeoutRunnable = Runnable {
        if (!pageVisibleCommitted && !isShowingError) {
            showWebErrorPage(webView.url ?: APP_URL, "页面长时间未正常渲染，可能被网站限制或渲染失败")
        }
    }

    // 错误页状态：防止 onPageFinished 在错误页上执行主题色注入
    private var isShowingError = false
    private var failedUrl: String? = null
    private var lastBlockedHint: String? = null
    private var lastConsoleError: String? = null

    // 按需权限：存储待处理的 web 权限请求
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraImageUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data != null -> parseSelectedUris(result.data!!)
            cameraVideoUri != null -> arrayOf(cameraVideoUri!!)
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
        revokeCapturedUriPermissions()
        fileChooserCallbackRef?.onReceiveValue(results)
        fileChooserCallbackRef = null
        pendingFileChooserParams = null
        cameraImageUri = null
        cameraVideoUri = null
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingFileChooserParams?.let { launchFileChooser(it) }
        } else {
            revokeCapturedUriPermissions()
            fileChooserCallbackRef?.onReceiveValue(null)
            fileChooserCallbackRef = null
            pendingFileChooserParams = null
            cameraImageUri = null
            cameraVideoUri = null
            android.widget.Toast.makeText(this, "缺少文件上传所需权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if ("{{NO_SCREENSHOT}}" == "true") {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_main)
        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlay     = findViewById(R.id.overlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#6366F1")
        )
        swipeRefresh.setOnRefreshListener {
            isShowingError = false
            failedUrl = null
            webView.reload()
        }
        showOverlay()
        setupWebView()
    }

    private fun showBlankPageError(url: String, detail: String) {
        showWebErrorPage(url, "页面未正常显示：$detail")
    }

    private fun inspectBlankPage(view: WebView, url: String) {
        if (isShowingError) return
        view.postVisualStateCallback(System.currentTimeMillis()) {
            view.evaluateJavascript(
                """
                (function(){
                  try{
                    var body=document.body;
                    var text=(body&&body.innerText?body.innerText:'').trim();
                    var html=(document.documentElement&&document.documentElement.outerHTML?document.documentElement.outerHTML:'');
                    var bg=window.getComputedStyle(document.body||document.documentElement).backgroundColor||'';
                    return JSON.stringify({
                      textLength:text.length,
                      htmlLength:html.length,
                      title:document.title||'',
                      bg:bg
                    });
                  }catch(e){
                    return JSON.stringify({error:String(e)});
                  }
                })();
                """.trimIndent()
            ) { raw ->
                val payload = raw.orEmpty()
                val looksBlank = payload.contains(""textLength":0") && !payload.contains(""htmlLength":0")
                val jsError = lastConsoleError
                if (!isShowingError && (looksBlank || !jsError.isNullOrBlank())) {
                    val detail = jsError ?: "页面内容为空或未渲染完成"
                    showBlankPageError(url, detail)
                }
            }
        }
    }

    private fun showBlockedBySitePage(url: String, reason: String) {
        hideOverlay()
        handler.removeCallbacks(renderTimeoutRunnable)
        pageVisibleCommitted = false
        isShowingError = true
        failedUrl = url
        lastBlockedHint = reason
        swipeRefresh.isRefreshing = false
        val safeUrl = url.replace("'", "&#39;")
        val safeReason = reason.replace("'", "&#39;")
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width,initial-scale=1" />
                <title>当前网站不支持内置打开</title>
                <style>
                    body{margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;background:#0f172a;color:#e5e7eb;display:flex;align-items:center;justify-content:center;min-height:100vh}
                    .card{max-width:560px;width:100%;background:#111827;border:1px solid rgba(255,255,255,.08);border-radius:20px;padding:24px;box-shadow:0 10px 30px rgba(0,0,0,.35)}
                    h1{margin:0 0 12px;font-size:24px;color:#fff}
                    p{margin:0 0 12px;line-height:1.7;color:#cbd5e1;word-break:break-word}
                    .url{font-size:13px;color:#93c5fd;background:rgba(59,130,246,.12);padding:10px 12px;border-radius:12px}
                    .actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:18px}
                    button{border:0;border-radius:12px;padding:12px 16px;font-size:15px;font-weight:600;cursor:pointer}
                    .primary{background:#2563eb;color:#fff}
                    .secondary{background:#1f2937;color:#e5e7eb;border:1px solid rgba(255,255,255,.08)}
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>该网站可能限制 WebView 打开</h1>
                    <p>这个网站在当前 App 内置浏览环境中未正常显示，常见原因包括登录风控、OAuth 限制、支付安全策略，或网站主动禁止 WebView。</p>
                    <p>检测结果：""" + safeReason + """</p>
                    <p class="url">""" + safeUrl + """</p>
                    <div class="actions">
                        <button class="primary" onclick="Android.openExternal('""" + safeUrl + """')">在系统浏览器打开</button>
                        <button class="secondary" onclick="location.reload()">重试</button>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
    }

    private fun looksLikeWebViewBlocked(url: String, title: String?, html: String?): String? {
        val target = listOf(url, title.orEmpty(), html.orEmpty()).joinToString("
").lowercase()
        val patterns = listOf(
            "disallowed_useragent" to "检测到 OAuth / 登录流程禁止嵌入式浏览器",
            "unsupported-browser" to "网站提示当前内置浏览器不受支持",
            "browser not supported" to "网站提示当前浏览器不受支持",
            "open in your browser" to "网站要求在外部浏览器中打开",
            "open in browser" to "网站要求在系统浏览器中打开",
            "not supported in webview" to "网站明确提示不支持 WebView",
            "embedded browser" to "网站检测到嵌入式浏览器",
            "couldn't sign you in" to "当前登录流程可能禁止 WebView",
            "403 forbidden" to "网站拒绝了当前内置访问请求"
        )
        return patterns.firstOrNull { target.contains(it.first) }?.second
    }

    private fun showWebErrorPage(url: String, message: String) {
        hideOverlay()
        handler.removeCallbacks(renderTimeoutRunnable)
        pageVisibleCommitted = false
        isShowingError = true
        failedUrl = url
        swipeRefresh.isRefreshing = false
        webView.loadDataWithBaseURL(url, errorHtml(url, message), "text/html", "UTF-8", url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(false)
            builtInZoomControls              = false
            displayZoomControls              = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 仅当加载的不是错误页本身时才重置错误状态
                // loadDataWithBaseURL 会触发 onPageStarted(url=failedUrl)，需跳过
                if (url != "about:blank" && url != failedUrl) {
                    isShowingError = false
                    failedUrl = null
                    lastBlockedHint = null
                    lastConsoleError = null
                }
                pageVisibleCommitted = false
                handler.removeCallbacks(renderTimeoutRunnable)
                handler.postDelayed(renderTimeoutRunnable, 12000)
                if (isFirstLoad) showOverlay()
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                handler.removeCallbacks(renderTimeoutRunnable)
                if (!isShowingError) {
                    view.evaluateJavascript("(function(){try{return document.documentElement.outerHTML.slice(0,4000)}catch(e){return ''}})();") { raw ->
                        val html = raw
                            ?.removePrefix(""")
                            ?.removeSuffix(""")
                            ?.replace("\u003C", "<")
                            ?.replace("\u003E", ">")
                            ?.replace("\n", "
")
                            ?.replace("\"", """)
                        val hint = looksLikeWebViewBlocked(url, view.title, html)
                        if (!hint.isNullOrBlank() && !isShowingError) {
                            showBlockedBySitePage(url, hint)
                        } else if (!isShowingError) {
                            fetchThemeColor(view)
                            inspectBlankPage(view, url)
                        }
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                pageVisibleCommitted = true
                handler.removeCallbacks(renderTimeoutRunnable)
                hideOverlay()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    val code = errorResponse.statusCode
                    val reason = errorResponse.reasonPhrase?.takeIf { it.isNotBlank() } ?: "HTTP 错误"
                    showWebErrorPage(request.url.toString(), "页面返回异常：HTTP $code $reason")
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                val msg = when (error.primaryError) {
                    SslError.SSL_UNTRUSTED -> "证书不受信任"
                    SslError.SSL_EXPIRED -> "证书已过期"
                    SslError.SSL_IDMISMATCH -> "证书域名不匹配"
                    SslError.SSL_NOTYETVALID -> "证书尚未生效"
                    else -> "SSL 证书异常"
                }
                showWebErrorPage(error.url ?: (view.url ?: APP_URL), msg)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val reason = if (detail.didCrash()) {
                    "页面渲染进程已崩溃"
                } else {
                    "页面渲染进程已被系统回收"
                }
                showWebErrorPage(view.url ?: APP_URL, reason)
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    hideOverlay()
                    val errDesc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        when (error.errorCode) {
                            android.webkit.WebViewClient.ERROR_HOST_LOOKUP        -> "域名解析失败，请检查网络连接"
                            -11 /* ERROR_INTERNET_DISCONNECTED */                 -> "网络未连接，请打开 Wi-Fi 或移动数据"
                            android.webkit.WebViewClient.ERROR_CONNECT            -> "无法连接到服务器"
                            android.webkit.WebViewClient.ERROR_TIMEOUT            -> "连接超时，请稍后重试"
                            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "SSL 握手失败，证书可能有问题"
                            android.webkit.WebViewClient.ERROR_UNKNOWN            -> "未知错误"
                            else -> error.description?.toString()?.takeIf { it.isNotBlank() } ?: "加载失败（错误码 \${error.errorCode}）"
                        }
                    } else {
                        "网络连接失败，请检查网络后重试"
                    }
                    showWebErrorPage(request.url.toString(), errDesc)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    lastConsoleError = consoleMessage.message()
                    Log.e("PakrWebView", consoleMessage.message() + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber())
                }
                return super.onConsoleMessage(consoleMessage)
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgress(newProgress)
                if (newProgress >= 75 && isFirstLoad) {
                    hideOverlay()
                    isFirstLoad = false
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                val androidPerms = mutableListOf<String>()
                for (res in request.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            androidPerms.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                val toRequest = androidPerms.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (toRequest.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermissionRequest = request
                    ActivityCompat.requestPermissions(
                        this@MainActivity, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE
                    )
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                val perms = arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
                val toRequest = perms.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (toRequest.isEmpty()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin   = origin
                    ActivityCompat.requestPermissions(
                        this@MainActivity, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE + 1
                    )
                }
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileChooserCallbackRef?.onReceiveValue(null)
                fileChooserCallbackRef = filePathCallback
                pendingFileChooserParams = fileChooserParams
                cameraImageUri = null
                cameraVideoUri = null
                val perms = requiredFileChooserPermissions(fileChooserParams)
                val denied = perms.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (denied.isEmpty()) {
                    launchFileChooser(fileChooserParams)
                } else {
                    mediaPermissionLauncher.launch(denied.toTypedArray())
                }
                return true
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("正在下载...")
                    setTitle(filename)
                    if (android.os.Build.VERSION.SDK_INT < 29) {
                        @Suppress("DEPRECATION") allowScanningByMediaScanner()
                    }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                android.widget.Toast.makeText(this, "开始下载：$filename", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        // 键盘弹出适配：FLAG_FULLSCREEN 下 adjustResize 失效，手动监听 Insets
        val rootView = window.decorView.rootView
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val navHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            webView.setPadding(0, 0, 0, if (imeHeight > 0) imeHeight - navHeight else 0)
            insets
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onThemeColor(hex: String) {
                try {
                    val color = android.graphics.Color.parseColor(hex)
                    runOnUiThread { progressBar.setBarColor(color) }
                } catch (e: Exception) {}
            }
        }, "ThemeBridge")

        // ── NativeBridge：供网页调用的原生功能 ──
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun vibrate(ms: Long) {
                try {
                    val dur = ms.coerceIn(1, 2000)
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        val vm = getSystemService(android.os.VibratorManager::class.java)
                        vm?.defaultVibrator?.vibrate(
                            VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        if (android.os.Build.VERSION.SDK_INT >= 26)
                            v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                        else
                            @Suppress("DEPRECATION") v.vibrate(dur)
                    }
                } catch (e: Exception) {}
            }

            @JavascriptInterface
            fun share(title: String, text: String, url: String) {
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, title)
                        putExtra(Intent.EXTRA_TEXT, if (url.isNotEmpty()) "$text\n$url" else text)
                    }
                    startActivity(Intent.createChooser(intent, title))
                }
            }

            @JavascriptInterface
            fun toast(msg: String) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            @JavascriptInterface
            fun getAppVersion(): String = APP_VERSION

            @JavascriptInterface
            fun openExternal(url: String) {
                runOnUiThread {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                }
            }

            @JavascriptInterface
            fun back() {
                runOnUiThread { if (webView.canGoBack()) webView.goBack() }
            }

            @JavascriptInterface
            fun reload() {
                runOnUiThread {
                    isShowingError = false
                    failedUrl = null
                    lastBlockedHint = null
                    lastConsoleError = null
                    webView.reload()
                }
            }

            @JavascriptInterface
            fun getPermissions(): String = "camera,microphone,location,storage"
        }, "NativeBridge")
        webView.loadUrl(APP_URL)
    }

    private fun fetchThemeColor(view: WebView) {
        val js = """
            (function() {
                var m = document.querySelector('meta[name="theme-color"]');
                if (m && m.content) { ThemeBridge.onThemeColor(m.content); return; }
                var el = document.elementFromPoint(window.innerWidth/2, 1);
                if (el) {
                    var bg = getComputedStyle(el).backgroundColor;
                    var r = bg.match(/rgba?\((\d+),(\d+),(\d+)/);
                    if (r) ThemeBridge.onThemeColor(
                        '#' + [r[1],r[2],r[3]].map(function(x){
                            return ('0' + parseInt(x).toString(16)).slice(-2);
                        }).join('')
                    );
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showOverlay() {
        if (overlayVisible) return
        overlayVisible = true
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        progressBar.setProgress(0)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10_000L)
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        handler.removeCallbacks(timeoutRunnable)
        overlayVisible = false
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    private fun errorHtml(url: String?, errDesc: String? = null): String {
        val safeUrl  = url?.replace("'", "\'") ?: "about:blank"
        val safeDesc = (errDesc ?: "网络连接失败，请检查网络后重试")
            .replace("&", "&amp;").replace("<", "&lt;")
        return """<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;display:flex;align-items:center;justify-content:center;height:100vh;font-family:-apple-system,sans-serif;flex-direction:column;background:#fff;color:#1a1a1a;padding:32px;box-sizing:border-box;text-align:center;">
<svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5" style="margin-bottom:8px;flex-shrink:0"><circle cx="12" cy="12" r="9"/><path d="M4.93 4.93l14.14 14.14"/></svg>
<p style="margin:12px 0 6px;font-size:17px;font-weight:600;color:#111;">网页加载失败</p>
<p style="margin:0 0 28px;font-size:13px;color:#888;max-width:260px;line-height:1.6">${"$"}{safeDesc}</p>
<button onclick="if(window.NativeBridge){NativeBridge.reload()}else{window.location.href='${"$"}{safeUrl}'}" style="padding:13px 36px;border:none;border-radius:999px;background:#111;color:#fff;font-size:15px;cursor:pointer;font-family:-apple-system,sans-serif;font-weight:500;-webkit-tap-highlight-color:transparent;active:opacity:.8">重试</button>
</body></html>""".trimIndent()
    }

    private var backPressedTime = 0L
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            } else {
                backPressedTime = now
                android.widget.Toast.makeText(this, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); webView.destroy(); super.onDestroy() }

    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val req = pendingWebPermissionRequest
                if (req != null) {
                    pendingWebPermissionRequest = null
                    if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                        req.grant(req.resources)
                    } else {
                        req.deny()
                    }
                }
            }
            PERMISSION_REQUEST_CODE + 1 -> {
                val cb     = pendingGeoCallback
                val origin = pendingGeoOrigin
                pendingGeoCallback = null
                pendingGeoOrigin   = null
                if (cb != null && origin != null) {
                    val granted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    cb.invoke(origin, granted, false)
                }
            }
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun createVideoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("VIDEO_${timeStamp}_", ".mp4", storageDir)
    }

    private fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams) {
        try {
            val contentIntent = fileChooserParams.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            val chooserIntents = mutableListOf<Intent>()
            val acceptTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
            val acceptJoined = acceptTypes.joinToString(",").lowercase()
            val wantsImage = acceptJoined.isBlank() || acceptJoined.contains("image")
            val wantsVideo = acceptJoined.contains("video")
            val wantsAudio = acceptJoined.contains("audio")

            if (wantsImage) {
                val photoFile = createImageFile()
                cameraImageUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    photoFile
                )
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("captured_image", cameraImageUri)
                }
                grantUriPermissionsToResolvedActivities(cameraIntent, cameraImageUri!!)
                if (cameraIntent.resolveActivity(packageManager) != null) {
                    chooserIntents.add(cameraIntent)
                    if (fileChooserParams.isCaptureEnabled && acceptTypes.all { it.isBlank() || it.startsWith("image/") }) {
                        fileChooserLauncher.launch(cameraIntent)
                        return
                    }
                }
            }

            if (wantsVideo) {
                val videoFile = createVideoFile()
                cameraVideoUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    videoFile
                )
                val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraVideoUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("captured_video", cameraVideoUri)
                }
                grantUriPermissionsToResolvedActivities(videoIntent, cameraVideoUri!!)
                if (videoIntent.resolveActivity(packageManager) != null) {
                    chooserIntents.add(videoIntent)
                    if (fileChooserParams.isCaptureEnabled && acceptTypes.all { it.startsWith("video/") }) {
                        fileChooserLauncher.launch(videoIntent)
                        return
                    }
                }
            }

            if (wantsAudio) {
                val audioIntent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                if (audioIntent.resolveActivity(packageManager) != null) {
                    chooserIntents.add(audioIntent)
                    if (fileChooserParams.isCaptureEnabled && acceptTypes.all { it.startsWith("audio/") }) {
                        fileChooserLauncher.launch(audioIntent)
                        return
                    }
                }
            }

            val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentIntent)
                putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserIntents.toTypedArray())
                putExtra(Intent.EXTRA_TITLE, "选择文件")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (contentIntent.resolveActivity(packageManager) != null) {
                fileChooserLauncher.launch(chooser)
            } else {
                throw IllegalStateException("No file chooser available")
            }
        } catch (e: Exception) {
            revokeCapturedUriPermissions()
            fileChooserCallbackRef?.onReceiveValue(null)
            fileChooserCallbackRef = null
            pendingFileChooserParams = null
            cameraImageUri = null
            cameraVideoUri = null
            android.widget.Toast.makeText(this, "无法打开文件选择器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSelectedUris(data: Intent): Array<Uri>? {
        val clipData = data.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        val parsed = WebChromeClient.FileChooserParams.parseResult(RESULT_OK, data)
        if (!parsed.isNullOrEmpty()) return parsed
        data.data?.let { return arrayOf(it) }
        return null
    }

    private fun grantUriPermissionsToResolvedActivities(intent: Intent, uri: Uri) {
        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resolveInfos) {
            grantUriPermission(
                resolveInfo.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun revokeCapturedUriPermissions() {
        cameraImageUri?.let {
            try {
                revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
        }
        cameraVideoUri?.let {
            try {
                revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
        }
    }

    private fun requiredFileChooserPermissions(fileChooserParams: WebChromeClient.FileChooserParams): Array<String> {
        val perms = linkedSetOf<String>()
        val acceptTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
        val acceptJoined = acceptTypes.joinToString(",").lowercase()
        val wantsImage = acceptJoined.isBlank() || acceptJoined.contains("image")
        val wantsVideo = acceptJoined.contains("video")
        val wantsAudio = acceptJoined.contains("audio")
        if (fileChooserParams.isCaptureEnabled && wantsImage) {
            perms.add(android.Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (wantsImage || acceptJoined.isBlank()) perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (wantsVideo) perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            if (wantsAudio) perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.toTypedArray()
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun createVideoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("VIDEO_${timeStamp}_", ".mp4", storageDir)
    }

    companion object {
        const val APP_URL = "{{APP_URL}}"
        const val APP_VERSION = "{{VERSION_NAME}}"
        const val PERMISSION_REQUEST_CODE = 1002
    }


}
