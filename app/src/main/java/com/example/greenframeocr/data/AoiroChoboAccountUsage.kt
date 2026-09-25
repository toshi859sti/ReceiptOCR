package com.example.greenframeocr.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * AoiroChobo 科目を用途（JA 購買・レシート・預金）ごとに候補から外すかどうか。農家がスマホで決める設定。
 *
 * 科目のミラー（[AoiroChoboAccount]）は取込のたびに丸ごと入れ替わるので、別のテーブルに持つ。
 * 行が無い科目は「どの用途でも外していない」＝PC のフラグ（`ocrRole*`）どおりに候補になる。
 * PC のフラグが false の用途をここで true にしても候補には足さない（REPLY-pc-2026-09-25b.md §2：
 * 絞り込みは `ocrRole*` の内側で減らすだけ）。
 *
 * [accountKeyName] は設定したときの科目名。取込で名前が変わっていたら作り替えとみなし、行を消して既定に戻す
 * （学習の紐付けと同じ扱い・契約 §4.6）。
 */
@Entity(tableName = "aoirochobo_account_usage")
data class AoiroChoboAccountUsage(
    @PrimaryKey val accountKey: String,
    val accountKeyName: String,
    val forPurchase: Boolean = true,
    val forReceipt: Boolean = true,
    val forDeposit: Boolean = true
)

@Dao
interface AoiroChoboAccountUsageDao {
    @Query("SELECT * FROM aoirochobo_account_usage")
    suspend fun getAll(): List<AoiroChoboAccountUsage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: AoiroChoboAccountUsage)

    @Query("DELETE FROM aoirochobo_account_usage WHERE accountKey = :accountKey")
    suspend fun delete(accountKey: String)
}
