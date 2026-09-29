package com.example.greenframeocr.data

import androidx.room.*

/**
 * AoiroChobo から取り込んだ科目・摘要スナップショットへのアクセス。
 *
 * 3テーブル（科目・摘要・ヘッダ）は常に同じ `vocabulary.json` 由来で揃っている必要があるため、
 * 入れ替えは [replaceSnapshot] で一括して行う。
 */
@Dao
interface AoiroChoboVocabDao {

    // ---- 科目 ----

    @Query("SELECT * FROM aoirochobo_accounts ORDER BY displayOrder, accountKey")
    suspend fun getAllAccounts(): List<AoiroChoboAccount>

    @Query("SELECT * FROM aoirochobo_accounts WHERE accountKey = :accountKey")
    suspend fun getAccountByKey(accountKey: String): AoiroChoboAccount?

    /** 預金スロット科目（設定画面でユーザーに科目名で選ばせるための候補） */
    @Query(
        "SELECT * FROM aoirochobo_accounts " +
            "WHERE ledgerAffinity = 'Bank' AND bankSlotNo IS NOT NULL ORDER BY bankSlotNo"
    )
    suspend fun getBankSlotAccounts(): List<AoiroChoboAccount>

    @Query("SELECT accountKey AS vocabKey, name FROM aoirochobo_accounts")
    suspend fun getAccountKeyNames(): List<KeyName>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AoiroChoboAccount>)

    @Query("DELETE FROM aoirochobo_accounts")
    suspend fun deleteAllAccounts()

    // ---- 摘要 ----

    @Query("SELECT * FROM aoirochobo_memo_templates ORDER BY ledgerType, direction, displayOrder")
    suspend fun getAllMemoTemplates(): List<AoiroChoboMemoTemplate>

    @Query("SELECT * FROM aoirochobo_memo_templates WHERE memoKey = :memoKey")
    suspend fun getMemoTemplateByKey(memoKey: String): AoiroChoboMemoTemplate?

    @Query(
        "SELECT * FROM aoirochobo_memo_templates " +
            "WHERE ledgerType = :ledgerType AND (direction = :direction OR direction = '') " +
            "ORDER BY displayOrder"
    )
    suspend fun getMemoTemplatesFor(ledgerType: String, direction: String): List<AoiroChoboMemoTemplate>

    @Query("SELECT memoKey AS vocabKey, name FROM aoirochobo_memo_templates")
    suspend fun getMemoKeyNames(): List<KeyName>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoTemplates(memos: List<AoiroChoboMemoTemplate>)

    @Query("DELETE FROM aoirochobo_memo_templates")
    suspend fun deleteAllMemoTemplates()

    // ---- ヘッダ ----

    @Query("SELECT * FROM aoirochobo_vocab_meta WHERE id = 1")
    suspend fun getMeta(): AoiroChoboVocabMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: AoiroChoboVocabMeta)

    @Query("DELETE FROM aoirochobo_vocab_meta")
    suspend fun deleteMeta()

    /**
     * スナップショットを丸ごと入れ替える。
     * 差分更新はしない（PC側が「ファイルから消えた＝無効化」を表現するため、
     * 消えた行が残っていると無効化が効かなくなる）。
     */
    @Transaction
    suspend fun replaceSnapshot(
        meta: AoiroChoboVocabMeta,
        accounts: List<AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>
    ) {
        deleteAllAccounts()
        deleteAllMemoTemplates()
        insertAccounts(accounts)
        insertMemoTemplates(memos)
        upsertMeta(meta.copy(id = AoiroChoboVocabMeta.SINGLETON_ID))
    }
}

/** キーと表示名だけを引くための射影（name の変化検出に使う） */
data class KeyName(
    val vocabKey: String,
    val name: String
)
