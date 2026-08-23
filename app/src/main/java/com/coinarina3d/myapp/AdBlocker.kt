package com.coinarina3d.myapp

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * アプリ内 WebView での広告読み込みをブロックする。
 *
 * AdSense のプログラムポリシーはアプリ／WebView 内での広告表示を認めていないため、
 * サイトをそのまま包むアプリでは広告リクエストを止めておく。
 * ゲーム本体（Three.js / PeerJS / Workers API / Google 認証）は対象外。
 */
object AdBlocker {

    private val BLOCKED_HOSTS = listOf(
        "googlesyndication.com",
        "googletagservices.com",
        "googleadservices.com",
        "doubleclick.net",
        "adtrafficquality.google",
        "ep1.adtrafficquality.google",
        "ep2.adtrafficquality.google",
    )

    fun isAd(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 空の 200 レスポンス。エラーにせず「何もない」を返す。 */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
