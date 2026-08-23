package com.coinarina3d.myapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** /release-notes のレスポンス解析。 */
class ReleaseNotesParseTest {

    @Test
    fun `新しい順に並び替えて読み取る`() {
        val json = """
            [
              {"id": 3, "title": "新ステージ", "body": "砂漠を追加", "created_at": "2026-08-20T10:00:00Z"},
              {"id": 5, "title": "バランス調整", "body": "連射を弱体化", "created_at": "2026-08-22T10:00:00Z"},
              {"id": 1, "title": "公開", "body": "リリースしました", "created_at": "2026-08-01T10:00:00Z"}
            ]
        """.trimIndent()

        val notes = ReleaseNotes.parse(json)

        assertEquals(listOf(5L, 3L, 1L), notes.map { it.id })
        assertEquals("バランス調整", notes.first().title)
        assertEquals("連射を弱体化", notes.first().body)
    }

    @Test
    fun `お知らせが 0 件でも壊れない`() {
        assertTrue(ReleaseNotes.parse("[]").isEmpty())
    }

    @Test
    fun `項目が欠けていても落ちない`() {
        val notes = ReleaseNotes.parse("""[{"id": 7}]""")

        assertEquals(1, notes.size)
        assertEquals(7L, notes.first().id)
        assertEquals("", notes.first().title)
    }
}
