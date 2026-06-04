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
    val createdAt: Long = System.currentTimeMillis()
)
