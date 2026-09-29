package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 商品マスタテーブル
 *
 * 購買伝票に出現する商品の正式名称を管理。
 * OCR結果の誤認識を補正するための辞書として機能。
 */
@Entity(
    tableName = "product_master",
    indices = [
        Index(value = ["canonicalKey", "category"], unique = true)
    ]
)
data class ProductMaster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 表示用商品名。スペース・表記ゆれを保持 (例: "フェニックス顆粒水和剤　250g") */
    val canonicalName: String,

    /**
     * 一意性チェック用キー。canonicalName からスペース除去・文字種統一した形。
     * INSERT 前に必ず withComputedKey() で設定すること。
     */
    val canonicalKey: String = "",

    /** カテゴリ: "一般購買", "給油所", "農業機械" */
    val category: String,

    /** 使用頻度（よく買う商品を優先マッチング） */
    val frequencyCount: Int = 0,

    /** 確定フラグ: 手動入力・訂正済みアイテムはtrue */
    val isCertified: Boolean = false,

    /** 弥生勘定科目ID（YayoiAccountのID、弥生モード時に使用） */
    val yayoiAccountId: Long? = null,

    /**
     * AoiroChobo の科目参照キー。あおいろ出力の借方科目。
     *
     * 弥生用の yayoiAccountId とは**独立**。同じ商品でも弥生で A、あおいろで B を選ぶことがある
     * （2026-09-23 ユーザー確認）。両者を結ぶ対応表は存在しない。
     */
    val accountKey: String? = null,

    /** accountKey を確定したときに見えていた AoiroChobo 側の科目名（作り替え検知用） */
    val accountKeyName: String? = null,

    /**
     * AoiroChobo の摘要参照キー。null = 未確定（matchStatus = "UnmatchedMemo" で出す）。
     *
     * 摘要は「相手科目・税区分・税率・事業割合」が不可分のセットで、内容は変えられない。
     * したがって選べるのは counterAccountKey == accountKey の摘要だけで、科目を変えたら外す。
     * 相手科目も税率も同じで事業割合だけ違う摘要があり（電気料金 40% / 電気料金（事業専用）100%）、
     * そこは商品名からは決まらないので最後はユーザーが選ぶ。
     */
    val memoKey: String? = null,

    /** memoKey を確定したときに見えていた AoiroChobo 側の摘要名（作り替え検知用） */
    val memoKeyName: String? = null
)
