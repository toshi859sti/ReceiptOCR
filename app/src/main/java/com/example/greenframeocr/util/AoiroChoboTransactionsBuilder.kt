package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.util.AoiroChoboMemoRules.MemoTab
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * AoiroChobo に渡す `transactions.json`（schemaVersion 2）を組み立てる。
 *
 * 仕様は docs/integration/transaction-import.md。Room には触らない純粋な変換で、
 * 読み込み（商品マスタの解決など）は呼び出し側がやる。今は JA 購買（`Purchase`）だけ。
 *
 * 科目・摘要は商品に保存したキーをそのまま載せ、当年度の vocabulary に照らして
 * 解決できないものは null にして matchStatus で知らせる（契約 §9：どの状態でも行は落とさない）。
 * 行を落とすのは契約上どうやっても出せないとき（金額 0・実在しない日付）だけで、その数は [Result.skipped] に返す。
 */
object AoiroChoboTransactionsBuilder {

    const val SCHEMA_VERSION = 2
    const val KIND = "aoirochobo.transactions"

    /** JA 購買の 1 行と、その行に紐付いた商品（無ければ null） */
    data class PurchaseRow(val item: ReceiptItem, val product: ProductMaster?)

    enum class SkipReason(val label: String) {
        ZERO_AMOUNT("金額が 0"),
        INVALID_DATE("日付が不正")
    }

    data class Skipped(val item: ReceiptItem, val reason: SkipReason)

    data class Result(
        val file: TransactionsFile,
        val skipped: List<Skipped>,
        /** 書き出したファイルは通るが、PC 側で要確認になりそうなこと */
        val warnings: List<String>
    ) {
        val json: String get() = toJson(file)
    }

    // ---- 契約の形そのもの（フィールド名がそのまま JSON のキーになる） ----

    data class TransactionsFile(
        val schemaVersion: Int = SCHEMA_VERSION,
        val kind: String = KIND,
        val generatedAt: String,
        val generatedBy: GeneratedBy,
        val vocabulary: VocabularyRef,
        val entries: List<Entry>
    )

    data class GeneratedBy(val app: String, val appVersion: String)

    data class VocabularyRef(val schemaVersion: Int, val fiscalYear: Int, val contentHash: String?)

    data class Entry(
        val externalId: String,
        val source: String,
        val ledgerType: String?,
        val bankSlotNo: Int?,
        val entryDate: String,
        val amount: Int,
        val debit: Side,
        val credit: Side,
        val memoKey: String?,
        val memoName: String?,
        val note: String?,
        val matchStatus: String,
        val confidence: String?,
        val meta: Meta
    )

    data class Side(
        val accountKey: String?,
        val accountName: String?,
        val taxRate: String?,
        val businessRatio: Int = 100
    )

    data class Meta(
        val sourceTable: String,
        val sourceRowId: Long,
        val productName: String?,
        val category: String?,
        val isReturn: Boolean,
        val phoneExportedAt: String?
    )

    object MatchStatus {
        const val MATCHED = "Matched"
        const val UNMATCHED_ACCOUNT = "UnmatchedAccount"
        const val UNMATCHED_MEMO = "UnmatchedMemo"
    }

    private val CONFIDENCES = setOf("high", "medium", "low")
    // minSdk 24 で java.time は使えない（desugaring なし）ので java.util で扱う
    private val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun isoOffsetFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = ZONE }

    /** 領収日。year は西暦 */
    private data class EntryDate(val year: Int, val month: Int, val day: Int) {
        val iso: String get() = "%04d-%02d-%02d".format(Locale.US, year, month, day)
    }

    /**
     * JA 購買の行から transactions.json を組み立てる。
     *
     * @param now generatedAt に入れる時刻（テストで固定するため引数にしている）
     */
    fun buildPurchase(
        rows: List<PurchaseRow>,
        accounts: List<AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>,
        vocabMeta: AoiroChoboVocabMeta,
        appVersion: String,
        now: Date = Date()
    ): Result {
        val accountsByKey = accounts.associateBy { it.accountKey }
        val memosByKey = memos.associateBy { it.memoKey }
        val warnings = mutableListOf<String>()

        // 買掛金。キーの綴りは PC 所有なので決め打ちせず、帳簿の所属で探す
        val payableAccounts = accounts.filter { it.ledgerAffinity == "AP" }
        val payable = payableAccounts.singleOrNull()
        if (payable == null) {
            warnings += if (payableAccounts.isEmpty()) {
                "取り込んだ科目に買掛金（帳簿の所属が AP の科目）がありません。貸方は未設定で出力します"
            } else {
                "買掛金にあたる科目が ${payableAccounts.size} 件あり決められません。貸方は未設定で出力します"
            }
        }

        val skipped = mutableListOf<Skipped>()
        val entries = mutableListOf<Entry>()
        var outOfFiscalYear = 0

        for (row in rows) {
            val item = row.item
            if (item.amount == 0) {
                skipped += Skipped(item, SkipReason.ZERO_AMOUNT)
                continue
            }
            val date = entryDateOf(item)
            if (date == null) {
                skipped += Skipped(item, SkipReason.INVALID_DATE)
                continue
            }
            if (vocabMeta.fiscalYear != 0 && date.year != vocabMeta.fiscalYear) outOfFiscalYear++

            entries += purchaseEntry(item, row.product, date, accountsByKey, memosByKey, payable)
        }

        if (outOfFiscalYear > 0) {
            warnings += "取り込んだ科目・摘要は ${vocabMeta.fiscalYear} 年度のものですが、" +
                "それ以外の年の取引が $outOfFiscalYear 件あります"
        }

        val file = TransactionsFile(
            generatedAt = isoOffsetFormat().format(now),
            generatedBy = GeneratedBy(app = "JA仕訳変換", appVersion = appVersion),
            vocabulary = VocabularyRef(
                schemaVersion = vocabMeta.schemaVersion,
                fiscalYear = vocabMeta.fiscalYear,
                contentHash = vocabMeta.contentHash.ifBlank { null }
            ),
            entries = entries
        )
        return Result(file, skipped, warnings)
    }

    private fun purchaseEntry(
        item: ReceiptItem,
        product: ProductMaster?,
        date: EntryDate,
        accountsByKey: Map<String, AoiroChoboAccount>,
        memosByKey: Map<String, AoiroChoboMemoTemplate>,
        payable: AoiroChoboAccount?
    ): Entry {
        // 当年度の科目に無いキーは PC で解決できないので送らない。
        // 名前の食い違いは取込時に外れているはずだが、残っていても確定時の名前をエコーすれば PC が要確認に回す（契約 §4.6）
        val expense = product?.accountKey?.let { accountsByKey[it] }
        val memo = product?.memoKey?.let { memosByKey[it] }
            ?.takeIf { expense != null && MemoTab.AP_IN.contains(it) && it.counterAccountKey == expense.accountKey }

        val expenseSide = Side(
            accountKey = expense?.accountKey,
            accountName = expense?.let { product?.accountKeyName ?: it.name },
            taxRate = memo?.taxRate
        )
        val payableSide = Side(
            accountKey = payable?.accountKey,
            accountName = payable?.name,
            taxRate = null
        )

        val status = when {
            expense == null || payable == null -> MatchStatus.UNMATCHED_ACCOUNT
            memo == null -> MatchStatus.UNMATCHED_MEMO
            else -> MatchStatus.MATCHED
        }
        // 科目が決まっていない行に摘要だけ載せない（契約 §11）
        val sentMemo = memo.takeIf { status != MatchStatus.UNMATCHED_ACCOUNT }

        // 返品・値引きは借方/貸方を入れ替えて正数で出す（契約 §7）
        val isReturn = item.amount < 0
        return Entry(
            externalId = "ocr:purchase:${item.uuid.lowercase()}",
            source = "Purchase",
            ledgerType = "AP",
            bankSlotNo = null,
            entryDate = date.iso,
            amount = abs(item.amount),
            debit = if (isReturn) payableSide else expenseSide,
            credit = if (isReturn) expenseSide else payableSide,
            memoKey = sentMemo?.memoKey,
            memoName = sentMemo?.let { product?.memoKeyName ?: it.name },
            note = item.productName.ifBlank { null },
            matchStatus = status,
            confidence = item.ocrConfidence?.takeIf { it in CONFIDENCES },
            meta = Meta(
                sourceTable = "receipt_items",
                sourceRowId = item.id,
                productName = item.productName.ifBlank { null },
                category = item.category.ifBlank { null },
                isReturn = isReturn,
                phoneExportedAt = item.exportedAt?.let(::exportedAtIso)
            )
        )
    }

    /** 領収日（令和）を西暦の日付に。実在しない日付（2月30日など）なら null */
    private fun entryDateOf(item: ReceiptItem): EntryDate? {
        val date = EntryDate(2018 + item.receiptYear, item.receiptMonth, item.receiptDay)
        if (date.month !in 1..12 || date.day < 1) return null
        val calendar = GregorianCalendar(date.year, date.month - 1, 1)
        if (date.day > calendar.getActualMaximum(GregorianCalendar.DAY_OF_MONTH)) return null
        return date
    }

    /** receipt_items.exportedAt（yyyy/MM/dd HH:mm・日本時間）を ISO 8601 に。読めなければ null */
    private fun exportedAtIso(text: String): String? = runCatching {
        val parser = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).apply {
            timeZone = ZONE
            isLenient = false
        }
        isoOffsetFormat().format(parser.parse(text)!!)
    }.getOrNull()

    /**
     * null も省略せず書く（契約の例と同じ形。`memoKey: null` は「摘要なし」という値）。
     * HTML エスケープは切る（Gson の既定では `=` や `'` が Unicode エスケープで出る）。
     */
    fun toJson(file: TransactionsFile): String =
        GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create().toJson(file)
}
