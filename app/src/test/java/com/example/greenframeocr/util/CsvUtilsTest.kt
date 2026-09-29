package com.example.greenframeocr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvUtilsTest {

    // ─── escapeCsvField ───────────────────────────────────────────────

    @Test
    fun escapeCsvField_通常の文字列はそのまま() {
        assertEquals("レギュラーガソリン", CsvUtils.escapeCsvField("レギュラーガソリン"))
    }

    @Test
    fun escapeCsvField_カンマを含むと引用符で囲む() {
        assertEquals("\"ガソリン,レギュラー\"", CsvUtils.escapeCsvField("ガソリン,レギュラー"))
    }

    @Test
    fun escapeCsvField_引用符は二重化して囲む() {
        assertEquals("\"肥料\"\"特選\"\"\"", CsvUtils.escapeCsvField("肥料\"特選\""))
    }

    @Test
    fun escapeCsvField_改行を含むと引用符で囲む() {
        assertEquals("\"行1\n行2\"", CsvUtils.escapeCsvField("行1\n行2"))
    }

    @Test
    fun escapeCsvField_空文字はそのまま() {
        assertEquals("", CsvUtils.escapeCsvField(""))
    }

    // ─── quoteField ───────────────────────────────────────────────────

    @Test
    fun quoteField_常に引用符で囲む() {
        assertEquals("\"現金\"", CsvUtils.quoteField("現金"))
        assertEquals("\"\"", CsvUtils.quoteField(""))
    }

    @Test
    fun quoteField_内部の引用符は二重化() {
        assertEquals("\"a\"\"b\"", CsvUtils.quoteField("a\"b"))
    }

    // ─── toYayoiDate（和暦変換）──────────────────────────────────────

    @Test
    fun toYayoiDate_令和元年の境界_2019年5月1日() {
        assertEquals("R.01/05/01", CsvUtils.toYayoiDate("2019-05-01"))
    }

    @Test
    fun toYayoiDate_平成最終日_2019年4月30日() {
        assertEquals("H.31/04/30", CsvUtils.toYayoiDate("2019-04-30"))
    }

    @Test
    fun toYayoiDate_令和8年() {
        assertEquals("R.08/06/13", CsvUtils.toYayoiDate("2026-06-13"))
    }

    @Test
    fun toYayoiDate_スラッシュ区切りも受け付ける() {
        assertEquals("R.07/01/05", CsvUtils.toYayoiDate("2025/1/5"))
    }

    @Test
    fun toYayoiDate_不正な文字列はそのまま返す() {
        assertEquals("", CsvUtils.toYayoiDate(""))
        assertEquals("2026-06", CsvUtils.toYayoiDate("2026-06"))
        assertEquals("abc-def-ghi", CsvUtils.toYayoiDate("abc-def-ghi"))
    }

    // ─── yayoiCharset（機種依存文字対応）─────────────────────────────

    @Test
    fun yayoiCharset_機種依存文字をエンコードできる() {
        // Shift_JIS では ①・㈱ 等が ? に化ける。windows-31j なら変換可能
        val encoder = CsvUtils.yayoiCharset().newEncoder()
        assertTrue("丸数字①がエンコードできること", encoder.canEncode("①"))
        assertTrue("組文字㈱がエンコードできること", encoder.canEncode("㈱"))
    }

    @Test
    fun yayoiCharset_通常の日本語をエンコードできる() {
        val encoder = CsvUtils.yayoiCharset().newEncoder()
        assertTrue(encoder.canEncode("弥生の青色申告・農業経費"))
    }
}
