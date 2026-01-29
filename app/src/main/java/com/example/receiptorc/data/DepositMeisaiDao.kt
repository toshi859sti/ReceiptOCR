package com.example.receiptorc.data

import androidx.room.*

@Dao
interface DepositMeisaiDao {

    @Query("SELECT * FROM deposit_meisai ORDER BY transactionDate DESC, transactionNumber")
    suspend fun getAll(): List<DepositMeisai>

    @Query("SELECT DISTINCT tekiyou FROM deposit_meisai ORDER BY tekiyou")
    suspend fun getUniqueTekiyouList(): List<String>

    @Query("SELECT * FROM deposit_meisai WHERE tekiyou LIKE :pattern")
    suspend fun getByTekiyouPattern(pattern: String): List<DepositMeisai>

    @Query("SELECT * FROM deposit_meisai WHERE matchingRuleId = :ruleId")
    suspend fun getByMatchingRuleId(ruleId: Int): List<DepositMeisai>

    @Query("SELECT * FROM deposit_meisai WHERE matchingRuleId IS NULL")
    suspend fun getUnmatched(): List<DepositMeisai>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(meisai: DepositMeisai): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(meisaiList: List<DepositMeisai>)

    @Update
    suspend fun update(meisai: DepositMeisai)

    @Query("UPDATE deposit_meisai SET matchingRuleId = :ruleId WHERE id = :meisaiId")
    suspend fun updateMatchingRule(meisaiId: Int, ruleId: Int?)

    @Query("UPDATE deposit_meisai SET matchingRuleId = :ruleId WHERE tekiyou LIKE :pattern")
    suspend fun updateMatchingRuleByPattern(pattern: String, ruleId: Int?)

    @Delete
    suspend fun delete(meisai: DepositMeisai)

    @Query("DELETE FROM deposit_meisai")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM deposit_meisai")
    suspend fun getCount(): Int
}
