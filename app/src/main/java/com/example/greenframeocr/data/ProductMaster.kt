package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
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
        Index(value = ["kaikakeTekiyouId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = RakurakuTekiyou::class,
            parentColumns = ["id"],
            childColumns = ["kaikakeTekiyouId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ProductMaster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 正規化された商品名 (例: "フェニックス顆粒水和剤250g") */
    val canonicalName: String,

    /** カテゴリ: "一般購買", "給油所", "農業機械" */
    val category: String,

    /** 使用頻度（よく買う商品を優先マッチング） */
    val frequencyCount: Int = 0,

    /** 買掛摘要辞書ID（RakurakuTekiyouのID） */
    val kaikakeTekiyouId: Int? = null
)
