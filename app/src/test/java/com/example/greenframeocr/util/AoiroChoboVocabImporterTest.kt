package com.example.greenframeocr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vocabulary.json 取込の主機構＝「name が変わった accountKey / memoKey の紐付けを外す」判定。
 *
 * PC 側は科目を作り替えても accountKey を据え置き、変わるのは name だけ
 * （docs/integration/CHANGELOG.md 2026-09-13 改訂・変更2）。この判定を落とすと、
 * 作り替えられた科目に古い学習が付いたまま仕訳が通ってしまう。
 */
class AoiroChoboVocabImporterTest {

    private val current = mapOf(
        "hiryou" to "肥料費",
        "acct-0007" to "水利費",
        "memo-102" to "肥料購入"
    )

    @Test
    fun `名前が同じなら紐付けを維持する`() {
        val verdict = AoiroChoboVocabImporter.verify("hiryou", "肥料費", current)
        assertEquals(AoiroChoboVocabImporter.Verdict.Unchanged, verdict)
    }

    @Test
    fun `名前が変わったら紐付けを外す`() {
        // 2025年度は「研修費」だった acct-0007 が 2026年度は「水利費」になった
        val verdict = AoiroChoboVocabImporter.verify("acct-0007", "研修費", current)

        assertTrue(verdict is AoiroChoboVocabImporter.Verdict.Renamed)
        verdict as AoiroChoboVocabImporter.Verdict.Renamed
        assertEquals("研修費", verdict.before)
        assertEquals("水利費", verdict.after)
    }

    @Test
    fun `全角半角などの表記ゆれだけなら外さない`() {
        // 「（正規化しても）変わっていたら外す」＝正規化して同じなら維持する
        val verdict = AoiroChoboVocabImporter.verify("hiryou", "肥料費 ", current)
        assertEquals(AoiroChoboVocabImporter.Verdict.Unchanged, verdict)
    }

    @Test
    fun `ファイルから消えたキーは Missing になる`() {
        // 科目・摘要の無効化。作り替えではないので「外す」ではなく別扱いにする
        val verdict = AoiroChoboVocabImporter.verify("sonzai-sinai", "何か", current)
        assertEquals(AoiroChoboVocabImporter.Verdict.Missing, verdict)
    }

    @Test
    fun `そのとき見た名前を持っていなければ今の名前を控える`() {
        val verdict = AoiroChoboVocabImporter.verify("hiryou", null, current)

        assertTrue(verdict is AoiroChoboVocabImporter.Verdict.NameUnknown)
        assertEquals("肥料費", (verdict as AoiroChoboVocabImporter.Verdict.NameUnknown).name)
    }

    @Test
    fun `摘要キーも同じ規則で判定する`() {
        assertEquals(
            AoiroChoboVocabImporter.Verdict.Unchanged,
            AoiroChoboVocabImporter.verify("memo-102", "肥料購入", current)
        )
        assertTrue(
            AoiroChoboVocabImporter.verify("memo-102", "農薬購入", current)
                is AoiroChoboVocabImporter.Verdict.Renamed
        )
    }

    @Test
    fun `鮮度は generatedAt の日付部分から出す`() {
        assertNull(AoiroChoboVocabImporter.ageInDays(null))
        assertNull(AoiroChoboVocabImporter.ageInDays("2026"))

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        assertEquals(0L, AoiroChoboVocabImporter.ageInDays("${today}T14:30:00+09:00"))
    }
}
