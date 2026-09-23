package com.example.greenframeocr.util

import com.example.greenframeocr.data.AoiroChoboMemoTemplate
import com.example.greenframeocr.data.RakurakuTekiyou
import com.example.greenframeocr.util.AoiroChoboMemoMapping.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AoiroChoboMemoMappingTest {

    private fun tekiyou(
        id: Int,
        name: String,
        main: String,
        sub: String,
        searchKey: String = "",
        memoKey: String? = null,
        isEnabled: Boolean = true
    ) = RakurakuTekiyou(
        id = id,
        mainCategory = main,
        subCategory = sub,
        tekiyouName = name,
        searchKey = searchKey,
        kamoku = "",
        memoKey = memoKey,
        isEnabled = isEnabled
    )

    private fun memo(
        key: String,
        name: String,
        ledgerType: String,
        direction: String,
        searchKey: String = ""
    ) = AoiroChoboMemoTemplate(
        memoKey = key,
        ledgerType = ledgerType,
        direction = direction,
        name = name,
        searchKey = searchKey
    )

    // ---- 帳簿の対応づけ ----

    @Test
    fun `同じ 入金 でも帳簿によって向きが変わる`() {
        // 現金の入金はお金が入る In、売掛の入金は債権が解消される Out
        assertEquals("Cash" to "In", AoiroChoboMemoMapping.ledgerOf(tekiyou(1, "", "現金", "入金")))
        assertEquals("AR" to "Out", AoiroChoboMemoMapping.ledgerOf(tekiyou(2, "", "売掛", "入金")))
    }

    @Test
    fun `買掛は 購入 が発生側`() {
        assertEquals("AP" to "In", AoiroChoboMemoMapping.ledgerOf(tekiyou(1, "", "買掛", "購入")))
        assertEquals("AP" to "Out", AoiroChoboMemoMapping.ledgerOf(tekiyou(2, "", "買掛", "出金")))
    }

    @Test
    fun `分類の全角ダッシュや空白を無視する`() {
        assertEquals("AP" to "In", AoiroChoboMemoMapping.ledgerOf(tekiyou(1, "", "買掛", "　購入")))
    }

    @Test
    fun `対応の無い分類は null`() {
        assertNull(AoiroChoboMemoMapping.ledgerOf(tekiyou(1, "", "未払", "発生")))
    }

    // ---- 名前の正規化 ----

    @Test
    fun `括弧の全角半角と区切り文字の差だけを吸収する`() {
        assertEquals(
            AoiroChoboMemoMapping.normalizeName("買掛支払（現金）"),
            AoiroChoboMemoMapping.normalizeName("買掛支払 (現金)")
        )
    }

    @Test
    fun `括弧の中身は落とさない`() {
        val genkin = AoiroChoboMemoMapping.normalizeName("買掛支払（現金）")
        val yokin = AoiroChoboMemoMapping.normalizeName("買掛支払（普通預金）")
        assertTrue("別の摘要が同じ形にならないこと", genkin != yokin)
    }

    // ---- 提案 ----

    @Test
    fun `同じ帳簿の中だけで名前を突き合わせる`() {
        // 「米販売代金」は現金にも売掛にもある。帳簿で絞らないと取り違える
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "米販売代金", "売掛", "販売")),
            listOf(
                memo("memo-0001", "米販売代金", "Cash", "In"),
                memo("memo-0004", "米販売代金", "AR", "In")
            )
        )
        assertEquals(listOf("memo-0004"), suggestions.getValue(1).candidates.map { it.memoKey })
        assertEquals(Basis.EXACT_NAME, suggestions.getValue(1).basis)
    }

    @Test
    fun `完全一致が無ければ正規化して突き合わせる`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "買掛支払 （現金）", "買掛", "出金")),
            listOf(memo("memo-0008", "買掛支払（現金）", "AP", "Out"))
        )
        assertEquals(Basis.NORMALIZED_NAME, suggestions.getValue(1).basis)
    }

    @Test
    fun `名前で当たらなければ検索文字で突き合わせる`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "肥料代", "買掛", "購入", searchKey = "hiryou")),
            listOf(memo("memo-0006", "肥料購入", "AP", "In", searchKey = "hiryou"))
        )
        assertEquals(Basis.SEARCH_KEY, suggestions.getValue(1).basis)
    }

    @Test
    fun `対応の無い帳簿の摘要には提案を出さない`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "修理代", "未払", "発生")),
            listOf(memo("memo-0009", "修理代", "Unpaid", "In"))
        )
        assertNull(suggestions[1])
    }

    @Test
    fun `すでに使われている memoKey は候補に出さない`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(
                tekiyou(1, "肥料購入", "買掛", "購入", memoKey = "memo-0006"),
                tekiyou(2, "肥料購入", "買掛", "購入")
            ),
            listOf(memo("memo-0006", "肥料購入", "AP", "In"))
        )
        assertNull(suggestions[1])
        assertNull(suggestions[2])
    }

    @Test
    fun `無効な摘要には提案を出さない`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "肥料購入", "買掛", "購入", isEnabled = false)),
            listOf(memo("memo-0006", "肥料購入", "AP", "In"))
        )
        assertNull(suggestions[1])
    }

    @Test
    fun `候補が複数ある提案は一括確定に回さない`() {
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(tekiyou(1, "種苗費", "買掛", "購入")),
            listOf(
                memo("memo-a", "種苗費", "AP", "In"),
                memo("memo-b", "種苗費", "AP", "In")
            )
        )
        assertEquals(2, suggestions.getValue(1).candidates.size)
        assertTrue(AoiroChoboMemoMapping.autoConfirmable(suggestions).isEmpty())
    }

    @Test
    fun `同じ memoKey を2つの摘要が取り合わない`() {
        // らくらく側に同名の摘要が 2 件ある（CSV 実データに「種苗費」「諸材料購入」等の重複がある）
        val suggestions = AoiroChoboMemoMapping.suggest(
            listOf(
                tekiyou(1, "肥料購入", "買掛", "購入"),
                tekiyou(2, "肥料代", "買掛", "購入", searchKey = "hiryou")
            ),
            listOf(memo("memo-0006", "肥料購入", "AP", "In", searchKey = "hiryou"))
        )
        val confirmable = AoiroChoboMemoMapping.autoConfirmable(suggestions)
        assertEquals(1, confirmable.size)
        // 根拠が強いほう（名前の完全一致）が勝つ
        assertEquals(1, confirmable.first().tekiyouId)
        assertEquals(Basis.EXACT_NAME, confirmable.first().basis)
    }
}
