package com.example.greenframeocr.util

import com.example.greenframeocr.data.DepositMeisai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通帳CSV取込の合成番号。AoiroChobo 連携の externalId（`ocr:deposit:{日付}-{通番}`）が
 * 一意かつ取り込み直しで変わらないことが要件
 * （docs/integration/REPLY-pc-2026-09-22.md §3）。
 */
class DepositNumberAssignerTest {

    private fun row(date: String, number: String, tekiyou: String, amount: Int, memo: String = "", passbookId: Int = 1) =
        DepositMeisai(
            passbookId = passbookId,
            transactionDate = date,
            transactionNumber = number,
            tekiyou = tekiyou,
            amount = amount,
            memo = memo
        )

    @Test
    fun `通番がある行は触らない`() {
        val parsed = listOf(row("2026-02-05", "0012", "ノウキヨウ", 50000))
        assertEquals(parsed, DepositNumberAssigner.assign(parsed, emptyList()))
    }

    @Test
    fun `同じ日の空欄が複数あっても全部に別の番号が付く`() {
        val parsed = listOf(
            row("2026-02-05", "", "デンキダイ", -8000),
            row("2026-02-05", "", "スイドウ", -3000),
            row("2026-02-05", "", "ガスダイ", -5000)
        )

        val assigned = DepositNumberAssigner.assign(parsed, emptyList())

        assertEquals(listOf("x01", "x02", "x03"), assigned.map { it.transactionNumber })
        // これが以前は UNIQUE(日付,通番) ＋ IGNORE で2件目以降が無言で落ちていた
        assertEquals(3, assigned.map { it.transactionDate to it.transactionNumber }.distinct().size)
    }

    @Test
    fun `同じCSVを取り込み直すと同じ番号になる`() {
        val parsed = listOf(
            row("2026-02-05", "", "デンキダイ", -8000),
            row("2026-02-06", "", "スイドウ", -3000)
        )
        val first = DepositNumberAssigner.assign(parsed, emptyList())

        val second = DepositNumberAssigner.assign(parsed, first)

        assertEquals(first.map { it.transactionNumber }, second.map { it.transactionNumber })
    }

    @Test
    fun `範囲の重なるCSVでも既存分の番号がずれない`() {
        // 1回目：2/5 の3件
        val firstCsv = listOf(
            row("2026-02-05", "", "デンキダイ", -8000),
            row("2026-02-05", "", "スイドウ", -3000),
            row("2026-02-05", "", "ガスダイ", -5000)
        )
        val stored = DepositNumberAssigner.assign(firstCsv, emptyList())

        // 2回目：先頭が欠けて末尾に1件増えたCSV（通帳ダウンロードの範囲違い）
        val secondCsv = listOf(
            row("2026-02-05", "", "スイドウ", -3000),
            row("2026-02-05", "", "ガスダイ", -5000),
            row("2026-02-05", "", "デンワダイ", -4000)
        )

        val assigned = DepositNumberAssigner.assign(secondCsv, stored)

        // 既存2件は前回と同じ番号のまま、新規だけが空き番号を取る
        assertEquals("x02", assigned[0].transactionNumber)
        assertEquals("x03", assigned[1].transactionNumber)
        assertEquals("x04", assigned[2].transactionNumber)
    }

    @Test
    fun `内容まで同じ行が同じ日に2件あっても別の番号になり再取込で安定する`() {
        val parsed = listOf(
            row("2026-02-05", "", "デンキダイ", -8000),
            row("2026-02-05", "", "デンキダイ", -8000)
        )
        val stored = DepositNumberAssigner.assign(parsed, emptyList())
        assertEquals(listOf("x01", "x02"), stored.map { it.transactionNumber })

        val again = DepositNumberAssigner.assign(parsed, stored)

        assertEquals(listOf("x01", "x02"), again.map { it.transactionNumber })
    }

    @Test
    fun `実際の通番と混在しても番号が衝突しない`() {
        val existing = listOf(row("2026-02-05", "0012", "ノウキヨウ", 50000))
        val parsed = listOf(row("2026-02-05", "", "デンキダイ", -8000))

        val assigned = DepositNumberAssigner.assign(parsed, existing)

        assertEquals("x01", assigned[0].transactionNumber)
    }

    @Test
    fun `合成番号は externalId の文字種に収まり シャープを含まない`() {
        val parsed = (1..12).map { row("2026-02-05", "", "テキヨウ$it", -it * 100) }

        val assigned = DepositNumberAssigner.assign(parsed, emptyList())

        assigned.forEach { meisai ->
            val number = meisai.transactionNumber
            assertTrue("合成番号に使えない文字がある: $number", number.matches(Regex("[a-z0-9:_-]+")))
            assertTrue("# は PC 側が別用途に予約している: $number", !number.contains("#"))
            assertTrue(DepositNumberAssigner.isSynthetic(number))
        }
        assertEquals("x12", assigned.last().transactionNumber)
    }

    @Test
    fun `通帳が違えば同じ日の合成番号は別々に振り、別の通帳の行は再利用しない`() {
        val existing = DepositNumberAssigner.assign(listOf(row("2026-02-05", "", "デンキダイ", -8000, passbookId = 1)), emptyList())
        val parsed = listOf(
            row("2026-02-05", "", "デンキダイ", -8000, passbookId = 2),
            row("2026-02-05", "", "スイドウ", -3000, passbookId = 2)
        )

        val assigned = DepositNumberAssigner.assign(parsed, existing)

        // 通帳1の x01 と同じ内容でも、通帳2では別の取引なので再利用せず、番号も通帳2の中で1から振る
        assertEquals(listOf("x01", "x02"), assigned.map { it.transactionNumber })
        assertEquals(listOf(2, 2), assigned.map { it.passbookId })
    }
}
