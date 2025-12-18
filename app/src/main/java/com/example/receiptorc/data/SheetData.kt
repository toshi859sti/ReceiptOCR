package com.example.receiptorc.data

import androidx.room.Entity

@Entity(
    tableName = "sheet_data",
    primaryKeys = ["issueYear", "issueMonth", "sheetNumber"]
)
data class SheetData(
    val issueYear: Int,              // 発行年
    val issueMonth: Int,             // 発行月
    val sheetNumber: Int,            // 伝票番号（何枚目）

    // OCR/手入力の値（編集可能）
    val totalFromInput: Int?,              // 合計
    val subtotalGeneral: Int?,             // 一般購買小計
    val subtotalGas: Int?,                 // 給油所小計
    val subtotalAgri: Int?,                // 農業機械小計

    // 再OCR上書きマーカー
    val isTotalOcrTarget: Boolean = false,
    val isSubtotalGeneralOcrTarget: Boolean = false,
    val isSubtotalGasOcrTarget: Boolean = false,
    val isSubtotalAgriOcrTarget: Boolean = false
)
