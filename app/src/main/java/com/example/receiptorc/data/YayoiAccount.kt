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

    /** 勘定科目名 (例: "種苗費") */
    val accountName: String,

    /** サーチキー英字 (例: "SHUBYOU") */
    val searchKeyAlpha: String = "",

    /** サーチキー数字 = 勘定科目コード (例: "602") */
    val accountCode: String,

    /** 借貸 (借/貸) */
    val debitCredit: String = "",

    /** 税区分 (例: "課税対応仕入", "対象外") */
    val taxCategory: String = ""
)
