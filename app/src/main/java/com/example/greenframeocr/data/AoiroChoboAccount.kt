package com.example.greenframeocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AoiroChobo（PC会計アプリ）の勘定科目スナップショット。
 *
 * `vocabulary.json` の `accounts[]` をそのまま写したミラーで、スマホ側では編集しない。
 * 参照キーは accountKey（PC側所有の不透明文字列・不変・年度非依存）。
 * name は表示用で、取込時に前回と変わっていたら「科目の作り替え」とみなし、
 * そのキーを指していた紐付けを外す（docs/integration/CHANGELOG.md 2026-09-13 改訂・変更2）。
 *
 * 保持するのは常に1年度分だけ。`fiscalYear` が変わったら全削除して入れ替える。
 */
@Entity(tableName = "aoirochobo_accounts")
data class AoiroChoboAccount(
    @PrimaryKey val accountKey: String,

    /** AoiroChobo の検索用文字列。参照には使わない（一意保証なし） */
    val searchKey: String = "",

    /** 表示名。これが変わったら作り替えとみなす */
    val name: String,

    /** Asset / Liability / Income / Expense / Capital */
    val accountType: String = "",

    val groupName: String? = null,
    val parentAccountKey: String? = null,

    /** 元帳の所属: Cash / Bank / AR / AP / Unpaid / Transfer / Any。未知の値も弾かずそのまま保持する */
    val ledgerAffinity: String = "",

    /** 預金スロット番号（有効値 1〜5）。スロット科目以外は null */
    val bankSlotNo: Int? = null,

    val allowsTaxable: Boolean = false,
    val allowsNonTaxable: Boolean = false,
    val defaultTaxCategory: String? = null,
    val displayOrder: Int = 0,
    val isSystem: Boolean = false,

    /** 経費の借方として使える科目か（スマホ側の候補絞り込み用ヒント） */
    val ocrRoleExpenseDebit: Boolean = false,

    /** 預金取引の相手科目として使える科目か */
    val ocrRoleDepositCounter: Boolean = false
)
