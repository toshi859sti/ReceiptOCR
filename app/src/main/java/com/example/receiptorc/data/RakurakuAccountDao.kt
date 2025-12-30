package com.example.receiptorc.data

import androidx.room.*

@Dao
interface RakurakuAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: RakurakuAccount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<RakurakuAccount>)

    @Update
    suspend fun update(account: RakurakuAccount)

    @Delete
    suspend fun delete(account: RakurakuAccount)

    @Query("SELECT * FROM rakuraku_accounts ORDER BY accountCode")
    suspend fun getAll(): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts WHERE id = :id")
    suspend fun getById(id: Long): RakurakuAccount?

    @Query("SELECT * FROM rakuraku_accounts WHERE accountCode = :code")
    suspend fun getByCode(code: String): RakurakuAccount?

    @Query("SELECT * FROM rakuraku_accounts WHERE category = :category ORDER BY accountCode")
    suspend fun getByCategory(category: String): List<RakurakuAccount>

    @Query("DELETE FROM rakuraku_accounts")
    suspend fun deleteAll()
}
