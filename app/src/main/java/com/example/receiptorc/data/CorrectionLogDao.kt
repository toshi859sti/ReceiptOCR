package com.example.receiptorc.data

import androidx.room.*

@Dao
interface CorrectionLogDao {
    @Insert
    suspend fun insert(log: CorrectionLog): Long

    @Insert
    suspend fun insertAll(logs: List<CorrectionLog>)

    @Query("SELECT * FROM correction_logs WHERE id = :id")
    suspend fun getById(id: Long): CorrectionLog?

    @Query("SELECT * FROM correction_logs WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun getBySessionId(sessionId: String): List<CorrectionLog>

    @Query("SELECT * FROM correction_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CorrectionLog>

    @Query("SELECT * FROM correction_logs WHERE decision = :decision ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByDecision(decision: String, limit: Int = 100): List<CorrectionLog>

    @Query("SELECT * FROM correction_logs WHERE matched = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSuccessful(limit: Int = 100): List<CorrectionLog>

    @Query("SELECT * FROM correction_logs WHERE matched = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailed(limit: Int = 100): List<CorrectionLog>

    /**
     * 統計: 判定結果別件数
     */
    @Query("""
        SELECT decision, COUNT(*) as count
        FROM correction_logs
        GROUP BY decision
    """)
    suspend fun getCountByDecision(): List<DecisionCount>

    /**
     * 統計: 成功率
     */
    @Query("""
        SELECT
            COUNT(*) as total,
            SUM(CASE WHEN matched = 1 THEN 1 ELSE 0 END) as successful
        FROM correction_logs
    """)
    suspend fun getSuccessRate(): SuccessRateStats

    /**
     * 指定日数より古いログを削除
     */
    @Query("DELETE FROM correction_logs WHERE timestamp < :thresholdTimestamp")
    suspend fun deleteOlderThan(
        thresholdTimestamp: Long = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
    )

    /**
     * 全ログ削除
     */
    @Query("DELETE FROM correction_logs")
    suspend fun deleteAll()
}

/**
 * 判定結果別件数
 */
data class DecisionCount(
    val decision: String,
    val count: Int
)

/**
 * 成功率統計
 */
data class SuccessRateStats(
    val total: Int,
    val successful: Int
) {
    val rate: Double get() = if (total > 0) successful.toDouble() / total else 0.0
}
