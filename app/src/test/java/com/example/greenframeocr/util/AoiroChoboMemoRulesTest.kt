package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 摘要の「どの帳簿のタブに出すか」と「自動で選んでよいか」。
 *
 * 後者を誤ると、PC は摘要の事業割合を仕訳に入れるので（REPLY-pc-2026-09-23b.md §1・答えB）
 * 電気料金 40% の行が 100% で帳簿に入り、全額経費になる。
 */
class AoiroChoboMemoRulesTest {

    private fun memo(
        key: String,
        ledgerType: String = "Cash",
        direction: String = "Out",
        counter: String? = "douryoku",
        taxRate: String? = "10",
        ratio: Int? = 100,
        showInCash: Boolean = false,
        showInBank: Boolean = false
    ) = AoiroChoboMemoTemplate(
        memoKey = key,
        ledgerType = ledgerType,
        direction = direction,
        name = key,
        counterAccountKey = counter,
        taxRate = taxRate,
        businessRatio = ratio,
        showInCash = showInCash,
        showInBank = showInBank
    )

    @Test
    fun `事業割合だけ違う摘要は組ごと自動選択の対象外`() {
        val memos = listOf(
            memo("電気料金", ratio = 40),
            memo("電気料金（事業専用）", ratio = 100),
            memo("ガス料金", ratio = 100)
        )
        assertEquals(
            setOf("電気料金", "電気料金（事業専用）", "ガス料金"),
            AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos)
        )
    }

    @Test
    fun `事業割合が揃っていれば同義の摘要でも自動で選んでよい`() {
        // 諸材料購入／資材購入：取り違えてもラベルの粒度の問題で済む
        val memos = listOf(
            memo("諸材料購入", ledgerType = "AP", direction = "In", counter = "syozairyou"),
            memo("資材購入", ledgerType = "AP", direction = "In", counter = "syozairyou")
        )
        assertTrue(AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos).isEmpty())
    }

    @Test
    fun `相手科目・税率・帳簿・方向のどれかが違えば別の組`() {
        val memos = listOf(
            memo("電話料金", counter = "tuusin", ratio = 40),
            memo("電気料金（事業専用）", counter = "douryoku", ratio = 100),
            memo("軽減税率の何か", taxRate = "8", ratio = 50),
            memo("入金側", direction = "In", ratio = 30),
            memo("買掛側", ledgerType = "AP", ratio = 60)
        )
        assertTrue(AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos).isEmpty())
    }

    @Test
    fun `振替は相手科目を持たないので判定しない`() {
        val memos = listOf(
            memo("地代（現物払い）", ledgerType = "Transfer", direction = "", counter = null, ratio = 100),
            memo("雇人費（現物支給）", ledgerType = "Transfer", direction = "", counter = null, ratio = 50)
        )
        assertTrue(AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos).isEmpty())
    }

    @Test
    fun `預金タブは ledgerType ではなく showInBank で絞る`() {
        // 預金出納帳の摘要も ledgerType = "Cash" で持っている（"Bank" の摘要は実在しない）
        val m = memo("電気料金", ledgerType = "Cash", showInCash = false, showInBank = true)
        assertTrue(MemoTab.BANK_OUT.contains(m))
        assertTrue(!MemoTab.CASH_OUT.contains(m))
        assertTrue(!MemoTab.BANK_IN.contains(m))
    }

    // ---- 本番の vocabulary.json（2026年分・PC 側 2026-09-23 書き出し）----

    private fun loadProductionMemos(): List<AoiroChoboMemoTemplate> {
        // Gradle のユニットテストは app/ を作業ディレクトリにして走る
        val file = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260923_194016.json")
        val parsed = Gson().fromJson(file.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
        return parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }
    }

    @Test
    fun `本番データでは自動で選ばない組は動力光熱費と雑費の2組12件で買掛には無い`() {
        val memos = loadProductionMemos()
        val sensitive = AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos)
        val sensitiveMemos = memos.filter { it.memoKey in sensitive }

        assertEquals(12, sensitive.size)
        assertEquals(
            setOf("douryoku", "zappi"),
            sensitiveMemos.map { it.counterAccountKey }.toSet()
        )
        assertTrue(sensitiveMemos.none { it.ledgerType == "AP" })
    }

    @Test
    fun `本番データを PC の摘要画面と同じタブに振り分けられる`() {
        val memos = loadProductionMemos()
        val counts = MemoTab.entries.associateWith { tab -> memos.count { tab.contains(it) } }

        assertEquals(107, memos.size)
        assertEquals(6, counts[MemoTab.AP_IN])      // 肥料・農薬・農具・諸材料・種苗・飼料
        assertEquals(2, counts[MemoTab.AP_OUT])
        assertEquals(11, counts[MemoTab.TRANSFER])
        // ledgerType で絞っていたら0件になるタブ
        assertEquals(75, (counts[MemoTab.BANK_IN] ?: 0) + (counts[MemoTab.BANK_OUT] ?: 0))
        assertEquals(72, (counts[MemoTab.CASH_IN] ?: 0) + (counts[MemoTab.CASH_OUT] ?: 0))
        // Cash の摘要は showInCash / showInBank のどちらかには必ず出ている（どのタブにも出ない摘要が無い）
        assertTrue(memos.all { m -> MemoTab.entries.any { it.contains(m) } })
    }
}
