package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * OCRフォールバックログDAO
 */
@Dao
interface OcrFallbackLogDao {

    /**
     * フォールバックログを挿入
     */
    @Insert
    suspend fun insert(log: OcrFallbackLog): Long

    /**
     * 複数のフォールバックログを一括挿入
     */
    @Insert
    suspend fun insertAll(logs: List<OcrFallbackLog>)

    /**
     * 全フォールバックログを取得（新しい順）
     */
    @Query("SELECT * FROM ocr_fallback_logs ORDER BY createdAt DESC")
    suspend fun getAll(): List<OcrFallbackLog>

    /**
     * 指定期間のフォールバックログを取得
     */
    @Query("""
        SELECT * FROM ocr_fallback_logs
        WHERE createdAt >= :startTime AND createdAt <= :endTime
        ORDER BY createdAt DESC
    """)
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<OcrFallbackLog>

    /**
     * 文字高さ別のフォールバック発生数を集計
     *
     * @return 文字高さ（5px刻み）ごとの発生回数
     */
    @Query("""
        SELECT
            CAST((textHeight / 5) AS INTEGER) * 5 AS heightBucket,
            COUNT(*) AS count
        FROM ocr_fallback_logs
        GROUP BY heightBucket
        ORDER BY heightBucket
    """)
    suspend fun getCountByTextHeightBucket(): List<TextHeightBucketCount>

    /**
     * 直近N件のフォールバックログを取得
     */
    @Query("SELECT * FROM ocr_fallback_logs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<OcrFallbackLog>

    /**
     * 総フォールバック数を取得
     */
    @Query("SELECT COUNT(*) FROM ocr_fallback_logs")
    suspend fun getTotalCount(): Int

    /**
     * 平均文字高さを取得
     */
    @Query("SELECT AVG(textHeight) FROM ocr_fallback_logs")
    suspend fun getAverageTextHeight(): Float?

    /**
     * 古いログを削除（指定日数より前）
     */
    @Query("DELETE FROM ocr_fallback_logs WHERE createdAt < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long): Int

    /**
     * 全ログを削除
     */
    @Query("DELETE FROM ocr_fallback_logs")
    suspend fun deleteAll()
}

/**
 * 文字高さバケット別カウント
 */
data class TextHeightBucketCount(
    val heightBucket: Int,
    val count: Int
)
