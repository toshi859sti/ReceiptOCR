package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OCR誤認識パターンテーブル
 *
 * 実際のOCR結果として検出された誤字・表記ゆれを記録。
 * 過去の誤認識パターンを学習して補正精度を向上。
 */
@Entity(
    tableName = "ocr_variants",
    foreignKeys = [
        ForeignKey(
            entity = ProductMaster::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["variantText"])
    ]
)
data class OcrVariant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 商品マスタID */
    val productId: Long,

    /** 誤認識された文字列 (例: "フェニックス類粒水和剤") */
    val variantText: String,

    /** 出現回数 */
    val occurrenceCount: Int = 0,

    /** 最終確認日時 (Unix timestamp) */
    val lastSeen: Long = System.currentTimeMillis()
)
