package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 品目別マッチングの「グループのデフォルト科目」。
 * canonicalKey（GeneralReceiptItem.canonicalKey）ごとに1件、デフォルト科目を保持する。
 * 個別明細側の GeneralReceiptItem.yayoiAccountId が null のときにここへフォールバックする。
 */
@Entity(tableName = "general_item_master")
data class GeneralItemMaster(
    @PrimaryKey val canonicalKey: String,
    val yayoiAccountId: Long? = null
)
