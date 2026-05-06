package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 信頼度レベル
 *
 * TENTATIVE : 自動学習だが、まだ弱い
 * CONFIRMED : 人手 or 高確率で確認済み
 * LOCKED    : 絶対に変えてはいけない
 */
enum class ConfidenceLevel {
    TENTATIVE,
    CONFIRMED,
    LOCKED
}

/**
 * 登録ソース
 *
 * SYSTEM  : 自動学習（OCR補正パイプラインが自動登録）
 * CAPTURE : 撮影時にユーザーが手動修正
 * PRESET  : 学習画面でユーザーが固定登録（常にLOCKED）
 */
enum class VariantSource {
    SYSTEM,
    CAPTURE,
    PRESET
}

/**
 * 降格/無効化アクション（V3）
 */
enum class DemotionAction {
    NONE,
    DEMOTE_TO_TENTATIVE,
    DISABLE
}

/**
 * OCR学習システム設計思想
 *
 * ⚠ この学習は「頻度最適化」ではない
 * ⚠ 人間の最終確定のみを真実とする
 * ⚠ 時間減衰を入れたらこの設計は壊れる
 * ⚠ 手動修正も絶対視しない（autoFailCountは全レベルで有効）
 * ⚠ 同一バッチ内の重複は1回としてカウント
 *
 * 利用想定: 月1回〜年1回の低頻度利用
 *
 * 設計原則:
 * 1. 誤変換は絶対にしない（補正は確信時のみ）
 * 2. 学習は「回数」ではなく「確定度」で判断
 * 3. 失敗した知識は即座に降格/無効化
 * 4. 同一バッチ内の重複は1回としてカウント
 * 5. 原本OCRは必ず保持し、3文字以上のみ学習対象
 * 6. 商品マスタ一致はID確定のみ（文字列一致禁止）
 *
 * 変更履歴:
 * - 2026-01-18: 低頻度利用向けに全面再設計（V3）
 * - 2026-05-07: enum リネーム（AUTO→TENTATIVE/SYSTEM, USER→CAPTURE, PRESET追加）
 */
@Entity(
    tableName = "ocr_variants",
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
        Index(value = ["variantText"]),
        Index(value = ["normalizedText"]),
        Index(value = ["confidenceLevel"])
    ]
)
data class OcrVariant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 商品マスタID */
    val productId: Long,

    /** 誤認識された文字列（生テキスト） */
    val variantText: String,

    /** 正規化後の文字列（濁点分離、記号除去後） */
    val normalizedText: String = "",

    /** 信頼度レベル */
    val confidenceLevel: String = ConfidenceLevel.TENTATIVE.name,

    /** 累積ヒット数 */
    val hitCount: Int = 0,

    /** 高スコア（>=0.90）でヒットした回数 */
    val highScoreHits: Int = 0,

    /** 平均最終スコア（累積） */
    val avgFinalScore: Double = 0.0,

    /** スコア合計（平均計算用） */
    val totalScore: Double = 0.0,

    /** 最初に見た日（Unix timestamp） */
    val firstSeenAt: Long = System.currentTimeMillis(),

    /** 最終確認日時（Unix timestamp） */
    val lastSeenAt: Long = System.currentTimeMillis(),

    /** ユニーク日数（異なる日に出現した回数） */
    val uniqueDays: Int = 1,

    /** 最後に見た日（日付のみ、YYYYMMDD形式） */
    val lastSeenDate: Int = 0,

    /** 登録ソース */
    val source: String = VariantSource.SYSTEM.name,

    /** 無効化フラグ */
    val isDisabled: Boolean = false,

    /** 無効化理由 */
    val disabledReason: String? = null,

    // === V3 新規追加カラム ===

    /** 手動修正回数（異なるバッチでの修正をカウント） */
    val manualCorrectCount: Int = 0,

    /** AUTO誤爆回数（誤った補正が発覚した回数） */
    val autoFailCount: Int = 0,

    /** 最後に手動修正されたバッチID（重複カウント防止用） */
    val lastManualCommitBatchId: String? = null
) {
    /**
     * CONFIRMED昇格条件チェック（V3設計）
     *
     * 自動学習由来（source = SYSTEM）:
     * - hitCount >= 3
     * - avgFinalScore >= 0.90
     * - highScoreHits >= 2
     * - autoFailCount == 0
     *
     * 手動修正由来（source = CAPTURE）:
     * - manualCorrectCount >= 2（異なるバッチで2回以上）
     */
    fun canPromoteToConfirmed(): Boolean {
        if (confidenceLevel != ConfidenceLevel.TENTATIVE.name) return false
        if (autoFailCount > 0) return false

        return when (source) {
            VariantSource.CAPTURE.name -> manualCorrectCount >= 2
            else -> hitCount >= 3 && avgFinalScore >= 0.90 && highScoreHits >= 2
        }
    }

    /**
     * LOCKED昇格条件チェック（V3設計）
     *
     * - hitCount >= 10
     * - avgFinalScore >= 0.92
     * - autoFailCount == 0
     */
    fun canPromoteToLocked(): Boolean {
        if (confidenceLevel != ConfidenceLevel.CONFIRMED.name) return false
        return hitCount >= 10 && avgFinalScore >= 0.92 && autoFailCount == 0
    }

    /**
     * 降格・無効化判定（V3設計）
     */
    fun shouldDemoteOrDisable(): DemotionAction {
        if (autoFailCount == 0) return DemotionAction.NONE

        return when (confidenceLevel) {
            ConfidenceLevel.TENTATIVE.name -> DemotionAction.DISABLE
            ConfidenceLevel.CONFIRMED.name -> DemotionAction.DEMOTE_TO_TENTATIVE
            ConfidenceLevel.LOCKED.name   -> DemotionAction.NONE
            else -> DemotionAction.NONE
        }
    }

    /**
     * 補正に使用可能かどうか
     */
    fun isUsableForCorrection(): Boolean {
        if (isDisabled) return false
        return when {
            confidenceLevel == ConfidenceLevel.LOCKED.name    -> true
            confidenceLevel == ConfidenceLevel.CONFIRMED.name -> true
            else -> false
        }
    }

    /**
     * 無条件適用（Layer 1）の対象かどうか
     *
     * LOCKED（PRESET含む）または CAPTURE+CONFIRMED
     */
    fun isUnconditionallyApplicable(): Boolean {
        if (isDisabled) return false
        return confidenceLevel == ConfidenceLevel.LOCKED.name ||
               (confidenceLevel == ConfidenceLevel.CONFIRMED.name && source == VariantSource.CAPTURE.name)
    }

    @Deprecated("V3では時間減衰を廃止。常に1.0を返す")
    fun calculateDecayScore(): Double = 1.0

    @Deprecated("V3では時間減衰を廃止。hitCountをそのまま返す")
    fun effectiveHits(): Double = hitCount.toDouble()

    @Deprecated("V3ではautoFailCountベースで判定。shouldDemoteOrDisable()を使用")
    fun shouldBeDisabled(): Boolean = shouldDemoteOrDisable() == DemotionAction.DISABLE

    companion object {
        const val HIGH_SCORE_THRESHOLD = 0.90

        fun todayAsInt(): Int {
            val cal = java.util.Calendar.getInstance()
            return cal.get(java.util.Calendar.YEAR) * 10000 +
                   (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
                   cal.get(java.util.Calendar.DAY_OF_MONTH)
        }
    }
}
