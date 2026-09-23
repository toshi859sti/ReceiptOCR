package com.example.greenframeocr.data

import androidx.room.*

@Dao
interface RakurakuTekiyouDao {

    /** AoiroChobo の摘要と紐付け済みの行（取込時の name 変化検出に使う） */
    @Query("SELECT * FROM rakuraku_tekiyou WHERE memoKey IS NOT NULL")
    suspend fun getLinkedToAoiroChobo(): List<RakurakuTekiyou>

    @Query("UPDATE rakuraku_tekiyou SET memoKey = NULL, memoKeyName = NULL WHERE id = :id")
    suspend fun clearMemoKey(id: Int)

    @Query("UPDATE rakuraku_tekiyou SET memoKeyName = :name WHERE id = :id")
    suspend fun updateMemoKeyName(id: Int, name: String)

    // ---- 学習の張り替え（らくらく廃止にともなう移行） ----
    //
    // `rakuraku_tekiyou` を消すと、そこを id で指している学習が全部 null になる。
    // そうならないよう、摘要マッピングを 1 件確定するたびに、その摘要を指している学習へ
    // memoKey を書き下ろす。ここが済んだ学習はらくらくのテーブルを見なくても解決できる。

    @Query("UPDATE rakuraku_tekiyou SET memoKey = :memoKey, memoKeyName = :memoKeyName WHERE id = :id")
    suspend fun setMemoKey(id: Int, memoKey: String, memoKeyName: String)

    @Query("UPDATE product_master SET memoKey = :memoKey, memoKeyName = :memoKeyName WHERE kaikakeTekiyouId = :tekiyouId")
    suspend fun applyMemoKeyToProducts(tekiyouId: Int, memoKey: String?, memoKeyName: String?)

    @Query("UPDATE tekiyou_matching_rules SET memoKey = :memoKey, memoKeyName = :memoKeyName WHERE rakurakuTekiyouId = :tekiyouId")
    suspend fun applyMemoKeyToMatchingRules(tekiyouId: Int, memoKey: String?, memoKeyName: String?)

    @Query("UPDATE deposit_meisai SET overrideMemoKey = :memoKey WHERE overrideTekiyouId = :tekiyouId")
    suspend fun applyMemoKeyToDepositOverrides(tekiyouId: Int, memoKey: String?)

    /** この摘要を参照している学習の件数（移行で失うものが見えるように画面へ出す） */
    @Query(
        "SELECT (SELECT COUNT(*) FROM product_master WHERE kaikakeTekiyouId = :tekiyouId) + " +
            "(SELECT COUNT(*) FROM tekiyou_matching_rules WHERE rakurakuTekiyouId = :tekiyouId) + " +
            "(SELECT COUNT(*) FROM deposit_meisai WHERE overrideTekiyouId = :tekiyouId)"
    )
    suspend fun countLearningReferences(tekiyouId: Int): Int

    /** まだ memoKey に張り替わっていない学習の件数（移行が終わったかの判定に使う） */
    @Query(
        "SELECT (SELECT COUNT(*) FROM product_master WHERE kaikakeTekiyouId IS NOT NULL AND memoKey IS NULL) + " +
            "(SELECT COUNT(*) FROM tekiyou_matching_rules WHERE rakurakuTekiyouId IS NOT NULL AND memoKey IS NULL) + " +
            "(SELECT COUNT(*) FROM deposit_meisai WHERE overrideTekiyouId IS NOT NULL AND overrideMemoKey IS NULL)"
    )
    suspend fun countLearningNotYetMigrated(): Int

    /** 摘要の紐付けを確定し、その摘要を指している学習にも書き下ろす */
    @Transaction
    suspend fun linkMemoKey(tekiyouId: Int, memoKey: String, memoKeyName: String) {
        setMemoKey(tekiyouId, memoKey, memoKeyName)
        applyMemoKeyToProducts(tekiyouId, memoKey, memoKeyName)
        applyMemoKeyToMatchingRules(tekiyouId, memoKey, memoKeyName)
        applyMemoKeyToDepositOverrides(tekiyouId, memoKey)
    }

    /**
     * 紐付けを外し、書き下ろした学習側も戻す。
     *
     * 学習の `kaikakeTekiyouId` 等は触らない。まだ `rakuraku_tekiyou` が残っている間は
     * そちらが元データなので、付け直せば同じところに書き下ろせる。
     */
    @Transaction
    suspend fun unlinkMemoKey(tekiyouId: Int) {
        clearMemoKey(tekiyouId)
        applyMemoKeyToProducts(tekiyouId, null, null)
        applyMemoKeyToMatchingRules(tekiyouId, null, null)
        applyMemoKeyToDepositOverrides(tekiyouId, null)
    }

    @Query("SELECT * FROM rakuraku_tekiyou ORDER BY mainCategory, subCategory, id")
    suspend fun getAll(): List<RakurakuTekiyou>

    @Query("SELECT * FROM rakuraku_tekiyou WHERE mainCategory = :mainCategory AND subCategory = :subCategory ORDER BY id")
    suspend fun getByCategory(mainCategory: String, subCategory: String): List<RakurakuTekiyou>

    @Query("SELECT * FROM rakuraku_tekiyou WHERE mainCategory = :mainCategory AND subCategory = :subCategory AND isEnabled = 1 ORDER BY id")
    suspend fun getEnabledByCategory(mainCategory: String, subCategory: String): List<RakurakuTekiyou>

    @Query("UPDATE rakuraku_tekiyou SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateEnabled(id: Int, isEnabled: Boolean)

    @Query("SELECT * FROM rakuraku_tekiyou WHERE id = :id")
    suspend fun getById(id: Int): RakurakuTekiyou?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tekiyou: RakurakuTekiyou): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tekiyou: RakurakuTekiyou): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tekiyouList: List<RakurakuTekiyou>)

    @Update
    suspend fun update(tekiyou: RakurakuTekiyou)

    @Delete
    suspend fun delete(tekiyou: RakurakuTekiyou)

    @Query("DELETE FROM rakuraku_tekiyou")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rakuraku_tekiyou")
    suspend fun getCount(): Int

    @Query("SELECT mainCategory || '|' || subCategory || '|' || tekiyouName FROM rakuraku_tekiyou")
    suspend fun getAllKeys(): List<String>
}
