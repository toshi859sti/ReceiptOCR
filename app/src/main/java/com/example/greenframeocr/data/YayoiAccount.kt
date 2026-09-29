package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "yayoi_accounts",
    indices = [Index(value = ["accountCode"])]
)
data class YayoiAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountName: String,
    val searchKeyAlpha: String = "",
    val accountCode: String?,
    val debitCredit: String = "",
    val categoryA: String = "",
    val categoryB: String = "",
    val defaultTaxCategory: String = "対象外",
    val usedForPurchase: Boolean = false,
    val usedForDeposit: Boolean = false,
    val usedForReceipt: Boolean = false,
    val isEnabled: Boolean = true,
    val parentId: Long? = null
)
