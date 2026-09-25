package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface DepositMeisaiDao {

    /** 全通帳の明細。通帳ごとに見せる画面は [DepositMeisai.passbookId] で絞る */
    @Query("SELECT * FROM deposit_meisai ORDER BY transactionDate ASC, passbookId, transactionNumber")
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

    /** 重複（passbookId+transactionDate+transactionNumber）はスキップして一括挿入。戻り値は挿入行IDリスト（スキップは -1L）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreDuplicates(meisaiList: List<DepositMeisai>): List<Long>

    @Update
    suspend fun update(meisai: DepositMeisai)

    @Query("UPDATE deposit_meisai SET matchingRuleId = :ruleId WHERE id = :meisaiId")
    suspend fun updateMatchingRule(meisaiId: Int, ruleId: Int?)

    @Query("UPDATE deposit_meisai SET matchingRuleId = :ruleId WHERE tekiyou LIKE :pattern")
    suspend fun updateMatchingRuleByPattern(pattern: String, ruleId: Int?)

    // CSV出力履歴：出力済みの明細に出力日時を記録
    @Query("UPDATE deposit_meisai SET exportedAt = :exportedAt WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Int>, exportedAt: String)

    @Delete
    suspend fun delete(meisai: DepositMeisai)

    @Query("DELETE FROM deposit_meisai")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM deposit_meisai")
    suspend fun getCount(): Int

    /** 合成番号の再利用判定で使う。取込先の通帳の、取込対象の日付ぶんだけ既存行を引く */
    @Query("SELECT * FROM deposit_meisai WHERE passbookId = :passbookId AND transactionDate IN (:dates)")
    suspend fun getByDates(passbookId: Int, dates: List<String>): List<DepositMeisai>

    @Query("SELECT COUNT(*) FROM deposit_meisai WHERE passbookId = :passbookId")
    suspend fun countByPassbook(passbookId: Int): Int

    @Query("DELETE FROM deposit_meisai WHERE passbookId = :passbookId")
    suspend fun deleteByPassbook(passbookId: Int)

    /** 個別オーバーライドを設定（tekiyouId=nullでクリア） */
    @Query("UPDATE deposit_meisai SET overrideTekiyouId = :tekiyouId WHERE id = :meisaiId")
    suspend fun updateOverrideTekiyou(meisaiId: Int, tekiyouId: Int?)

    /** グループ全件の個別オーバーライドをクリア（グループ全件上書き時に使用） */
    @Query("UPDATE deposit_meisai SET overrideTekiyouId = NULL WHERE matchingRuleId = :ruleId")
    suspend fun clearOverridesForRule(ruleId: Int)

    /** 個別オーバーライド弥生科目を設定（accountId=nullでクリア） */
    @Query("UPDATE deposit_meisai SET overrideYayoiAccountId = :accountId WHERE id = :meisaiId")
    suspend fun updateOverrideYayoiAccount(meisaiId: Int, accountId: Long?)

    /** グループ全件の弥生個別オーバーライドをクリア（グループ全件上書き時に使用） */
    @Query("UPDATE deposit_meisai SET overrideYayoiAccountId = NULL WHERE matchingRuleId = :ruleId")
    suspend fun clearYayoiOverridesForRule(ruleId: Int)

    /** 指定グループの明細を個別オーバーライド情報付きで取得（らくらく摘要・弥生科目の両方をJOIN） */
    @Query("""
        SELECT dm.id, dm.transactionDate, dm.transactionNumber, dm.tekiyou, dm.amount,
               dm.matchingRuleId, dm.overrideTekiyouId, dm.overrideYayoiAccountId,
               t.tekiyouName AS overrideTekiyouName, t.kamoku AS overrideKamoku,
               y.accountName AS overrideYayoiAccountName, y.accountCode AS overrideYayoiAccountCode
        FROM deposit_meisai dm
        LEFT JOIN rakuraku_tekiyou t ON dm.overrideTekiyouId = t.id
        LEFT JOIN yayoi_accounts y ON dm.overrideYayoiAccountId = y.id
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
    // らくらく個別オーバーライド
    val overrideTekiyouId: Int?,
    val overrideTekiyouName: String?,
    val overrideKamoku: String?,
    // 弥生個別オーバーライド
    val overrideYayoiAccountId: Long?,
    val overrideYayoiAccountName: String?,
    val overrideYayoiAccountCode: String?
)
