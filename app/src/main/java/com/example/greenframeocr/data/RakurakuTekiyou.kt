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
    val isEnabled: Boolean = true,

    /**
     * AoiroChobo（PC会計アプリ）の摘要参照キー。null = 未マッピング
     * （その摘要を使う仕訳は matchStatus = "UnmatchedMemo" で出す）。
     */
    val memoKey: String? = null,

    /**
     * memoKey を確定したときに見えていた AoiroChobo 側の摘要名。
     * vocabulary.json 取込時にこれと現在名が食い違ったら memoKey を外す
     * （CHANGELOG 2026-09-13 改訂・変更2）。
     */
    val memoKeyName: String? = null
)
