package com.coinarina3d.myapp

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** WebView 内に留める URL と、外部ブラウザに渡す URL の切り分け。 */
@RunWith(AndroidJUnit4::class)
class GameSiteTest {

    @Test
    fun `自サイトはWebViewで開く`() {
        listOf(
            "https://fally-itwork.net/game3d_arena.html",
            "https://fally-itwork.net/guide.html",
            "https://www.fally-itwork.net/updates.html",
        ).forEach { assertTrue(it, GameSite.isInternal(Uri.parse(it))) }
    }

    @Test
    fun `他サイトはWebViewで開かない`() {
        listOf(
            "https://example.com/",
            "https://fally-itwork.net.evil.com/",
            "https://evil-fally-itwork.net/",
            "mailto:someone@example.com",
            "intent://scan/#Intent;scheme=zxing;end",
        ).forEach { assertFalse(it, GameSite.isInternal(Uri.parse(it))) }
    }
}
