package com.example.greenframeocr.data

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

    /** 勘定科目コード (例: "501") */
    val accountCode: String,

    /** 勘定科目名 (例: "種苗費") */
    val accountName: String,

    /** サーチキー英字 (例: "syubyou") */
    val searchKeyAlpha: String = "",

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
