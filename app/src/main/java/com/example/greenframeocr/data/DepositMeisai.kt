package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 預金明細エンティティ
 */
@Entity(
    tableName = "deposit_meisai",
    indices = [
        Index(value = ["transactionDate"]),
        Index(value = ["tekiyou"]),
        Index(value = ["transactionDate", "transactionNumber"], unique = true)
    ]
)
data class DepositMeisai(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** 取引日 (yyyy-MM-dd) */
    val transactionDate: String,

    /** 取引通番 */
    val transactionNumber: String,

    /** 摘要（原文） */
    val tekiyou: String,

    /** 金額（正:入金、負:出金） */
    val amount: Int,

    /** メモ */
    val memo: String = "",

    /** マッチングルールID（nullならマッチなし） */
    val matchingRuleId: Int? = null,

    /** 個別オーバーライド摘要ID（nullならグループのデフォルトを使用・らくらく用） */
    val overrideTekiyouId: Int? = null,

    /** 個別オーバーライド弥生勘定科目ID（nullならグループのデフォルトを使用・弥生用） */
    val overrideYayoiAccountId: Long? = null,

    /** 個別オーバーライド摘要の AoiroChobo キー。`overrideTekiyouId` の置き換え先 */
    val overrideMemoKey: String? = null,

    /** CSV出力日時（yyyy/MM/dd HH:mm）。未出力ならnull */
    val exportedAt: String? = null
)
