package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 摘要マッチングルールエンティティ
 * 預金明細の摘要パターンとらくらく摘要辞書を紐付ける
 */
@Entity(
    tableName = "tekiyou_matching_rules",
    indices = [
        Index(value = ["pattern"], unique = true),
        Index(value = ["rakurakuTekiyouId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = RakurakuTekiyou::class,
            parentColumns = ["id"],
            childColumns = ["rakurakuTekiyouId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TekiyouMatchingRule(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** マッチング用パターン（正規表現可） */
    val pattern: String,

    /** 表示用の正規化された摘要名 */
    val normalizedTekiyou: String,

    /** 正規表現として扱うか */
    val isRegex: Boolean = false,

    /** 紐付けるらくらく摘要辞書ID */
    val rakurakuTekiyouId: Int? = null,

    /** サンプル（元の摘要テキストの例） */
    val sampleText: String = "",

    /** 該当件数 */
    val matchCount: Int = 0,

    /** 入金(true) or 出金(false) - 金額の符号から自動判定 */
    val isDeposit: Boolean = true,

    /** 弥生勘定科目ID（弥生モード時に使用） */
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
