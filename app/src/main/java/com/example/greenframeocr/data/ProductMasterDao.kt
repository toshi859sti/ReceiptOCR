package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface ProductMasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductMaster): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(product: ProductMaster): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductMaster>)

    @Update
    suspend fun update(product: ProductMaster)

    @Delete
    suspend fun delete(product: ProductMaster)

    @Query("SELECT * FROM product_master ORDER BY frequencyCount DESC, canonicalName")
    suspend fun getAll(): List<ProductMaster>

    @Query("SELECT * FROM product_master WHERE category = :category ORDER BY frequencyCount DESC, canonicalName")
    suspend fun getByCategory(category: String): List<ProductMaster>

    @Query("SELECT * FROM product_master WHERE id = :id")
    suspend fun getById(id: Long): ProductMaster?

    @Query("SELECT * FROM product_master WHERE canonicalName = :name")
    suspend fun getByName(name: String): ProductMaster?

    /** canonicalKey + category で一意検索（重複チェック用） */
    @Query("SELECT * FROM product_master WHERE canonicalKey = :key AND category = :category LIMIT 1")
    suspend fun getByCanonicalKey(key: String, category: String): ProductMaster?

    /**
     * 使用頻度をインクリメント
     */
    @Query("UPDATE product_master SET frequencyCount = frequencyCount + 1 WHERE id = :id")
    suspend fun incrementFrequency(id: Long)

    /**
     * 全商品の数を取得
     */
    @Query("SELECT COUNT(*) FROM product_master")
    suspend fun getCount(): Int

    /**
     * カテゴリ別の商品数を取得
     */
    @Query("SELECT COUNT(*) FROM product_master WHERE category = :category")
    suspend fun getCountByCategory(category: String): Int

    /**
     * 全商品を削除（初期化用）
     */
    @Query("DELETE FROM product_master")
    suspend fun deleteAll()

    /**
     * 商品名で検索（部分一致）
     */
    @Query("SELECT * FROM product_master WHERE canonicalName LIKE '%' || :query || '%' ORDER BY frequencyCount DESC, canonicalName")
    suspend fun searchByName(query: String): List<ProductMaster>

    /**
     * カテゴリと商品名で検索
     */
    @Query("SELECT * FROM product_master WHERE category = :category AND canonicalName LIKE '%' || :query || '%' ORDER BY frequencyCount DESC, canonicalName")
    suspend fun searchByCategoryAndName(category: String, query: String): List<ProductMaster>

    /**
     * IDで削除
     */
    @Query("DELETE FROM product_master WHERE id = :id")
    suspend fun deleteById(id: Long)
}
