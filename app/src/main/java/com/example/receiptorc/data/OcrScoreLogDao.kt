package com.example.receiptorc.data

import androidx.room.*

@Dao
interface OcrScoreLogDao {
    @Insert
    suspend fun insert(log: OcrScoreLog): Long

    @Insert
    suspend fun insertAll(logs: List<OcrScoreLog>)

    @Update
    suspend fun update(log: OcrScoreLog)

    @Query("SELECT * FROM ocr_score_logs WHERE id = :id")
    suspend fun getById(id: Long): OcrScoreLog?

    /**
     * 手動修正されたことを記録
     */
    @Query("""
        UPDATE ocr_score_logs
        SET manualOverride = 1, manualCorrectedProductId = :correctedProductId
        WHERE id = :id
    """)
    suspend fun markAsManuallyOverridden(id: Long, correctedProductId: Long)

    /**
     * バッチ内のログを取得
     */
    @Query("""
        SELECT * FROM ocr_score_logs
        WHERE commitBatchId = :batchId
        ORDER BY createdAt ASC
    """)
    suspend fun getByBatchId(batchId: String): List<OcrScoreLog>

    /**
     * 最近のログを取得
     */
    @Query("""
        SELECT * FROM ocr_score_logs
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentLogs(limit: Int = 100): List<OcrScoreLog>

    /**
     * 判定別のログを取得
     */
    @Query("""
        SELECT * FROM ocr_score_logs
        WHERE decision = :decision
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getByDecision(decision: String, limit: Int = 100): List<OcrScoreLog>

    /**
     * 手動修正されたログを取得（閾値チューニング分析用）
     */
    @Query("""
        SELECT * FROM ocr_score_logs
        WHERE manualOverride = 1
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getManuallyOverriddenLogs(limit: Int = 100): List<OcrScoreLog>

    /**
     * AUTO判定で手動修正されたログを取得（誤判定分析用）
     */
    @Query("""
        SELECT * FROM ocr_score_logs
        WHERE decision = 'AUTO' AND manualOverride = 1
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getAutoDecisionOverriddenLogs(limit: Int = 100): List<OcrScoreLog>

    /**
     * 統計: 判定別件数
     */
    @Query("""
        SELECT decision, COUNT(*) as count
        FROM ocr_score_logs
        GROUP BY decision
    """)
    suspend fun getCountByDecision(): List<ScoreLogDecisionCount>

    /**
     * 統計: 手動修正率
     */
    @Query("""
        SELECT
            COUNT(*) as totalCount,
            SUM(CASE WHEN manualOverride = 1 THEN 1 ELSE 0 END) as overriddenCount
        FROM ocr_score_logs
    """)
    suspend fun getOverrideStats(): OverrideStats

    /**
     * 統計: スコア分布（閾値チューニング用）
     */
    @Query("""
        SELECT
            CAST(totalScore / 5 AS INTEGER) * 5 as scoreRange,
            COUNT(*) as count,
            SUM(CASE WHEN manualOverride = 1 THEN 1 ELSE 0 END) as overriddenCount
        FROM ocr_score_logs
        WHERE totalScore IS NOT NULL
        GROUP BY CAST(totalScore / 5 AS INTEGER)
        ORDER BY scoreRange
    """)
    suspend fun getScoreDistribution(): List<ScoreRangeStats>

    /**
     * 古いログを削除（90日以上前）
     */
    @Query("""
        DELETE FROM ocr_score_logs
        WHERE createdAt < :thresholdTimestamp
    """)
    suspend fun deleteOldLogs(
        thresholdTimestamp: Long = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
    )

    /**
     * 全ログを削除（初期化用）
     */
    @Query("DELETE FROM ocr_score_logs")
    suspend fun deleteAll()
}

/**
 * 判定別件数（OcrScoreLog用）
 */
data class ScoreLogDecisionCount(
    val decision: String,
    val count: Int
)

/**
 * 手動修正統計
 */
data class OverrideStats(
    val totalCount: Int,
    val overriddenCount: Int
) {
    val overrideRate: Double
        get() = if (totalCount > 0) overriddenCount.toDouble() / totalCount else 0.0
}

/**
 * スコア分布統計
 */
data class ScoreRangeStats(
    val scoreRange: Int,
    val count: Int,
    val overriddenCount: Int
) {
    val overrideRate: Double
        get() = if (count > 0) overriddenCount.toDouble() / count else 0.0
}
