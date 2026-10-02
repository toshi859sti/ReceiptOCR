package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.google.gson.Gson
import org.junit.Assert.assertEquals
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
    fun `事業割合だけ違う摘要は両方とも候補に出る`() {
        // 本番の買掛にこの組は無いが、PC で足されれば起こりうる。取り違えると経費の額が変わるので農家が選ぶ
        val memos = listOf(
            memo("軽油購入（事業専用）", "douryoku", ratio = 100),
            memo("軽油購入", "douryoku", ratio = 50)
        )
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
    fun `本番データ：借方25科目のうち6科目は買掛の摘要が1件だけ`() {
        // Gradle のユニットテストは app/ を作業ディレクトリにして走る
        val file = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_134932.json")
        val parsed = Gson().fromJson(file.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
        val accounts = parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
        val memos = parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }

        val candidates = AoiroChoboPurchaseRules.accountCandidates(accounts)
        // minor（6）で事業主貸が加わって 25 件
        assertEquals(25, candidates.size)
        val single = candidates.mapNotNull { a ->
            AoiroChoboPurchaseRules.memoCandidates(a.accountKey, memos).singleOrNull()?.let { a.accountKey to it.name }
        }.toMap()
        assertEquals(
            mapOf(
                "syubyou" to "種苗購入", "hiryou" to "肥料購入", "siryou" to "飼料購入",
                "nougu" to "農具購入", "nouyaku" to "農薬購入", "syozairyou" to "諸材料購入"
            ),
            single
        )
        // 給油所の行き先・修繕費は摘要なし（PC は UnmatchedMemo で受ける）
        assertTrue(AoiroChoboPurchaseRules.memoCandidates("douryoku", memos).isEmpty())
        assertTrue(AoiroChoboPurchaseRules.memoCandidates("syuuzen", memos).isEmpty())
    }
}
