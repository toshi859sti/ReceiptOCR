package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "general_receipts",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class GeneralReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val storeName: String = "",
    val total: Int = 0,
    val rawOcrText: String = "",
    val geminiUsed: Boolean = false,
    val registrationNumber: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // 合計欄付近から抽出した支払方法の印字テキスト（例:「クレジット」「PayPay」）。
    // 手書き領収書等で記載がなければnull。ReceiptPaymentMethodRuleとの照合に使う
    val paymentMethodText: String? = null,
    // 相手科目（貸方勘定科目）の個別上書き。null=ReceiptPaymentMethodRuleでの自動判定に従う
    val paymentAccountOverride: Long? = null,
    // AoiroChobo連携の externalId（"ocr:receipt:{uuid}"）の材料。
    // autoincrement の id はバックアップ復元や再インポートで意味が変わり得るため、
    // 行の同一性はこのUUIDで持つ（docs/integration/transaction-import.md §4）。
    // 既存行はマイグレーション33→34で採番済み
    val uuid: String = UUID.randomUUID().toString()
)
