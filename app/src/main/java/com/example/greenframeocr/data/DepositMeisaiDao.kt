package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface DepositMeisaiDao {

    @Query("SELECT * FROM deposit_meisai ORDER BY transactionDate ASC, transactionNumber")
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

    /** 重複（transactionDate+transactionNumber）はスキップして一括挿入。戻り値は挿入行IDリスト（スキップは -1L）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreDuplicates(meisaiList: List<DepositMeisai>): List<Long>

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

    @Query("SELECT * FROM deposit_meisai WHERE transactionDate = :date AND transactionNumber = :number LIMIT 1")
    suspend fun findByDateAndNumber(date: String, number: String): DepositMeisai?

    /** 個別オーバーライドを設定（tekiyouId=nullでクリア） */
    @Query("UPDATE deposit_meisai SET overrideTekiyouId = :tekiyouId WHERE id = :meisaiId")
    suspend fun updateOverrideTekiyou(meisaiId: Int, tekiyouId: Int?)

    /** グループ全件の個別オーバーライドをクリア（グループ全件上書き時に使用） */
    @Query("UPDATE deposit_meisai SET overrideTekiyouId = NULL WHERE matchingRuleId = :ruleId")
    suspend fun clearOverridesForRule(ruleId: Int)

    /** 指定グループの明細を個別オーバーライド情報付きで取得 */
    @Query("""
        SELECT dm.id, dm.transactionDate, dm.transactionNumber, dm.tekiyou, dm.amount,
               dm.matchingRuleId, dm.overrideTekiyouId,
               t.tekiyouName AS overrideTekiyouName, t.kamoku AS overrideKamoku
        FROM deposit_meisai dm
        LEFT JOIN rakuraku_tekiyou t ON dm.overrideTekiyouId = t.id
        WHERE dm.matchingRuleId = :ruleId
        ORDER BY dm.transactionDate ASC, dm.transactionNumber ASC
    """)
    suspend fun getAllWithOverrideByRuleId(ruleId: Int): List<DepositMeisaiWithOverride>
}

/** 預金明細＋個別オーバーライド情報の結合結果 */
data class DepositMeisaiWithOverride(
    val id: Int,
    val transactionDate: String,
    val transactionNumber: String,
    val tekiyou: String,
    val amount: Int,
    val matchingRuleId: Int?,
    val overrideTekiyouId: Int?,
    val overrideTekiyouName: String?,
    val overrideKamoku: String?
)
