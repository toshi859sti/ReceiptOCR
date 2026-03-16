package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * らくらく青色申告農業版 摘要辞書エンティティ
 */
@Entity(
    tableName = "rakuraku_tekiyou",
    indices = [
        Index(value = ["mainCategory", "subCategory"])
    ]
)
data class RakurakuTekiyou(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** メインカテゴリ: 現金, 預金, 売掛, 買掛 */
    val mainCategory: String,

    /** サブカテゴリ: 入金, 出金, 販売, 購入 */
    val subCategory: String,

    /** 摘要名 */
    val tekiyouName: String,

    /** 検索文字（ローマ字） */
    val searchKey: String,

    /** 勘定科目 */
    val kamoku: String,

    /** 税率: 8%, 10%, 非, 不, 空 */
    val taxRate: String = "",

    /** 事業割合（%）: null = 指定なし */
    val businessRatio: Int? = null,

    /** 共有フラグ（現金/預金のみ） */
    val isShared: Boolean? = null,

    /** 使用するフラグ（選択可能かどうか） */
    val isEnabled: Boolean = true
)
