package com.example.receiptorc.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OCR明示的結合パターン
 *
 * 分離認識された文字列を結合するパターンを学習・保存。
 * 例: "灯" + "油" → "灯油"
 *
 * フォールバック処理時に、同一Y座標で分離認識されたTextBoxを
 * このパターンに基づいて結合する。
 */
@Entity(
    tableName = "ocr_explicit_joins",
    foreignKeys = [
        ForeignKey(
            entity = ProductMaster::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["normalizedPattern"]),
        Index(value = ["confidenceLevel"])
    ]
)
data class OcrExplicitJoin(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 商品マスタID */
    val productId: Long,

    /**
     * 分離パターン（正規化済み）
     * 例: "灯|油" （"|"で分離を表現）
     */
    val normalizedPattern: String,

    /**
     * 結合後のテキスト
     * 例: "灯油"
     */
    val joinedText: String,

    /**
     * 元の分離テキストリスト（JSON形式）
     * 例: ["灯", "油"]
     */
    val originalTexts: String,

    /** 信頼度レベル (AUTO / CONFIRMED / LOCKED) */
    val confidenceLevel: String = "AUTO",

    /** ヒット数（このパターンが使用された回数） */
    val hitCount: Int = 0,

    /** 手動確認回数 */
    val manualConfirmCount: Int = 0,

    /** ソース (AUTO / USER) */
    val source: String = "AUTO",

    /** 無効化フラグ */
    val isDisabled: Boolean = false,

    /** 初回記録日時 */
    val firstSeenAt: Long = System.currentTimeMillis(),

    /** 最終使用日時 */
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** パターン区切り文字 */
        const val SEPARATOR = "|"

        /**
         * 分離テキストリストから正規化パターンを生成
         */
        fun createNormalizedPattern(texts: List<String>): String {
            return texts.joinToString(SEPARATOR) { it.trim() }
        }

        /**
         * 正規化パターンを分離テキストリストに分解
         */
        fun parsePattern(pattern: String): List<String> {
            return pattern.split(SEPARATOR).map { it.trim() }
        }
    }

    /**
     * AUTO → CONFIRMED 昇格条件
     */
    fun canPromoteToConfirmed(): Boolean {
        if (confidenceLevel != "AUTO") return false
        // 手動確認2回以上、またはヒット数5以上
        return manualConfirmCount >= 2 || hitCount >= 5
    }

    /**
     * CONFIRMED → LOCKED 昇格条件
     */
    fun canPromoteToLocked(): Boolean {
        if (confidenceLevel != "CONFIRMED") return false
        return hitCount >= 10 && manualConfirmCount >= 3
    }
}
