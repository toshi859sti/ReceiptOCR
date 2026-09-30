package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * JA 購買伝票の商品に AoiroChobo の科目・摘要を紐付けるときの候補の出し方。
 *
 * 画面では**摘要を先に選ぶ**（2026-09-30 ユーザー指示。それまでは科目 → 摘要の順だった）。
 * 摘要は「相手科目・税率・事業割合」の不可分のセットなので、摘要を選べば科目はその相手科目に決まる。
 * 科目だけ決まって摘要が 0 件のこともある（給油所→動力光熱費・修繕費は買掛/仕入の摘要が無い）ので、
 * 摘要なし（memoKey = null）も正しい状態として扱う。PC はそれを UnmatchedMemo で受ける。
 * 摘要は自動では埋めない（AI 提案も科目だけ）。空欄のまま PC で決めることがあるため
 */
object AoiroChoboPurchaseRules {

    /**
     * PC が許す借方の候補（契約 §4.5：`Purchase` 借方は `ocrRoleExpenseDebit == true`）。農家の絞り込みは通さない。
     * 絞り込み後の候補は [AoiroChoboUsageRules.candidates]
     */
    fun accountCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        AoiroChoboUsageRules.pcCandidates(AoiroChoboUsageRules.Usage.PURCHASE, accounts)

    /**
     * [accountKey] で絞った摘要の候補。JA 購買は買掛帳の仕入（`AP` × `In`）の摘要だけから選ぶ。
     */
    fun memoCandidates(accountKey: String, memos: List<AoiroChoboMemoTemplate>): List<AoiroChoboMemoTemplate> =
        memos.filter { MemoTab.AP_IN.contains(it) && it.counterAccountKey == accountKey }
            .sortedBy { it.displayOrder }
}
