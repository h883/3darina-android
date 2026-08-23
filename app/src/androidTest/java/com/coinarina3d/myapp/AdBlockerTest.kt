package com.coinarina3d.myapp

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** アプリ内で遮断する URL の判定。 */
@RunWith(AndroidJUnit4::class)
class AdBlockerTest {

    @Test
    fun `広告ホストは遮断する`() {
        listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://googlesyndication.com/x",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://ep1.adtrafficquality.google/beacon",
        ).forEach { assertTrue(it, AdBlocker.isAd(Uri.parse(it))) }
    }

    @Test
    fun `ゲームに必要な通信は遮断しない`() {
        listOf(
            "https://fally-itwork.net/game3d_arena.html",
            "https://coinarena-api.nopzqql.workers.dev/release-notes",
            "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js",
            "https://unpkg.com/peerjs@1.5.4/dist/peerjs.min.js",
            "https://accounts.google.com/gsi/client",
        ).forEach { assertFalse(it, AdBlocker.isAd(Uri.parse(it))) }
    }

    @Test
    fun `似た名前のホストを巻き込まない`() {
        assertFalse(AdBlocker.isAd(Uri.parse("https://notgooglesyndication.com/x")))
        assertFalse(AdBlocker.isAd(Uri.parse("https://doubleclick.net.example.com/x")))
    }
}
