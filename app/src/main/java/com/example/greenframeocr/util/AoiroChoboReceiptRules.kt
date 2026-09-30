package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * レシートの品目グループ・支払方法のルールに AoiroChobo の科目・摘要を紐付けるときの候補の出し方。
 *
 * レシートの帳簿は**貸方（支払方法）の科目の `ledgerAffinity`** で決まる（契約 2026-09-30 minor（9）・
 * transaction-import §5）。現金 → 現金出納帳、未払金 → 未払帳、それ以外（事業主借など）→ 振替伝票。
 * 摘要はその帳簿のものから選ぶ（[Ledger]）。帳簿の違うレシートに回ったときの置き換えは
 * [AoiroChoboTransactionsBuilder.buildReceipt] がやる。
 */
object AoiroChoboReceiptRules {

    /**
     * レシートが入る帳簿と、その帳簿の摘要の分類。
     * [ledgerType] は `transactions.json` の `ledgerType`
     */
    enum class Ledger(val ledgerType: String, val memoTabLabel: String) {
        CASH("Cash", "現金/出金"),
        UNPAID("Unpaid", "未払/発生"),
        TRANSFER("Transfer", "振替");
    }

    /** 支払方法の科目から帳簿を決める。科目が決まらなければ null（貸方未設定で送る） */
    fun ledgerOf(payment: AoiroChoboAccount?): Ledger? = when (payment?.ledgerAffinity) {
        null -> null
        "Cash" -> Ledger.CASH
        "Unpaid" -> Ledger.UNPAID
        else -> Ledger.TRANSFER
    }

    /** 品目の借方に PC が許す科目（`ocrRoleExpenseDebit == true`・契約 §4.5）。農家の絞り込みは通さない */
    fun accountCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        AoiroChoboUsageRules.pcCandidates(AoiroChoboUsageRules.Usage.RECEIPT, accounts)

    /**
     * 品目の経費科目 [accountKey] で絞った摘要の候補。
     *
     * - 現金出納帳：現金/出金 かつ `counterAccountKey == 経費科目`
     * - 未払帳：未払/発生 かつ `counterAccountKey == 経費科目`
     * - 振替伝票：振替 かつ `debitAccountKey == 経費科目` かつ `creditAccountKey == 支払方法の科目`（[paymentKey]）
     */
    fun memoCandidates(
        accountKey: String,
        ledger: Ledger,
        paymentKey: String?,
        memos: List<AoiroChoboMemoTemplate>
    ): List<AoiroChoboMemoTemplate> = memos.filter {
        when (ledger) {
            Ledger.CASH -> MemoTab.CASH_OUT.contains(it) && it.counterAccountKey == accountKey
            Ledger.UNPAID -> MemoTab.UNPAID_IN.contains(it) && it.counterAccountKey == accountKey
            Ledger.TRANSFER -> MemoTab.TRANSFER.contains(it) && it.debitAccountKey == accountKey &&
                it.creditAccountKey == paymentKey
        }
    }.sortedBy { it.displayOrder }

    /** 契約 §4.5 が支払方法（貸方）の候補に挙げる科目：現金・未払金・事業主借 */
    private val PAYMENT_KEYS = listOf("genkin", "mibarai", "zigyounusikari")

    /** 支払方法の科目の既定の候補。契約が名指しする 3 科目のうち、取り込んだ辞書にあるもの */
    fun paymentCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> {
        val byKey = accounts.associateBy { it.accountKey }
        return PAYMENT_KEYS.mapNotNull { byKey[it] }
    }

    /**
     * 支払方法の科目として選べる全部（「絞り込み外も表示」）。取引の相手になれる（`ocrRoleDepositCounter`）
     * 資産・負債・資本のうち、経費の借方候補でないもの（借入金など。事業主貸は借方候補なので入らない）。
     * 預金・買掛金の科目は入れない：Receipt は `bankSlotNo` を持てず、その帳簿に正しく入らない（契約 transaction-import §5）
     */
    fun allPaymentCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        accounts.filter {
            it.ocrRoleDepositCounter && !it.ocrRoleExpenseDebit && it.accountType in setOf("Asset", "Liability", "Capital") &&
                it.ledgerAffinity !in setOf("Bank", "AP")
        }.sortedBy { it.displayOrder }
}
