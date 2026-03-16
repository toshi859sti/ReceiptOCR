package com.example.greenframeocr.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OCRフォールバックログ
 *
 * 商品名列OCRが失敗し、フルOCRからフォールバックが発動した際のログ。
 * 文字高さと発生率の相関分析、閾値チューニングに使用。
 */
@Entity(
    tableName = "ocr_fallback_logs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["textHeight"]),
        Index(value = ["sessionId"])
    ]
)
data class OcrFallbackLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** セッションID（1回のOCR処理を識別） */
    val sessionId: String,

    /** 行インデックス */
    val rowIndex: Int,

    /** 行のY座標（px） */
    val rowY: Int,

    /** フォールバックで取得した生テキスト */
    val rawText: String,

    /** クリーニング後のテキスト */
    val cleanedText: String,

    /** フォールバック理由 */
    val reason: String,

    /** テキストの平均高さ（px） */
    val textHeight: Float,

    /** 使用したフォールバックTextBox数 */
    val boxCount: Int,

    /** 分離されたテキストリスト（JSON形式、explicitJoin学習用） */
    @ColumnInfo(defaultValue = "")
    val separatedTexts: String = "",

    /** 作成日時（エポックミリ秒） */
    val createdAt: Long = System.currentTimeMillis()
)
