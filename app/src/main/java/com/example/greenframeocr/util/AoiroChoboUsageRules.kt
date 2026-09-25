package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboAccountUsage

/**
 * 用途（JA 購買・レシート・預金）ごとの科目の候補。
 *
 * 候補は二段で決まる：PC のフラグ（`ocrRole*`・PC の規則が決める。農家は変えられない）で絞り、
 * その内側を農家がスマホで減らす（[AoiroChoboAccountUsage]）。外側に足すことはしない
 * （REPLY-pc-2026-09-25b.md §2）。
 */
object AoiroChoboUsageRules {

    enum class Usage(val label: String) {
        PURCHASE("購買"),
        RECEIPT("レシート"),
        DEPOSIT("預金");

        /** PC がこの用途の候補にしてよいとした科目か。JA 購買とレシートは同じフラグ（契約 §4.5） */
        fun allowedByPc(account: AoiroChoboAccount): Boolean = when (this) {
            PURCHASE, RECEIPT -> account.ocrRoleExpenseDebit
            DEPOSIT -> account.ocrRoleDepositCounter
        }

        /** 農家がこの用途で外していないか。設定が無ければ外していない */
        fun keptBy(usage: AoiroChoboAccountUsage?): Boolean = when (this) {
            PURCHASE -> usage?.forPurchase ?: true
            RECEIPT -> usage?.forReceipt ?: true
            DEPOSIT -> usage?.forDeposit ?: true
        }
    }

    /** PC が許す科目（農家の絞り込み前）。枠番号順 */
    fun pcCandidates(usage: Usage, accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        accounts.filter { usage.allowedByPc(it) }.sortedBy { it.displayOrder }

    /**
     * 農家の絞り込みを通した候補。全部外してしまった場合は PC の候補に戻す
     * （候補 0 件では商品に科目を付けられず、AI にも渡せないため。弥生のフラグと同じ扱い）。
     */
    fun candidates(
        usage: Usage,
        accounts: List<AoiroChoboAccount>,
        settings: List<AoiroChoboAccountUsage>
    ): List<AoiroChoboAccount> {
        val pc = pcCandidates(usage, accounts)
        val byKey = settings.associateBy { it.accountKey }
        return pc.filter { usage.keptBy(byKey[it.accountKey]) }.ifEmpty { pc }
    }

    /** 設定画面の対象：どれか 1 つの用途で PC が許している科目。枠番号順 */
    fun configurableAccounts(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        accounts.filter { a -> Usage.entries.any { it.allowedByPc(a) } }.sortedBy { it.displayOrder }

    /** [usage] の用途だけを [kept] に切り替えた設定を返す。名前は今の科目名で控え直す */
    fun toggled(
        account: AoiroChoboAccount,
        current: AoiroChoboAccountUsage?,
        usage: Usage,
        kept: Boolean
    ): AoiroChoboAccountUsage {
        val base = current ?: AoiroChoboAccountUsage(accountKey = account.accountKey, accountKeyName = account.name)
        return when (usage) {
            Usage.PURCHASE -> base.copy(forPurchase = kept)
            Usage.RECEIPT -> base.copy(forReceipt = kept)
            Usage.DEPOSIT -> base.copy(forDeposit = kept)
        }.copy(accountKeyName = account.name)
    }
}
