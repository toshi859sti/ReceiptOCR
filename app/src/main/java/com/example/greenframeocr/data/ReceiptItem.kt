package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "receipt_items")
data class ReceiptItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val issueYear: Int,              // 発行年
    val issueMonth: Int,             // 発行月
    val sheetNumber: Int,            // 伝票番号（何枚目）
    val itemNumber: Int,             // 伝票内行番号
    val receiptYear: Int,            // 領収日：年
    val receiptMonth: Int,           // 領収日：月
    val receiptDay: Int,             // 領収日：日
    val productName: String,         // 商品名
    val amount: Int,                 // 税込金額（マイナス可：返品処理）
    val category: String,            // 分類（未分類/一般購買/給油所/農業機械）
    val isOcrOverwriteTarget: Boolean = false,  // 再OCR上書き対象フラグ
    val ocrConfidence: String? = null,  // Gemini自己申告の確信度（"high"/"medium"/"low"）。ML Kit由来はnull
    val productMasterId: Long? = null  // product_master.id への紐づけ（未マッチ・小計/合計行はnull）
)
