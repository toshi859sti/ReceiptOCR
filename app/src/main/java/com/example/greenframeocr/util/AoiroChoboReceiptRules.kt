package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * レシートの品目グループ・支払方法のルールに AoiroChobo の科目・摘要を紐付けるときの候補の出し方。
 *
 * 品目は JA 購買と同じく **科目 → その科目で絞った摘要 → ユーザーが確定**。摘要は「現金/出金」のタブから選ぶ
 * （レシートの大半は現金払い。本番の「未払/発生」の摘要は 3 件しか無い）。現金以外の支払いのときの置き換えは
 * [AoiroChoboTransactionsBuilder.buildReceipt] がやる。
 */
object AoiroChoboReceiptRules {

    /** 品目の借方に PC が許す科目（`ocrRoleExpenseDebit == true`・契約 §4.5）。農家の絞り込みは通さない */
    fun accountCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        AoiroChoboUsageRules.pcCandidates(AoiroChoboUsageRules.Usage.RECEIPT, accounts)

    /** [accountKey] で絞った摘要の候補（現金/出金） */
    fun memoCandidates(accountKey: String, memos: List<AoiroChoboMemoTemplate>): List<AoiroChoboMemoTemplate> =
        memos.filter { MemoTab.CASH_OUT.contains(it) && it.counterAccountKey == accountKey }.sortedBy { it.displayOrder }

    /** 考え方は [AoiroChoboPurchaseRules.preselectedMemo] と同じ */
    fun preselectedMemo(accountKey: String, memos: List<AoiroChoboMemoTemplate>): AoiroChoboMemoTemplate? {
        val only = memoCandidates(accountKey, memos).singleOrNull() ?: return null
        return only.takeUnless { it.memoKey in AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) }
    }

    /** 契約 §4.5 が支払方法（貸方）の候補に挙げる科目：現金・未払金・事業主借 */
    private val PAYMENT_KEYS = listOf("genkin", "mibarai", "zigyounusikari")

    /** 支払方法の科目の既定の候補。契約が名指しする 3 科目のうち、取り込んだ辞書にあるもの */
    fun paymentCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> {
        val byKey = accounts.associateBy { it.accountKey }
        return PAYMENT_KEYS.mapNotNull { byKey[it] }
    }

    /**
     * 支払方法の科目として選べる全部（「絞り込み外も表示」）。取引の相手になれる（`ocrRoleDepositCounter`）
     * 資産・負債・資本のうち、経費の借方候補でないもの（口座払い・借入金など。事業主貸は借方候補なので入らない）
     */
    fun allPaymentCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        accounts.filter {
            it.ocrRoleDepositCounter && !it.ocrRoleExpenseDebit && it.accountType in setOf("Asset", "Liability", "Capital")
        }.sortedBy { it.displayOrder }
}
