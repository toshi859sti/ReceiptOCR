package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
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
 * JA 購買の商品に付ける科目・摘要の候補。科目 → その科目で絞った摘要 → ユーザーが確定、の順。
 */
class AoiroChoboPurchaseRulesTest {

    private fun memo(
        key: String,
        counter: String,
        ledgerType: String = "AP",
        direction: String = "In",
        ratio: Int = 100,
        order: Int = 0
    ) = AoiroChoboMemoTemplate(
        memoKey = key,
        ledgerType = ledgerType,
        direction = direction,
        name = key,
        counterAccountKey = counter,
        taxRate = "10",
        businessRatio = ratio,
        displayOrder = order
    )

    @Test
    fun `摘要は買掛の仕入でその科目を指すものだけ`() {
        val memos = listOf(
            memo("肥料購入", "hiryou"),
            memo("肥料購入（現金）", "hiryou", ledgerType = "Cash", direction = "Out"),
            memo("買掛支払", "hiryou", direction = "Out"),
            memo("農薬購入", "nouyaku")
        )
        assertEquals(listOf("肥料購入"), AoiroChoboPurchaseRules.memoCandidates("hiryou", memos).map { it.memoKey })
    }

    @Test
    fun `候補が1件なら先に埋める・2件以上や0件なら埋めない`() {
        val memos = listOf(
            memo("肥料購入", "hiryou"),
            memo("諸材料購入", "syozairyou", order = 1),
            memo("資材購入", "syozairyou", order = 2)
        )
        assertEquals("肥料購入", AoiroChoboPurchaseRules.preselectedMemo("hiryou", memos)?.memoKey)
        // 同義の摘要でも、どちらで帳簿に載せるかは農家が決める
        assertNull(AoiroChoboPurchaseRules.preselectedMemo("syozairyou", memos))
        // 給油所→動力光熱費は買掛/仕入の摘要が無い。科目だけで保存する
        assertNull(AoiroChoboPurchaseRules.preselectedMemo("douryoku", memos))
    }

    @Test
    fun `事業割合だけ違う摘要がある科目は埋めない`() {
        // 本番の買掛にこの組は無いが、PC で足されれば起こりうる。取り違えると経費の額が変わる
        val memos = listOf(
            memo("軽油購入（事業専用）", "douryoku", ratio = 100),
            memo("軽油購入", "douryoku", ratio = 50)
        )
        assertNull(AoiroChoboPurchaseRules.preselectedMemo("douryoku", memos))
        assertEquals(2, AoiroChoboPurchaseRules.memoCandidates("douryoku", memos).size)
    }

    @Test
    fun `科目の候補は借方に使ってよい科目だけ・枠番号順`() {
        val accounts = listOf(
            AoiroChoboAccount(accountKey = "zappi", name = "雑費", ocrRoleExpenseDebit = true, displayOrder = 4300),
            AoiroChoboAccount(accountKey = "genkin", name = "現金", displayOrder = 1010),
            AoiroChoboAccount(accountKey = "hiryou", name = "肥料費", ocrRoleExpenseDebit = true, displayOrder = 4040)
        )
        assertEquals(listOf("hiryou", "zappi"), AoiroChoboPurchaseRules.accountCandidates(accounts).map { it.accountKey })
    }

    @Test
    fun `本番データ：借方24科目のうち6科目は摘要が1件で先に埋まる`() {
        // Gradle のユニットテストは app/ を作業ディレクトリにして走る
        val file = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_120800.json")
        val parsed = Gson().fromJson(file.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
        val accounts = parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
        val memos = parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }

        val candidates = AoiroChoboPurchaseRules.accountCandidates(accounts)
        assertEquals(24, candidates.size)
        val preselected = candidates.mapNotNull { a ->
            AoiroChoboPurchaseRules.preselectedMemo(a.accountKey, memos)?.let { a.accountKey to it.name }
        }.toMap()
        assertEquals(
            mapOf(
                "syubyou" to "種苗購入", "hiryou" to "肥料購入", "siryou" to "飼料購入",
                "nougu" to "農具購入", "nouyaku" to "農薬購入", "syozairyou" to "諸材料購入"
            ),
            preselected
        )
        // 給油所の行き先・修繕費は摘要なし（PC は UnmatchedMemo で受ける）
        assertTrue(AoiroChoboPurchaseRules.memoCandidates("douryoku", memos).isEmpty())
        assertTrue(AoiroChoboPurchaseRules.memoCandidates("syuuzen", memos).isEmpty())
    }
}
