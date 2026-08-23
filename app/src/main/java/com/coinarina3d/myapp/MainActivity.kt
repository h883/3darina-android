package com.coinarina3d.myapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * コインアリーナ 3D をフルスクリーンで遊ぶためのシェル。
 * ゲーム本体は https://fally-itwork.net/game3d_arena.html をそのまま WebView で描画する。
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout

    /** requestFullscreen() 用に WebView が渡してくるビュー。 */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** 読み込み失敗を検知したか（成功時にエラー画面を出さないための印）。 */
    private var loadFailed = false
    private var lastBackPressAt = 0L

    /** 通知から起動された場合、読み込み後にお知らせ画面を開く。 */
    private var pendingOpenNotes = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒否されても通常どおり遊べる */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadingView = findViewById(R.id.loadingView)
        errorView = findViewById(R.id.errorView)
        progressBar = findViewById(R.id.progressBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        webView = findViewById(R.id.webView)

        setupWindow()
        setupWebView()
        setupBackHandling()

        findViewById<Button>(R.id.retryButton).setOnClickListener { reload() }

        setupNotices()
        pendingOpenNotes = intent?.getBooleanExtra(NoticeNotifier.EXTRA_OPEN_NOTES, false) == true

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(GameSite.START_URL)
        }
    }

    // ---------------------------------------------------------------- notice

    /** お知らせの定期チェックを仕掛け、必要なら通知の許可を求める。 */
    private fun setupNotices() {
        NoticeNotifier.ensureChannel(this)
        NoticeWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NoticeNotifier.hasPermission(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(NoticeNotifier.EXTRA_OPEN_NOTES, false)) openNotesInPage()
    }

    /** ページ側のお知らせボタンを押す。まだ描画されていないこともあるので少し待つ。 */
    private fun openNotesInPage() {
        webView.evaluateJavascript(
            """
            (function(){
              var n = 0;
              var timer = setInterval(function(){
                var btn = document.getElementById('openNotes');
                if (btn) { clearInterval(timer); btn.click(); }
                else if (++n > 40) { clearInterval(timer); }
              }, 250);
            })();
            """.trimIndent(),
            null,
        )
    }

    // ---------------------------------------------------------------- window

    /** 全画面・画面消灯なし・ノッチまで使う。 */
    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    // --------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        webView.setBackgroundColor(Color.BLACK)
        webView.keepScreenOn = true
        // ゲーム画面での長押し選択・拡大鏡を無効化する。
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        with(webView.settings) {
            javaScriptEnabled = true          // ゲーム本体
            domStorageEnabled = true          // ca3d_settings / ca3d_layout3 などの保存
            mediaPlaybackRequiresUserGesture = false  // 効果音・BGM を即再生
            loadWithOverviewMode = true
            useWideViewPort = true            // <meta viewport> を尊重
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString ${GameSite.UA_SUFFIX}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)  // Firebase / Google 認証用
        }

        webView.webViewClient = GameWebViewClient()
        webView.webChromeClient = GameWebChromeClient()
    }

    private inner class GameWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            if (GameSite.isInternal(uri)) return false
            openExternally(uri)
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? =
            if (AdBlocker.isAd(request.url)) AdBlocker.blockedResponse() else null

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            loadFailed = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (pendingOpenNotes) {
                pendingOpenNotes = false
                openNotesInPage()
            }
            if (!loadFailed) {
                loadingView.visibility = View.GONE
                errorView.visibility = View.GONE
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // メインフレームの失敗だけをエラー画面にする（画像 1 枚の失敗で落とさない）。
            if (request.isForMainFrame) showError()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            // WebGL でメモリ不足になるとレンダラが死ぬことがある。
            // ここで true を返さないとアプリごとクラッシュするため、作り直して復帰する。
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            Toast.makeText(this@MainActivity, R.string.renderer_crashed, Toast.LENGTH_LONG).show()
            recreate()
            return true
        }
    }

    private inner class GameWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
        }

        /** canvas の requestFullscreen() に対応する。 */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            applyImmersiveMode()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            applyImmersiveMode()
        }

        /** マイク・カメラは使わないゲームなので、要求が来ても許可しない。 */
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    // ------------------------------------------------------------ navigation

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> (webView.webChromeClient as? GameWebChromeClient)
                        ?.onHideCustomView()

                    webView.canGoBack() -> webView.goBack()

                    else -> confirmExit()
                }
            }
        })
    }

    /** 対戦中の誤操作で落ちないよう、2 回押しで終了する。 */
    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt < 2000) {
            finish()
        } else {
            lastBackPressAt = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError() {
        loadFailed = true
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun reload() {
        loadFailed = false
        errorView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        webView.loadUrl(GameSite.START_URL)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()   // バックグラウンドで描画と JS タイマーを止める
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyImmersiveMode()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
