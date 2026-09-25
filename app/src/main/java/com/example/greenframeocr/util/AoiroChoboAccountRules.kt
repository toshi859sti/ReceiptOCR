package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount

/**
 * AoiroChobo の勘定科目を PC の「科目・残高登録」画面と同じタブ・並び・表記に振り分ける規則。
 *
 * `vocabulary.json` だけで決まる。閲覧画面が使う（docs/integration/examples/AoiroChobo_2024_screens/科目_*.png）。
 */
object AoiroChoboAccountRules {

    /** PC の科目画面のタブ。PC に無い区分が来たら [OTHER] に出す（REPLY-pc-2026-09-23b.md §4） */
    enum class AccountTab(val label: String) {
        ASSET("資産"),
        LIABILITY("負債"),
        INCOME("収入"),
        EXPENSE("支出"),
        OTHER("その他")
    }

    /**
     * `Capital` の科目がどのタブに出るか。
     *
     * PC の画面では資本の 6 件が資産・負債・支出に散っている。accountType からは決まらないので
     * accountKey で対応を持つ（6 件ともシステム科目でキーは不変・契約 §4.6。PC 側も 23b §4 で了承済み）。
     * ここに無い Capital は [AccountTab.OTHER]。黙って消えるよりは目に見えるほうがよい。
     */
    private val CAPITAL_TABS: Map<String, AccountTab> = mapOf(
        "zigyounusikas" to AccountTab.ASSET,      // 事業主貸
        "zigyounusikari" to AccountTab.LIABILITY, // 事業主借
        "motoire" to AccountTab.LIABILITY,        // 元入金
        "kouzyo" to AccountTab.LIABILITY,         // 青申特別控除前の所得金額
        "senzyuusya" to AccountTab.EXPENSE,       // 専従者給与
        "kakei" to AccountTab.EXPENSE             // 家計費
    )

    fun tabOf(account: AoiroChoboAccount): AccountTab = when (account.accountType) {
        "Asset" -> AccountTab.ASSET
        "Liability" -> AccountTab.LIABILITY
        "Income" -> AccountTab.INCOME
        "Expense" -> AccountTab.EXPENSE
        "Capital" -> CAPITAL_TABS[account.accountKey] ?: AccountTab.OTHER
        else -> AccountTab.OTHER
    }

    /**
     * 表の 1 行。
     *
     * @property isChild 内訳科目（親がこのタブにいるもの）。PC では「内訳科目名」の列に出る
     * @property hasChildren 内訳科目を持つ親。PC ではこの行の内訳欄が空白（白）で、持たない行は灰色
     * @property startsGroup グループ欄にグループ名を出す行（同じグループが続く間の先頭）
     */
    data class AccountRow(
        val account: AoiroChoboAccount,
        val isChild: Boolean,
        val hasChildren: Boolean,
        val startsGroup: Boolean
    )

    /**
     * [tab] に出す科目を PC と同じ順に並べる。
     *
     * 順は displayOrder（2026-09-24 から PC の科目マスタの枠番号・契約 vocabulary-snapshot.md §4.4 の後）。
     * 内訳科目は親の直後に置き、グループは親のものを引き継ぐ。親がこのタブにいない内訳は普通の行として出す。
     */
    fun rowsFor(tab: AccountTab, accounts: List<AoiroChoboAccount>): List<AccountRow> {
        val inTab = accounts.filter { tabOf(it) == tab }
        val keysInTab = inTab.map { it.accountKey }.toSet()
        fun isChild(a: AoiroChoboAccount) = a.parentAccountKey != null && a.parentAccountKey in keysInTab

        val children = inTab.filter(::isChild).groupBy { it.parentAccountKey }
        val rows = mutableListOf<AccountRow>()
        var previousGroup: String? = null
        inTab.filterNot(::isChild).sortedBy { it.displayOrder }.forEachIndexed { i, parent ->
            val group = parent.groupName?.takeIf { it.isNotBlank() }
            val kids = children[parent.accountKey].orEmpty().sortedBy { it.displayOrder }
            rows += AccountRow(parent, isChild = false, hasChildren = kids.isNotEmpty(),
                startsGroup = i == 0 || group != previousGroup)
            kids.forEach { rows += AccountRow(it, isChild = true, hasChildren = false, startsGroup = false) }
            previousGroup = group
        }
        return rows
    }

    /** PC の「有効な課税区分」列。どちらも選べない科目（繰入額・資産など）は null＝灰色の空欄 */
    fun allowedTaxLabel(account: AoiroChoboAccount): String? = when {
        account.allowsTaxable && account.allowsNonTaxable -> "すべて"
        account.allowsTaxable -> "課税のみ"
        account.allowsNonTaxable -> "課税以外"
        else -> null
    }

    /** PC の「既定の課税区分」列。NA は区分を持たない科目なので null＝灰色の空欄 */
    fun taxCategoryLabel(value: String?): String? = when (value) {
        null, "NA" -> null
        "Taxable" -> "課税"
        "NonTaxable" -> "非課税"
        "NotApplicable" -> "不課税"
        "TaxExempt" -> "免税"
        else -> value
    }

    /** 摘要の税率。PC の摘要画面の表記に合わせる（8% に「軽」は付けていない） */
    fun taxRateLabel(value: String?): String? = when (value) {
        null -> null
        "10" -> "10%"
        "8" -> "8%"
        "1" -> "1%"
        "8_old" -> "8%(旧)"
        "non" -> "非課税"
        "na" -> "不課税"
        "men" -> "免税"
        else -> value
    }
}
