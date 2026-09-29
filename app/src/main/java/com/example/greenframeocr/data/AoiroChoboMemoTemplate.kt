package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AoiroChobo（PC会計アプリ）の摘要辞書スナップショット。
 *
 * `vocabulary.json` の `memos[]` のミラー。参照キーは memoKey（不透明・不変）。
 * 摘要は閉じた語彙で、`transactions.json` に載せられるのは辞書のキーか null の二択
 * （生テキストは note 列へ）。
 *
 * accountKey と違い memoKey は「行」に1対1で、作り替えは新しい行＝新しいキーになる。
 * ただし name の変化で紐付けを外す扱いは科目と同じ。
 */
@Entity(tableName = "aoirochobo_memo_templates")
data class AoiroChoboMemoTemplate(
    @PrimaryKey val memoKey: String,

    /** Cash / Bank / AR / AP / Unpaid / Transfer */
    val ledgerType: String = "",

    /** In / Out。Transfer のときは空文字 */
    val direction: String = "",

    /** 表示名。これが変わったら作り替えとみなす */
    val name: String,

    /** AoiroChobo の検索用文字列。参照には使わない */
    val searchKey: String = "",

    /** 相手科目（単式入力の摘要）。Transfer のときは null で debit/credit を使う */
    val counterAccountKey: String? = null,
    val debitAccountKey: String? = null,
    val creditAccountKey: String? = null,

    /** 10 / 8 / 1 / 8_old / non / na / men。未知の値も弾かず保持する */
    val taxRate: String? = null,
    val creditTaxRate: String? = null,

    val businessRatio: Int? = null,
    val creditBusinessRatio: Int? = null,

    val hasInvoiceDefault: Boolean = false,
    val showInCash: Boolean = false,
    val showInBank: Boolean = false,

    /** この摘要が特定の預金スロット専用ならその番号 */
    val bankSlotNo: Int? = null,

    val displayOrder: Int = 0,
    val isPreset: Boolean = false
)
