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
        // 通番は口座ごとに振られるので、口座が違えば同じ日・同じ通番があり得る（DB v39）
        Index(value = ["passbookId", "transactionDate", "transactionNumber"], unique = true)
    ]
)
data class DepositMeisai(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** どの通帳の明細か（[Passbook.id]）。外部キーは張らない：通帳を消して明細が道連れになるのを避ける */
    val passbookId: Int = Passbook.DEFAULT_ID,

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

    /** 個別オーバーライド弥生勘定科目ID（nullならグループのデフォルトを使用・弥生用） */
    val overrideYayoiAccountId: Long? = null,

    /** 個別オーバーライドの AoiroChobo 科目キー（overrideYayoiAccountId のあおいろ版） */
    val overrideAccountKey: String? = null,
    val overrideAccountKeyName: String? = null,

    /** 個別オーバーライドの AoiroChobo 摘要キー。選べるのは overrideAccountKey に属する摘要だけ */
    val overrideMemoKey: String? = null,
    val overrideMemoKeyName: String? = null,

    /** CSV出力日時（yyyy/MM/dd HH:mm）。未出力ならnull */
    val exportedAt: String? = null
)
