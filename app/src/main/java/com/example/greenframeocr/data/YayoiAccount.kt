package com.example.greenframeocr.data

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

    /** 区分C - 小分類 (例: "【経費】") */
    val categoryC: String = "",

    /** 区分B - 中分類 (例: "【経費】") */
    val categoryB: String = "",

    /** 区分A - 大分類 (例: "【経常損益】") */
    val categoryA: String = "",

    /** 購買取引で使用するか */
    val usedForPurchase: Boolean = false,

    /** 預金取引で使用するか */
    val usedForDeposit: Boolean = true,

    /** 親科目ID (階層構造用) */
    val parentId: Long? = null
)
