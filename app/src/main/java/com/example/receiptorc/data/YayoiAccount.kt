package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 弥生会計 勘定科目マスタ
 *
 * 弥生会計に自動連携するための勘定科目情報。
 */
@Entity(
    tableName = "yayoi_accounts",
    indices = [Index(value = ["accountCode"], unique = true)]
)
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 勘定科目コード (例: "601") */
    val accountCode: String,

    /** 勘定科目名 (例: "種苗費") */
    val accountName: String,

    /** カテゴリ (例: "農業経費") */
    val category: String? = null,

    /** サブカテゴリ (例: "種苗") */
    val subcategory: String? = null,

    /** 説明 */
    val description: String? = null
)
