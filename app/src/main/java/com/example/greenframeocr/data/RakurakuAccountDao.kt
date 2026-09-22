package com.example.greenframeocr.data

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

    @Query("SELECT * FROM rakuraku_accounts ORDER BY categoryA, categoryB, categoryC, accountCode")
    suspend fun getAll(): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts WHERE id = :id")
    suspend fun getById(id: Long): RakurakuAccount?

    @Query("SELECT * FROM rakuraku_accounts WHERE accountCode = :code")
    suspend fun getByCode(code: String): RakurakuAccount?

    @Query("SELECT * FROM rakuraku_accounts WHERE categoryA = :categoryA ORDER BY categoryB, categoryC, accountCode")
    suspend fun getByCategoryA(categoryA: String): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts WHERE categoryB = :categoryB ORDER BY categoryC, accountCode")
    suspend fun getByCategoryB(categoryB: String): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts WHERE categoryC = :categoryC ORDER BY accountCode")
    suspend fun getByCategoryC(categoryC: String): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts WHERE usedForPurchase = 1 ORDER BY categoryA, categoryB, categoryC, accountCode")
    suspend fun getForPurchase(): List<RakurakuAccount>

    @Query("SELECT DISTINCT categoryA FROM rakuraku_accounts ORDER BY categoryA")
    suspend fun getDistinctCategoryA(): List<String>

    @Query("SELECT DISTINCT categoryB FROM rakuraku_accounts ORDER BY categoryB")
    suspend fun getDistinctCategoryB(): List<String>

    @Query("SELECT DISTINCT categoryC FROM rakuraku_accounts ORDER BY categoryC")
    suspend fun getDistinctCategoryC(): List<String>

    @Query("SELECT * FROM rakuraku_accounts WHERE parentId = :parentId ORDER BY accountCode")
    suspend fun getByParentId(parentId: Long): List<RakurakuAccount>

    @Query("SELECT * FROM rakuraku_accounts ORDER BY id")
    suspend fun getAllById(): List<RakurakuAccount>

    /** AoiroChobo の科目と紐付け済みの行（取込時の name 変化検出に使う） */
    @Query("SELECT COUNT(*) FROM rakuraku_accounts WHERE accountKey IS NULL")
    suspend fun countWithoutAccountKey(): Int

    @Query("SELECT * FROM rakuraku_accounts WHERE accountKey IS NOT NULL")
    suspend fun getLinkedToAoiroChobo(): List<RakurakuAccount>

    @Query("UPDATE rakuraku_accounts SET accountKey = NULL, accountKeyName = NULL WHERE id = :id")
    suspend fun clearAccountKey(id: Long)

    @Query("UPDATE rakuraku_accounts SET accountKeyName = :name WHERE id = :id")
    suspend fun updateAccountKeyName(id: Long, name: String)

    @Query("DELETE FROM rakuraku_accounts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rakuraku_accounts")
    suspend fun count(): Int
}
