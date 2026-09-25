package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.AoiroChoboVocabMeta
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.ProductMaster
import com.example.greenframeocr.data.ReceiptItem
import com.example.greenframeocr.data.toEntityOrNull
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
}
