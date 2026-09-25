package com.example.greenframeocr.util

import com.example.greenframeocr.data.DepositMeisai
import com.example.greenframeocr.data.DepositMeisaiDao

/**
 * 取引通番が空欄の預金明細に「合成番号」を振る。
 *
 * ## なぜ要るか
 *
 * `deposit_meisai` には `UNIQUE(passbookId, transactionDate, transactionNumber)` があり、CSV取込は
 * `insertAllIgnoreDuplicates`（IGNORE）で入れる。そのため**同じ日に通番が空欄の行が複数あると、
 * 2件目以降が無言で落ちていた**（ユーザーは取込件数が合わないことに気づけない）。
 * 空欄を埋めてしまえば、この取りこぼしも AoiroChobo の `externalId`
 * （`ocr:deposit:{日付}-{通番}`）の一意性も同時に片付く。
 *
 * ## 番号の形（PC側の条件・docs/integration/REPLY-pc-2026-09-22.md §3）
 *
 * - **`#` は使えない**。`externalId` の文字種は `[a-z0-9:_-]` で、`#` が入ったファイルは
 *   行単位ではなく**ファイルごと弾かれる**。加えて PC 側は `#2` を「別の取引として追加」の
 *   連番サフィックスに予約している。→ `x01` / `x02` … を使う。
 * - **同じCSVを取り込み直したら同じ番号になること**。位置だけで振ると、範囲の違うCSV
 *   （前月末から重なる通帳ダウンロード等）で番号がずれ、同じ取引が別取引として二重に届く。
 *
 * ## 冪等性の作り方
 *
 * 番号を振る前に、**同じ日付・摘要・金額・メモを持つ既存の合成番号行を先に探して再利用する**。
 * 一致する既存行が無いときだけ、その日付の空き番号を新しく振る。
 * 内容まで同じ行が同じ日に複数あっても、既存行は1件につき1回しか再利用しないので、
 * 2件目は次の空き番号を取る（CSVの並び順が同じなら結果も同じになる）。
 */
object DepositNumberAssigner {

    /** 合成番号の接頭辞。実際の取引通番は数字なので衝突しない */
    private const val PREFIX = "x"

    /** `x01` のような合成番号か */
    fun isSynthetic(transactionNumber: String): Boolean = indexOf(transactionNumber) != null

    private fun indexOf(transactionNumber: String): Int? =
        if (transactionNumber.startsWith(PREFIX)) transactionNumber.removePrefix(PREFIX).toIntOrNull()
        else null

    private fun format(index: Int): String = PREFIX + "%02d".format(index)

    /**
     * 取込予定リストのうち、取引通番が空欄の行に合成番号を埋めて返す。
     * 空欄が1件も無ければ DB を引かずにそのまま返す。
     */
    suspend fun assign(dao: DepositMeisaiDao, parsed: List<DepositMeisai>): List<DepositMeisai> {
        val blankDatesByPassbook = parsed.filter { it.transactionNumber.isBlank() }
            .groupBy({ it.passbookId }, { it.transactionDate })
            .mapValues { (_, dates) -> dates.distinct() }
        if (blankDatesByPassbook.isEmpty()) return parsed

        // SQLite のバインド変数上限（端末により999）に当たらないよう日付を分割して引く
        val existing = blankDatesByPassbook.flatMap { (passbookId, dates) ->
            dates.chunked(500).flatMap { dao.getByDates(passbookId, it) }
        }
        return assign(parsed, existing)
    }

    /**
     * [assign] の本体。DBアクセスを切り離した純粋関数（テストはこちらを突く）。
     *
     * @param existing 取込対象の日付にすでに入っている行
     */
    fun assign(parsed: List<DepositMeisai>, existing: List<DepositMeisai>): List<DepositMeisai> {
        // 通帳・日付ごとに「すでに使われている合成番号」。通番は通帳ごとなので、別の通帳の番号とは衝突しない
        val usedByDate = mutableMapOf<Pair<Int, String>, MutableSet<Int>>()
        existing.forEach { row ->
            indexOf(row.transactionNumber)?.let {
                usedByDate.getOrPut(row.passbookId to row.transactionDate) { mutableSetOf() }.add(it)
            }
        }
        // 再利用候補（1行につき1回だけ使う）
        val reusable = existing.filter { isSynthetic(it.transactionNumber) }.toMutableList()

        return parsed.map { row ->
            if (row.transactionNumber.isNotBlank()) return@map row

            val sameContent = reusable.firstOrNull {
                it.passbookId == row.passbookId &&
                    it.transactionDate == row.transactionDate &&
                    it.tekiyou == row.tekiyou &&
                    it.amount == row.amount &&
                    it.memo == row.memo
            }
            if (sameContent != null) {
                // 取り込み直し・範囲の重なるCSV → 前回と同じ番号を返す（IGNORE で重複扱いになる）
                reusable.remove(sameContent)
                row.copy(transactionNumber = sameContent.transactionNumber)
            } else {
                val used = usedByDate.getOrPut(row.passbookId to row.transactionDate) { mutableSetOf() }
                var next = 1
                while (next in used) next++
                used.add(next)
                row.copy(transactionNumber = format(next))
            }
        }
    }
}
