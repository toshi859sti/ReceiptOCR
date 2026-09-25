package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab

/**
 * JA 購買伝票の商品に AoiroChobo の科目・摘要を紐付けるときの候補の出し方。
 *
 * 順序は **科目 → その科目で絞った摘要 → ユーザーが確定**（CURRENT_TASK.md 2026-09-23 第2の訂正）。
 * 摘要は「相手科目・税率・事業割合」の不可分のセットなので、摘要から科目を上書きすることはない。
 * 科目だけ決まって摘要が 0 件のこともある（給油所→動力光熱費・修繕費は買掛/仕入の摘要が無い）ので、
 * 摘要なし（memoKey = null）も正しい状態として扱う。PC はそれを UnmatchedMemo で受ける。
 */
object AoiroChoboPurchaseRules {

    /** 借方の候補。契約 vocabulary-snapshot.md §4.5：`Purchase` 借方は `ocrRoleExpenseDebit == true` */
    fun accountCandidates(accounts: List<AoiroChoboAccount>): List<AoiroChoboAccount> =
        accounts.filter { it.ocrRoleExpenseDebit }.sortedBy { it.displayOrder }

    /**
     * [accountKey] で絞った摘要の候補。JA 購買は買掛帳の仕入（`AP` × `In`）の摘要だけから選ぶ。
     */
    fun memoCandidates(accountKey: String, memos: List<AoiroChoboMemoTemplate>): List<AoiroChoboMemoTemplate> =
        memos.filter { MemoTab.AP_IN.contains(it) && it.counterAccountKey == accountKey }
            .sortedBy { it.displayOrder }

    /**
     * 科目を選んだ直後に摘要を先に埋めておいてよいか。候補がちょうど 1 件で、事業割合だけ違う組
     * （[AoiroChoboMemoRules.ratioSensitiveMemoKeys]）に入っていないときだけ返す。
     * 2 件以上あるときは同義の摘要（諸材料購入／資材購入）でも選ばない。どちらで帳簿に載るかは農家が決める。
     */
    fun preselectedMemo(accountKey: String, memos: List<AoiroChoboMemoTemplate>): AoiroChoboMemoTemplate? {
        val candidates = memoCandidates(accountKey, memos)
        val only = candidates.singleOrNull() ?: return null
        return only.takeUnless { it.memoKey in AoiroChoboMemoRules.ratioSensitiveMemoKeys(memos) }
    }
}
