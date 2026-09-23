package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 商品マスタテーブル
 *
 * 購買伝票に出現する商品の正式名称を管理。
 * OCR結果の誤認識を補正するための辞書として機能。
 */
@Entity(
    tableName = "product_master",
    indices = [
        Index(value = ["kaikakeTekiyouId"]),
        Index(value = ["canonicalKey", "category"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = RakurakuTekiyou::class,
            parentColumns = ["id"],
            childColumns = ["kaikakeTekiyouId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ProductMaster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 表示用商品名。スペース・表記ゆれを保持 (例: "フェニックス顆粒水和剤　250g") */
    val canonicalName: String,

    /**
     * 一意性チェック用キー。canonicalName からスペース除去・文字種統一した形。
     * INSERT 前に必ず withComputedKey() で設定すること。
     */
    val canonicalKey: String = "",

    /** カテゴリ: "一般購買", "給油所", "農業機械" */
    val category: String,

    /** 使用頻度（よく買う商品を優先マッチング） */
    val frequencyCount: Int = 0,

    /** 買掛摘要辞書ID（RakurakuTekiyouのID） */
    val kaikakeTekiyouId: Int? = null,

    /** 確定フラグ: 手動入力・訂正済みアイテムはtrue */
    val isCertified: Boolean = false,

    /** 弥生勘定科目ID（YayoiAccountのID、弥生モード時に使用） */
    val yayoiAccountId: Long? = null,

    /**
     * AoiroChobo の摘要参照キー。`kaikakeTekiyouId` の置き換え先。
     *
     * らくらくのサポート終了にともない `rakuraku_tekiyou` を廃止するため、学習が摘要を
     * 指す先をキー直指しに移す。摘要マッピング画面で `rakuraku_tekiyou.memoKey` を確定したとき、
     * その摘要を指していた学習にここまで書き下ろす（`RakurakuTekiyouDao.linkMemoKey`）。
     */
    val memoKey: String? = null,

    /**
     * memoKey を確定したときに見えていた AoiroChobo 側の摘要名。
     * 取込時にこれと現在名が食い違ったら memoKey を外す（CHANGELOG 2026-09-13 改訂・変更2）。
     */
    val memoKeyName: String? = null
)
