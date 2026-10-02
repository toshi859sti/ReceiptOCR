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
 * 摘要を PC の摘要画面と同じ帳簿のタブに振り分ける。
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
