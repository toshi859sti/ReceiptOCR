package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoice_stores")
data class InvoiceStore(
    @PrimaryKey val registrationNumber: String, // "T" + 13桁
    val storeName: String,
    val address: String = "",
    val cachedAt: Long = System.currentTimeMillis()
)
