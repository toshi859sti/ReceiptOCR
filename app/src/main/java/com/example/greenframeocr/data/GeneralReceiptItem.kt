package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "general_receipt_items",
    foreignKeys = [ForeignKey(
        entity = GeneralReceipt::class,
        parentColumns = ["id"],
        childColumns = ["receiptId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("receiptId")]
)
data class GeneralReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val itemName: String = "",
    val price: Int = 0,
    val category: String = "未分類",
    val tekiyouId: Int? = null,
    val yayoiAccountId: Long? = null,
    val isExcluded: Boolean = false
)
