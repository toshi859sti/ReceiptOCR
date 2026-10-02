package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.example.greenframeocr.util.AoiroChoboReceiptRules.Ledger
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

    private fun cashOut(key: String, counter: String, common: Boolean = false) = AoiroChoboMemoTemplate(
        memoKey = key, ledgerType = "Cash", direction = "Out", name = key,
        counterAccountKey = counter, showInCash = true, paymentCommon = common
    )

    private fun unpaidIn(key: String, counter: String) = AoiroChoboMemoTemplate(
        memoKey = key, ledgerType = "Unpaid", direction = "In", name = key, counterAccountKey = counter
    )

    private fun transfer(key: String, debit: String, credit: String) = AoiroChoboMemoTemplate(
        memoKey = key, ledgerType = "Transfer", name = key, debitAccountKey = debit, creditAccountKey = credit
    )

    private val sample = listOf(
        cashOut("農薬（共通）", "nouyaku", common = true),
        cashOut("農薬（現金）", "nouyaku"),
        unpaidIn("農薬（未払）", "nouyaku"),
        transfer("農薬（振替）", "nouyaku", "zigyounusikari"),
        transfer("農薬（振替・別の貸方）", "nouyaku", "kariirekin"),
        cashOut("肥料（共通）", "hiryou", common = true)
    )

    private fun keys(list: List<AoiroChoboMemoTemplate>) = list.map { it.memoKey }.toSet()

    @Test
    fun `品目の科目は借方候補と同じ25科目・本番の雑費は現金出金に5件`() {
        assertEquals(25, AoiroChoboReceiptRules.accountCandidates(accounts).size)
        // 雑費はガソリン代（自動車）50%・雑費 100% など 5 件。農家が選ぶ
        assertEquals(5, AoiroChoboReceiptRules.memoCandidates("zappi", Ledger.CASH, "genkin", memos).size)
        assertTrue(AoiroChoboReceiptRules.memoCandidates("sagyouitaku", Ledger.CASH, "genkin", memos).isEmpty())
    }

    @Test
    fun `品目グループの摘要はレシート共通でその科目のものだけ`() {
        assertEquals(setOf("農薬（共通）"), keys(AoiroChoboReceiptRules.groupMemoCandidates("nouyaku", sample)))
    }

    @Test
    fun `明細の摘要はその帳簿のものとレシート共通`() {
        assertEquals(
            setOf("農薬（共通）", "農薬（現金）"),
            keys(AoiroChoboReceiptRules.memoCandidates("nouyaku", Ledger.CASH, "genkin", sample))
        )
        assertEquals(
            setOf("農薬（共通）", "農薬（未払）"),
            keys(AoiroChoboReceiptRules.memoCandidates("nouyaku", Ledger.UNPAID, "mibarai", sample))
        )
        // 振替は貸方が支払方法の科目のものだけ。レシート共通は貸方を持たないのでどの支払方法でも出る
        assertEquals(
            setOf("農薬（共通）", "農薬（振替）"),
            keys(AoiroChoboReceiptRules.memoCandidates("nouyaku", Ledger.TRANSFER, "zigyounusikari", sample))
        )
    }

    @Test
    fun `paymentCommon が無い古い vocabulary は false・新しい vocabulary は読める`() {
        assertTrue(memos.none { it.paymentCommon })
        val latest = Gson().fromJson(
            File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20261002_124522.json").readText(Charsets.UTF_8),
            AoiroChoboVocabularyFile::class.java
        ).memoTemplates.orEmpty()
        assertEquals(107, latest.size)
        // 2026-10-02 の書き出しはフィールドがあり、まだどれもレシート共通ではない
        assertTrue(latest.all { it.paymentCommon == false })
    }

    @Test
    fun `帳簿は支払方法の科目の帳簿の所属で決まる`() {
        fun acct(affinity: String) = AoiroChoboAccount(accountKey = affinity, name = affinity, ledgerAffinity = affinity)
        assertEquals(Ledger.CASH, AoiroChoboReceiptRules.ledgerOf(acct("Cash")))
        assertEquals(Ledger.UNPAID, AoiroChoboReceiptRules.ledgerOf(acct("Unpaid")))
        assertEquals(Ledger.TRANSFER, AoiroChoboReceiptRules.ledgerOf(acct("")))
        assertNull(AoiroChoboReceiptRules.ledgerOf(null))
    }

    @Test
    fun `支払方法の既定の候補は契約の3科目・全部には借入金が入り口座や経費と収入は入らない`() {
        assertEquals(
            listOf("genkin", "mibarai", "zigyounusikari"),
            AoiroChoboReceiptRules.paymentCandidates(accounts).map { it.accountKey }
        )
        val all = AoiroChoboReceiptRules.allPaymentCandidates(accounts).map { it.accountKey }
        assertTrue(all.containsAll(listOf("genkin", "mibarai", "zigyounusikari")))
        // 預金・買掛金は Receipt で正しく帳簿に入らないので外す（PC 回答 2026-09-30 §1）
        assertTrue("einou" !in all)
        assertTrue("kaikake" !in all)
        assertTrue("hiryou" !in all)
        assertTrue("suitou" !in all)
        assertTrue("zigyounusikas" !in all)
    }
}
