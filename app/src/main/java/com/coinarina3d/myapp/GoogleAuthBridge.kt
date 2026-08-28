package com.coinarina3d.myapp

/**
 * アプリ内 Google ログインの設定と、ページへ差し込む JavaScript。
 *
 * ページ（game3d_arena.html）は Firebase の signInWithPopup を使うが、WebView はポップアップを
 * 開けないため、認証ハンドラへの遷移＝外部ブラウザ移動になってしまう。
 * そこで「ログインボタンのタップを横取り → 端末の Google アカウント選択（Credential Manager）
 * → 得た ID トークンをページの Firebase インスタンスへ渡す」流れに差し替える。ブラウザは開かない。
 */
object GoogleAuthBridge {

    /**
     * ★ここに Firebase のウェブクライアント ID を貼る★
     *
     * Firebase コンソール → プロジェクト oic-teis-app → Authentication → Sign-in method →
     * Google → 「ウェブ SDK の設定」に表示される ID（末尾 .apps.googleusercontent.com）。
     *
     * ページ内にある 893380824378-... は別プロジェクトのものなので使えない。
     * 空にするとアプリ内ログインは動かず、その旨をトーストで知らせる。
     */
    const val WEB_CLIENT_ID = "532952437299-rkvu8lkqrhm2amn19so01bh434m8b2un.apps.googleusercontent.com"

    /** ページが読み込んでいる Firebase SDK と同じバージョンを指すこと。 */
    private const val FIREBASE_SDK = "https://www.gstatic.com/firebasejs/10.12.2/"

    /** WebView に公開するネイティブ側の名前。 */
    const val JS_INTERFACE_NAME = "CoinArenaNative"

    /**
     * onPageFinished ごとに流し込むスクリプト。
     * 同じ URL の ES モジュールはドキュメント内で共有されるため、ここで import した
     * firebase-app / firebase-auth はページ側が初期化した Firebase アプリと同一インスタンスになる。
     */
    val INJECTED_JS: String = """
        (function(){
          if (window.__caNativeAuthReady) return;
          window.__caNativeAuthReady = true;
          var FB = '$FIREBASE_SDK';

          // ネイティブ側で取得した ID トークンで Firebase にサインインする。
          // 成功するとページ側の onAuthStateChanged が走り、Web と同じログイン状態になる。
          window.__caNativeGoogleAuth = async function(idToken){
            try {
              var appMod = await import(FB + 'firebase-app.js');
              var authMod = await import(FB + 'firebase-auth.js');
              var app = appMod.getApps()[0];
              for (var i = 0; !app && i < 40; i++) {
                await new Promise(function(r){ setTimeout(r, 150); });
                app = appMod.getApps()[0];
              }
              if (!app) { $JS_INTERFACE_NAME.onAuthError('firebase-not-ready'); return; }
              var cred = authMod.GoogleAuthProvider.credential(idToken);
              await authMod.signInWithCredential(authMod.getAuth(app), cred);
            } catch (e) {
              $JS_INTERFACE_NAME.onAuthError(String((e && e.message) || e));
            }
          };

          // ログインボタンのタップを capture 段階で奪い、ページ側のポップアップ処理を走らせない。
          document.addEventListener('click', function(ev){
            var t = ev.target;
            if (!t || !t.closest) return;
            if (!t.closest('#googleLoginBtn, #gbtn')) return;
            ev.preventDefault();
            ev.stopImmediatePropagation();
            $JS_INTERFACE_NAME.signInWithGoogle();
          }, true);
        })();
    """.trimIndent()
}
