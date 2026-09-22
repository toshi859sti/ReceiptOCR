package com.example.greenframeocr.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface YayoiAccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: YayoiAccount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<YayoiAccount>)

    @Update
    suspend fun update(account: YayoiAccount)

    @Upsert
    suspend fun upsert(account: YayoiAccount)

    @Delete
    suspend fun delete(account: YayoiAccount)

    @Query("SELECT * FROM yayoi_accounts ORDER BY categoryA, categoryB, accountCode")
    suspend fun getAll(): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE id = :id")
    suspend fun getById(id: Long): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountCode = :code")
    suspend fun getByCode(code: String): YayoiAccount?

    @Query("SELECT * FROM yayoi_accounts WHERE accountName LIKE '%' || :query || '%' ORDER BY accountCode")
    suspend fun searchByName(query: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE categoryA = :categoryA ORDER BY categoryB, accountCode")
    suspend fun getByCategoryA(categoryA: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE categoryB = :categoryB ORDER BY accountCode")
    suspend fun getByCategoryB(categoryB: String): List<YayoiAccount>

    @Query("SELECT * FROM yayoi_accounts WHERE parentId = :parentId ORDER BY accountCode")
    suspend fun getByParentId(parentId: Long): List<YayoiAccount>

    /** AoiroChobo の科目と紐付け済みの行（取込時の name 変化検出に使う） */
    @Query("SELECT COUNT(*) FROM yayoi_accounts WHERE accountKey IS NULL AND isEnabled = 1")
    suspend fun countWithoutAccountKey(): Int

    @Query("SELECT * FROM yayoi_accounts WHERE accountKey IS NOT NULL")
    suspend fun getLinkedToAoiroChobo(): List<YayoiAccount>

    /** 紐付けを外す（PC側で科目が作り替えられたとき） */
    @Query("UPDATE yayoi_accounts SET accountKey = NULL, accountKeyName = NULL WHERE id = :id")
    suspend fun clearAccountKey(id: Long)

    @Query("UPDATE yayoi_accounts SET accountKeyName = :name WHERE id = :id")
    suspend fun updateAccountKeyName(id: Long, name: String)

    @Query("DELETE FROM yayoi_accounts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM yayoi_accounts")
    suspend fun count(): Int

    // 親科目のみ取得（UI階層表示用）
    @Query("SELECT * FROM yayoi_accounts WHERE parentId IS NULL AND isEnabled = 1 ORDER BY categoryA, categoryB, accountCode")
    fun getParentAccounts(): Flow<List<YayoiAccount>>

    // 指定親科目の補助科目取得
    @Query("SELECT * FROM yayoi_accounts WHERE parentId = :parentId AND isEnabled = 1")
    fun getSubAccounts(parentId: Long): Flow<List<YayoiAccount>>

    // 購買部門用（usedForPurchase=true・親科目のみ）
    @Query("SELECT * FROM yayoi_accounts WHERE usedForPurchase = 1 AND isEnabled = 1 AND parentId IS NULL ORDER BY categoryA, categoryB")
    fun getPurchaseAccounts(): Flow<List<YayoiAccount>>

    // 預金部門用（usedForDeposit=true・親科目＋補助科目）
    @Query("SELECT * FROM yayoi_accounts WHERE usedForDeposit = 1 AND isEnabled = 1 ORDER BY parentId IS NOT NULL, accountCode")
    fun getDepositAccounts(): Flow<List<YayoiAccount>>

    // isEnabled の切り替え
    @Query("UPDATE yayoi_accounts SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    // 全件取得（設定画面用・非表示含む）
    @Query("SELECT * FROM yayoi_accounts WHERE parentId IS NULL ORDER BY categoryA, categoryB, accountCode")
    fun getAllParentAccounts(): Flow<List<YayoiAccount>>
}
