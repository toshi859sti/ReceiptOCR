package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * 通帳（預金口座）。預金明細（[DepositMeisai.passbookId]）はどれか 1 冊に属する。
 *
 * 弥生とあおいろの口座は**別々に**持つ（科目と同じく 1 対 1 に対応しない・CLAUDE.md）。
 * - 弥生：借方/貸方の科目は「普通預金」固定で、[yayoiSubAccountName] を補助科目に入れる。
 *   補助科目は弥生側で農家が作るものなので、スマホの科目マスタから選ばせず文字で持つ
 * - あおいろ：PC の預金スロット科目（`ledgerAffinity = Bank` かつ `bankSlotNo` 1〜5）の [aoiroAccountKey]。
 *   [aoiroAccountKeyName] は選んだときの科目名で、取込で名前が変わっていたら外す（学習と同じ・契約 §4.6）
 *
 * 冊数の上限は [MAX_COUNT]（PC のスロット数に合わせる）。
 */
@Entity(tableName = "passbooks")
data class Passbook(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val displayOrder: Int = 0,
    val yayoiSubAccountName: String = "",
    val aoiroAccountKey: String? = null,
    val aoiroAccountKeyName: String? = null
) {
    companion object {
        const val MAX_COUNT = 5

        /** マイグレーションで既存の明細を入れる 1 冊目 */
        const val DEFAULT_ID = 1
    }
}

@Dao
interface PassbookDao {
    @Query("SELECT * FROM passbooks ORDER BY displayOrder, id")
    suspend fun getAll(): List<Passbook>

    @Query("SELECT * FROM passbooks WHERE id = :id")
    suspend fun getById(id: Int): Passbook?

    @Insert
    suspend fun insert(passbook: Passbook): Long

    /** バックアップ復元用。同じ id があれば上書き */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertAll(passbooks: List<Passbook>)

    @Update
    suspend fun update(passbook: Passbook)

    /** 明細が残っている通帳は消さない（呼び出し側で [DepositMeisaiDao.countByPassbook] を見る） */
    @Query("DELETE FROM passbooks WHERE id = :id")
    suspend fun delete(id: Int)

    /** 全データ削除用。次に通帳を使う画面が [ensureDefault] で1冊目を作り直す */
    @Query("DELETE FROM passbooks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM passbooks")
    suspend fun count(): Int

    /**
     * 通帳が 1 冊も無ければ既定の 1 冊を作る。明細の passbookId が指す通帳が無い状態
     * （全データ削除のあと・古いバックアップの復元）でも画面が通帳を選べるようにする。
     */
    suspend fun ensureDefault(): List<Passbook> {
        if (count() == 0) insert(Passbook(id = Passbook.DEFAULT_ID, name = "通帳1"))
        return getAll()
    }
}
