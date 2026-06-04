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
    val yayoiAccountId: Long? = null
)
