package com.example.receiptorc.data

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
        Index(value = ["tekiyou"])
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
    val matchingRuleId: Int? = null
)
