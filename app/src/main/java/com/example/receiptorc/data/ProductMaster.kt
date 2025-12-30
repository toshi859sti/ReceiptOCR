package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 商品マスタテーブル
 *
 * 購買伝票に出現する商品の正式名称を管理。
 * OCR結果の誤認識を補正するための辞書として機能。
 */
@Entity(
    tableName = "product_master",
    foreignKeys = [
        ForeignKey(
            entity = YayoiAccount::class,
            parentColumns = ["id"],
            childColumns = ["yayoiAccountId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RakurakuAccount::class,
            parentColumns = ["id"],
            childColumns = ["rakurakuAccountId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ProductMaster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 正規化された商品名 (例: "フェニックス顆粒水和剤250g") */
    val canonicalName: String,

    /** カテゴリ: "一般購買", "給油所", "農業機械" */
    val category: String,

    /** 使用頻度（よく買う商品を優先マッチング） */
    val frequencyCount: Int = 0,

    /** 弥生会計 勘定科目ID */
    val yayoiAccountId: Long? = null,

    /** らくらく青色申告 勘定科目ID */
    val rakurakuAccountId: Long? = null
)
