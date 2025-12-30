package com.example.receiptorc.data

import androidx.room.*

@Dao
interface OcrVariantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(variant: OcrVariant): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(variants: List<OcrVariant>)

    @Update
    suspend fun update(variant: OcrVariant)

    @Delete
    suspend fun delete(variant: OcrVariant)

    @Query("SELECT * FROM ocr_variants WHERE productId = :productId ORDER BY occurrenceCount DESC")
    suspend fun getByProductId(productId: Long): List<OcrVariant>

    @Query("SELECT * FROM ocr_variants WHERE variantText = :text")
    suspend fun getByText(text: String): OcrVariant?

    /**
     * 誤認識パターンをカウントアップ
     */
    @Query("""
        UPDATE ocr_variants
        SET occurrenceCount = occurrenceCount + 1, lastSeen = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementOccurrence(id: Long, timestamp: Long = System.currentTimeMillis())

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
}
