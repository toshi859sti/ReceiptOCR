package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "yayoi_accounts",
    indices = [Index(value = ["accountCode"])]
)
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,
    val searchKeyAlpha: String = "",
    val accountCode: String?,
    val debitCredit: String = "",
    val categoryA: String = "",
    val categoryB: String = "",
    val defaultTaxCategory: String = "対象外",
    val usedForPurchase: Boolean = false,
    val usedForDeposit: Boolean = false,
    val usedForReceipt: Boolean = false,
    val isEnabled: Boolean = true,
    val parentId: Long? = null,

    /**
     * AoiroChobo（PC会計アプリ）の科目参照キー。null = 未マッピング
     * （その科目を使う仕訳は matchStatus = "UnmatchedAccount" で出す）。
     */
    val accountKey: String? = null,

    /**
     * accountKey を確定したときに見えていた AoiroChobo 側の科目名。
     * vocabulary.json 取込時にこれと現在名が食い違ったら「科目の作り替え」とみなし、
     * accountKey を外してユーザーに再確認させる（CHANGELOG 2026-09-13 改訂・変更2）。
     */
    val accountKeyName: String? = null
)
