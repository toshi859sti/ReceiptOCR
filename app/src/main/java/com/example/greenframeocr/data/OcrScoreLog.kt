package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OCRスコアログテーブル（V3）
 *
 * OCR補正時のスコア計算詳細を記録。
 * - 判定の透明性確保
 * - 閾値チューニングの根拠データ
 * - 手動修正との差分分析
 *
 * 記録タイミング:
 * - AUTO補正時: 必ず記録
 * - NEED_CONFIRM時: 必ず記録
 * - NO_MATCH時: 必ず記録
 * - 手動修正時: manualOverride = true に更新
 */
@Entity(
    tableName = "ocr_score_logs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["decision"]),
        Index(value = ["commitBatchId"]),
        Index(value = ["rawOcrText"])
    ]
)
data class OcrScoreLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** OCR生テキスト */
    val rawOcrText: String,

    /** 候補商品ID（null = マッチなし） */
    val candidateProductId: Long? = null,

    /** 判定結果 */
    val decision: String,  // AUTO / NEED_CONFIRM / NO_MATCH

    // === スコア詳細 ===

    /** 総合スコア（100点満点） */
    val totalScore: Double? = null,

    /** 文字類似度（最大60） */
    val textSimilarity: Double? = null,

    /** 先頭欠落ボーナス（最大10） */
    val prefixBonus: Double? = null,

    /** 濁点誤認識ボーナス（最大5） */
    val dakutenBonus: Double? = null,

    /** 既存知識ボーナス（最大15） */
    val variantBonus: Double? = null,

    /** 手動修正履歴ボーナス（最大10） */
    val historyBonus: Double? = null,

    /** リスクペナルティ（最大-30） */
    val riskPenalty: Double? = null,

    /** 2位との差分 */
    val gapToSecond: Double? = null,

    // === 状態管理 ===

    /** 後で手動修正されたか */
    val manualOverride: Boolean = false,

    /** 手動修正後の商品ID（manualOverride=trueの場合） */
    val manualCorrectedProductId: Long? = null,

    /** コミットバッチID */
    val commitBatchId: String? = null,

    /** 作成日時 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 判定結果: 自動補正 */
        const val DECISION_AUTO = "AUTO"

        /** 判定結果: 確認必要 */
        const val DECISION_NEED_CONFIRM = "NEED_CONFIRM"

        /** 判定結果: マッチなし */
        const val DECISION_NO_MATCH = "NO_MATCH"
    }
}

/**
 * スコア内訳データクラス（計算時の一時構造）
 */
data class ScoreBreakdown(
    val totalScore: Double,
    val textSimilarity: Double,
    val prefixBonus: Double,
    val dakutenBonus: Double,
    val variantBonus: Double,
    val historyBonus: Double,
    val riskPenalty: Double
) {
    fun toMap(): Map<String, Double> = mapOf(
        "totalScore" to totalScore,
        "textSimilarity" to textSimilarity,
        "prefixBonus" to prefixBonus,
        "dakutenBonus" to dakutenBonus,
        "variantBonus" to variantBonus,
        "historyBonus" to historyBonus,
        "riskPenalty" to riskPenalty
    )
}
