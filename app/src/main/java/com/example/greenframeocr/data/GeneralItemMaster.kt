package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 品目別マッチングの「グループのデフォルト科目」。
 * canonicalKey（GeneralReceiptItem.canonicalKey）ごとに1件、デフォルト科目を保持する。
 * 個別明細側の GeneralReceiptItem.yayoiAccountId が null のときにここへフォールバックする。
 */
@Entity(tableName = "general_item_master")
data class GeneralItemMaster(
    @PrimaryKey val canonicalKey: String,
    val yayoiAccountId: Long? = null,
    /**
     * AoiroChobo の科目参照キー。あおいろ出力の借方科目。
     *
     * 弥生用の yayoiAccountId とは**独立**。同じ商品でも弥生で A、あおいろで B を選ぶことがある
     * （2026-09-23 ユーザー確認）。両者を結ぶ対応表は存在しない。
     */
    val accountKey: String? = null,

    /** accountKey を確定したときに見えていた AoiroChobo 側の科目名（作り替え検知用） */
    val accountKeyName: String? = null,

    /**
     * AoiroChobo の摘要参照キー。null = 未確定（matchStatus = "UnmatchedMemo" で出す）。
     *
     * 摘要は「相手科目・税区分・税率・事業割合」が不可分のセットで、内容は変えられない。
     * したがって選べるのは counterAccountKey == accountKey の摘要だけで、科目を変えたら外す。
     * 相手科目も税率も同じで事業割合だけ違う摘要があり（電気料金 40% / 電気料金（事業専用）100%）、
     * そこは商品名からは決まらないので最後はユーザーが選ぶ。
     */
    val memoKey: String? = null,

    /** memoKey を確定したときに見えていた AoiroChobo 側の摘要名（作り替え検知用） */
    val memoKeyName: String? = null
)
