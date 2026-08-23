package com.coinarina3d.myapp

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** サイトのお知らせ 1 件分。 */
data class ReleaseNote(
    val id: Long,
    val title: String,
    val body: String,
)

/**
 * ゲームと同じ Worker API からお知らせ一覧を取得する。
 * ページ側の fetchNotes() と同じエンドポイントなので、サイトに追加すればアプリにも届く。
 */
object ReleaseNotes {

    private const val ENDPOINT = "https://coinarena-api.nopzqql.workers.dev/release-notes"

    /** 新しい順のお知らせ一覧。取得できなければ例外を投げる。 */
    fun fetch(): List<ReleaseNote> {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(json)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): List<ReleaseNote> {
        val array = JSONArray(json)
        return (0 until array.length())
            .mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ReleaseNote(
                    id = item.optLong("id"),
                    title = item.optString("title"),
                    body = item.optString("body"),
                )
            }
            .sortedByDescending { it.id }
    }
}
