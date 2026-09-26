package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.DepositMeisai
import com.example.greenframeocr.data.Passbook
import com.example.greenframeocr.data.TekiyouMatchingRule
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.toEntityOrNull
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder.DepositRow
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder.PurchaseRow
import com.example.greenframeocr.util.AoiroChoboTransactionsBuilder.SkipReason
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.GregorianCalendar

/**
 * transactions.json の契約テスト（docs/integration/transaction-import.md §11）。
 *
 * 本番の vocabulary.json を入力にしてビルダーを通し、**書き出した JSON 文字列を独立に読み直して**
 * §11 の検証項目を1つずつ当てる。ビルダーのデータクラスは信用しない。
 */
class AoiroChoboTransactionsBuilderTest {

    // Gradle のユニットテストは app/ を作業ディレクトリにして走る
    private val vocabFile = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_134932.json")
    private val parsed = Gson().fromJson(vocabFile.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
    private val accounts: List<AoiroChoboAccount> = parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
    private val memos: List<AoiroChoboMemoTemplate> = parsed.memoTemplates.orEmpty().mapNotNull { it.toEntityOrNull() }
    private val meta = AoiroChoboVocabMeta(
        schemaVersion = parsed.schemaVersion ?: 0,
        fiscalYear = parsed.fiscalYear?.year ?: 0,
        contentHash = parsed.contentHash.orEmpty()
    )

    private fun account(key: String) = accounts.single { it.accountKey == key }
    private fun apMemo(accountKey: String) = AoiroChoboPurchaseRules.memoCandidates(accountKey, memos).single()

    private var nextId = 1L

    /** 令和8年＝2026年 */
    private fun item(
        productName: String,
        amount: Int,
        year: Int = 8,
        month: Int = 1,
        day: Int = 20,
        uuid: String = java.util.UUID.randomUUID().toString(),
        confidence: String? = "high"
    ) = ReceiptItem(
        id = nextId++,
        issueYear = year, issueMonth = month, sheetNumber = 1, itemNumber = nextId.toInt(),
        receiptYear = year, receiptMonth = month, receiptDay = day,
        productName = productName, amount = amount, category = "一般購買",
        ocrConfidence = confidence, uuid = uuid
    )

    private fun product(accountKey: String?, memo: AoiroChoboMemoTemplate? = null) = ProductMaster(
        canonicalName = "x", category = "一般購買",
        accountKey = accountKey, accountKeyName = accountKey?.let { account(it).name },
        memoKey = memo?.memoKey, memoKeyName = memo?.name
    )

    private fun build(vararg rows: PurchaseRow) = AoiroChoboTransactionsBuilder.buildPurchase(
        rows.toList(), accounts, memos, meta, appVersion = "1.0",
        now = GregorianCalendar(2026, 8, 25, 15, 0, 0).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        }.time
    )

    /** 代表的な行を一通り含む出力 */
    private fun typicalRows(): List<PurchaseRow> {
        val hiryou = apMemo("hiryou")
        return listOf(
            PurchaseRow(item("ダイアジノン粒剤3", 11000), product("hiryou", hiryou)),     // Matched
            PurchaseRow(item("レギュラーガソリン", 5500), product("douryoku")),           // 摘要なし
            PurchaseRow(item("謎の商品", 330), null),                                      // 科目なし
            PurchaseRow(item("化成肥料 返品", -1100), product("hiryou", hiryou)),         // 返品
            PurchaseRow(item("空行", 0), product("hiryou", hiryou)),                       // 出せない
            PurchaseRow(item("2月30日", 100, month = 2, day = 30), product("hiryou", hiryou)) // 出せない
        )
    }

    // ---- §11 の検証（出力の JSON 文字列だけを見る） ----

    private val externalIdPattern = Regex("^[a-z0-9:_-]{1,128}$")
    private val taxRates = setOf("10", "8", "1", "8_old", "non", "na", "men")
    private val statuses = setOf("Matched", "UnmatchedAccount", "UnmatchedMemo", "Ambiguous")

    private fun JsonObject.str(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun assertContract(json: String) {
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(2, root.get("schemaVersion").asInt)
        assertEquals("aoirochobo.transactions", root.str("kind"))
        assertEquals(meta.contentHash, root.getAsJsonObject("vocabulary").str("contentHash"))

        val accountNames = accounts.associate { it.accountKey to it.name }
        val memoNames = memos.associate { it.memoKey to it.name }
        val seen = mutableSetOf<String>()

        for (e in root.getAsJsonArray("entries").map { it.asJsonObject }) {
            val id = e.str("externalId")!!
            assertTrue("externalId の文字種: $id", externalIdPattern.matches(id))
            assertTrue("externalId の重複: $id", seen.add(id))

            val date = e.str("entryDate")!!
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date))
            val (y, m, d) = date.split("-").map(String::toInt)
            val cal = GregorianCalendar().apply { isLenient = false; set(y, m - 1, d) }
            cal.time // 実在しない日付ならここで例外

            assertTrue(e.get("amount").asInt >= 1)
            assertTrue(e.str("source") in setOf("Purchase", "Deposit", "Receipt"))
            if (e.str("source") == "Deposit") assertTrue(e.get("bankSlotNo").asInt in 1..5)

            for (sideName in listOf("debit", "credit")) {
                val side = e.getAsJsonObject(sideName)
                val key = side.str("accountKey")
                if (key == null) {
                    assertEquals(null, side.str("accountName"))
                } else {
                    assertTrue(key.isNotEmpty())
                    assertEquals("$sideName.accountName", accountNames[key], side.str("accountName"))
                }
                side.str("taxRate")?.let { assertTrue("taxRate: $it", it in taxRates) }
            }

            val status = e.str("matchStatus")
            assertTrue(status in statuses)
            val memoKey = e.str("memoKey")
            when (status) {
                "UnmatchedMemo", "UnmatchedAccount" -> assertEquals(null, memoKey)
                else -> assertTrue("memoKey が辞書に無い: $memoKey", memoKey in memoNames)
            }
            if (memoKey == null) assertEquals(null, e.str("memoName"))
            else assertEquals(memoNames[memoKey], e.str("memoName"))
        }
    }

    @Test
    fun `代表的な行の出力が契約 §11 を満たす`() {
        val result = build(*typicalRows().toTypedArray())
        assertContract(result.json)
        assertEquals(4, result.file.entries.size)
    }

    @Test
    fun `科目と摘要が揃った行は Matched で、貸方は買掛金`() {
        val hiryou = apMemo("hiryou")
        val e = build(PurchaseRow(item("ダイアジノン粒剤3", 11000), product("hiryou", hiryou))).file.entries.single()
        assertEquals("Matched", e.matchStatus)
        assertEquals("hiryou", e.debit.accountKey)
        assertEquals("kaikake", e.credit.accountKey)
        assertEquals(hiryou.memoKey, e.memoKey)
        assertEquals(hiryou.taxRate, e.debit.taxRate)
        assertEquals(null, e.credit.taxRate)
        assertEquals("ダイアジノン粒剤3", e.note)
        assertEquals("2026-01-20", e.entryDate)
        assertEquals("AP", e.ledgerType)
    }

    @Test
    fun `摘要の無い科目は UnmatchedMemo で、税率は PC に任せる`() {
        val e = build(PurchaseRow(item("軽油", 5500), product("douryoku"))).file.entries.single()
        assertEquals("UnmatchedMemo", e.matchStatus)
        assertEquals("douryoku", e.debit.accountKey)
        assertEquals(null, e.memoKey)
        assertEquals(null, e.debit.taxRate)
    }

    @Test
    fun `商品が紐付いていない行も落とさず UnmatchedAccount で出す`() {
        val e = build(PurchaseRow(item("謎の商品", 330), null)).file.entries.single()
        assertEquals("UnmatchedAccount", e.matchStatus)
        assertEquals(null, e.debit.accountKey)
        assertEquals("kaikake", e.credit.accountKey)
    }

    @Test
    fun `返品は借方貸方を入れ替えて正数、isReturn を立てる`() {
        val hiryou = apMemo("hiryou")
        val e = build(PurchaseRow(item("化成肥料 返品", -1100), product("hiryou", hiryou))).file.entries.single()
        assertEquals(1100, e.amount)
        assertEquals("kaikake", e.debit.accountKey)
        assertEquals("hiryou", e.credit.accountKey)
        assertEquals(hiryou.taxRate, e.credit.taxRate)
        assertTrue(e.meta.isReturn)
    }

    @Test
    fun `金額0と実在しない日付だけは出さずに理由を返す`() {
        val result = build(*typicalRows().toTypedArray())
        assertEquals(
            listOf(SkipReason.ZERO_AMOUNT, SkipReason.INVALID_DATE),
            result.skipped.map { it.reason }
        )
    }

    @Test
    fun `当年度の辞書に無い科目・摘要のキーは送らない`() {
        val stale = ProductMaster(
            canonicalName = "x", category = "一般購買",
            accountKey = "acct-deleted", accountKeyName = "消えた科目", memoKey = "memo-deleted", memoKeyName = "消えた摘要"
        )
        val e = build(PurchaseRow(item("古い商品", 500), stale)).file.entries.single()
        assertEquals("UnmatchedAccount", e.matchStatus)
        assertEquals(null, e.debit.accountKey)
        assertEquals(null, e.memoKey)
    }

    @Test
    fun `科目と噛み合わない摘要は外して UnmatchedMemo`() {
        // 肥料費の商品に農薬の摘要が残っている（相手科目が違う）
        val mismatched = product("hiryou", apMemo("nouyaku"))
        val e = build(PurchaseRow(item("ずれた商品", 500), mismatched)).file.entries.single()
        assertEquals("UnmatchedMemo", e.matchStatus)
        assertEquals(null, e.memoKey)
    }

    @Test
    fun `accountName は確定したときに見えていた名前を送る`() {
        // PC で科目名が変わったのに紐付けが残っているケース。PC はこの食い違いで要確認に回す（契約 §4.6）
        val renamed = product("hiryou").copy(accountKeyName = "肥料費（旧）")
        val e = build(PurchaseRow(item("肥料", 500), renamed)).file.entries.single()
        assertEquals("肥料費（旧）", e.debit.accountName)
    }

    @Test
    fun `externalId は行の uuid を小文字で使い、保存し直しても変わらない`() {
        val uuid = "3F2B9C14-77A1-4A6E-9C02-1D5E8B4A0C71"
        val first = build(PurchaseRow(item("肥料", 500, uuid = uuid), null)).file.entries.single()
        val again = build(PurchaseRow(item("肥料", 500, uuid = uuid), null)).file.entries.single()
        assertEquals("ocr:purchase:3f2b9c14-77a1-4a6e-9c02-1d5e8b4a0c71", first.externalId)
        assertEquals(first.externalId, again.externalId)
    }

    @Test
    fun `年度外の取引は出すが警告する`() {
        val result = build(PurchaseRow(item("去年の肥料", 500, year = 7), null))
        assertEquals(1, result.file.entries.size)
        assertTrue(result.warnings.single().contains("2026"))
    }

    @Test
    fun `日本語と記号はエスケープせずにそのまま書き、null も省略しない`() {
        val json = build(PurchaseRow(item("A=B 'テスト'", 500), null)).json
        assertTrue(json.contains("A=B 'テスト'"))
        assertTrue(json.contains("\"memoKey\": null"))
        assertTrue(json.contains("\"generatedAt\": \"2026-09-25T15:00:00+09:00\""))
        assertFalse(json.contains("\\u"))
    }

    // ---- 預金（Deposit） ----

    private val einou = Passbook(id = 1, name = "通帳1", aoiroAccountKey = "einou", aoiroAccountKeyName = "営農口座")
    private val tyokubai = Passbook(id = 2, name = "通帳2", aoiroAccountKey = "tyokubai", aoiroAccountKeyName = "直売口座")

    private fun bankMemo(accountKey: String, income: Boolean, name: String? = null): AoiroChoboMemoTemplate {
        val tab = if (income) AoiroChoboMemoRules.MemoTab.BANK_IN else AoiroChoboMemoRules.MemoTab.BANK_OUT
        return memos.filter { tab.contains(it) && it.counterAccountKey == accountKey }
            .single { name == null || it.name == name }
    }

    private var nextMeisaiId = 1

    private fun meisai(
        tekiyou: String,
        amount: Int,
        date: String = "2026-02-05",
        number: String = "0012",
        passbookId: Int = 1,
        memo: String = ""
    ) = DepositMeisai(
        id = nextMeisaiId++, passbookId = passbookId, transactionDate = date,
        transactionNumber = number, tekiyou = tekiyou, amount = amount, memo = memo
    )

    private fun rule(accountKey: String?, memo: AoiroChoboMemoTemplate? = null) = TekiyouMatchingRule(
        pattern = "x", normalizedTekiyou = "x",
        accountKey = accountKey, accountKeyName = accountKey?.let { account(it).name },
        memoKey = memo?.memoKey, memoKeyName = memo?.name
    )

    private fun buildDeposit(vararg rows: DepositRow) = AoiroChoboTransactionsBuilder.buildDeposit(
        rows.toList(), accounts, memos, meta, appVersion = "1.0"
    )

    @Test
    fun `預金の代表的な行の出力が契約 §11 を満たす`() {
        val result = buildDeposit(
            DepositRow(meisai("ﾉｳｷﾖｳ ﾋﾘﾖｳ", -11000, number = "0001"), einou, rule("hiryou", bankMemo("hiryou", false))),
            DepositRow(meisai("ｻﾞﾂｼｭｳﾆｭｳ", 500, number = "0002"), einou, rule("zatu", bankMemo("zatu", true))),
            DepositRow(meisai("ﾃﾞﾝｷﾀﾞｲ", -3000, number = "0003"), einou, rule("douryoku")),
            DepositRow(meisai("ﾌﾒｲ", -100, number = "x01"), tyokubai, null),
            DepositRow(meisai("口座なし", 100, number = "0004"), Passbook(id = 3, name = "通帳3"), null)
        )
        assertContract(result.json)
        assertEquals(4, result.file.entries.size)
        assertEquals(listOf(SkipReason.NO_BANK_ACCOUNT), result.skipped.map { it.reason })
    }

    @Test
    fun `出金は 借方＝相手科目・貸方＝口座 で、返品扱いにしない`() {
        val memo = bankMemo("hiryou", false)
        val e = buildDeposit(DepositRow(meisai("ﾋﾘﾖｳ", -11000), einou, rule("hiryou", memo))).file.entries.single()
        assertEquals("Deposit", e.source)
        assertEquals("Bank", e.ledgerType)
        assertEquals(1, e.bankSlotNo)
        assertEquals(11000, e.amount)
        assertEquals("hiryou", e.debit.accountKey)
        assertEquals("einou", e.credit.accountKey)
        assertEquals("営農口座", e.credit.accountName)
        assertEquals(memo.taxRate, e.debit.taxRate)
        assertEquals(null, e.credit.taxRate)
        assertEquals("Matched", e.matchStatus)
        assertFalse(e.meta.isReturn)
    }

    @Test
    fun `入金は 借方＝口座・貸方＝相手科目`() {
        val memo = bankMemo("zatu", true)
        val e = buildDeposit(DepositRow(meisai("ｻﾞﾂ", 500), tyokubai, rule("zatu", memo))).file.entries.single()
        assertEquals(2, e.bankSlotNo)
        assertEquals("tyokubai", e.debit.accountKey)
        assertEquals("zatu", e.credit.accountKey)
        assertEquals(memo.taxRate, e.credit.taxRate)
        assertEquals(memo.memoKey, e.memoKey)
    }

    @Test
    fun `externalId は 通帳ID・日付・通番 で、口座を付け替えても変わらない`() {
        val m = meisai("ﾋﾘﾖｳ", -100, date = "2026-02-05", number = "0012", passbookId = 1)
        val first = buildDeposit(DepositRow(m, einou, null)).file.entries.single()
        val moved = buildDeposit(DepositRow(m, einou.copy(aoiroAccountKey = "tyokubai", aoiroAccountKeyName = "直売口座"), null))
            .file.entries.single()
        assertEquals("ocr:deposit:p1-2026-02-05-0012", first.externalId)
        assertEquals(first.externalId, moved.externalId)
        assertEquals(2, moved.bankSlotNo)
    }

    @Test
    fun `別の通帳の同じ日・同じ通番は別の externalId になる`() {
        val result = buildDeposit(
            DepositRow(meisai("A", -100, passbookId = 1), einou, null),
            DepositRow(meisai("B", -100, passbookId = 2), tyokubai, null)
        )
        assertEquals(
            listOf("ocr:deposit:p1-2026-02-05-0012", "ocr:deposit:p2-2026-02-05-0012"),
            result.file.entries.map { it.externalId }
        )
    }

    @Test
    fun `口座間の振替は除外せず両方の通帳から出す`() {
        // 営農口座 → 直売口座。相手科目にもう一方の口座を選んでいれば 2 行の借方貸方がそろい、PC が重複の可能性で受ける
        val result = buildDeposit(
            DepositRow(meisai("ﾌﾘｶｴ", -50000, passbookId = 1), einou, rule("tyokubai")),
            DepositRow(meisai("ﾌﾘｶｴ", 50000, passbookId = 2), tyokubai, rule("einou"))
        )
        val (out, inn) = result.file.entries
        assertEquals(out.debit.accountKey, inn.debit.accountKey)
        assertEquals(out.credit.accountKey, inn.credit.accountKey)
        assertEquals("tyokubai", out.debit.accountKey)
        assertEquals("einou", out.credit.accountKey)
    }

    @Test
    fun `明細の個別指定がルールより優先し、摘要も個別の科目のものを使う`() {
        val electricity = bankMemo("douryoku", false, "電気料金（事業専用）")
        val m = meisai("ﾃﾞﾝｷ", -3000).copy(
            overrideAccountKey = "douryoku", overrideAccountKeyName = "動力光熱費",
            overrideMemoKey = electricity.memoKey, overrideMemoKeyName = electricity.name
        )
        val e = buildDeposit(DepositRow(m, einou, rule("hiryou", bankMemo("hiryou", false)))).file.entries.single()
        assertEquals("douryoku", e.debit.accountKey)
        assertEquals(electricity.memoKey, e.memoKey)
        assertEquals("Matched", e.matchStatus)
    }

    @Test
    fun `個別に科目だけ指定した明細はルールの摘要を持ち込まない`() {
        val m = meisai("ﾃﾞﾝｷ", -3000).copy(overrideAccountKey = "douryoku", overrideAccountKeyName = "動力光熱費")
        val e = buildDeposit(DepositRow(m, einou, rule("hiryou", bankMemo("hiryou", false)))).file.entries.single()
        assertEquals("UnmatchedMemo", e.matchStatus)
        assertEquals(null, e.memoKey)
    }

    @Test
    fun `入出金の向きと合わない摘要は外す`() {
        // 出金ルールの摘要が、入金の明細（返金など）に当たったケース
        val e = buildDeposit(DepositRow(meisai("ﾋﾘﾖｳ ﾍﾝｷﾝ", 1100), einou, rule("hiryou", bankMemo("hiryou", false))))
            .file.entries.single()
        assertEquals("UnmatchedMemo", e.matchStatus)
        assertEquals(null, e.memoKey)
        assertEquals("einou", e.debit.accountKey)
        assertEquals("hiryou", e.credit.accountKey)
    }

    @Test
    fun `ルールの無い明細は UnmatchedAccount で出し、口座側は埋める`() {
        val e = buildDeposit(DepositRow(meisai("ﾌﾒｲ", -100), einou, null)).file.entries.single()
        assertEquals("UnmatchedAccount", e.matchStatus)
        assertEquals(null, e.debit.accountKey)
        assertEquals("einou", e.credit.accountKey)
    }

    @Test
    fun `口座が決まらない・通番が使えない・金額0の明細は出さずに理由を返す`() {
        val result = buildDeposit(
            DepositRow(meisai("口座なし", 100, number = "0001"), Passbook(id = 3, name = "通帳3"), null),
            DepositRow(meisai("消えた口座", 100, number = "0002"), einou.copy(aoiroAccountKey = "acct-deleted"), null),
            DepositRow(meisai("普通預金（親）", 100, number = "0003"), einou.copy(aoiroAccountKey = "hutuu"), null),
            DepositRow(meisai("記号入り", 100, number = "12#3"), einou, null),
            DepositRow(meisai("ゼロ", 0, number = "0005"), einou, null),
            DepositRow(meisai("日付", 100, date = "2026-02-30"), einou, null)
        )
        assertEquals(0, result.file.entries.size)
        assertEquals(
            listOf(
                SkipReason.NO_BANK_ACCOUNT, SkipReason.BANK_ACCOUNT_NOT_FOUND, SkipReason.BANK_ACCOUNT_NOT_FOUND,
                SkipReason.INVALID_NUMBER, SkipReason.ZERO_AMOUNT, SkipReason.INVALID_DATE
            ),
            result.skipped.map { it.reason }
        )
    }

    @Test
    fun `note は通帳の摘要と農家のメモ`() {
        val e = buildDeposit(DepositRow(meisai("ﾃﾞﾝｷ", -3000, memo = "6月分"), einou, null)).file.entries.single()
        assertEquals("ﾃﾞﾝｷ　6月分", e.note)
    }
}
