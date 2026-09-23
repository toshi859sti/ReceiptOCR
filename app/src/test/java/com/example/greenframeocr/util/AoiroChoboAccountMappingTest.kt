package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboAccount
import com.example.greenframeocr.data.YayoiAccount
import com.example.greenframeocr.util.AoiroChoboAccountMapping.Basis
import com.example.greenframeocr.util.AoiroChoboAccountMapping.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AoiroChoboAccountMappingTest {

    private fun aoiro(
        key: String,
        name: String,
        type: String = "Expense",
        searchKey: String = ""
    ) = AoiroChoboAccount(
        accountKey = key,
        searchKey = searchKey,
        name = name,
        accountType = type
    )

    private fun yayoi(
        id: Long,
        name: String,
        searchKeyAlpha: String = "",
        accountKey: String? = null,
        isEnabled: Boolean = true
    ) = YayoiAccount(
        id = id,
        accountName = name,
        searchKeyAlpha = searchKeyAlpha,
        accountCode = null,
        accountKey = accountKey,
        isEnabled = isEnabled
    )

    // ---- タブ分け ----

    @Test
    fun `accountType がそのままタブになる`() {
        assertEquals(Tab.ASSET, AoiroChoboAccountMapping.tabOf(aoiro("genkin", "現金", "Asset")))
        assertEquals(Tab.LIABILITY, AoiroChoboAccountMapping.tabOf(aoiro("kaikake", "買掛金", "Liability")))
        assertEquals(Tab.INCOME, AoiroChoboAccountMapping.tabOf(aoiro("suitou", "水稲", "Income")))
        assertEquals(Tab.EXPENSE, AoiroChoboAccountMapping.tabOf(aoiro("hiryou", "肥料費", "Expense")))
    }

    @Test
    fun `Capital の6件は PC 画面と同じタブに散る`() {
        assertEquals(Tab.ASSET, AoiroChoboAccountMapping.tabOf(aoiro("zigyounusikas", "事業主貸", "Capital")))
        assertEquals(Tab.LIABILITY, AoiroChoboAccountMapping.tabOf(aoiro("zigyounusikari", "事業主借", "Capital")))
        assertEquals(Tab.LIABILITY, AoiroChoboAccountMapping.tabOf(aoiro("motoire", "元入金", "Capital")))
        assertEquals(Tab.LIABILITY, AoiroChoboAccountMapping.tabOf(aoiro("kouzyo", "青申特別控除前の所得金額", "Capital")))
        assertEquals(Tab.EXPENSE, AoiroChoboAccountMapping.tabOf(aoiro("senzyuusya", "専従者給与", "Capital")))
        assertEquals(Tab.EXPENSE, AoiroChoboAccountMapping.tabOf(aoiro("kakei", "家計費", "Capital")))
    }

    @Test
    fun `知らない Capital は消さずに その他 へ落とす`() {
        assertEquals(Tab.OTHER, AoiroChoboAccountMapping.tabOf(aoiro("acct-xyz", "新しい資本科目", "Capital")))
    }

    // ---- 名前の正規化 ----

    @Test
    fun `括弧と中黒と末尾の等を落とす`() {
        assertEquals("農産物", AoiroChoboAccountMapping.normalizeName("農産物等（資産）"))
        assertEquals("農機具", AoiroChoboAccountMapping.normalizeName("農機具等（資産）"))
        assertEquals("家事消費", AoiroChoboAccountMapping.normalizeName("家事消費等"))
        assertEquals("地代賃借料", AoiroChoboAccountMapping.normalizeName("地代・賃借料"))
    }

    @Test
    fun `等 だけの名前を空にしない`() {
        assertEquals("等", AoiroChoboAccountMapping.normalizeName("等"))
    }

    // ---- 提案 ----

    @Test
    fun `科目名が完全に一致すれば最優先で提案する`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("hiryou", "肥料費", searchKey = "hiryou")),
            listOf(yayoi(1, "肥料費", "HIRYOU"), yayoi(2, "飼料費", "SIRYOU"))
        )
        val s = suggestions.getValue("hiryou")
        assertEquals(Basis.EXACT_NAME, s.basis)
        assertEquals(listOf(1L), s.candidates.map { it.id })
        assertTrue(s.isUnambiguous)
    }

    @Test
    fun `完全一致が無ければ正規化して突き合わせる`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("noukigu", "農機具等（資産）", "Asset")),
            listOf(yayoi(1, "農機具"))
        )
        val s = suggestions.getValue("noukigu")
        assertEquals(Basis.NORMALIZED_NAME, s.basis)
        assertEquals(listOf(1L), s.candidates.map { it.id })
    }

    @Test
    fun `名前で当たらなければ検索文字で突き合わせる`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("genkin", "現金あり高", "Asset", searchKey = "genkin")),
            listOf(yayoi(1, "現金", "GENKIN"))
        )
        val s = suggestions.getValue("genkin")
        assertEquals(Basis.SEARCH_KEY, s.basis)
    }

    @Test
    fun `すでに紐付いている弥生科目は候補に出さない`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("hiryou", "肥料費")),
            listOf(yayoi(1, "肥料費", accountKey = "hiryou_old"))
        )
        assertNull(suggestions["hiryou"])
    }

    @Test
    fun `確定済みの AoiroChobo 科目には提案を出さない`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("hiryou", "肥料費")),
            listOf(yayoi(1, "肥料費", accountKey = "hiryou"), yayoi(2, "肥料費"))
        )
        assertNull(suggestions["hiryou"])
    }

    @Test
    fun `無効な弥生科目は候補に出さない`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("hiryou", "肥料費")),
            listOf(yayoi(1, "肥料費", isEnabled = false))
        )
        assertNull(suggestions["hiryou"])
    }

    @Test
    fun `候補が複数ある提案は一括確定に回さない`() {
        val suggestions = AoiroChoboAccountMapping.suggest(
            listOf(aoiro("tatemono", "建物・構築物（資産）", "Asset")),
            // 弥生は「建物」「構築物」に分かれている。正規化しても 1 つには決まらない
            listOf(yayoi(1, "建物・構築物"), yayoi(2, "建物・構築物"))
        )
        val s = suggestions.getValue("tatemono")
        assertEquals(2, s.candidates.size)
        assertTrue(AoiroChoboAccountMapping.autoConfirmable(suggestions).isEmpty())
    }

    @Test
    fun `同じ弥生科目を2つのキーが取り合わないようにする`() {
        // 「雑費」が 2 つの AoiroChobo 科目から提案されうる状況を作る
        val suggestions = mapOf(
            "zappi" to AoiroChoboAccountMapping.Suggestion(
                "zappi", listOf(yayoi(1, "雑費")), Basis.EXACT_NAME
            ),
            "zappi2" to AoiroChoboAccountMapping.Suggestion(
                "zappi2", listOf(yayoi(1, "雑費")), Basis.SEARCH_KEY
            )
        )
        val confirmable = AoiroChoboAccountMapping.autoConfirmable(suggestions)
        assertEquals(1, confirmable.size)
        // 根拠が強いほうが勝つ
        assertEquals(Basis.EXACT_NAME, confirmable.first().basis)
    }
}
