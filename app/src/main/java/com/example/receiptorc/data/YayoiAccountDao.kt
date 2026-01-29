package com.example.receiptorc.data

import androidx.room.*

@Dao
interface YayoiAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: YayoiAccount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<YayoiAccount>)

    @Update
    suspend fun update(account: YayoiAccount)

    @Delete
    suspend fun delete(account: YayoiAccount)

    @Query("SELECT * FROM yayoi_accounts ORDER BY categoryA, categoryB, categoryC, accountCode")
    suspend fun getAll(): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE id = :id")
    suspend fun getById(id: Long): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountCode = :code")
    suspend fun getByCode(code: String): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountName LIKE '%' || :query || '%' ORDER BY accountCode")
    suspend fun searchByName(query: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE categoryA = :categoryA ORDER BY categoryB, categoryC, accountCode")
    suspend fun getByCategoryA(categoryA: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE categoryB = :categoryB ORDER BY categoryC, accountCode")
    suspend fun getByCategoryB(categoryB: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE categoryC = :categoryC ORDER BY accountCode")
    suspend fun getByCategoryC(categoryC: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE usedForPurchase = 1 ORDER BY categoryA, categoryB, categoryC, accountCode")
    suspend fun getForPurchase(): List<YayoiAccount>

    @Query("SELECT DISTINCT categoryA FROM yayoi_accounts ORDER BY categoryA")
    suspend fun getDistinctCategoryA(): List<String>

    @Query("SELECT DISTINCT categoryB FROM yayoi_accounts ORDER BY categoryB")
    suspend fun getDistinctCategoryB(): List<String>

    @Query("SELECT DISTINCT categoryC FROM yayoi_accounts ORDER BY categoryC")
    suspend fun getDistinctCategoryC(): List<String>

    @Query("SELECT * FROM yayoi_accounts WHERE parentId = :parentId ORDER BY accountCode")
    suspend fun getByParentId(parentId: Long): List<YayoiAccount>

    @Query("DELETE FROM yayoi_accounts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM yayoi_accounts")
    suspend fun count(): Int
}
