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
    // グループのデフォルト科目（general_item_master.yayoiAccountId）からの個別上書き。
    // null = グループのデフォルトに従う、非null = この行だけ個別に指定
    val yayoiAccountId: Long? = null,
    val isExcluded: Boolean = false,
    // あおいろの科目・摘要の個別上書き（yayoiAccountId のあおいろ版。DB v41）。
    // null = グループ（general_item_master）の設定に従う。摘要は科目と一緒に上書き・解除する
    val overrideAccountKey: String? = null,
    val overrideAccountKeyName: String? = null,
    val overrideMemoKey: String? = null,
    val overrideMemoKeyName: String? = null,
    // itemNameの正規化キー（スペース除去・文字種統一）。品目別マッチングのグルーピングに使用。
    // INSERT/UPDATE前に必ず withComputedKey() で設定すること
    val canonicalKey: String = "",
    // CSV出力日時（yyyy/MM/dd HH:mm）。未出力ならnull
    val exportedAt: String? = null
)
