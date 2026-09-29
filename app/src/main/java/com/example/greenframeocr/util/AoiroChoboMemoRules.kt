package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate

/**
 * AoiroChobo の摘要辞書を「どの帳簿で見せるか」「自動で選んでよいか」に振り分ける規則。
 *
 * どちらも `vocabulary.json` だけで決まる。一覧画面と、商品編集で摘要候補を出す処理の両方がここを使う。
 */
object AoiroChoboMemoRules {

    /**
     * PC の摘要画面のタブ（docs/integration/REPLY-pc-2026-09-23b.md §4）。
     *
     * 現金・預金のタブは `ledgerType` を見ず、`showInCash` / `showInBank` だけで絞る。
     * `ledgerType == "Bank"` の摘要は実在しないので、ledgerType で預金を絞ると常に0件になる。
     * `direction` はお金の向きではなく帳簿上の発生（In）と解消（Out）。
     */
    enum class MemoTab(val label: String, private val match: (AoiroChoboMemoTemplate) -> Boolean) {
        CASH_IN("現金/入金", { it.showInCash && it.direction == "In" }),
        CASH_OUT("現金/出金", { it.showInCash && it.direction == "Out" }),
        BANK_IN("預金/入金", { it.showInBank && it.direction == "In" }),
        BANK_OUT("預金/出金", { it.showInBank && it.direction == "Out" }),
        AR_IN("売掛/売上", { it.ledgerType == "AR" && it.direction == "In" }),
        AR_OUT("売掛/入金", { it.ledgerType == "AR" && it.direction == "Out" }),
        AP_IN("買掛/仕入", { it.ledgerType == "AP" && it.direction == "In" }),
        AP_OUT("買掛/支払", { it.ledgerType == "AP" && it.direction == "Out" }),
        UNPAID_IN("未払/発生", { it.ledgerType == "Unpaid" && it.direction == "In" }),
        UNPAID_OUT("未払/支払", { it.ledgerType == "Unpaid" && it.direction == "Out" }),
        TRANSFER("振替", { it.ledgerType == "Transfer" });

        fun contains(memo: AoiroChoboMemoTemplate): Boolean = match(memo)
    }

    /**
     * 自動で選んではいけない摘要の memoKey。
     *
     * PC は memoKey が解決できたら**摘要側の businessRatio を仕訳に入れる**（REPLY-pc-2026-09-23b.md §1・答えB）。
     * 相手科目・税率が同じで事業割合だけ違う摘要（電気料金 40% ／ 電気料金（事業専用）100%）を
     * 取り違えると帳簿の金額が変わるので、そういう組に属する摘要はユーザーに確定させる。
     *
     * 判定：同じ `ledgerType × direction × counterAccountKey × taxRate` の中で businessRatio が
     * 1つでも違えば、その組の全員が対象。振替（相手科目を持たない）は対象外。
     */
    fun ratioSensitiveMemoKeys(memos: List<AoiroChoboMemoTemplate>): Set<String> =
        memos.asSequence()
            .filter { it.ledgerType != "Transfer" }
            .groupBy { listOf(it.ledgerType, it.direction, it.counterAccountKey, it.taxRate) }
            .values
            .filter { group -> group.map { it.businessRatio }.distinct().size > 1 }
            .flatMap { group -> group.map { it.memoKey } }
            .toSet()
}
