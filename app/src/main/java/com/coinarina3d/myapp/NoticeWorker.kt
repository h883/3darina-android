package com.coinarina3d.myapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 定期的にお知らせを確認し、前回より新しいものが増えていれば通知を出す。
 * サイトの /release-notes をそのまま見るだけなので、サーバー側の変更は要らない。
 */
class NoticeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val notes = try {
            ReleaseNotes.fetch()
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotifiedId = prefs.getLong(KEY_LAST_ID, NOT_INITIALIZED)

        // お知らせがまだ 1 件もない状態なら、基準を 0 にしておく。
        // こうしないと最初の 1 件が「初回の基準作り」で握りつぶされ、通知されない。
        if (notes.isEmpty()) {
            if (lastNotifiedId == NOT_INITIALIZED) prefs.edit().putLong(KEY_LAST_ID, 0L).apply()
            return@withContext Result.success()
        }

        val latestId = notes.first().id

        // 初回は基準を作るだけ。インストール直後に過去のお知らせが一斉に飛ぶのを防ぐ。
        if (lastNotifiedId == NOT_INITIALIZED) {
            prefs.edit().putLong(KEY_LAST_ID, latestId).apply()
            return@withContext Result.success()
        }

        val fresh = notes.filter { it.id > lastNotifiedId }
        if (fresh.isEmpty()) return@withContext Result.success()

        // 古いものから順に出して、通知欄で新しいものが上に来るようにする。
        fresh.asReversed().forEach { NoticeNotifier.notify(applicationContext, it) }
        prefs.edit().putLong(KEY_LAST_ID, latestId).apply()
        Result.success()
    }

    companion object {
        private const val PREFS = "notices"
        private const val KEY_LAST_ID = "last_notified_id"
        private const val NOT_INITIALIZED = -1L
        private const val WORK_NAME = "release-notes-check"

        /** 6 時間ごとの確認を登録する（すでにあれば据え置き）。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NoticeWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
