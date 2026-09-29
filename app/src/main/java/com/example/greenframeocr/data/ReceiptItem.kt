package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "receipt_items",
    indices = [Index(value = ["uuid"], unique = true)]
)
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
    val productMasterId: Long? = null,  // product_master.id への紐づけ（未マッチ・小計/合計行はnull）
    val exportedAt: String? = null,  // CSV出力日時（yyyy/MM/dd HH:mm）。未出力ならnull
    // AoiroChobo連携の externalId（"ocr:purchase:{uuid}"）の材料。
    // 月データの保存は「月単位で全DELETE→全INSERT」で id も itemNumber も安定しないため、
    // 行の同一性はこのUUIDで持つ（docs/integration/transaction-import.md §4）。
    // グリッドの「挿入」「削除」は行オブジェクトごとシフトするので、uuid は行の内容に付いて動く。
    // 小文字のまま送ること（externalId の文字種は [a-z0-9:_-]）
    val uuid: String = UUID.randomUUID().toString()
)
