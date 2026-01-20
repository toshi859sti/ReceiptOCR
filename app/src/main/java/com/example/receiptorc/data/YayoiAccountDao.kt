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

    @Query("SELECT * FROM yayoi_accounts ORDER BY accountCode")
    suspend fun getAll(): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE id = :id")
    suspend fun getById(id: Long): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountCode = :code")
    suspend fun getByCode(code: String): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountName LIKE '%' || :query || '%' ORDER BY accountCode")
    suspend fun searchByName(query: String): List<YayoiAccount>

    @Query("DELETE FROM yayoi_accounts")
    suspend fun deleteAll()
}
