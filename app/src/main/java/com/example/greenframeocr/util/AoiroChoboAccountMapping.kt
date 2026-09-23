package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.YayoiAccount

/**
 * 弥生の勘定科目に AoiroChobo の `accountKey` を割り当てるときの、タブ分けと自動提案。
 *
 * 並べる主軸は **AoiroChobo 側の科目**（実測 64 件・PC 画面と同じ 4 タブ）。
 * 弥生側（98 行）を頭から舐めると相手のいない科目が 34 件出るうえ、
 * ユーザーが PC で見慣れている順と合わない。
 *
 * 列そのものは `yayoi_accounts.accountKey` にあるので、
 * **1 つの accountKey に複数の弥生科目**を割り当てられる（弥生の「建物」「構築物」が
 * AoiroChobo の「建物・構築物（資産）」1 つに集まる、など）。
 */
object AoiroChoboAccountMapping {

    /** PC（あおいろ帳簿）の科目・残高登録画面のタブ */
    enum class Tab(val label: String) {
        ASSET("資産"),
        LIABILITY("負債"),
        INCOME("収入"),
        EXPENSE("支出"),
        OTHER("その他")
    }

    /**
     * `Capital` の科目がどのタブに出るか。
     *
     * `displayOrder` は accountType 順の通し番号（Asset 1〜/ … / Capital 59〜）で、
     * PC 画面上の位置とは対応しない。Capital の 6 件は画面上では資産・負債・支出に散るので、
     * ここだけは accountKey で対応を持つ。6 件ともシステム科目で accountKey は不変（契約 §4.6）。
     *
     * ここに無い Capital が来たら [Tab.OTHER] に落とす。黙って消えるよりは目に見えるほうがよい。
     */
    private val CAPITAL_TABS: Map<String, Tab> = mapOf(
        "zigyounusikas" to Tab.ASSET,      // 事業主貸
        "zigyounusikari" to Tab.LIABILITY, // 事業主借
        "motoire" to Tab.LIABILITY,        // 元入金
        "kouzyo" to Tab.LIABILITY,         // 青申特別控除前の所得金額
        "senzyuusya" to Tab.EXPENSE,       // 専従者給与
        "kakei" to Tab.EXPENSE             // 家計費
    )

    fun tabOf(account: AoiroChoboAccount): Tab = when (account.accountType) {
        "Asset" -> Tab.ASSET
        "Liability" -> Tab.LIABILITY
        "Income" -> Tab.INCOME
        "Expense" -> Tab.EXPENSE
        "Capital" -> CAPITAL_TABS[account.accountKey] ?: Tab.OTHER
        else -> Tab.OTHER
    }

    /**
     * 科目名の表記ゆれを吸収する。
     *
     * 実測（AoiroChobo 64 件 ↔ 弥生 98 件）では、そのままの名前で 40 件一致し、
     * この正規化で「農産物等（資産）→農産物等」「農機具等→農機具」「家事消費→家事消費等」の
     * 3 件が追加で一致して 43 件になる。
     */
    fun normalizeName(name: String): String {
        var s = name
            .replace(PARENS_FULL, "")
            .replace(PARENS_HALF, "")
            .replace(SEPARATORS, "")
            .trim()
        // 「農機具等」と「農機具」、「家事消費」と「家事消費等」を同じものとして扱う。
        // 1 文字になるまで削らない（「等」だけの科目名を空にしないため）
        if (s.length > 1 && s.endsWith("等")) s = s.dropLast(1)
        return s
    }

    private val PARENS_FULL = Regex("（[^）]*）")
    private val PARENS_HALF = Regex("\\([^)]*\\)")
    private val SEPARATORS = Regex("[・･\\s　]")

    /** 提案の根拠。UI に理由として出すので、強い順に並べておく */
    enum class Basis(val label: String) {
        EXACT_NAME("科目名が一致"),
        NORMALIZED_NAME("科目名がほぼ一致"),
        SEARCH_KEY("検索文字が一致")
    }

    data class Suggestion(
        val accountKey: String,
        val candidates: List<YayoiAccount>,
        val basis: Basis
    ) {
        /** 候補が 1 つに決まっているか。複数なら一括確定には回さず、ユーザーに選ばせる */
        val isUnambiguous: Boolean get() = candidates.size == 1
    }

    /**
     * 未マッピングの AoiroChobo 科目に、弥生科目の候補を提案する。
     *
     * - すでに `accountKey` が入っている弥生科目は候補から外す（取り合いを避ける）
     * - 根拠が強い段（名前の完全一致 → 正規化一致 → 検索文字一致）で最初に当たったものを返す
     * - 候補が複数ある段はそのまま複数返す。UI はそれを「選んでください」として出す
     */
    fun suggest(
        aoiroAccounts: List<AoiroChoboAccount>,
        yayoiAccounts: List<YayoiAccount>
    ): Map<String, Suggestion> {
        val available = yayoiAccounts.filter { it.accountKey == null && it.isEnabled }

        val byExactName = available.groupBy { it.accountName.trim() }
        val byNormalizedName = available.groupBy { normalizeName(it.accountName) }
        val bySearchKey = available
            .filter { it.searchKeyAlpha.isNotBlank() }
            .groupBy { it.searchKeyAlpha.trim().lowercase() }

        val alreadyLinked = yayoiAccounts.mapNotNull { it.accountKey }.toSet()

        return aoiroAccounts
            .filter { it.accountKey !in alreadyLinked }
            .mapNotNull { aoiro ->
                val hit = byExactName[aoiro.name.trim()]?.let { it to Basis.EXACT_NAME }
                    ?: byNormalizedName[normalizeName(aoiro.name)]?.let { it to Basis.NORMALIZED_NAME }
                    ?: aoiro.searchKey.takeIf { it.isNotBlank() }
                        ?.let { bySearchKey[it.trim().lowercase()] }
                        ?.let { it to Basis.SEARCH_KEY }
                    ?: return@mapNotNull null
                aoiro.accountKey to Suggestion(aoiro.accountKey, hit.first, hit.second)
            }
            .toMap()
    }

    /**
     * 一括確定に回してよい提案だけを取り出す。
     *
     * 候補が 1 つに決まっているものに限る。同じ弥生科目が 2 つの accountKey から
     * 提案されることは [suggest] の作りからは起きないが、念のためここでも弾く。
     */
    fun autoConfirmable(suggestions: Map<String, Suggestion>): List<Suggestion> {
        val claimed = mutableSetOf<Long>()
        return suggestions.values
            .filter { it.isUnambiguous }
            .sortedBy { it.basis.ordinal }
            .filter { claimed.add(it.candidates.first().id) }
    }
}
