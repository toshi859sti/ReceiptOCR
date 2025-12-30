package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * らくらく青色申告（農業版） 勘定科目マスタ
 *
 * らくらく青色申告に自動連携するための勘定科目情報。
 */
@Entity(
    tableName = "rakuraku_accounts",
    indices = [Index(value = ["accountCode"], unique = true)]
)
data class RakurakuAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 勘定科目コード (例: "7001") */
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
