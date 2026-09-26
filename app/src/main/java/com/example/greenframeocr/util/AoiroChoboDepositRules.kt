package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * 通帳の摘要パターン（と個別の明細）に AoiroChobo の相手科目・摘要を紐付けるときの候補の出し方。
 *
 * 順序は JA 購買（[AoiroChoboPurchaseRules]）と同じく **科目 → その科目で絞った摘要 → ユーザーが確定**。
 * 違うのは摘要のタブで、預金は入金なら「預金/入金」、出金なら「預金/出金」（契約 vocabulary-snapshot.md §4.5）。
 * 預金の摘要は `ledgerType` では絞れない（`Bank` の摘要は実在せず、`Cash` の摘要を `showInBank` で出し分けている）。
 */
object AoiroChoboDepositRules {

    /** PC が許す相手科目（`ocrRoleDepositCounter == true`）。農家の絞り込みは通さない */
    fun accountCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        AoiroChoboUsageRules.pcCandidates(AoiroChoboUsageRules.Usage.DEPOSIT, accounts)

    private fun tabOf(isIncome: Boolean) = if (isIncome) MemoTab.BANK_IN else MemoTab.BANK_OUT

    /** [accountKey] で絞った摘要の候補。[isIncome] は入金（金額 ≥ 0）か */
    fun memoCandidates(
        accountKey: String,
        isIncome: Boolean,
        memos: List<AoiroChoboMemoTemplate>
    ): List<AoiroChoboMemoTemplate> {
        val tab = tabOf(isIncome)
        return memos.filter { tab.contains(it) && it.counterAccountKey == accountKey }.sortedBy { it.displayOrder }
    }

    /**
     * 科目を選んだ直後に摘要を先に埋めておいてよいか。考え方は [AoiroChoboPurchaseRules.preselectedMemo] と同じ
     * （候補がちょうど 1 件で、事業割合だけ違う組に入っていないときだけ）。
     */
    fun preselectedMemo(
        accountKey: String,
        isIncome: Boolean,
        memos: List<AoiroChoboMemoTemplate>
    ): AoiroChoboMemoTemplate? {
        val only = memoCandidates(accountKey, isIncome, memos).singleOrNull() ?: return null
        return only.takeUnless { it.memoKey in AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) }
    }
}
