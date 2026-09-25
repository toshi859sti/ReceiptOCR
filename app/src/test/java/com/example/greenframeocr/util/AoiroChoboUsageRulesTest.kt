package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboAccountUsage
import com.example.greenframeocr.util.AoiroChoboUsageRules.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用途ごとの科目の候補＝PC のフラグの内側を、農家が減らしたもの。
 */
class AoiroChoboUsageRulesTest {

    private fun account(key: String, order: Int, debit: Boolean = false, deposit: Boolean = false) =
        AoiroChoboAccount(
            accountKey = key,
            name = key,
            displayOrder = order,
            ocrRoleExpenseDebit = debit,
            ocrRoleDepositCounter = deposit
        )

    private val accounts = listOf(
        account("zappi", 4300, debit = true, deposit = true),
        account("hiryou", 4040, debit = true, deposit = true),
        account("tatemono", 1140, debit = true, deposit = true),
        account("genkin", 1010, deposit = true),
        account("genka", 4130)  // 減価償却費：どの用途にも使わない
    )

    @Test
    fun `設定が無ければ PC のフラグどおり・枠番号順`() {
        assertEquals(listOf("tatemono", "hiryou", "zappi"),
            AoiroChoboUsageRules.candidates(Usage.PURCHASE, accounts, emptyList()).map { it.accountKey })
        assertEquals(listOf("genkin", "tatemono", "hiryou", "zappi"),
            AoiroChoboUsageRules.candidates(Usage.DEPOSIT, accounts, emptyList()).map { it.accountKey })
    }

    @Test
    fun `外した用途だけ候補から消える・他の用途には影響しない`() {
        val settings = listOf(AoiroChoboAccountUsage("tatemono", "tatemono", forPurchase = false))
        assertEquals(listOf("hiryou", "zappi"),
            AoiroChoboUsageRules.candidates(Usage.PURCHASE, accounts, settings).map { it.accountKey })
        assertTrue(AoiroChoboUsageRules.candidates(Usage.RECEIPT, accounts, settings).any { it.accountKey == "tatemono" })
        assertTrue(AoiroChoboUsageRules.candidates(Usage.DEPOSIT, accounts, settings).any { it.accountKey == "tatemono" })
    }

    @Test
    fun `PC が許していない科目は設定で残しても足さない`() {
        // 現金は借方の候補ではない。forPurchase = true の行があっても購買の候補にはならない
        val settings = listOf(AoiroChoboAccountUsage("genkin", "genkin", forPurchase = true))
        assertFalse(AoiroChoboUsageRules.candidates(Usage.PURCHASE, accounts, settings).any { it.accountKey == "genkin" })
    }

    @Test
    fun `全部外したら PC の候補に戻す`() {
        val settings = listOf("zappi", "hiryou", "tatemono").map {
            AoiroChoboAccountUsage(it, it, forPurchase = false)
        }
        assertEquals(3, AoiroChoboUsageRules.candidates(Usage.PURCHASE, accounts, settings).size)
    }

    @Test
    fun `設定画面にはどれかの用途で使える科目だけ出す`() {
        assertEquals(listOf("genkin", "tatemono", "hiryou", "zappi"),
            AoiroChoboUsageRules.configurableAccounts(accounts).map { it.accountKey })
    }

    @Test
    fun `切り替えは指定の用途だけ変え・名前を今の名前で控え直す`() {
        val account = AoiroChoboAccount(accountKey = "acct-7", name = "水利費")
        val before = AoiroChoboAccountUsage("acct-7", "研修費", forPurchase = true, forReceipt = false)
        val after = AoiroChoboUsageRules.toggled(account, before, Usage.PURCHASE, kept = false)
        assertEquals(AoiroChoboAccountUsage("acct-7", "水利費", forPurchase = false, forReceipt = false), after)
        // 設定が無い科目は全部残す状態から始める
        assertEquals(
            AoiroChoboAccountUsage("acct-7", "水利費", forPurchase = true, forReceipt = true, forDeposit = false),
            AoiroChoboUsageRules.toggled(account, null, Usage.DEPOSIT, kept = false)
        )
    }
}
