package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通帳の摘要パターンに付ける相手科目・摘要の候補。預金は入金/出金でタブが分かれる。
 */
class AoiroChoboDepositRulesTest {

    private fun memo(
        key: String,
        counter: String,
        direction: String,
        showInBank: Boolean = true,
        ratio: Int = 100
    ) = AoiroChoboMemoTemplate(
        memoKey = key,
        ledgerType = "Cash",
        direction = direction,
        name = key,
        counterAccountKey = counter,
        taxRate = "10",
        businessRatio = ratio,
        showInBank = showInBank
    )

    @Test
    fun `摘要は預金のタブで、入金と出金を分け、その科目を指すものだけ`() {
        val memos = listOf(
            memo("肥料購入", "hiryou", "Out"),
            memo("肥料返金", "hiryou", "In"),
            memo("肥料購入（現金のみ）", "hiryou", "Out", showInBank = false),
            memo("農薬購入", "nouyaku", "Out")
        )
        assertEquals(listOf("肥料購入"), AoiroChoboDepositRules.memoCandidates("hiryou", false, memos).map { it.memoKey })
        assertEquals(listOf("肥料返金"), AoiroChoboDepositRules.memoCandidates("hiryou", true, memos).map { it.memoKey })
    }

    @Test
    fun `事業割合だけ違う摘要がある科目は先に埋めない`() {
        val memos = listOf(
            memo("電気料金", "douryoku", "Out", ratio = 40),
            memo("電気料金（事業専用）", "douryoku", "Out", ratio = 100),
            memo("肥料購入", "hiryou", "Out")
        )
        assertNull(AoiroChoboDepositRules.preselectedMemo("douryoku", false, memos))
        assertEquals("肥料購入", AoiroChoboDepositRules.preselectedMemo("hiryou", false, memos)?.memoKey)
    }

    @Test
    fun `本番データ：相手科目の候補と、よく使う摘要の候補`() {
        // Gradle のユニットテストは app/ を作業ディレクトリにして走る
        val file = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_134932.json")
        val parsed = Gson().fromJson(file.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
        val accounts = parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
        val memos = parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }

        val candidates = AoiroChoboDepositRules.accountCandidates(accounts).map { it.accountKey }
        // 口座間の振替の相手になる両口座・買掛金（JA の引き落とし）・事業主貸は候補に入る
        assertTrue(candidates.containsAll(listOf("einou", "tyokubai", "kaikake", "zigyounusikas", "hiryou")))
        // 親の普通預金は見出しなので入らない
        assertTrue("hutuu" !in candidates)

        assertEquals("肥料購入", AoiroChoboDepositRules.preselectedMemo("hiryou", false, memos)?.name)
        // 水稲の入金は 米販売代金／農協売上入金／直売店売上入金 の 3 つから農家が選ぶ
        assertEquals(3, AoiroChoboDepositRules.memoCandidates("suitou", true, memos).size)
        assertNull(AoiroChoboDepositRules.preselectedMemo("suitou", true, memos))
        // 電気料金 40% と 電気料金（事業専用）100% がある
        assertNull(AoiroChoboDepositRules.preselectedMemo("douryoku", false, memos))
    }
}
