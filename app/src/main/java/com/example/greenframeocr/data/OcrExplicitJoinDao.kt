package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * OCR明示的結合パターンDAO
 */
@Dao
interface OcrExplicitJoinDao {

    /**
     * パターンを挿入
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(join: OcrExplicitJoin): Long

    /**
     * パターンを更新
     */
    @Update
    suspend fun update(join: OcrExplicitJoin)

    /**
     * 正規化パターンで検索（有効なもののみ）
     */
    @Query("""
        SELECT * FROM ocr_explicit_joins
        WHERE normalizedPattern = :pattern
        AND isDisabled = 0
        ORDER BY
            CASE confidenceLevel
                WHEN 'LOCKED' THEN 0
                WHEN 'CONFIRMED' THEN 1
                ELSE 2
            END,
            hitCount DESC
        LIMIT 1
    """)
    suspend fun findByPattern(pattern: String): OcrExplicitJoin?

    /**
     * 商品IDで検索
     */
    @Query("""
        SELECT * FROM ocr_explicit_joins
        WHERE productId = :productId
        AND isDisabled = 0
        ORDER BY hitCount DESC
    """)
    suspend fun findByProductId(productId: Long): List<OcrExplicitJoin>

    /**
     * 先頭テキストで部分一致検索（フォールバック時の候補検索用）
     *
     * 例: "灯" で検索 → "灯|油" がヒット
     */
    @Query("""
        SELECT * FROM ocr_explicit_joins
        WHERE normalizedPattern LIKE :prefix || '%'
        AND isDisabled = 0
        AND (confidenceLevel = 'LOCKED' OR confidenceLevel = 'CONFIRMED')
        ORDER BY hitCount DESC
        LIMIT 10
    """)
    suspend fun findByPrefix(prefix: String): List<OcrExplicitJoin>

    /**
     * 結合後テキストで検索
     */
    @Query("""
        SELECT * FROM ocr_explicit_joins
        WHERE joinedText = :joinedText
        AND isDisabled = 0
        ORDER BY hitCount DESC
    """)
    suspend fun findByJoinedText(joinedText: String): List<OcrExplicitJoin>

    /**
     * ヒット数を増加
     */
    @Query("""
        UPDATE ocr_explicit_joins
        SET hitCount = hitCount + 1,
            lastSeenAt = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementHitCount(id: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * 手動確認回数を増加
     */
    @Query("""
        UPDATE ocr_explicit_joins
        SET manualConfirmCount = manualConfirmCount + 1,
            lastSeenAt = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementManualConfirmCount(id: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * 信頼度レベルを更新
     */
    @Query("""
        UPDATE ocr_explicit_joins
        SET confidenceLevel = :level
        WHERE id = :id
    """)
    suspend fun updateConfidenceLevel(id: Long, level: String)

    /**
     * パターンを無効化
     */
    @Query("""
        UPDATE ocr_explicit_joins
        SET isDisabled = 1
        WHERE id = :id
    """)
    suspend fun disable(id: Long)

    /**
     * 全パターンを取得（管理画面用）
     */
    @Query("""
        SELECT * FROM ocr_explicit_joins
        ORDER BY lastSeenAt DESC
    """)
    suspend fun getAll(): List<OcrExplicitJoin>

    /**
     * 有効なパターン数を取得
     */
    @Query("SELECT COUNT(*) FROM ocr_explicit_joins WHERE isDisabled = 0")
    suspend fun getActiveCount(): Int

    /**
     * 信頼度レベル別のカウントを取得
     */
    @Query("""
        SELECT confidenceLevel, COUNT(*) as count
        FROM ocr_explicit_joins
        WHERE isDisabled = 0
        GROUP BY confidenceLevel
    """)
    suspend fun getCountByConfidenceLevel(): List<ExplicitJoinConfidenceCount>

    /**
     * 古いAUTOパターンを削除（クリーンアップ用）
     */
    @Query("""
        DELETE FROM ocr_explicit_joins
        WHERE confidenceLevel = 'AUTO'
        AND hitCount = 0
        AND lastSeenAt < :cutoffTime
    """)
    suspend fun deleteOldUnusedPatterns(cutoffTime: Long): Int
}

/**
 * 明示的結合パターンの信頼度レベル別カウント
 */
data class ExplicitJoinConfidenceCount(
    val confidenceLevel: String,
    val count: Int
)
