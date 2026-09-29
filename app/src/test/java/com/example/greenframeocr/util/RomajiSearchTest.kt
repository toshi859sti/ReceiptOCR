package com.example.greenframeocr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日本語キーボードで打ったかなでも、PC の検索文字（ローマ字）に当たること。
 * 検索文字は本番 vocabulary.json の実際の値。
 */
class RomajiSearchTest {

    @Test
    fun `かなをローマ字に直す`() {
        assertEquals("douryoku", RomajiSearch.kanaToRomaji("どうりょく"))
        assertEquals("syubyou", RomajiSearch.kanaToRomaji("しゅびょう"))
        assertEquals("zappi", RomajiSearch.kanaToRomaji("ざっぴ"))
        assertEquals("tidai", RomajiSearch.kanaToRomaji("チダイ"))
        assertEquals("hiryou", RomajiSearch.kanaToRomaji("ヒリョー".replace("ー", "ウ")))
    }

    @Test
    fun `日本語キーボードで打ったかなで検索文字に当たる`() {
        assertTrue(RomajiSearch.matches("douryoku", "どう"))
        assertTrue(RomajiSearch.matches("syubyou", "しゅびょ"))  // びょ は 1 音（しゅび では当たらない）
        assertTrue(RomajiSearch.matches("zappi", "ざっぴ"))
        assertTrue(RomajiSearch.matches("tyokubai", "ちょく"))
        assertTrue(RomajiSearch.matches("hiryoucyo", "ちょ"))  // PC 側の綴りが cyo でも当たる
        assertTrue(RomajiSearch.matches("zigyounusikas", "じぎょう"))
    }

    @Test
    fun `ヘボン式で打っても訓令式の検索文字に当たる`() {
        assertTrue(RomajiSearch.matches("syubyou", "shubyou"))
        assertTrue(RomajiSearch.matches("tidai", "chidai"))
        assertTrue(RomajiSearch.matches("tuusin", "tsuushin"))
        assertTrue(RomajiSearch.matches("hutuu", "futsuu"))
        assertTrue(RomajiSearch.matches("zigyounusikas", "jigyou"))
        assertTrue(RomajiSearch.matches("DOURYOKU", "dou"))
    }

    @Test
    fun `弥生の検索文字（大文字ヘボン式・8文字で切れている）にもかなで当たる`() {
        // 実機の yayoi_accounts.searchKeyAlpha の値
        assertTrue(RomajiSearch.matches("FUTSUUYO", "ふつう"))   // 普通預金
        assertTrue(RomajiSearch.matches("MISHUUKI", "みしゅう")) // 未収金
        assertTrue(RomajiSearch.matches("KOUCHIKU", "こうちく")) // 構築物
        assertTrue(RomajiSearch.matches("ZATSUSHI", "ざつし"))   // 雑資産
        assertTrue(RomajiSearch.matches("GENKIN", "genkin"))
    }

    @Test
    fun `関係ない入力は当たらない・空なら全部当たる`() {
        assertFalse(RomajiSearch.matches("douryoku", "ひりょう"))
        assertTrue(RomajiSearch.matches("douryoku", "  "))
    }
}
