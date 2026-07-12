package com.example.greenframeocr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtaInvoiceClientTest {

    @Test
    fun 登録番号単体を抽出できる() {
        assertEquals("T1234567890123", NtaInvoiceClient.extractRegistrationNumber("T1234567890123"))
    }

    @Test
    fun OCRテキスト中に埋め込まれた登録番号を抽出できる() {
        val ocrText = """
            スーパーマルシェ 島原店
            登録番号 T8100001234567
            2026/06/13 15:32
            レギュラーガソリン ¥3,500
        """.trimIndent()
        assertEquals("T8100001234567", NtaInvoiceClient.extractRegistrationNumber(ocrText))
    }

    @Test
    fun 登録番号がなければnull() {
        assertNull(NtaInvoiceClient.extractRegistrationNumber("合計 ¥1,234 現金"))
    }

    @Test
    fun 桁数が足りない場合はマッチしない() {
        assertNull(NtaInvoiceClient.extractRegistrationNumber("T123456789012"))  // 12桁
    }

    @Test
    fun 小文字のtはマッチしない() {
        assertNull(NtaInvoiceClient.extractRegistrationNumber("t1234567890123"))
    }
}
