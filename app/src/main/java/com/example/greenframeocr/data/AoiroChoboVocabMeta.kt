package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 取り込んだ `vocabulary.json` のヘッダ情報。常に1行だけ持つ（id = 1 固定）。
 *
 * 取込のスキップ判定と、`transactions.json` への contentHash 転記に使う。
 * contentHash はスマホ側では再計算せず、受け取った値の文字列一致だけを見る。
 */
@Entity(tableName = "aoirochobo_vocab_meta")
data class AoiroChoboVocabMeta(
    @PrimaryKey val id: Int = SINGLETON_ID,

    val schemaVersion: Int = 0,
    val generatedAt: String = "",
    val generatedByApp: String = "",
    val generatedByAppVersion: String = "",

    /** 保持している年度。これが変わったら科目・摘要を全入れ替えする */
    val fiscalYear: Int = 0,
    val fiscalStartDate: String = "",
    val fiscalEndDate: String = "",

    /** "sha256:..." 形式。再計算せずそのまま転記する */
    val contentHash: String = "",

    /** enums をJSONのまま保持。未知の値を弾かず、警告表示の判定に使う */
    val enumsJson: String = "",

    /** スマホに取り込んだ時刻（鮮度表示用） */
    val importedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
