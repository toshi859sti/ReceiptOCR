package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.DepositMeisai
import com.example.greenframeocr.data.GeneralItemMaster
import com.example.greenframeocr.data.GeneralReceipt
import com.example.greenframeocr.data.GeneralReceiptItem
import com.example.greenframeocr.data.Passbook
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.ReceiptPaymentMethodRule
import com.example.greenframeocr.data.TekiyouMatchingRule
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
 * 読み込み（商品マスタの解決など）は呼び出し側がやる。JA 購買（`Purchase`）・預金（`Deposit`）・レシート（`Receipt`）。
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

    /**
     * 預金の 1 明細・その通帳・摘要パターンのルール（無ければ null）。
     * 科目・摘要は明細の個別指定（override*）が最優先、無ければルールのもの（弥生の出力と同じ優先順）
     */
    data class DepositRow(val meisai: DepositMeisai, val passbook: Passbook?, val rule: TekiyouMatchingRule?)

    /**
     * レシートの 1 品目。[itemIndex] はそのレシートの全品目（経費対象外も含む）を id 順に並べた 0 始まりの位置
     * （契約 §4 の `ocr:receipt:{receiptUuid}:{itemIndex}`）。[group] は品目の正規化キーのグループ設定（無ければ null）
     */
    data class ReceiptRow(
        val receipt: GeneralReceipt,
        val item: GeneralReceiptItem,
        val itemIndex: Int,
        val group: GeneralItemMaster?
    )

    enum class SkipReason(val label: String) {
        ZERO_AMOUNT("金額が 0"),
        INVALID_DATE("日付が不正"),
        NO_BANK_ACCOUNT("通帳にあおいろの口座が未設定"),
        BANK_ACCOUNT_NOT_FOUND("通帳の口座が今の科目にない"),
        INVALID_NUMBER("通番に使えない文字がある")
    }

    /** [label] は画面に出す行の名前（商品名・通帳の摘要） */
    data class Skipped(val label: String, val sourceRowId: Long, val reason: SkipReason)

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
        val phoneExportedAt: String?,
        // レシートだけ（契約 §3 の meta）。購買・預金は null
        val storeName: String? = null,
        val registrationNumber: String? = null,
        val paymentMethodText: String? = null
    )

    object MatchStatus {
        const val MATCHED = "Matched"
        const val UNMATCHED_ACCOUNT = "UnmatchedAccount"
        const val UNMATCHED_MEMO = "UnmatchedMemo"
    }

    private val CONFIDENCES = setOf("high", "medium", "low")
    /** deposit_meisai.transactionDate・general_receipts.date の形（yyyy-MM-dd） */
    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    /** 契約 §4 の要件 4 */
    private val EXTERNAL_ID = Regex("[a-z0-9:_-]{1,128}")
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

        for (row in rows) {
            val item = row.item
            fun skip(reason: SkipReason) {
                skipped += Skipped(item.productName.ifBlank { "（商品名なし）" }, item.id, reason)
            }
            if (item.amount == 0) {
                skip(SkipReason.ZERO_AMOUNT)
                continue
            }
            val date = validDate(2018 + item.receiptYear, item.receiptMonth, item.receiptDay)
            if (date == null) {
                skip(SkipReason.INVALID_DATE)
                continue
            }
            entries += purchaseEntry(item, row.product, date, accountsByKey, memosByKey, payable)
        }
        return finish(entries, skipped, warnings, vocabMeta, appVersion, now)
    }

    /**
     * 預金の明細から transactions.json を組み立てる（契約 §5 の Deposit 行・§6）。
     *
     * 預金口座側は通帳に選んだあおいろの口座（`bankSlotNo` を持つ科目）。口座が決まらない通帳の明細は
     * `bankSlotNo`（Deposit では必須）を書けないので出さずに [Result.skipped] に返す。
     * 相手科目・摘要が決まっていない明細は落とさず、matchStatus で PC の「要確認」に回す。
     * 口座間の振替も除外しない（両方の通帳から届いた 2 行は PC が「重複の可能性」で受ける・契約 §10）。
     */
    fun buildDeposit(
        rows: List<DepositRow>,
        accounts: List<AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>,
        vocabMeta: AoiroChoboVocabMeta,
        appVersion: String,
        now: Date = Date()
    ): Result {
        val accountsByKey = accounts.associateBy { it.accountKey }
        val memosByKey = memos.associateBy { it.memoKey }
        val skipped = mutableListOf<Skipped>()
        val entries = mutableListOf<Entry>()

        for (row in rows) {
            val meisai = row.meisai
            fun skip(reason: SkipReason) {
                skipped += Skipped(meisai.tekiyou.ifBlank { "（摘要なし）" }, meisai.id.toLong(), reason)
            }
            if (meisai.amount == 0) {
                skip(SkipReason.ZERO_AMOUNT)
                continue
            }
            val date = ISO_DATE.matchEntire(meisai.transactionDate)
                ?.destructured?.let { (y, m, d) -> validDate(y.toInt(), m.toInt(), d.toInt()) }
            if (date == null) {
                skip(SkipReason.INVALID_DATE)
                continue
            }
            val bankKey = row.passbook?.aoiroAccountKey
            if (bankKey == null) {
                skip(SkipReason.NO_BANK_ACCOUNT)
                continue
            }
            val bank = accountsByKey[bankKey]?.takeIf { it.bankSlotNo in 1..5 }
            if (bank == null) {
                skip(SkipReason.BANK_ACCOUNT_NOT_FOUND)
                continue
            }
            val externalId = "ocr:deposit:p${meisai.passbookId}-${date.iso}-${meisai.transactionNumber}"
            if (!EXTERNAL_ID.matches(externalId)) {
                skip(SkipReason.INVALID_NUMBER)
                continue
            }
            entries += depositEntry(row, externalId, date, bank, accountsByKey, memosByKey)
        }
        return finish(entries, skipped, mutableListOf(), vocabMeta, appVersion, now)
    }

    private fun depositEntry(
        row: DepositRow,
        externalId: String,
        date: EntryDate,
        bank: AoiroChoboAccount,
        accountsByKey: Map<String, AoiroChoboAccount>,
        memosByKey: Map<String, AoiroChoboMemoTemplate>
    ): Entry {
        val meisai = row.meisai
        val isIncome = meisai.amount > 0

        // 明細の個別指定を最優先。個別の摘要は個別の科目に属するので、科目ごと切り替える
        val overridden = meisai.overrideAccountKey != null
        val counterKey = if (overridden) meisai.overrideAccountKey else row.rule?.accountKey
        val counterKeyName = if (overridden) meisai.overrideAccountKeyName else row.rule?.accountKeyName
        val memoKey = if (overridden) meisai.overrideMemoKey else row.rule?.memoKey
        val memoKeyName = if (overridden) meisai.overrideMemoKeyName else row.rule?.memoKeyName

        val counter = counterKey?.let { accountsByKey[it] }
        // 預金の摘要は 預金/入金・預金/出金 のタブのもの（契約 vocabulary-snapshot §4.5）で、相手科目が一致するものだけ
        val tab = if (isIncome) MemoTab.BANK_IN else MemoTab.BANK_OUT
        val memo = memoKey?.let { memosByKey[it] }
            ?.takeIf { counter != null && tab.contains(it) && it.counterAccountKey == counter.accountKey }

        val counterSide = Side(
            accountKey = counter?.accountKey,
            accountName = counter?.let { counterKeyName ?: it.name },
            taxRate = memo?.taxRate
        )
        val bankSide = Side(
            accountKey = bank.accountKey,
            accountName = row.passbook?.aoiroAccountKeyName ?: bank.name,
            taxRate = null
        )
        val status = when {
            counter == null -> MatchStatus.UNMATCHED_ACCOUNT
            memo == null -> MatchStatus.UNMATCHED_MEMO
            else -> MatchStatus.MATCHED
        }

        // 入金は 借方＝口座／貸方＝相手科目、出金は逆（契約 §5）。出金は返品ではない（§7）
        return Entry(
            externalId = externalId,
            source = "Deposit",
            ledgerType = "Bank",
            bankSlotNo = bank.bankSlotNo,
            entryDate = date.iso,
            amount = abs(meisai.amount),
            debit = if (isIncome) bankSide else counterSide,
            credit = if (isIncome) counterSide else bankSide,
            memoKey = memo?.memoKey,
            memoName = memo?.let { memoKeyName ?: it.name },
            // 通帳の摘要（原文）と、農家が付けたメモ
            note = listOf(meisai.tekiyou, meisai.memo).filter { it.isNotBlank() }.joinToString("　").ifBlank { null },
            matchStatus = status,
            confidence = null,
            meta = Meta(
                sourceTable = "deposit_meisai",
                sourceRowId = meisai.id.toLong(),
                productName = null,
                category = null,
                isReturn = false,
                phoneExportedAt = meisai.exportedAt?.let(::exportedAtIso)
            )
        )
    }

    /**
     * レシートの品目から transactions.json を組み立てる（契約 §5 の Receipt 行）。
     *
     * 借方＝品目グループのあおいろ科目・摘要。貸方＝支払方法の科目で、弥生の出力と同じく
     * 支払方法の印字に最初に部分一致したルール（[paymentRules] の並び順）、どれにも当たらなければ現金。
     * 当たったルールにあおいろの科目が無ければ貸方は未設定（`UnmatchedAccount`）にする（現金にすると誤りになる）。
     * 帳簿は貸方が現金なら `Cash`、それ以外は `Unpaid`（契約 §5）。
     *
     * 摘要は品目グループに「現金/出金」のものを持つ。現金以外の支払いでは帳簿が変わるので、
     * 「未払/発生」に同じ名前・税率・事業割合の摘要があればそれに置き換え、無ければ摘要なしで送る。
     */
    fun buildReceipt(
        rows: List<ReceiptRow>,
        paymentRules: List<ReceiptPaymentMethodRule>,
        accounts: List<AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>,
        vocabMeta: AoiroChoboVocabMeta,
        appVersion: String,
        now: Date = Date()
    ): Result {
        val accountsByKey = accounts.associateBy { it.accountKey }
        val warnings = mutableListOf<String>()

        // 現金。キーの綴りは PC 所有なので決め打ちせず、帳簿の所属で探す（買掛金と同じ）
        val cashAccounts = accounts.filter { it.ledgerAffinity == "Cash" }
        val cash = cashAccounts.singleOrNull()
        if (cash == null) {
            warnings += if (cashAccounts.isEmpty()) {
                "取り込んだ科目に現金（帳簿の所属が Cash の科目）がありません。支払方法のルールに当たらないレシートは貸方を未設定で出力します"
            } else {
                "現金にあたる科目が ${cashAccounts.size} 件あり決められません。支払方法のルールに当たらないレシートは貸方を未設定で出力します"
            }
        }

        val skipped = mutableListOf<Skipped>()
        val entries = mutableListOf<Entry>()
        for (row in rows) {
            val item = row.item
            fun skip(reason: SkipReason) {
                skipped += Skipped(item.itemName.ifBlank { "（品名なし）" }, item.id, reason)
            }
            if (item.price == 0) {
                skip(SkipReason.ZERO_AMOUNT)
                continue
            }
            val date = ISO_DATE.matchEntire(row.receipt.date)
                ?.destructured?.let { (y, m, d) -> validDate(y.toInt(), m.toInt(), d.toInt()) }
            if (date == null) {
                skip(SkipReason.INVALID_DATE)
                continue
            }
            val externalId = "ocr:receipt:${row.receipt.uuid.lowercase()}:${row.itemIndex}"
            if (!EXTERNAL_ID.matches(externalId)) {
                skip(SkipReason.INVALID_NUMBER)
                continue
            }

            val text = row.receipt.paymentMethodText
            val rule = text?.takeIf { it.isNotBlank() }
                ?.let { t -> paymentRules.firstOrNull { t.contains(it.keyword, ignoreCase = true) } }
            val payment: AoiroChoboAccount?
            val paymentName: String?
            if (rule != null) {
                payment = rule.accountKey?.let { accountsByKey[it] }
                paymentName = payment?.let { rule.accountKeyName ?: it.name }
            } else {
                payment = cash
                paymentName = cash?.name
            }
            entries += receiptEntry(row, externalId, date, payment, paymentName, accountsByKey, memos)
        }
        return finish(entries, skipped, warnings, vocabMeta, appVersion, now)
    }

    private fun receiptEntry(
        row: ReceiptRow,
        externalId: String,
        date: EntryDate,
        payment: AoiroChoboAccount?,
        paymentName: String?,
        accountsByKey: Map<String, AoiroChoboAccount>,
        memos: List<AoiroChoboMemoTemplate>
    ): Entry {
        val item = row.item
        val group = row.group
        val expense = group?.accountKey?.let { accountsByKey[it] }

        val isCash = payment?.ledgerAffinity == "Cash"
        val tab = if (isCash) MemoTab.CASH_OUT else MemoTab.UNPAID_IN
        val stored = group?.memoKey?.let { key -> memos.find { it.memoKey == key } }
        val memo = if (expense == null || payment == null || stored == null) null else when {
            tab.contains(stored) && stored.counterAccountKey == expense.accountKey -> stored
            // 帳簿の違う同じ摘要（現金/出金の 修理代 ↔ 未払/発生の 修理代）
            else -> memos.firstOrNull {
                tab.contains(it) && it.counterAccountKey == expense.accountKey && it.name == stored.name &&
                    it.taxRate == stored.taxRate && it.businessRatio == stored.businessRatio
            }
        }
        // 置き換えたときは保存時の名前ではなく置き換え先の名前をエコーする
        val memoName = memo?.let { if (it.memoKey == stored?.memoKey) group.memoKeyName ?: it.name else it.name }

        val expenseSide = Side(
            accountKey = expense?.accountKey,
            accountName = expense?.let { group.accountKeyName ?: it.name },
            taxRate = memo?.taxRate
        )
        val paymentSide = Side(accountKey = payment?.accountKey, accountName = paymentName, taxRate = null)
        val status = when {
            expense == null || payment == null -> MatchStatus.UNMATCHED_ACCOUNT
            memo == null -> MatchStatus.UNMATCHED_MEMO
            else -> MatchStatus.MATCHED
        }
        val sentMemo = memo.takeIf { status != MatchStatus.UNMATCHED_ACCOUNT }

        // 値引き・返品（金額が負）は借方/貸方を入れ替えて正数で出す（契約 §7）
        val isReturn = item.price < 0
        return Entry(
            externalId = externalId,
            source = "Receipt",
            ledgerType = when {
                payment == null -> null
                isCash -> "Cash"
                else -> "Unpaid"
            },
            bankSlotNo = null,
            entryDate = date.iso,
            amount = abs(item.price),
            debit = if (isReturn) paymentSide else expenseSide,
            credit = if (isReturn) expenseSide else paymentSide,
            memoKey = sentMemo?.memoKey,
            memoName = sentMemo?.let { memoName },
            note = item.itemName.ifBlank { null },
            matchStatus = status,
            confidence = null,
            meta = Meta(
                sourceTable = "general_receipt_items",
                sourceRowId = item.id,
                productName = item.itemName.ifBlank { null },
                category = item.category.ifBlank { null },
                isReturn = isReturn,
                phoneExportedAt = item.exportedAt?.let(::exportedAtIso),
                storeName = row.receipt.storeName.ifBlank { null },
                registrationNumber = row.receipt.registrationNumber.ifBlank { null },
                paymentMethodText = row.receipt.paymentMethodText?.ifBlank { null }
            )
        )
    }

    /** ファイル全体を組み立て、年度外の取引があれば警告を足す */
    private fun finish(
        entries: List<Entry>,
        skipped: List<Skipped>,
        warnings: MutableList<String>,
        vocabMeta: AoiroChoboVocabMeta,
        appVersion: String,
        now: Date
    ): Result {
        val outOfFiscalYear = entries.count {
            vocabMeta.fiscalYear != 0 && it.entryDate.take(4).toInt() != vocabMeta.fiscalYear
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

    /** 西暦の年月日を日付に。実在しない日付（2月30日など）なら null */
    private fun validDate(year: Int, month: Int, day: Int): EntryDate? {
        val date = EntryDate(year, month, day)
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
