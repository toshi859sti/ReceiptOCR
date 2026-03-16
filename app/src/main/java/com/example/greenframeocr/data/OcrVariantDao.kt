package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface OcrVariantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(variant: OcrVariant): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(variant: OcrVariant): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(variants: List<OcrVariant>)

    @Update
    suspend fun update(variant: OcrVariant)

    @Delete
    suspend fun delete(variant: OcrVariant)

    @Query("SELECT * FROM ocr_variants WHERE productId = :productId ORDER BY hitCount DESC")
    suspend fun getByProductId(productId: Long): List<OcrVariant>

    @Query("SELECT * FROM ocr_variants WHERE variantText = :text")
    suspend fun getByText(text: String): OcrVariant?

    @Query("SELECT * FROM ocr_variants WHERE normalizedText = :normalizedText")
    suspend fun getByNormalizedText(normalizedText: String): OcrVariant?

    @Query("SELECT * FROM ocr_variants WHERE id = :id")
    suspend fun getById(id: Long): OcrVariant?

    /**
     * CONFIRMED以上の誤認識パターンを検索（補正用）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND confidenceLevel IN ('CONFIRMED', 'LOCKED')
        AND isDisabled = 0
        ORDER BY confidenceLevel DESC, hitCount DESC
        LIMIT 1
    """)
    suspend fun findConfirmedVariant(normalizedText: String): OcrVariant?

    /**
     * 指定商品候補の中からCONFIRMED以上の誤認識パターンを検索
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND productId IN (:productIds)
        AND confidenceLevel IN ('CONFIRMED', 'LOCKED')
        AND isDisabled = 0
        ORDER BY confidenceLevel DESC, hitCount DESC
        LIMIT 1
    """)
    suspend fun findConfirmedVariantInProducts(
        normalizedText: String,
        productIds: List<Long>
    ): OcrVariant?

    /**
     * 正規化テキストと商品IDで検索
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND productId = :productId
    """)
    suspend fun findByNormalizedTextAndProduct(
        normalizedText: String,
        productId: Long
    ): OcrVariant?

    /**
     * 学習データ登録/更新（V3: 自動学習用）
     */
    @Transaction
    suspend fun registerLearning(
        variantText: String,
        normalizedText: String,
        productId: Long,
        finalScore: Double,
        source: VariantSource
    ) {
        val existing = findByNormalizedTextAndProduct(normalizedText, productId)
        val today = OcrVariant.todayAsInt()
        val now = System.currentTimeMillis()

        if (existing != null) {
            // 既存パターンの更新
            val newHitCount = existing.hitCount + 1
            val newTotalScore = existing.totalScore + finalScore
            val newAvgScore = newTotalScore / newHitCount
            val newHighScoreHits = if (finalScore >= OcrVariant.HIGH_SCORE_THRESHOLD) {
                existing.highScoreHits + 1
            } else {
                existing.highScoreHits
            }
            val newUniqueDays = if (existing.lastSeenDate != today) {
                existing.uniqueDays + 1
            } else {
                existing.uniqueDays
            }

            val updated = existing.copy(
                hitCount = newHitCount,
                highScoreHits = newHighScoreHits,
                totalScore = newTotalScore,
                avgFinalScore = newAvgScore,
                lastSeenAt = now,
                lastSeenDate = today,
                uniqueDays = newUniqueDays
            )

            // 昇格チェック（V3: autoFailCount考慮）
            val finalVariant = when {
                updated.canPromoteToLocked() -> updated.copy(
                    confidenceLevel = ConfidenceLevel.LOCKED.name
                )
                updated.canPromoteToConfirmed() -> updated.copy(
                    confidenceLevel = ConfidenceLevel.CONFIRMED.name
                )
                else -> updated
            }

            update(finalVariant)
        } else {
            // 新規パターンの登録
            val newVariant = OcrVariant(
                productId = productId,
                variantText = variantText,
                normalizedText = normalizedText,
                confidenceLevel = ConfidenceLevel.AUTO.name,
                hitCount = 1,
                highScoreHits = if (finalScore >= OcrVariant.HIGH_SCORE_THRESHOLD) 1 else 0,
                avgFinalScore = finalScore,
                totalScore = finalScore,
                firstSeenAt = now,
                lastSeenAt = now,
                uniqueDays = 1,
                lastSeenDate = today,
                source = source.name,
                isDisabled = false,
                disabledReason = null
            )
            insert(newVariant)
        }
    }

    // ============================================================
    // V3: 手動修正の学習登録
    // ============================================================

    /**
     * 手動修正を学習登録（V3）
     *
     * - 同一バッチ内の重複は+1のみ
     * - 2回以上の異なるバッチでの確定でCONFIRMED昇格
     */
    @Transaction
    suspend fun registerManualCorrection(
        ocrText: String,
        normalizedText: String,
        correctProductId: Long,
        commitBatchId: String
    ) {
        val existing = findByNormalizedTextAndProduct(normalizedText, correctProductId)
        val now = System.currentTimeMillis()

        if (existing != null) {
            // 同一バッチで既にカウント済みならスキップ
            if (existing.lastManualCommitBatchId == commitBatchId) {
                return
            }

            val newManualCount = existing.manualCorrectCount + 1
            val newConfidence = if (newManualCount >= 2 && existing.confidenceLevel == ConfidenceLevel.AUTO.name) {
                ConfidenceLevel.CONFIRMED.name
            } else {
                existing.confidenceLevel
            }

            update(existing.copy(
                manualCorrectCount = newManualCount,
                confidenceLevel = newConfidence,
                source = VariantSource.USER.name,
                lastManualCommitBatchId = commitBatchId,
                lastSeenAt = now,
                hitCount = existing.hitCount + 1
            ))
        } else {
            // 新規登録（まずAUTOとして、source=USER）
            insert(OcrVariant(
                productId = correctProductId,
                variantText = ocrText,
                normalizedText = normalizedText,
                confidenceLevel = ConfidenceLevel.AUTO.name,
                source = VariantSource.USER.name,
                manualCorrectCount = 1,
                hitCount = 1,
                lastManualCommitBatchId = commitBatchId,
                firstSeenAt = now,
                lastSeenAt = now,
                lastSeenDate = OcrVariant.todayAsInt()
            ))
        }
    }

    /**
     * AUTO誤爆時の処理（V3）
     *
     * - AUTO: 即座に無効化
     * - CONFIRMED: AUTO降格
     * - LOCKED: 失敗カウントのみ記録
     */
    @Transaction
    suspend fun onAutoFailure(variantId: Long) {
        val variant = getById(variantId) ?: return
        val newFailCount = variant.autoFailCount + 1

        when (variant.confidenceLevel) {
            ConfidenceLevel.AUTO.name -> {
                update(variant.copy(
                    autoFailCount = newFailCount,
                    isDisabled = true,
                    disabledReason = "AUTO_FAIL_COUNT_$newFailCount"
                ))
            }
            ConfidenceLevel.CONFIRMED.name -> {
                // CONFIRMEDでも1回の失敗でAUTO降格
                update(variant.copy(
                    autoFailCount = newFailCount,
                    confidenceLevel = ConfidenceLevel.AUTO.name
                ))
            }
            ConfidenceLevel.LOCKED.name -> {
                // LOCKEDは失敗カウントのみ記録（降格しない）
                update(variant.copy(
                    autoFailCount = newFailCount
                ))
            }
        }
    }

    // ============================================================
    // V3: 補正用検索クエリ
    // ============================================================

    /**
     * LOCKED または 手動修正由来を検索（無条件適用: Layer 1）
     *
     * 手動修正由来（source=USER）は1回の訂正で即座に適用対象とする
     * - LOCKED: 最優先
     * - CONFIRMED + USER: 2番目
     * - AUTO + USER: 3番目（1回の手動訂正でも適用）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND isDisabled = 0
        AND (
            confidenceLevel = 'LOCKED'
            OR source = 'USER'
        )
        ORDER BY
            CASE confidenceLevel WHEN 'LOCKED' THEN 0 WHEN 'CONFIRMED' THEN 1 ELSE 2 END,
            hitCount DESC
        LIMIT 1
    """)
    suspend fun findUnconditionalVariant(normalizedText: String): OcrVariant?

    /**
     * CONFIRMED（自動昇格分）を検索（スコア検証必要: Layer 2）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND confidenceLevel = 'CONFIRMED'
        AND source != 'USER'
        AND isDisabled = 0
        ORDER BY avgFinalScore DESC, hitCount DESC
    """)
    suspend fun findAutoConfirmedVariants(normalizedText: String): List<OcrVariant>

    /**
     * 指定商品IDリストからLOCKED/手動修正由来を検索
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND productId IN (:productIds)
        AND isDisabled = 0
        AND (
            confidenceLevel = 'LOCKED'
            OR source = 'USER'
        )
        ORDER BY
            CASE confidenceLevel WHEN 'LOCKED' THEN 0 WHEN 'CONFIRMED' THEN 1 ELSE 2 END,
            hitCount DESC
        LIMIT 1
    """)
    suspend fun findUnconditionalVariantInProducts(
        normalizedText: String,
        productIds: List<Long>
    ): OcrVariant?

    /**
     * 任意の正規化テキストに対する既存知識を検索（ボーナス計算用）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE normalizedText = :normalizedText
        AND productId = :productId
        AND isDisabled = 0
    """)
    suspend fun findVariantForBonus(normalizedText: String, productId: Long): OcrVariant?

    /**
     * 無効化
     */
    @Query("""
        UPDATE ocr_variants
        SET isDisabled = 1, disabledReason = :reason
        WHERE id = :id
    """)
    suspend fun disable(id: Long, reason: String)

    /**
     * 自動無効化対象を取得（90日以上未使用のAUTO）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE confidenceLevel = 'AUTO'
        AND isDisabled = 0
        AND lastSeenAt < :thresholdTimestamp
    """)
    suspend fun getStaleAutoVariants(
        thresholdTimestamp: Long = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
    ): List<OcrVariant>

    /**
     * 昇格候補を取得（V3: 日数条件廃止、失敗カウント考慮）
     *
     * 自動学習由来: hitCount >= 3 AND avgFinalScore >= 0.90 AND highScoreHits >= 2 AND autoFailCount == 0
     * 手動修正由来: manualCorrectCount >= 2
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE confidenceLevel = 'AUTO'
        AND isDisabled = 0
        AND autoFailCount = 0
        AND (
            (source != 'USER' AND hitCount >= 3 AND avgFinalScore >= 0.90 AND highScoreHits >= 2)
            OR (source = 'USER' AND manualCorrectCount >= 2)
        )
        ORDER BY avgFinalScore DESC
    """)
    suspend fun getPromotionCandidates(): List<OcrVariant>

    /**
     * 昇格間近の自動学習パターン（V3）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE confidenceLevel = 'AUTO'
        AND source != 'USER'
        AND isDisabled = 0
        AND autoFailCount = 0
        AND (hitCount >= 2 OR avgFinalScore >= 0.85)
        ORDER BY avgFinalScore DESC, hitCount DESC
        LIMIT :limit
    """)
    suspend fun getNearAutoPromotionPatterns(limit: Int = 10): List<OcrVariant>

    /**
     * 昇格間近の手動修正パターン（V3）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE confidenceLevel = 'AUTO'
        AND source = 'USER'
        AND isDisabled = 0
        AND manualCorrectCount = 1
        ORDER BY lastSeenAt DESC
        LIMIT :limit
    """)
    suspend fun getNearManualPromotionPatterns(limit: Int = 10): List<OcrVariant>

    /**
     * 統計: 信頼度レベル別件数
     */
    @Query("""
        SELECT confidenceLevel, COUNT(*) as count
        FROM ocr_variants
        WHERE isDisabled = 0
        GROUP BY confidenceLevel
    """)
    suspend fun getCountByConfidenceLevel(): List<ConfidenceLevelCount>

    /**
     * 統計: 総件数
     */
    @Query("SELECT COUNT(*) FROM ocr_variants WHERE isDisabled = 0")
    suspend fun getTotalCount(): Int

    /**
     * 統計: ソース別件数
     */
    @Query("""
        SELECT source, COUNT(*) as count
        FROM ocr_variants
        WHERE isDisabled = 0
        GROUP BY source
    """)
    suspend fun getCountBySource(): List<SourceCount>

    /**
     * 最近学習したパターン（新しい順）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE isDisabled = 0
        ORDER BY lastSeenAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentPatterns(limit: Int = 20): List<OcrVariant>

    /**
     * 最も使われているパターン（ヒット数順）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE isDisabled = 0
        ORDER BY hitCount DESC
        LIMIT :limit
    """)
    suspend fun getMostUsedPatterns(limit: Int = 20): List<OcrVariant>

    /**
     * 昇格間近のパターン（V3: autoFailCount考慮）
     */
    @Query("""
        SELECT * FROM ocr_variants
        WHERE confidenceLevel = 'AUTO'
        AND isDisabled = 0
        AND autoFailCount = 0
        AND (hitCount >= 2 OR manualCorrectCount >= 1)
        ORDER BY
            CASE WHEN source = 'USER' THEN 0 ELSE 1 END,
            avgFinalScore DESC,
            hitCount DESC
        LIMIT :limit
    """)
    suspend fun getNearPromotionPatterns(limit: Int = 10): List<OcrVariant>

    /**
     * 全誤認識パターンを取得（エクスポート用）
     */
    @Query("SELECT * FROM ocr_variants ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<OcrVariant>

    /**
     * 全誤認識パターンを削除（初期化用）
     */
    @Query("DELETE FROM ocr_variants")
    suspend fun deleteAll()

    /**
     * 商品IDに紐づく誤認識パターンを削除
     */
    @Query("DELETE FROM ocr_variants WHERE productId = :productId")
    suspend fun deleteByProductId(productId: Long)

    /**
     * 商品統合: 統合元の全OcrVariantを統合先に付け替え
     */
    @Query("UPDATE ocr_variants SET productId = :newProductId WHERE productId = :oldProductId")
    suspend fun updateProductId(oldProductId: Long, newProductId: Long)

    /**
     * 商品IDに紐づく誤認識パターン数を取得
     */
    @Query("SELECT COUNT(*) FROM ocr_variants WHERE productId = :productId")
    suspend fun countByProductId(productId: Long): Int

    // ==================================
    // 旧API互換（既存コードとの互換性維持）
    // ==================================

    /**
     * @deprecated Use registerLearning instead
     */
    @Query("""
        UPDATE ocr_variants
        SET hitCount = hitCount + 1, lastSeenAt = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementOccurrence(id: Long, timestamp: Long = System.currentTimeMillis())
}

/**
 * 信頼度レベル別件数
 */
data class ConfidenceLevelCount(
    val confidenceLevel: String,
    val count: Int
)

/**
 * ソース別件数
 */
data class SourceCount(
    val source: String,
    val count: Int
)
