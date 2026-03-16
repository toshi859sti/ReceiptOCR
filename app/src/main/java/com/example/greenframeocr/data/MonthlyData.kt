package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_data")
data class MonthlyData(
    @PrimaryKey
    val id: String,              // "YYYY_MM"
    val issueYear: Int,          // 発行年
    val issueMonth: Int,         // 発行月
    val totalSheets: Int,        // 合計枚数
    val generalPurchaseTotal: Int,   // 一般購買合計
    val agriculturalTotal: Int,      // 農業機械合計
    val gasStationTotal: Int,        // 給油所合計
    val monthlyTotal: Int            // 月合計
)
