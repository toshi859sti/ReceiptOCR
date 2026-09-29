package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * レシートの品目・支払方法に付ける科目・摘要の候補（本番の vocabulary で確かめる）。
 */
class AoiroChoboReceiptRulesTest {

    // Gradle のユニットテストは app/ を作業ディレクトリにして走る
    private val parsed = Gson().fromJson(
        File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_134932.json").readText(Charsets.UTF_8),
        AoiroChoboVocabularyFile::class.java
    )
    private val accounts = parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
    private val memos = parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }

    @Test
    fun `品目の科目は借方候補と同じ25科目・摘要は現金出金から`() {
        assertEquals(25, AoiroChoboReceiptRules.accountCandidates(accounts).size)
        assertEquals("諸材料購入", AoiroChoboReceiptRules.preselectedMemo("syozairyou", memos)?.name)
        // 雑費はガソリン代（自動車）50%・雑費 100% など 5 件。農家が選ぶ
        assertEquals(5, AoiroChoboReceiptRules.memoCandidates("zappi", memos).size)
        assertNull(AoiroChoboReceiptRules.preselectedMemo("zappi", memos))
        assertTrue(AoiroChoboReceiptRules.memoCandidates("sagyouitaku", memos).isEmpty())
    }

    @Test
    fun `支払方法の既定の候補は契約の3科目・全部には口座や借入金が入り経費と収入は入らない`() {
        assertEquals(
            listOf("genkin", "mibarai", "zigyounusikari"),
            AoiroChoboReceiptRules.paymentCandidates(accounts).map { it.accountKey }
        )
        val all = AoiroChoboReceiptRules.allPaymentCandidates(accounts).map { it.accountKey }
        assertTrue(all.containsAll(listOf("genkin", "mibarai", "zigyounusikari", "einou")))
        assertTrue("hiryou" !in all)
        assertTrue("suitou" !in all)
        assertTrue("zigyounusikas" !in all)
    }
}
