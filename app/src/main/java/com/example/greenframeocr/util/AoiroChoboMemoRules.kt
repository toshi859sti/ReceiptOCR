package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate

/**
 * AoiroChobo の摘要辞書を「どの帳簿で見せるか」「自動で選んでよいか」に振り分ける規則。
 *
 * どちらも `vocabulary.json` だけで決まる。一覧画面と、商品編集で摘要候補を出す処理の両方がここを使う。
 */
object AoiroChoboMemoRules {

    /**
     * 摘要を選んだときに決まる科目。ふつうの摘要は相手科目（`counterAccountKey`）、
     * 振替の摘要は借方の科目（`debitAccountKey`。レシートでは品目の経費科目。貸方は支払方法の科目）
     */
    fun accountKeyOf(memo: AoiroChoboMemoTemplate): String? = memo.counterAccountKey ?: memo.debitAccountKey

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
        TRANSFER("振替", { it.ledgerType == "Transfer" }),

        /** レシート共通（契約 minor（10））。現金/出金の摘要の一部で、未払・振替のレシートにも同じ摘要で使える */
        RECEIPT_COMMON("レシート共通", { it.paymentCommon });

        fun contains(memo: AoiroChoboMemoTemplate): Boolean = match(memo)
    }
}
