package com.coinarina3d.myapp

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** お知らせが実際に端末の通知として出るかを確かめる。 */
@RunWith(AndroidJUnit4::class)
class NoticeNotifierTest {

    @Test
    fun お知らせが通知として表示される() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        val note = ReleaseNote(id = 987_654L, title = "テスト告知", body = "新ステージを追加しました。")
        NoticeNotifier.ensureChannel(context)
        NoticeNotifier.notify(context, note)

        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val posted = manager.activeNotifications.firstOrNull { it.id == note.id.toInt() }
            assertNotNull("通知が投稿されていない", posted)

            val extras = posted!!.notification.extras
            assertEquals(note.title, extras.getString(Notification.EXTRA_TITLE))
            assertEquals(note.body, extras.getString(Notification.EXTRA_TEXT))
            assertNotNull("タップ時の遷移先がない", posted.notification.contentIntent)
        } finally {
            manager.cancel(note.id.toInt())
        }
    }
}
