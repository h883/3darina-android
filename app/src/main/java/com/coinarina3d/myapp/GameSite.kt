package com.coinarina3d.myapp

import android.net.Uri

/** コインアリーナ 3D のサイト設定。 */
object GameSite {

    /** アプリ起動時に開くページ（ゲーム本体）。 */
    const val START_URL = "https://fally-itwork.net/game3d_arena.html"

    private const val HOST = "fally-itwork.net"

    /** WebView 内で開いてよい URL か（自サイトのみ）。 */
    fun isInternal(uri: Uri): Boolean {
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    /**
     * User-Agent の末尾に付ける識別子。
     * サイト側で `navigator.userAgent.includes('CoinArenaApp')` を見れば、
     * アプリ経由のアクセスだけ広告やインストール導線を出し分けできる。
     */
    const val UA_SUFFIX = "CoinArenaApp/1.0"
}
