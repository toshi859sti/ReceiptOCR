package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.AoiroChoboVocabularyFile
import com.example.greenframeocr.data.toEntityOrNull
import com.example.greenframeocr.util.AoiroChoboAccountRules.AccountTab
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 科目を PC の「科目・残高登録」画面と同じタブ・並びに振り分ける規則。
 */
class AoiroChoboAccountRulesTest {

    private fun account(
        key: String,
        type: String,
        order: Int,
        group: String? = null,
        parent: String? = null
    ) = AoiroChoboAccount(
        accountKey = key,
        name = key,
        accountType = type,
        displayGroup = group,
        parentAccountKey = parent,
        displayOrder = order
    )

    @Test
    fun `資本の6件は PC の画面どおり資産・負債・支出に散る`() {
        val tabs = listOf("zigyounusikas", "zigyounusikari", "motoire", "kouzyo", "senzyuusya", "kakei")
            .map { AoiroChoboAccountRules.tabOf(account(it, "Capital", 0)) }
        assertEquals(
            listOf(AccountTab.ASSET, AccountTab.LIABILITY, AccountTab.LIABILITY, AccountTab.LIABILITY,
                AccountTab.EXPENSE, AccountTab.EXPENSE),
            tabs
        )
    }

    @Test
    fun `知らない資本や区分はその他に出して消さない`() {
        assertEquals(AccountTab.OTHER, AoiroChoboAccountRules.tabOf(account("acct-new", "Capital", 0)))
        assertEquals(AccountTab.OTHER, AoiroChoboAccountRules.tabOf(account("x", "Equity", 0)))
    }

    @Test
    fun `内訳科目は親の直後・グループ名は続く間の先頭だけ`() {
        val accounts = listOf(
            account("tatemono", "Asset", 1160, group = "償却資産"),
            account("einou", "Asset", 1021, parent = "hutuu"),
            account("hutuu", "Asset", 1020),
            account("genkin", "Asset", 1010),
            account("noukigu", "Asset", 1170, group = "償却資産"),
            account("toti", "Asset", 1190)
        )
        val rows = AoiroChoboAccountRules.rowsFor(AccountTab.ASSET, accounts)

        assertEquals(listOf("genkin", "hutuu", "einou", "tatemono", "noukigu", "toti"), rows.map { it.account.accountKey })
        assertEquals(listOf(true, false, false, true, false, true), rows.map { it.startsGroup })
        assertTrue(rows[1].hasChildren)
        assertTrue(rows[2].isChild)
        assertFalse(rows[0].hasChildren)
    }

    @Test
    fun `課税区分の表記は PC と同じ（NotApplicable は不課税）`() {
        assertEquals("不課税", AoiroChoboAccountRules.taxCategoryLabel("NotApplicable"))
        assertEquals("非課税", AoiroChoboAccountRules.taxCategoryLabel("NonTaxable"))
        assertNull(AoiroChoboAccountRules.taxCategoryLabel("NA"))
        assertEquals("不課税", AoiroChoboAccountRules.taxRateLabel("na"))

        val a = account("x", "Expense", 0)
        assertEquals("すべて", AoiroChoboAccountRules.allowedTaxLabel(a.copy(allowsTaxable = true, allowsNonTaxable = true)))
        assertEquals("課税のみ", AoiroChoboAccountRules.allowedTaxLabel(a.copy(allowsTaxable = true)))
        assertEquals("課税以外", AoiroChoboAccountRules.allowedTaxLabel(a.copy(allowsNonTaxable = true)))
        assertNull(AoiroChoboAccountRules.allowedTaxLabel(a))
    }

    private fun loadProductionAccounts(): List<AoiroChoboAccount> {
        // Gradle のユニットテストは app/ を作業ディレクトリにして走る
        val file = File("../docs/AoiroChobo_export/aoirochobo_vocabulary_2026_20260925_134932.json")
        val parsed = Gson().fromJson(file.readText(Charsets.UTF_8), AoiroChoboVocabularyFile::class.java)
        return parsed.accounts.orEmpty().mapNotNull { it.toEntityOrNull() }
    }

    @Test
    fun `本番データの64科目がどれも4つのタブのどこかに出る`() {
        val accounts = loadProductionAccounts()
        assertEquals(64, accounts.size)
        val shown = listOf(AccountTab.ASSET, AccountTab.LIABILITY, AccountTab.INCOME, AccountTab.EXPENSE)
            .sumOf { AoiroChoboAccountRules.rowsFor(it, accounts).size }
        assertEquals(64, shown)
        assertTrue(AoiroChoboAccountRules.rowsFor(AccountTab.OTHER, accounts).isEmpty())
        // 支出は経費22件・繰入額2件＋資本から専従者給与・家計費
        assertEquals(26, AoiroChoboAccountRules.rowsFor(AccountTab.EXPENSE, accounts).size)
    }

    @Test
    fun `本番データの支出タブは PC と同じグループの塊になる`() {
        // REPLY-pc-2026-09-25.md §2・§3：経費は (任意) 経費を挟んで 2 つ、繰入額は 1 つにまとまる
        val rows = AoiroChoboAccountRules.rowsFor(AccountTab.EXPENSE, loadProductionAccounts())
        val blocks = rows.filter { it.startsGroup }.map { it.account.displayGroup }
        assertEquals(listOf("経費", "(任意) 経費", "経費", "繰入額", null), blocks)
        assertEquals("kakei", rows.last().account.accountKey)
    }
}
