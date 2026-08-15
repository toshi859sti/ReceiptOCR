package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "general_receipts")
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
    val paymentAccountOverride: Long? = null
)
