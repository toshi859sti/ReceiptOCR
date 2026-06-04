package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface TekiyouMatchingRuleDao {

    @Query("""
        SELECT r.id, r.pattern, r.normalizedTekiyou, r.isRegex, r.rakurakuTekiyouId, r.yayoiAccountId,
               r.sampleText, r.matchCount, r.isDeposit,
               t.tekiyouName as rakurakuTekiyouName, t.mainCategory, t.subCategory, t.kamoku,
               y.accountName as yayoiAccountName, y.accountCode as yayoiAccountCode
        FROM tekiyou_matching_rules r
        LEFT JOIN rakuraku_tekiyou t ON r.rakurakuTekiyouId = t.id
        LEFT JOIN yayoi_accounts y ON r.yayoiAccountId = y.id
        ORDER BY r.isDeposit DESC, r.normalizedTekiyou
    """)
    suspend fun getAllWithTekiyou(): List<MatchingRuleWithTekiyou>

    @Query("SELECT * FROM tekiyou_matching_rules ORDER BY normalizedTekiyou")
    suspend fun getAll(): List<TekiyouMatchingRule>

    @Query("SELECT * FROM tekiyou_matching_rules WHERE id = :id")
    suspend fun getById(id: Int): TekiyouMatchingRule?

    @Query("SELECT * FROM tekiyou_matching_rules WHERE pattern = :pattern")
    suspend fun getByPattern(pattern: String): TekiyouMatchingRule?

    @Query("SELECT * FROM tekiyou_matching_rules WHERE rakurakuTekiyouId IS NULL")
    suspend fun getUnmatched(): List<TekiyouMatchingRule>

    @Query("SELECT * FROM tekiyou_matching_rules WHERE rakurakuTekiyouId IS NOT NULL")
    suspend fun getMatched(): List<TekiyouMatchingRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: TekiyouMatchingRule): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rule: TekiyouMatchingRule): Long

    @Update
    suspend fun update(rule: TekiyouMatchingRule)

    @Delete
    suspend fun delete(rule: TekiyouMatchingRule)

    @Query("DELETE FROM tekiyou_matching_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tekiyou_matching_rules")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM tekiyou_matching_rules WHERE rakurakuTekiyouId IS NOT NULL")
    suspend fun getMatchedCount(): Int
}

/**
 * マッチングルールとらくらく摘要／弥生勘定科目の結合結果
 */
data class MatchingRuleWithTekiyou(
    val id: Int,
    val pattern: String,
    val normalizedTekiyou: String,
    val isRegex: Boolean,
    val rakurakuTekiyouId: Int?,
    val yayoiAccountId: Long?,
    val sampleText: String,
    val matchCount: Int,
    val isDeposit: Boolean,
    // らくらく摘要辞書の情報
    val rakurakuTekiyouName: String?,
    val mainCategory: String?,
    val subCategory: String?,
    val kamoku: String?,
    // 弥生勘定科目の情報
    val yayoiAccountName: String?,
    val yayoiAccountCode: String?
)
