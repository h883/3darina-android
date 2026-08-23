package com.coinarina3d.myapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    /** バックグラウンドに回った時刻（復帰時に読み直すか判断するため）。 */
    private var backgroundedAt = 0L

    /** アカウント選択シートの二重表示を防ぐ。 */
    private var signInInProgress = false

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

        // 保存したセッションを復元すると古いページのまま復帰することがあるため、常に読み直す。
        // サイトを更新したのにアプリだけ古いまま、という状態を作らない。
        webView.loadUrl(GameSite.START_URL)
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
        webView.addJavascriptInterface(NativeBridge(), GoogleAuthBridge.JS_INTERFACE_NAME)
    }

    private inner class GameWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            if (GameSite.isInternal(uri)) return false
            // Google 認証ページへの遷移は外部ブラウザに出さず、アプリ内ログインに置き換える。
            if (isGoogleAuthUrl(uri)) {
                startGoogleSignIn()
                return true
            }
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
            view.evaluateJavascript(GoogleAuthBridge.INJECTED_JS, null)
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

    // ---------------------------------------------------------- google login

    /** ページから呼ばれるネイティブ側の窓口。 */
    private inner class NativeBridge {

        /** ログインボタンのタップ（JS 側で横取り済み）。 */
        @JavascriptInterface
        fun signInWithGoogle() {
            runOnUiThread { startGoogleSignIn() }
        }

        /** Firebase 側でのサインイン失敗を通知してもらう。 */
        @JavascriptInterface
        fun onAuthError(message: String) {
            runOnUiThread { toastSignInFailure(message) }
        }
    }

    private fun isGoogleAuthUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host == "accounts.google.com") return true
        // Firebase の認証ハンドラ（signInWithPopup / signInWithRedirect の遷移先）
        return host.endsWith(".firebaseapp.com") && uri.path?.startsWith("/__/auth") == true
    }

    /**
     * 端末に登録済みの Google アカウントをアプリ内のシートで選ばせ、ID トークンを取得する。
     * ブラウザは一切開かない。
     */
    private fun startGoogleSignIn() {
        if (signInInProgress) return
        if (GoogleAuthBridge.WEB_CLIENT_ID.isBlank()) {
            Toast.makeText(this, R.string.client_id_missing, Toast.LENGTH_LONG).show()
            return
        }
        signInInProgress = true

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(GoogleAuthBridge.WEB_CLIENT_ID).build()
            )
            .build()

        lifecycleScope.launch {
            try {
                val response = CredentialManager.create(this@MainActivity)
                    .getCredential(this@MainActivity, request)
                passIdTokenToPage(response)
            } catch (e: GetCredentialCancellationException) {
                // ユーザーがシートを閉じただけなので何も出さない
            } catch (e: NoCredentialException) {
                Toast.makeText(this@MainActivity, R.string.no_google_account, Toast.LENGTH_LONG)
                    .show()
            } catch (e: GetCredentialException) {
                toastSignInFailure(e.message ?: e.javaClass.simpleName)
            } finally {
                signInInProgress = false
            }
        }
    }

    private fun passIdTokenToPage(response: GetCredentialResponse) {
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            toastSignInFailure(credential.type)
            return
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        webView.evaluateJavascript(
            "window.__caNativeGoogleAuth(${JSONObject.quote(idToken)})",
            null,
        )
    }

    private fun toastSignInFailure(reason: String) {
        Toast.makeText(this, getString(R.string.google_signin_failed, reason), Toast.LENGTH_LONG)
            .show()
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

    override fun onPause() {
        super.onPause()
        backgroundedAt = SystemClock.elapsedRealtime()
        webView.onPause()   // バックグラウンドで描画と JS タイマーを止める
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyImmersiveMode()
        reloadIfStale()
    }

    /**
     * 長時間バックグラウンドにいた後の復帰では、ページを読み直してサイトの更新を取り込む。
     * ただし対戦中は切断してしまうので、メニューに戻っているときだけにする。
     */
    private fun reloadIfStale() {
        val awayFor = SystemClock.elapsedRealtime() - backgroundedAt
        if (backgroundedAt == 0L || awayFor < RELOAD_AFTER_BACKGROUND_MS) return
        backgroundedAt = 0L

        webView.evaluateJavascript(MENU_VISIBLE_JS) { result ->
            if (result?.contains("idle") == true) webView.reload()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** これ以上バックグラウンドにいたら、復帰時に読み直す。 */
        const val RELOAD_AFTER_BACKGROUND_MS = 10 * 60 * 1000L

        /**
         * メニュー（ログイン・ルーム選択）が見えているかを調べる。
         * 判定できないときは busy を返し、読み直さない側に倒す。
         */
        const val MENU_VISIBLE_JS = """
            (function(){
              try {
                var overlay = document.getElementById('overlay');
                if (!overlay) return 'busy';
                return getComputedStyle(overlay).display !== 'none' ? 'idle' : 'busy';
              } catch (e) { return 'busy'; }
            })();
        """
    }
}
